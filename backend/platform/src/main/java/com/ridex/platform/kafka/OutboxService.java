package com.ridex.platform.kafka;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.platform.contracts.EventEnvelope;

/**
 * The only way to publish an event. It does NOT talk to Kafka: it saves the event in the outbox table as
 * part of the caller's database transaction.
 *
 * The problem this solves is the "dual write". Saving to PostgreSQL and sending to Kafka are two different
 * systems that cannot share one transaction. If we did them one after the other:
 *   - save, then send: a crash between the two means the data changed but nobody was told;
 *   - send, then save: a rollback means everybody was told about a change that never happened.
 * With an outbox the event and the change commit together or not at all. Getting the event to Kafka is then
 * a separate, retryable job (OutboxRelay), so a Kafka outage only delays events, never loses them.
 */
public class OutboxService {

    private final JdbcTemplate jdbc;
    private final EventCodec codec;
    private final TraceContextCarrier trace;

    public OutboxService(JdbcTemplate jdbc, EventCodec codec, TraceContextCarrier trace) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.trace = trace;
    }

    /** MANDATORY: refuses to run outside a transaction, because without one the whole guarantee is gone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope append(String topic, String key, String type, Object data) {
        EventEnvelope envelope = codec.envelope(type, key, data);
        jdbc.update("""
                insert into outbox_events (event_id, topic, event_key, event_type, payload, created_at, traceparent)
                values (?, ?, ?, ?, ?, ?, ?)
                """, envelope.eventId(), topic, key, type, codec.toJson(envelope), Timestamp.from(Instant.now()), trace.capture());
        return envelope;
    }
}
