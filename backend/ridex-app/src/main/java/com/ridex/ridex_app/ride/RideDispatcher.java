package com.ridex.ridex_app.ride;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ridex.ridex_app.driver.DriverProfile;
import com.ridex.ridex_app.driver.Vehicle;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Gives rides to drivers. This is "DB-based matching": the database answers "who is the nearest
 * free driver?". In Phase 4 the same job is done by Redis GEO, and this class keeps its shape.
 *
 * It is driven from three places: when a ride is requested, when a driver declines, and by a
 * background job that handles timeouts (an offer nobody answered, a search that found nobody).
 */
@Service
public class RideDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RideDispatcher.class);

    private final RideRepository rideRepository;
    private final DispatchRepository dispatchRepository;
    private final RideRejectionRepository rejectionRepository;
    private final MatchingProperties properties;
    private final TransactionTemplate transaction;

    @PersistenceContext
    private EntityManager entityManager;

    public RideDispatcher(RideRepository rideRepository,
                          DispatchRepository dispatchRepository,
                          RideRejectionRepository rejectionRepository,
                          MatchingProperties properties,
                          PlatformTransactionManager transactionManager) {
        this.rideRepository = rideRepository;
        this.dispatchRepository = dispatchRepository;
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
        List<DispatchRepository.Candidate> found = dispatchRepository.lockNearestAvailableDriver(
                ride.getId(), ride.getCategory().name(),
                ride.getPickupLatitude(), ride.getPickupLongitude(),
                properties.radiusKm(), now.minusSeconds(properties.locationFreshnessSeconds()));
        if (found.isEmpty()) {
            return false;
        }
        DispatchRepository.Candidate candidate = found.get(0);
        // References, not loads: we only need the ids to set the foreign keys
        DriverProfile driver = entityManager.getReference(DriverProfile.class, candidate.getDriverProfileId());
        Vehicle vehicle = entityManager.getReference(Vehicle.class, candidate.getVehicleId());
        ride.offerTo(driver, vehicle, now.plusSeconds(properties.offerTimeoutSeconds()), now);
        return true;
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
        if (ride.getRequestedAt().plusSeconds(properties.searchTimeoutSeconds()).isBefore(now)) {
            ride.cancel(null, ActorRole.SYSTEM, "No driver available nearby", now);
            return;
        }
        tryAssign(ride, now);
    }

    private void inOwnTransaction(UUID rideId, Consumer<Ride> action) {
        try {
            transaction.executeWithoutResult(status -> rideRepository.findById(rideId).ifPresent(action));
        } catch (ObjectOptimisticLockingFailureException e) {
            // Someone (a driver accepting, a rider cancelling) changed this ride a moment ago.
            // Their change wins; the next cycle looks at the ride again if it still needs work.
            log.debug("Ride {} changed concurrently, skipping this cycle", rideId);
        } catch (RuntimeException e) {
            log.error("Dispatch failed for ride {}", rideId, e);
        }
    }
}
