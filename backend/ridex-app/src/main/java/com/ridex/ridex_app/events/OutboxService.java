package com.ridex.ridex_app.events;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way to publish an event. It does NOT talk to Kafka: it saves the event in the outbox table as
 * part of the caller's database transaction.
 *
 * The problem this solves is the "dual write". Saving to PostgreSQL and sending to Kafka are two different
 * systems that cannot share one transaction. If we did them one after the other:
 *   - save, then send: a crash between the two means the ride changed but nobody was told;
 *   - send, then save: a rollback means everybody was told about a change that never happened.
 * With an outbox the event and the change commit together or not at all. Getting the event to Kafka is then
 * a separate, retryable job (OutboxRelay), so a Kafka outage only delays events, never loses them.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final EventCodec codec;

    public OutboxService(OutboxEventRepository repository, EventCodec codec) {
        this.repository = repository;
        this.codec = codec;
    }

    /** MANDATORY: refuses to run outside a transaction, because without one the whole guarantee is gone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope append(String topic, String key, String type, Object data) {
        EventEnvelope envelope = codec.envelope(type, key, data);
        repository.save(new OutboxEvent(envelope.eventId(), topic, key, type, codec.toJson(envelope), Instant.now()));
        return envelope;
    }
}
