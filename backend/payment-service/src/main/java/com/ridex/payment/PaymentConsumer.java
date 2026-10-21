package com.ridex.payment;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.ridex.platform.contracts.EventEnvelope;
import com.ridex.platform.contracts.RideEventData;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.kafka.EventCodec;
import com.ridex.platform.kafka.PoisonEventException;
import com.ridex.platform.kafka.ProcessedEvents;

/** When a ride completes, charge the rider. Reacts to the event; the ride code knows nothing about payments. */
@Component
public class PaymentConsumer {

    public static final String GROUP = "payments";

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final PaymentService payments;

    public PaymentConsumer(EventCodec codec, ProcessedEvents processed, PaymentService payments) {
        this.codec = codec;
        this.processed = processed;
        this.payments = payments;
    }

    @KafkaListener(topics = Topics.RIDE_EVENTS, groupId = GROUP)
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
        processed.runOnce(GROUP, event.eventId(), () -> payments.chargeForCompletedRide(
                ride.rideId(), ride.riderUserId(), ride.driverUserId(), ride.fareTotal(), ride.currency()));
    }
}
