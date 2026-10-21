package com.ridex.ridex_app.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.kafka.OutboxService;
import com.ridex.ridex_app.support.AbstractEventTest;

/** The outbox: events are saved with the change, then reach Kafka, in order, keyed by ride. */
class OutboxIntegrationTest extends AbstractEventTest {

    @Autowired
    private OutboxService outbox;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void anEventCannotBeAppendedOutsideATransaction() {
        assertThatThrownBy(() -> outbox.append(Topics.RIDE_EVENTS, "k", "t", java.util.Map.of("a", 1)))
                .as("without a transaction the outbox guarantee would be meaningless")
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRolledBackTransactionLeavesNoEventBehind() {
        int before = count("select count(*) from outbox_events");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outbox.append(Topics.RIDE_EVENTS, UUID.randomUUID().toString(), "ride.status-changed", java.util.Map.of("a", 1));
            status.setRollbackOnly();   // the business change fails after the event was added
        });

        assertThat(count("select count(*) from outbox_events"))
                .as("no event describes a change that never happened").isEqualTo(before);
    }

    @Test
    void aCommittedTransactionKeepsItsEvent() {
        String key = UUID.randomUUID().toString();

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                outbox.append("outbox-test-topic", key, "test.event", java.util.Map.of("a", 1)));

        assertThat(count("select count(*) from outbox_events where event_key = ?", key)).isEqualTo(1);
    }

    @Test
    void everyRideChangeIsSavedAsAnEventTogetherWithTheChange() {
        Journey journey = completeRide(50.0, 70.0);

        List<String> types = jdbc.queryForList(
                "select payload from outbox_events where event_key = ? and topic = ? order by seq",
                String.class, journey.rideId(), Topics.RIDE_EVENTS);

        assertThat(types.stream().map(p -> JsonPath.<String>read(p, "$.data.to")).toList()).containsExactly(
                "SEARCHING_DRIVER", "DRIVER_ASSIGNED", "DRIVER_ACCEPTED", "DRIVER_ARRIVED", "RIDE_STARTED", "RIDE_COMPLETED");
    }

    @Test
    void theRelayDeliversThemToKafkaKeyedByRideAndInOrder() {
        Journey journey = completeRide(51.0, 70.0);

        try (KafkaConsumer<String, String> consumer = consumer(uniqueGroup("outbox-order"), Topics.RIDE_EVENTS)) {
            List<ConsumerRecord<String, String>> all = readUntil(consumer, records -> records.stream()
                    .filter(r -> journey.rideId().equals(r.key())).count() >= 6, 30);
            List<ConsumerRecord<String, String>> mine = all.stream().filter(r -> journey.rideId().equals(r.key())).toList();

            assertThat(mine).hasSize(6);
            assertThat(mine.stream().map(r -> JsonPath.<String>read(r.value(), "$.data.to")).toList()).containsExactly(
                    "SEARCHING_DRIVER", "DRIVER_ASSIGNED", "DRIVER_ACCEPTED", "DRIVER_ARRIVED", "RIDE_STARTED", "RIDE_COMPLETED");
            assertThat(mine.stream().map(ConsumerRecord::partition).distinct())
                    .as("the ride id is the key, so all its events share a partition and keep their order").hasSize(1);
            // Headers a consumer can read without parsing the body
            ConsumerRecord<String, String> first = mine.get(0);
            assertThat(new String(first.headers().lastHeader("event-type").value(), StandardCharsets.UTF_8)).isEqualTo("ride.status-changed");
            assertThat(new String(first.headers().lastHeader("event-id").value(), StandardCharsets.UTF_8))
                    .isEqualTo(JsonPath.<String>read(first.value(), "$.eventId"));
        }
    }

    @Test
    void eventsCarryTheEnvelopeWithAVersionSoTheirShapeCanEvolve() {
        Journey journey = completeRide(52.0, 70.0);
        String payload = publishedEvent(journey.rideId(), "RIDE_COMPLETED");

        assertThat(JsonPath.<String>read(payload, "$.type")).isEqualTo("ride.status-changed");
        assertThat(JsonPath.<Integer>read(payload, "$.version")).isEqualTo(1);
        assertThat(JsonPath.<String>read(payload, "$.key")).isEqualTo(journey.rideId());
        assertThat(JsonPath.<String>read(payload, "$.eventId")).isNotBlank();
        assertThat(JsonPath.<Number>read(payload, "$.data.fareTotal").doubleValue()).isPositive();
        assertThat(JsonPath.<String>read(payload, "$.data.currency")).isEqualTo("INR");
    }

    @Test
    void publishedEventsAreMarkedSoTheyAreNotSentAgain() {
        Journey journey = completeRide(53.0, 70.0);

        assertThat(awaitTrue(() -> count("select count(*) from outbox_events where event_key = ? and published_at is null",
                journey.rideId()) == 0, 20)).isTrue();
        assertThat(count("select count(*) from outbox_events where event_key = ?", journey.rideId())).isGreaterThanOrEqualTo(6);
    }
}
