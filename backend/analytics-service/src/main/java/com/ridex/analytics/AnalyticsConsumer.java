package com.ridex.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.ridex.platform.contracts.EventEnvelope;
import com.ridex.platform.contracts.PaymentEventData;
import com.ridex.platform.contracts.RideEventData;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.kafka.EventCodec;
import com.ridex.platform.kafka.ProcessedEvents;

/**
 * Counts what happened per day, built ONLY from events (it never reads the rides or payments tables, which belong to other
 * services). Because it is a separate consumer group, adding or changing analytics never touches the ride code.
 */
@Component
public class AnalyticsConsumer {

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final JdbcTemplate jdbc;

    public AnalyticsConsumer(EventCodec codec, ProcessedEvents processed, JdbcTemplate jdbc) {
        this.codec = codec;
        this.processed = processed;
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = {Topics.RIDE_EVENTS, Topics.PAYMENT_EVENTS}, groupId = Groups.ANALYTICS)
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = codec.parse(record.value());
        LocalDate day = event.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        switch (event.type()) {
            case EventEnvelope.RIDE_STATUS_CHANGED -> {
                RideEventData data = codec.data(event, RideEventData.class);
                processed.runOnce(Groups.ANALYTICS, event.eventId(), () -> jdbc.update("""
                        insert into ride_status_counts_daily (day, status, event_count) values (?, ?, 1)
                        on conflict (day, status) do update set event_count = ride_status_counts_daily.event_count + 1
                        """, java.sql.Date.valueOf(day), data.to()));
            }
            case EventEnvelope.PAYMENT_SUCCEEDED, EventEnvelope.PAYMENT_FAILED -> {
                PaymentEventData data = codec.data(event, PaymentEventData.class);
                String outcome = EventEnvelope.PAYMENT_SUCCEEDED.equals(event.type()) ? "SUCCEEDED" : "FAILED";
                BigDecimal amount = data.amount() == null ? BigDecimal.ZERO : data.amount();
                processed.runOnce(Groups.ANALYTICS, event.eventId(), () -> jdbc.update("""
                        insert into payment_outcomes_daily (day, outcome, event_count, total_amount) values (?, ?, 1, ?)
                        on conflict (day, outcome) do update set event_count = payment_outcomes_daily.event_count + 1,
                                                                 total_amount = payment_outcomes_daily.total_amount + excluded.total_amount
                        """, java.sql.Date.valueOf(day), outcome, amount));
            }
            default -> {
                // An event type this service does not know is ignored: a consumer must tolerate what it does not understand
            }
        }
    }
}
