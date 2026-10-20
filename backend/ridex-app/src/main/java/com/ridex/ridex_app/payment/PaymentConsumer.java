package com.ridex.ridex_app.payment;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.ridex.ridex_app.events.EventCodec;
import com.ridex.ridex_app.events.EventEnvelope;
import com.ridex.ridex_app.events.Groups;
import com.ridex.ridex_app.events.PoisonEventException;
import com.ridex.ridex_app.events.ProcessedEvents;
import com.ridex.ridex_app.events.RideEventData;
import com.ridex.ridex_app.events.Topics;

/** When a ride completes, charge the rider. Reacts to the event; the ride code knows nothing about payments. */
@Component
public class PaymentConsumer {

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final PaymentService payments;

    public PaymentConsumer(EventCodec codec, ProcessedEvents processed, PaymentService payments) {
        this.codec = codec;
        this.processed = processed;
        this.payments = payments;
    }

    @KafkaListener(topics = Topics.RIDE_EVENTS, groupId = Groups.PAYMENTS)
    public void onRideEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = codec.parse(record.value());
        if (!EventEnvelope.RIDE_STATUS_CHANGED.equals(event.type())) {
            return;
        }
        RideEventData ride = codec.data(event, RideEventData.class);
        if (!"RIDE_COMPLETED".equals(ride.to())) {
            return;
        }
        if (ride.driverUserId() == null || ride.fareTotal() == null || ride.currency() == null) {
            throw new PoisonEventException("A completed ride must name its driver and its fare");
        }
        processed.runOnce(Groups.PAYMENTS, event.eventId(), () -> payments.chargeForCompletedRide(
                ride.rideId(), ride.riderUserId(), ride.driverUserId(), ride.fareTotal(), ride.currency()));
    }
}
