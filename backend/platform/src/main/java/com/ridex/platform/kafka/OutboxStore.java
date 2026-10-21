package com.ridex.platform.kafka;

import java.util.List;
import java.util.UUID;

/** Storage for the outbox: events waiting to be copied to Kafka. */
public interface OutboxStore {

    /** traceparent: the trace context of the request that caused the event (null if there was none). */
    record Pending(long seq, UUID eventId, String topic, String key, String type, String payload, String traceparent) {
    }

    /** Takes the "only one relay at a time" lock for the current transaction. False means another server holds it. */
    boolean tryLockForRelay();

    /** The oldest unpublished events first: this order keeps the events of one ride in sequence. */
    List<Pending> findUnpublished(int limit);

    void markPublished(long seq);

    long countUnpublished();

    int deletePublishedOlderThanDays(int days);
}
