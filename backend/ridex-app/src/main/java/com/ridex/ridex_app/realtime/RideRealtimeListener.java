package com.ridex.ridex_app.realtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ridex.ridex_app.ride.RideStatus;
import com.ridex.ridex_app.ride.RideStatusChanged;

/**
 * Turns "a ride changed" (an event from the ride module) into pushes to the browsers.
 *
 * AFTER_COMMIT matters: we only announce a change once the database really has it. Announcing earlier
 * could tell a rider "your driver accepted" and then have the transaction roll back.
 *
 * The push carries only a small hint (ride id and new status), NOT the ride itself. The browser then asks
 * the REST API for the details. Two reasons: what a rider may see differs from what a driver may see (phone
 * numbers appear only after acceptance), and REST stays the single place that decides that.
 */
@Component
public class RideRealtimeListener {

    private static final Logger log = LoggerFactory.getLogger(RideRealtimeListener.class);

    private final RealtimePublisher publisher;

    public RideRealtimeListener(RealtimePublisher publisher) {
        this.publisher = publisher;
    }

    // fallbackExecution: also react when a ride was saved outside a transaction (rare, but then nothing to wait for)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRideStatusChanged(RideStatusChanged event) {
        try {
            push(event);
        } catch (RuntimeException e) {
            // A failed push must never break a ride: the browser also refreshes on its own schedule
            log.warn("Could not push the update of ride {}: {}", event.rideId(), e.getClass().getSimpleName());
        }
    }

    private void push(RideStatusChanged event) {
        if (event.from() == null) {
            return;   // the ride was just created: nobody is watching it yet
        }
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("rideId", event.rideId().toString());
        update.put("status", event.to().name());
        update.put("previousStatus", event.from().name());
        update.put("at", event.at().toString());

        publisher.toUser(event.riderUserId(), "/queue/ride-updates", update);

        boolean offerGone = event.from() == RideStatus.DRIVER_ASSIGNED
                && (event.to() == RideStatus.SEARCHING_DRIVER || event.to() == RideStatus.CANCELLED);

        if (event.driverUserId() != null && !offerGone) {
            publisher.toUser(event.driverUserId(), "/queue/ride-updates", update);
        }

        // Driver offers: the offer appears on the driver's screen at once, and disappears when it is
        // answered by somebody else, timed out, or cancelled by the rider
        if (event.to() == RideStatus.DRIVER_ASSIGNED && event.offer() != null && event.driverUserId() != null) {
            RideStatusChanged.OfferSummary offer = event.offer();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rideId", event.rideId().toString());
            payload.put("category", offer.category());
            payload.put("pickupAddress", offer.pickupAddress());
            payload.put("dropoffAddress", offer.dropoffAddress());
            payload.put("distanceKm", offer.distanceKm());
            payload.put("totalFare", offer.totalFare());
            payload.put("expiresAt", offer.expiresAt() == null ? null : offer.expiresAt().toString());
            payload.put("withdrawn", false);
            publisher.toUser(event.driverUserId(), "/queue/offers", payload);
        } else if (offerGone && event.driverUserId() != null) {
            publisher.toUser(event.driverUserId(), "/queue/offers", Map.of(
                    "rideId", event.rideId().toString(), "withdrawn", true, "at", Instant.now().toString()));
        }
    }
}
