package com.ridex.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutboxMetricsTest {

    private final OutboxStore store = mock(OutboxStore.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void theBacklogIsExposedAsAGauge() {
        when(store.countUnpublished()).thenReturn(7L);
        new OutboxMetrics(store, registry);

        assertThat(registry.get("ridex.outbox.pending").gauge().value()).isEqualTo(7.0);
    }

    @Test
    void theDatabaseIsAskedAtMostOnceEveryFewSecondsHoweverOftenPrometheusScrapes() {
        when(store.countUnpublished()).thenReturn(3L);
        new OutboxMetrics(store, registry);

        for (int i = 0; i < 20; i++) {
            registry.get("ridex.outbox.pending").gauge().value();
        }

        verify(store, times(1)).countUnpublished();
    }

    @Test
    void anUnreachableDatabaseIsReportedAsUnknownNotAsTheLastValue() {
        when(store.countUnpublished()).thenThrow(new IllegalStateException("database down"));
        new OutboxMetrics(store, registry);

        assertThat(registry.get("ridex.outbox.pending").gauge().value()).isNaN();
    }
}
