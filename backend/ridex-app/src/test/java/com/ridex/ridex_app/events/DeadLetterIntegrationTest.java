package com.ridex.ridex_app.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.support.AbstractEventTest;
import com.ridex.ridex_app.support.ApiClient.Response;

/** Failures: retried with a pause, then parked in a dead-letter topic, without ever blocking the messages behind. */
class DeadLetterIntegrationTest extends AbstractEventTest {

    private List<String> deadGroups(String key) {
        return jdbc.queryForList("select failed_group from dead_letter_events where event_key = ? order by failed_group", String.class, key);
    }

    @Test
    void aMessageThatIsNotAnEventGoesStraightToTheDeadLetterTopicOfEveryGroup() {
        String key = "garbage-" + UUID.randomUUID();

        publish(Topics.RIDE_EVENTS, key, "this is not json at all");

        assertThat(awaitTrue(() -> deadGroups(key).size() >= 4, 40)).as("all four groups give up on it").isTrue();
        assertThat(deadGroups(key)).containsExactly("analytics", "audit", "notifications", "payments");
        assertThat(jdbc.queryForObject("select payload from dead_letter_events where event_key = ? limit 1", String.class, key))
                .isEqualTo("this is not json at all");
        assertThat(jdbc.queryForObject("select error_message from dead_letter_events where event_key = ? limit 1", String.class, key))
                .as("with the reason it failed").isNotBlank();
    }

    @Test
    void theDeadLetterMessageIsAlsoKeptInTheDeadLetterTopicWithItsOrigin() {
        String key = "garbage-" + UUID.randomUUID();
        publish(Topics.RIDE_EVENTS, key, "{ broken");

        try (KafkaConsumer<String, String> consumer = consumer(uniqueGroup("dlt-reader"), Topics.deadLetterOf(Topics.RIDE_EVENTS))) {
            List<ConsumerRecord<String, String>> found = readUntil(consumer,
                    records -> records.stream().anyMatch(r -> key.equals(r.key())), 40);

            ConsumerRecord<String, String> dead = found.stream().filter(r -> key.equals(r.key())).findFirst().orElseThrow();
            assertThat(dead.value()).isEqualTo("{ broken");
            assertThat(dead.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
            assertThat(dead.headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
        }
    }

    @Test
    void aPoisonMessageDoesNotBlockTheMessagesBehindIt() {
        String key = "garbage-" + UUID.randomUUID();
        publish(Topics.RIDE_EVENTS, key, "poison");   // first a bad message...

        Journey journey = completeRide(80.0, 70.0);   // ...then a normal ride

        assertThat(awaitTrue(() -> {
            Response list = api().get("/api/notifications?size=50", journey.rider().token());
            return list.status() == 200 && list.<List<String>>json("$.content[*].type").contains("TRIP_COMPLETED");
        }, 40)).as("normal events are still handled").isTrue();
    }

    @Test
    void aFailureThatMightBeTemporaryIsRetriedBeforeGivingUp() {
        // The rider does not exist, so saving the notification fails. A real system cannot tell "not there YET" from
        // "never there", so it tries again after a pause, a few times, and only then dead-letters.
        String rideId = UUID.randomUUID().toString();
        long started = System.currentTimeMillis();
        String event = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"ride.status-changed\",\"version\":1,\"occurredAt\":\"" + Instant.now()
                + "\",\"key\":\"" + rideId + "\",\"data\":{\"rideId\":\"" + rideId + "\",\"riderUserId\":\"" + UUID.randomUUID()
                + "\",\"driverUserId\":null,\"from\":\"DRIVER_ASSIGNED\",\"to\":\"DRIVER_ACCEPTED\",\"actor\":\"DRIVER\",\"note\":null,\"at\":\""
                + Instant.now() + "\",\"pickupAddress\":\"a\",\"dropoffAddress\":\"b\",\"fareTotal\":100.00,\"currency\":\"INR\"}}";

        publish(Topics.RIDE_EVENTS, rideId, event);

        assertThat(awaitTrue(() -> deadGroups(rideId).contains("notifications"), 40)).isTrue();
        // With 3 attempts and pauses of 50 ms and 100 ms, giving up takes at least the pauses (and in practice much more)
        assertThat(System.currentTimeMillis() - started).isGreaterThanOrEqualTo(150);
        assertThat(deadGroups(rideId)).as("audit and analytics succeeded, only the failing group dead-lettered")
                .doesNotContain("audit", "analytics");
    }

    @Test
    void anEventForARideThatDoesNotExistCannotBeChargedAndEndsInTheDeadLetterTopic() {
        String rideId = UUID.randomUUID().toString();
        String event = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"ride.status-changed\",\"version\":1,\"occurredAt\":\"" + Instant.now()
                + "\",\"key\":\"" + rideId + "\",\"data\":{\"rideId\":\"" + rideId + "\",\"riderUserId\":\"" + UUID.randomUUID()
                + "\",\"driverUserId\":\"" + UUID.randomUUID() + "\",\"from\":\"RIDE_STARTED\",\"to\":\"RIDE_COMPLETED\",\"actor\":\"DRIVER\",\"note\":null,\"at\":\""
                + Instant.now() + "\",\"pickupAddress\":\"a\",\"dropoffAddress\":\"b\",\"fareTotal\":100.00,\"currency\":\"INR\"}}";

        publish(Topics.RIDE_EVENTS, rideId, event);

        assertThat(awaitTrue(() -> deadGroups(rideId).contains("payments"), 40)).isTrue();
        assertThat(count("select count(*) from payments where ride_id = ?::uuid", rideId)).as("nobody was charged").isZero();
    }
}
