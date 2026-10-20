package com.ridex.ridex_app.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * What the relay does when Kafka misbehaves. Mocks, so a broken broker can be simulated.
 * The rules: nothing is marked published unless Kafka confirmed it, and after a failure the pass STOPS, so a ride
 * never has a later event delivered before an earlier one.
 */
class OutboxRelayTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OutboxRelay relay = new OutboxRelay(repository, kafka, jdbc, mock(PlatformTransactionManager.class), 100);

    private OutboxEvent event(String key, String type) {
        return new OutboxEvent(UUID.randomUUID(), Topics.RIDE_EVENTS, key, type, "{}", Instant.now());
    }

    private void weHoldTheLock(boolean yes) {
        when(jdbc.queryForObject(any(String.class), any(Class.class), anyLong())).thenReturn(yes);
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
        OutboxEvent first = event("ride-1", "a");
        OutboxEvent second = event("ride-1", "b");
        weHoldTheLock(true);
        when(repository.findUnpublished(any(Pageable.class))).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(ok());

        int published = relay.relayOnce();

        assertThat(published).isEqualTo(2);
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
    }

    @Test
    void whenKafkaFailsNothingIsMarkedAndLaterEventsAreNotSentAhead() {
        OutboxEvent first = event("ride-1", "a");
        OutboxEvent second = event("ride-1", "b");
        weHoldTheLock(true);
        when(repository.findUnpublished(any(Pageable.class))).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(broken());

        int published = relay.relayOnce();

        assertThat(published).isZero();
        assertThat(first.getPublishedAt()).as("not confirmed, so not marked: it will be sent again").isNull();
        assertThat(second.getPublishedAt()).isNull();
        verify(kafka, times(1)).send(any(ProducerRecord.class));   // it did not try the second one past the failure
    }

    @Test
    void afterAnOutageTheSameEventsAreSentOnTheNextPass() {
        OutboxEvent first = event("ride-1", "a");
        weHoldTheLock(true);
        when(repository.findUnpublished(any(Pageable.class))).thenReturn(List.of(first));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(broken(), ok());

        assertThat(relay.relayOnce()).isZero();
        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(first.getPublishedAt()).isNotNull();
    }

    @Test
    void aSuccessfulEventBeforeAFailureStaysPublished() {
        OutboxEvent first = event("ride-1", "a");
        OutboxEvent second = event("ride-1", "b");
        OutboxEvent third = event("ride-1", "c");
        weHoldTheLock(true);
        when(repository.findUnpublished(any(Pageable.class))).thenReturn(List.of(first, second, third));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(ok(), broken());

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNull();
        assertThat(third.getPublishedAt()).isNull();
    }

    @Test
    void ifAnotherServerIsRelayingThisOneDoesNothing() {
        weHoldTheLock(false);

        assertThat(relay.relayOnce()).isZero();

        verify(repository, never()).findUnpublished(any(Pageable.class));
        verify(kafka, never()).send(any(ProducerRecord.class));
    }
}
