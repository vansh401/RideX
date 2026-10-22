package com.ridex.analytics;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.ridex.platform.contracts.EventEnvelope;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.kafka.EventCodec;
import com.ridex.platform.kafka.ProcessedEvents;

/**
 * An append-only record of every event, with its place in Kafka (topic, partition, offset). It answers "what exactly happened
 * to ride X, and in what order?" long after the rows in the other services changed. It reads the same events as the other
 * groups but has its own position, so it works independently of them.
 */
@Component
public class AuditConsumer {

    private final EventCodec codec;
    private final ProcessedEvents processed;
    private final JdbcTemplate jdbc;

    public AuditConsumer(EventCodec codec, ProcessedEvents processed, JdbcTemplate jdbc) {
        this.codec = codec;
        this.processed = processed;
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = {Topics.RIDE_EVENTS, Topics.PAYMENT_EVENTS, Topics.DRIVER_EVENTS, Topics.COMPLAINT_EVENTS}, groupId = Groups.AUDIT)
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = codec.parse(record.value());
        processed.runOnce(Groups.AUDIT, event.eventId(), () -> jdbc.update("""
                insert into event_audit_log (event_id, topic, partition_no, offset_no, event_key, event_type, payload, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, event.eventId(), record.topic(), record.partition(), record.offset(),
                event.key(), event.type(), record.value(), java.sql.Timestamp.from(event.occurredAt())));
    }
}
