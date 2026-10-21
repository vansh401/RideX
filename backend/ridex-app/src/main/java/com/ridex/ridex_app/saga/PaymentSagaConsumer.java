package com.ridex.ridex_app.saga;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.ridex.platform.contracts.EventEnvelope;
import com.ridex.platform.contracts.PaymentEventData;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.kafka.EventCodec;
import com.ridex.platform.kafka.PoisonEventException;
import com.ridex.platform.kafka.ProcessedEvents;

/**
 * The core's part of the ride-payment SAGA.
 *
 * A saga is a business process spread over several services. There is no distributed transaction (see docs/saga-concepts.md),
 * so each step is a local transaction in one service and the next step is triggered by an event:
 *
 *   core:     ride COMPLETED                 -> publishes ride.status-changed
 *   payment:  charges the rider              -> publishes payment.succeeded  OR  payment.failed
 *   core:     (this class) on failed         -> COMPENSATE: the rider may not book new rides until the payment is settled
 *             on succeeded                   -> lift the block
 *
 * Why compensation and not rollback: the trip already happened; nothing can make the rider "not have ridden". The only thing
 * that can be undone is what we allow next. This is the typical shape of a compensation: a new business action that
 * counteracts the earlier one, not a technical undo.
 */
@Component
public class PaymentSagaConsumer {

    public static final String GROUP = "ride-payment-saga";

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final RiderPaymentBlocks blocks;

    public PaymentSagaConsumer(EventCodec codec, ProcessedEvents processed, RiderPaymentBlocks blocks) {
        this.codec = codec;
        this.processed = processed;
        this.blocks = blocks;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = GROUP)
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = codec.parse(record.value());
        boolean failed = EventEnvelope.PAYMENT_FAILED.equals(event.type());
        boolean succeeded = EventEnvelope.PAYMENT_SUCCEEDED.equals(event.type());
        if (!failed && !succeeded) {
            return;
        }
        PaymentEventData payment = codec.data(event, PaymentEventData.class);
        if (payment.riderUserId() == null || payment.rideId() == null) {
            throw new PoisonEventException("A payment event must name the rider and the ride");
        }
        processed.runOnce(GROUP, event.eventId(), () -> {
            if (failed) {
                blocks.block(payment.riderUserId(), payment.rideId(), payment.amount(), payment.currency(), payment.failureReason());
            } else {
                blocks.release(payment.riderUserId(), payment.rideId());
            }
        });
    }
}
