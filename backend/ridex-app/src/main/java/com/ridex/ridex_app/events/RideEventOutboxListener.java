package com.ridex.ridex_app.events;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ridex.platform.contracts.EventEnvelope;
import com.ridex.platform.contracts.RideEventData;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.kafka.OutboxService;
import com.ridex.ridex_app.ride.Ride;
import com.ridex.ridex_app.ride.RideRepository;
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
    private final RideRepository rides;

    public RideEventOutboxListener(OutboxService outbox, RideRepository rides) {
        this.outbox = outbox;
        this.rides = rides;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onRideStatusChanged(RideStatusChanged e) {
        if (e.from() == null) {
            return;
        }
        // The ride is already loaded in this transaction (it is the thing being saved), so this is not another query
        Ride ride = rides.findById(e.rideId()).orElse(null);
        RideEventData data = new RideEventData(e.rideId(), e.riderUserId(), e.driverUserId(),
                e.from().name(), e.to().name(), e.actor() == null ? null : e.actor().name(), e.note(), e.at(),
                e.pickupAddress(), e.dropoffAddress(), e.fareTotal(), e.currency(),
                ride == null ? null : ride.getCategory().name(), ride == null ? null : ride.getPickupCell(),
                ride == null ? null : ride.getSurgeMultiplier(), ride == null ? null : ride.getCouponCode());
        outbox.append(Topics.RIDE_EVENTS, e.rideId().toString(), EventEnvelope.RIDE_STATUS_CHANGED, data);
    }
}
