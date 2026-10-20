package com.ridex.ridex_app.analytics;

import java.time.ZoneOffset;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.ridex.ridex_app.events.EventCodec;
import com.ridex.ridex_app.events.EventEnvelope;
import com.ridex.ridex_app.events.Groups;
import com.ridex.ridex_app.events.ProcessedEvents;
import com.ridex.ridex_app.events.RideEventData;
import com.ridex.ridex_app.events.Topics;

/**
 * Counts how many times each ride status happened per day, built ONLY from events (it never reads the rides
 * table). Because it is a separate consumer group, adding or changing analytics never touches the ride code.
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

    @KafkaListener(topics = Topics.RIDE_EVENTS, groupId = Groups.ANALYTICS)
    public void onRideEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = codec.parse(record.value());
        if (!EventEnvelope.RIDE_STATUS_CHANGED.equals(event.type())) {
            return;
        }
        RideEventData data = codec.data(event, RideEventData.class);
        java.time.LocalDate day = event.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        processed.runOnce(Groups.ANALYTICS, event.eventId(), () -> jdbc.update("""
                insert into ride_status_counts_daily (day, status, event_count) values (?, ?, 1)
                on conflict (day, status) do update set event_count = ride_status_counts_daily.event_count + 1
                """, java.sql.Date.valueOf(day), data.to()));
    }
}
