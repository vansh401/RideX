package com.ridex.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.ridex.platform.contracts.Topics;

/**
 * What the relay does when Kafka misbehaves. Mocks, so a broken broker can be simulated.
 * The rules: nothing is marked published unless Kafka confirmed it, and after a failure the pass STOPS, so a ride
 * never has a later event delivered before an earlier one.
 */
class OutboxRelayTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final OutboxStore store = mock(OutboxStore.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxRelay relay = new OutboxRelay(store, kafka, mock(PlatformTransactionManager.class), 100, new TraceContextCarrier(null, null), registry);

    private static long seq = 0;

    private OutboxStore.Pending event(String key, String type) {
        return new OutboxStore.Pending(++seq, UUID.randomUUID(), Topics.RIDE_EVENTS, key, type, "{}", null);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    private static CompletableFuture<SendResult<String, String>> broken() {
        return CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"));
    }

    @Test
    void confirmedEventsAreMarkedPublishedInOrder() {
        OutboxStore.Pending first = event("ride-1", "a");
        OutboxStore.Pending second = event("ride-1", "b");
        when(store.tryLockForRelay()).thenReturn(true);
        when(store.findUnpublished(100)).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(ok());

        int published = relay.relayOnce();

        assertThat(published).isEqualTo(2);
        var order = org.mockito.Mockito.inOrder(store);
        order.verify(store).markPublished(first.seq());
        order.verify(store).markPublished(second.seq());
    }

    @Test
    void whenKafkaFailsNothingIsMarkedAndLaterEventsAreNotSentAhead() {
        when(store.tryLockForRelay()).thenReturn(true);
        when(store.findUnpublished(100)).thenReturn(List.of(event("ride-1", "a"), event("ride-1", "b")));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(broken());

        int published = relay.relayOnce();

        assertThat(published).isZero();
        verify(store, never()).markPublished(anyLong());   // not confirmed, so not marked: it will be sent again
        verify(kafka, times(1)).send(any(ProducerRecord.class));   // it did not try the second one past the failure
    }

    @Test
    void afterAnOutageTheSameEventsAreSentOnTheNextPass() {
        OutboxStore.Pending first = event("ride-1", "a");
        when(store.tryLockForRelay()).thenReturn(true);
        when(store.findUnpublished(100)).thenReturn(List.of(first));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(broken(), ok());

        assertThat(relay.relayOnce()).isZero();
        assertThat(relay.relayOnce()).isEqualTo(1);

        verify(store, times(1)).markPublished(first.seq());
    }

    @Test
    void aSuccessfulEventBeforeAFailureStaysPublished() {
        OutboxStore.Pending first = event("ride-1", "a");
        OutboxStore.Pending second = event("ride-1", "b");
        OutboxStore.Pending third = event("ride-1", "c");
        when(store.tryLockForRelay()).thenReturn(true);
        when(store.findUnpublished(100)).thenReturn(List.of(first, second, third));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(ok(), broken());

        assertThat(relay.relayOnce()).isEqualTo(1);

        verify(store).markPublished(first.seq());
        verify(store, never()).markPublished(second.seq());
        verify(store, never()).markPublished(third.seq());
    }

    @Test
    void ifAnotherServerIsRelayingThisOneDoesNothing() {
        when(store.tryLockForRelay()).thenReturn(false);

        assertThat(relay.relayOnce()).isZero();

        verify(store, never()).findUnpublished(100);
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void theEventIdAndTypeTravelAsHeadersSoConsumersCanFilterWithoutParsing() {
        OutboxStore.Pending first = event("ride-1", "ride.status-changed");
        when(store.tryLockForRelay()).thenReturn(true);
        when(store.findUnpublished(100)).thenReturn(List.of(first));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(ok());

        relay.relayOnce();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<ProducerRecord<String, String>> sent = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        assertThat(sent.getValue().key()).isEqualTo("ride-1");
        assertThat(new String(sent.getValue().headers().lastHeader("event-type").value())).isEqualTo("ride.status-changed");
        assertThat(new String(sent.getValue().headers().lastHeader("event-id").value())).isEqualTo(first.eventId().toString());
    }

    @Test
    void confirmedAndFailedPublicationsAreCountedForTheDashboards() {
        when(store.tryLockForRelay()).thenReturn(true);
        when(store.findUnpublished(100)).thenReturn(List.of(event("ride-1", "a"), event("ride-1", "b")));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(ok(), broken());

        relay.relayOnce();

        assertThat(registry.get("ridex.outbox.published").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ridex.outbox.publish.failures").counter().count()).isEqualTo(1.0);
    }
}
