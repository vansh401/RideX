package com.ridex.platform.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.kafka.common.header.Headers;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * Carries a trace across the outbox.
 *
 * A trace follows a request from thread to thread by itself, but an event leaves the request through a database row and is published
 * later by the relay, on a scheduler thread that knows nothing about the request. So:
 *  1. when the event is saved ({@link #capture()}), the current trace context is written next to it, as a W3C "traceparent" value;
 *  2. when the relay publishes it ({@link #publishWithin}), it starts a span that is a child of that saved context and puts the
 *     traceparent of ITS span in the Kafka message headers;
 *  3. the consumer (Spring Kafka with observation enabled) reads the header and continues the same trace.
 * The result is one trace: HTTP request, database work, event publication, event handling in another service.
 *
 * Without a tracer on the classpath or in the context everything here does nothing and events flow exactly as before.
 */
public class TraceContextCarrier {

    public static final String HEADER = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public TraceContextCarrier(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    private boolean enabled() {
        return tracer != null && propagator != null;
    }

    /** The traceparent of the current span, or null if there is none. */
    public String capture() {
        if (!enabled()) {
            return null;
        }
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new java.util.HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(HEADER);
    }

    /**
     * Runs {@code send} inside a new span ("outbox publish") whose parent is the saved context. The headers passed in get the traceparent of
     * that span. If nothing was saved, or tracing is off, {@code send} just runs.
     */
    public <T> T publishWithin(String traceparent, String spanName, Headers headers, Callable<T> send) throws Exception {
        if (!enabled() || traceparent == null || traceparent.isBlank()) {
            return send.call();
        }
        Span span = propagator.extract(Map.of(HEADER, traceparent), Map<String, String>::get)
                .name(spanName).kind(Span.Kind.PRODUCER).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            propagator.inject(span.context(), headers, (target, key, value) -> {
                target.remove(key);
                target.add(key, value.getBytes(StandardCharsets.UTF_8));
            });
            return send.call();
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
