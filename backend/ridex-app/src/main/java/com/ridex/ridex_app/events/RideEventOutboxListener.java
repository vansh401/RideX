package com.ridex.ridex_app.events;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ridex.ridex_app.ride.RideStatusChanged;

/**
 * Turns the in-process "a ride changed" event (Phase 5) into a durable Kafka event.
 *
 * BEFORE_COMMIT is the point: this runs inside the ride's own transaction, just before it commits. The outbox
 * row is therefore saved or rolled back together with the ride change itself.
 */
@Component
public class RideEventOutboxListener {

    private final OutboxService outbox;

    public RideEventOutboxListener(OutboxService outbox) {
        this.outbox = outbox;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onRideStatusChanged(RideStatusChanged e) {
        if (e.from() == null) {
            return;
        }
        RideEventData data = new RideEventData(e.rideId(), e.riderUserId(), e.driverUserId(),
                e.from().name(), e.to().name(), e.actor() == null ? null : e.actor().name(), e.note(), e.at(),
                e.pickupAddress(), e.dropoffAddress(), e.fareTotal(), e.currency());
        outbox.append(Topics.RIDE_EVENTS, e.rideId().toString(), EventEnvelope.RIDE_STATUS_CHANGED, data);
    }
}
