package com.ridex.payment.refund;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.ridex.platform.contracts.ComplaintEventData;
import com.ridex.platform.contracts.EventEnvelope;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.kafka.EventCodec;
import com.ridex.platform.kafka.PoisonEventException;
import com.ridex.platform.kafka.ProcessedEvents;
import com.ridex.platform.web.ConflictException;

/**
 * A complaint was resolved WITH a refund: give the money back. This is a saga step in the other direction of the ride payment: the core decides
 * (an administrator resolved the complaint), the payment service executes, and neither calls the other.
 *
 * Failures are sorted by whether trying again can help:
 *  - the payment does not exist yet (the ride just completed): a normal exception, so Kafka retries and the payment is there a moment later;
 *  - the payment exists but cannot be refunded (it failed, or the complainant is not the rider, or the amount is more than was paid): the
 *    event can never work, so it is a "poison" event: no retries, straight to the dead-letter topic where a person looks at it.
 */
@Component
public class ComplaintRefundConsumer {

    private static final Logger log = LoggerFactory.getLogger(ComplaintRefundConsumer.class);
    public static final String GROUP = "payment-refunds";

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final RefundService refunds;

    public ComplaintRefundConsumer(EventCodec codec, ProcessedEvents processed, RefundService refunds) {
        this.codec = codec;
        this.processed = processed;
        this.refunds = refunds;
    }

    @KafkaListener(topics = Topics.COMPLAINT_EVENTS, groupId = GROUP)
    public void onComplaintEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = codec.parse(record.value());
        if (!EventEnvelope.COMPLAINT_RESOLVED.equals(event.type())) {
            return;
        }
        ComplaintEventData complaint = codec.data(event, ComplaintEventData.class);
        if (complaint.refundAmount() == null || complaint.refundAmount().signum() <= 0) {
            return;   // resolved without money: nothing to do here
        }
        processed.runOnce(GROUP, event.eventId(), () -> {
            try {
                refunds.request(complaint.rideId(), complaint.refundAmount(), "Complaint: " + complaint.category(), "COMPLAINT",
                        complaint.complaintId().toString(), "WALLET", "complaint-refund-" + complaint.complaintId(), complaint.userId());
            } catch (ConflictException e) {
                log.warn("The refund of a complaint cannot be made: {}", e.getMessage());
                throw new PoisonEventException("The refund for complaint " + complaint.complaintId() + " cannot be made: " + e.getMessage());
            }
        });
    }
}
