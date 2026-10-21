package com.ridex.platform.kafka;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * "How many events are waiting to be sent to Kafka?" as a metric.
 *
 * This is the number to watch for the outbox: it is near zero while Kafka and the relay work, and it only grows when events cannot get
 * out (Kafka down, relay stuck). The value is read from the database at most every 10 seconds, however often Prometheus asks.
 */
public class OutboxMetrics {

    static final String PENDING = "ridex.outbox.pending";
    private static final long MAX_AGE_MS = 10_000;

    private final LongSupplier pending;
    private final AtomicLong cached = new AtomicLong();
    private volatile long readAt = 0;

    public OutboxMetrics(OutboxStore store, MeterRegistry registry) {
        this.pending = store::countUnpublished;
        Gauge.builder(PENDING, this, OutboxMetrics::value)
                .description("Events saved in the outbox and not yet confirmed by Kafka")
                .strongReference(true)   // the registry alone would only hold this object weakly
                .register(registry);
    }

    private double value() {
        long now = System.currentTimeMillis();
        if (now - readAt > MAX_AGE_MS) {
            try {
                cached.set(pending.getAsLong());
            } catch (RuntimeException e) {
                return Double.NaN;   // database unreachable: "unknown" is more honest than the last value
            }
            readAt = now;
        }
        return cached.get();
    }
}
