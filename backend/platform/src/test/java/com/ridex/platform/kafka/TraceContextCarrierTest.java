package com.ridex.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

/**
 * The trace must survive the trip through the outbox: saved with the event when the request runs, restored when the relay publishes it
 * on another thread. Uses the same OpenTelemetry bridge Spring Boot wires in the services.
 */
class TraceContextCarrierTest {

    private final OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    private final io.opentelemetry.api.trace.Tracer otelTracer = otel.getTracer("test");
    private final OtelTracer tracer = new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> { });
    private final TraceContextCarrier carrier = new TraceContextCarrier(tracer, new OtelPropagator(otel.getPropagators(), otelTracer));

    @Test
    void theCurrentTraceIsCapturedAsAW3cTraceparent() {
        Span request = tracer.nextSpan().name("http request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            String traceparent = carrier.capture();

            assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[0-3]");
            assertThat(traceparent).contains(request.context().traceId()).contains(request.context().spanId());
        } finally {
            request.end();
        }
    }

    @Test
    void withNoCurrentSpanNothingIsCaptured() {
        assertThat(carrier.capture()).isNull();
    }

    @Test
    void thePublishedMessageContinuesTheSameTraceWithANewSpan() throws Exception {
        Span request = tracer.nextSpan().name("http request").start();
        String saved;
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            saved = carrier.capture();
        } finally {
            request.end();
        }
        // ... later, on another thread with no current span, the relay publishes it
        RecordHeaders headers = new RecordHeaders();
        AtomicReference<Span> during = new AtomicReference<>();

        String result = carrier.publishWithin(saved, "outbox publish ride.status-changed", headers, () -> {
            during.set(tracer.currentSpan());
            return "sent";
        });

        assertThat(result).isEqualTo("sent");
        String sent = new String(headers.lastHeader("traceparent").value(), StandardCharsets.UTF_8);
        assertThat(sent).contains(request.context().traceId());                    // same trace as the request
        assertThat(sent).doesNotContain(request.context().spanId());               // but its own span
        assertThat(during.get()).isNotNull();                                      // the send ran inside that span
        assertThat(tracer.currentSpan()).isNull();                                 // and the scope was closed again
    }

    @Test
    void withoutASavedContextOrWithoutTracingTheSendJustRuns() throws Exception {
        RecordHeaders headers = new RecordHeaders();

        assertThat(carrier.publishWithin(null, "x", headers, () -> "a")).isEqualTo("a");
        assertThat(new TraceContextCarrier(null, null).publishWithin("00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01", "x", headers, () -> "b"))
                .isEqualTo("b");
        assertThat(headers.lastHeader("traceparent")).isNull();
    }
}
