package com.ridex.ridex_app.ride;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ridex.ridex_app.common.AfterCommit;
import com.ridex.ridex_app.driver.DriverProfile;
import com.ridex.ridex_app.driver.Vehicle;
import com.ridex.ridex_app.redis.DistributedLock;
import com.ridex.ridex_app.redis.DriverLocationIndex;
import com.ridex.ridex_app.redis.RedisKeys;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Gives rides to drivers.
 *
 * Phase 4 matching in three steps:
 * 1. Redis GEO answers "who is near the pickup?": the nearest drivers with a live heartbeat, closest first.
 * 2. For each candidate, take a short Redis lock on that driver, so two servers do not assign the same
 *    driver at the same moment.
 * 3. PostgreSQL decides whether the driver is REALLY free (approved, online, no active ride...) and locks
 *    their row. The database stays the final authority, because Redis only knows positions.
 *
 * It is driven from three places: when a ride is requested, when a driver declines, and by a
 * background job that handles timeouts (an offer nobody answered, a search that found nobody).
 */
@Service
public class RideDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RideDispatcher.class);

    private final RideRepository rideRepository;
    private final DispatchRepository dispatchRepository;
    private final DriverLocationIndex locations;
    private final DistributedLock locks;
    private final RideRejectionRepository rejectionRepository;
    private final MatchingProperties properties;
    private final TransactionTemplate transaction;

    @PersistenceContext
    private EntityManager entityManager;

    public RideDispatcher(RideRepository rideRepository,
                          DispatchRepository dispatchRepository,
                          DriverLocationIndex locations,
                          DistributedLock locks,
                          RideRejectionRepository rejectionRepository,
                          MatchingProperties properties,
                          PlatformTransactionManager transactionManager) {
        this.rideRepository = rideRepository;
        this.dispatchRepository = dispatchRepository;
        this.locations = locations;
        this.locks = locks;
        this.rejectionRepository = rejectionRepository;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Offers a SEARCHING ride to the nearest available driver, if there is one.
     * MANDATORY: the driver row lock only works inside the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryAssign(Ride ride, Instant now) {
        List<DriverLocationIndex.Nearby> nearby;
        try {
            nearby = locations.findNearby(ride.getPickupLatitude().doubleValue(), ride.getPickupLongitude().doubleValue(),
                    properties.radiusKm(), properties.candidateLimit());
        } catch (DataAccessException e) {
            // Redis is unreachable: nobody can be matched right now. The ride stays SEARCHING and the
            // background job tries again in a few seconds, so a Redis outage delays rides, never loses them.
            log.warn("Driver search unavailable (Redis): {}", e.getClass().getSimpleName());
            return false;
        }

        for (DriverLocationIndex.Nearby candidate : nearby) {
            Optional<DistributedLock.Handle> lock = locks.tryLock(
                    RedisKeys.driverAssignmentLock(candidate.driverProfileId()),
                    Duration.ofSeconds(properties.assignmentLockSeconds()));
            if (lock.isEmpty()) {
                continue;   // another request is assigning this driver right now: try the next nearest
            }
            // Hold the lock until THIS transaction ends (commit or rollback), not just until this method
            // returns. Releasing earlier would open a gap before the assignment is committed.
            AfterCommit.runAfterCompletion(lock.get()::release);

            List<DispatchRepository.Candidate> found = dispatchRepository.lockIfAvailable(
                    ride.getId(), candidate.driverProfileId(), ride.getCategory().name());
            if (found.isEmpty()) {
                lock.get().release();   // not our driver after all: let others try them
                continue;               // not actually free (on a ride, offline in the database, wrong vehicle...)
            }
            DispatchRepository.Candidate chosen = found.get(0);
            // References, not loads: we only need the ids to set the foreign keys
            DriverProfile driver = entityManager.getReference(DriverProfile.class, chosen.getDriverProfileId());
            Vehicle vehicle = entityManager.getReference(Vehicle.class, chosen.getVehicleId());
            ride.offerTo(driver, vehicle, now.plusSeconds(properties.offerTimeoutSeconds()), now);
            return true;
        }
        return false;
    }

    /** The current driver says no (or ran out of time): remember them, then try the next one. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void declineAndReassign(Ride ride, RejectionReason reason, UUID actorId, ActorRole role,
                                   String note, Instant now) {
        rejectionRepository.save(new RideRejection(ride, ride.getDriver(), reason, now));
        ride.declineOffer(actorId, role, note, now);
        tryAssign(ride, now);
    }

    // ----- background job -----

    /** One pass of the dispatch job. Each ride is handled in its own transaction. */
    public void runCycle() {
        Instant now = Instant.now();
        for (UUID id : rideRepository.findIdsWithExpiredOffer(RideStatus.DRIVER_ASSIGNED, now)) {
            inOwnTransaction(id, ride -> expireOffer(ride, now));
        }
        for (UUID id : rideRepository.findIdsByStatusOldestFirst(RideStatus.SEARCHING_DRIVER)) {
            inOwnTransaction(id, ride -> continueSearch(ride, now));
        }
    }

    private void expireOffer(Ride ride, Instant now) {
        boolean stillExpired = ride.getStatus() == RideStatus.DRIVER_ASSIGNED
                && ride.getOfferExpiresAt() != null && ride.getOfferExpiresAt().isBefore(now);
        if (stillExpired) {
            declineAndReassign(ride, RejectionReason.TIMEOUT, null, ActorRole.SYSTEM,
                    "Driver did not answer in time", now);
        }
    }

    private void continueSearch(Ride ride, Instant now) {
        if (ride.getStatus() != RideStatus.SEARCHING_DRIVER) {
            return;
        }
        if (ride.searchWindowStart().plusSeconds(properties.searchTimeoutSeconds()).isBefore(now)) {
            ride.cancel(null, ActorRole.SYSTEM, "No driver available nearby", now);
            return;
        }
        tryAssign(ride, now);
    }

    private void inOwnTransaction(UUID rideId, Consumer<Ride> action) {
        try {
            transaction.executeWithoutResult(status -> rideRepository.findById(rideId).ifPresent(ride -> {
                action.accept(ride);
                rideRepository.save(ride);   // publishes the status changes (see Ride.domainEvents)
            }));
        } catch (ObjectOptimisticLockingFailureException e) {
            // Someone (a driver accepting, a rider cancelling) changed this ride a moment ago.
            // Their change wins; the next cycle looks at the ride again if it still needs work.
            log.debug("Ride {} changed concurrently, skipping this cycle", rideId);
        } catch (RuntimeException e) {
            log.error("Dispatch failed for ride {}", rideId, e);
        }
    }
}
