package com.ridex.analytics;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.ridex.platform.contracts.ComplaintEventData;
import com.ridex.platform.contracts.EventEnvelope;
import com.ridex.platform.contracts.PaymentEventData;
import com.ridex.platform.contracts.RideEventData;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.kafka.EventCodec;
import com.ridex.platform.kafka.ProcessedEvents;

/**
 * Builds the FACTS the admin charts are computed from: one row per ride (ride_facts), one per payment (payment_facts), complaints per day.
 *
 * Each event updates its row with an upsert, and a field that an event does not carry (the driver is not known until a driver is offered the ride)
 * keeps what the table already knows: {@code coalesce(new, old)}. Events of one ride arrive in order (they share a Kafka key, so one partition), and
 * every update is idempotent, so replaying the topic rebuilds the same table. After the facts are updated, the fraud rules look at them.
 */
@Component
public class FactsConsumer {

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final JdbcTemplate jdbc;
    private final FraudDetector fraud;

    public FactsConsumer(EventCodec codec, ProcessedEvents processed, JdbcTemplate jdbc, FraudDetector fraud) {
        this.codec = codec;
        this.processed = processed;
        this.jdbc = jdbc;
        this.fraud = fraud;
    }

    @KafkaListener(topics = {Topics.RIDE_EVENTS, Topics.PAYMENT_EVENTS, Topics.COMPLAINT_EVENTS}, groupId = Groups.FACTS)
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = codec.parse(record.value());
        switch (event.type()) {
            case EventEnvelope.RIDE_STATUS_CHANGED -> {
                RideEventData ride = codec.data(event, RideEventData.class);
                processed.runOnce(Groups.FACTS, event.eventId(), () -> rideChanged(ride, event.occurredAt()));
            }
            case EventEnvelope.PAYMENT_SUCCEEDED, EventEnvelope.PAYMENT_FAILED, EventEnvelope.PAYMENT_REFUNDED -> {
                PaymentEventData payment = codec.data(event, PaymentEventData.class);
                processed.runOnce(Groups.FACTS, event.eventId(), () -> paymentChanged(event.type(), payment, event.occurredAt()));
            }
            case EventEnvelope.COMPLAINT_FILED -> {
                ComplaintEventData complaint = codec.data(event, ComplaintEventData.class);
                processed.runOnce(Groups.FACTS, event.eventId(), () -> jdbc.update("""
                        insert into complaints_daily (day, category, event_count) values (?, ?, 1)
                        on conflict (day, category) do update set event_count = complaints_daily.event_count + 1
                        """, java.sql.Date.valueOf(event.occurredAt().atZone(ZoneOffset.UTC).toLocalDate()), complaint.category()));
            }
            default -> {
                // an event type this service does not know is ignored
            }
        }
    }

    private void rideChanged(RideEventData ride, Instant eventTime) {
        Instant at = ride.at() != null ? ride.at() : eventTime;
        String to = ride.to();
        Timestamp accepted = "DRIVER_ACCEPTED".equals(to) ? Timestamp.from(at) : null;
        Timestamp completed = "RIDE_COMPLETED".equals(to) ? Timestamp.from(at) : null;
        Timestamp cancelled = "CANCELLED".equals(to) ? Timestamp.from(at) : null;
        String cancelledBy = "CANCELLED".equals(to) ? ride.actor() : null;
        String finalStatus = "RIDE_COMPLETED".equals(to) || "CANCELLED".equals(to) ? to : null;
        jdbc.update("""
                insert into ride_facts (ride_id, rider_user_id, driver_user_id, category, pickup_cell, surge_multiplier, coupon_code, fare,
                                        requested_at, accepted_at, completed_at, cancelled_at, cancelled_by, final_status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (ride_id) do update set
                    driver_user_id   = coalesce(excluded.driver_user_id, ride_facts.driver_user_id),
                    category         = coalesce(excluded.category, ride_facts.category),
                    pickup_cell      = coalesce(excluded.pickup_cell, ride_facts.pickup_cell),
                    surge_multiplier = coalesce(excluded.surge_multiplier, ride_facts.surge_multiplier),
                    coupon_code      = coalesce(excluded.coupon_code, ride_facts.coupon_code),
                    fare             = coalesce(excluded.fare, ride_facts.fare),
                    accepted_at      = coalesce(ride_facts.accepted_at, excluded.accepted_at),
                    completed_at     = coalesce(ride_facts.completed_at, excluded.completed_at),
                    cancelled_at     = coalesce(ride_facts.cancelled_at, excluded.cancelled_at),
                    cancelled_by     = coalesce(ride_facts.cancelled_by, excluded.cancelled_by),
                    final_status     = coalesce(excluded.final_status, ride_facts.final_status)
                """, ride.rideId(), ride.riderUserId(), ride.driverUserId(), ride.category(), ride.pickupCell(), ride.surgeMultiplier(), ride.couponCode(),
                ride.fareTotal(), Timestamp.from(at), accepted, completed, cancelled, cancelledBy, finalStatus);
        fraud.afterRideEvent(ride.rideId(), at);
    }

    private void paymentChanged(String type, PaymentEventData payment, Instant at) {
        UUID ride = payment.rideId();
        BigDecimal amount = payment.amount() == null ? BigDecimal.ZERO : payment.amount();
        BigDecimal discount = payment.discount() == null ? BigDecimal.ZERO : payment.discount();
        if (EventEnvelope.PAYMENT_REFUNDED.equals(type)) {
            jdbc.update("""
                    insert into payment_facts (ride_id, rider_user_id, driver_user_id, amount, discount, method, status, paid_at, refunded, event_at)
                    values (?, ?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?)
                    on conflict (ride_id) do update set refunded = payment_facts.refunded + excluded.refunded, event_at = greatest(payment_facts.event_at, excluded.event_at)
                    """, ride, payment.riderUserId(), payment.driverUserId(), amount, discount, payment.method(), Timestamp.from(at),
                    payment.refundAmount() == null ? BigDecimal.ZERO : payment.refundAmount(), Timestamp.from(at));
        } else {
            String status = EventEnvelope.PAYMENT_SUCCEEDED.equals(type) ? "SUCCEEDED" : "FAILED";
            jdbc.update("""
                    insert into payment_facts (ride_id, rider_user_id, driver_user_id, amount, discount, method, status, paid_at, event_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (ride_id) do update set status = excluded.status, amount = excluded.amount, discount = excluded.discount,
                                                        method = coalesce(excluded.method, payment_facts.method),
                                                        paid_at = case when excluded.status = 'SUCCEEDED' then excluded.paid_at else payment_facts.paid_at end
                                                        , event_at = greatest(payment_facts.event_at, excluded.event_at)
                    """, ride, payment.riderUserId(), payment.driverUserId(), amount, discount, payment.method(), status,
                    "SUCCEEDED".equals(status) ? Timestamp.from(at) : null, Timestamp.from(at));
        }
        fraud.afterPaymentEvent(ride, at);
    }
}
