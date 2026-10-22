package com.ridex.ridex_app.ride;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns rides booked ahead into real searches when their time is near.
 *
 * A booked ride sits in the status SCHEDULED, costing nothing. Every half minute this job looks for the ones whose pickup time is within
 * {@link Ride#SEARCH_LEAD} and moves them to SEARCHING_DRIVER; the ordinary dispatcher takes it from there (so booked rides get exactly the same
 * matching, offers, timeouts and events as any other ride).
 *
 * Why polling a table and not a timer per ride: a timer lives in one server's memory and is lost when it restarts or when another server is
 * the one running. The ride's own row says when it is due, so any server, at any time, after any restart, finds the same work. Two servers
 * promoting the same ride at once is safe: the ride has a version, the second update fails and is skipped.
 *
 * A rider who is still on another ride when the time comes is not interrupted: the job waits and tries again; after a grace period the booked
 * ride is cancelled as missed (a driver cannot be sent to a rider who is somewhere else).
 */
@Component
public class ScheduledRidePromoter {

    private static final Logger log = LoggerFactory.getLogger(ScheduledRidePromoter.class);
    static final Duration MISSED_AFTER = Duration.ofMinutes(15);
    private static final Set<RideStatus> ACTIVE = RideStatus.activeStatuses();

    private final RideRepository rides;
    private final TransactionTemplate transaction;

    public ScheduledRidePromoter(RideRepository rides, PlatformTransactionManager transactionManager) {
        this.rides = rides;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** One pass (called by ScheduledRideScheduler every half minute). */
    public void run() {
        try {
            promoteDue(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Promoting booked rides failed, will retry: {}", e.getClass().getSimpleName());
        }
    }

    /** Public so tests can drive it with a chosen "now". Returns how many rides started searching. */
    public int promoteDue(Instant now) {
        List<UUID> due = rides.findScheduledDueIds(now.plus(Ride.SEARCH_LEAD));
        int started = 0;
        for (UUID id : due) {
            try {
                Boolean promoted = transaction.execute(status -> promote(id, now));
                if (Boolean.TRUE.equals(promoted)) {
                    started++;
                }
            } catch (RuntimeException e) {
                log.warn("Booked ride {} could not be promoted now: {}", id, e.getClass().getSimpleName());   // for example another server did it first
            }
        }
        return started;
    }

    private boolean promote(UUID id, Instant now) {
        Ride ride = rides.findById(id).orElse(null);
        if (ride == null || ride.getStatus() != RideStatus.SCHEDULED) {
            return false;
        }
        if (rides.findFirstByRiderIdAndStatusIn(ride.getRider().getId(), ACTIVE).isPresent()) {
            if (ride.getScheduledFor().plus(MISSED_AFTER).isBefore(now)) {
                ride.cancel(null, ActorRole.SYSTEM, "Missed: the rider was still on another ride", now);
                rides.save(ride);
            }
            return false;   // still busy: try again at the next run
        }
        ride.beginSearch(now);
        rides.save(ride);   // publishes the status change (see Ride.domainEvents)
        return true;
    }
}
