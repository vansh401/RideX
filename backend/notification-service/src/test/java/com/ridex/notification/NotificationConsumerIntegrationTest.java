package com.ridex.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.ridex.platform.contracts.Topics;
import com.ridex.platform.testing.EventTestSupport;

/**
 * The notification service as a pure event consumer: the tests play the part of the core and payment services by
 * publishing the events they would publish, and check what the notification service does.
 */
class NotificationConsumerIntegrationTest extends NotificationTestBase {

    private static final BigDecimal FARE = new BigDecimal("252.00");

    @Autowired
    private RedisConnectionFactory redisConnections;

    private List<String> typesFor(UUID user) {
        return jdbc.queryForList("select type from notifications where user_id = ? order by created_at, type", String.class, user);
    }

    private void ride(UUID rideId, UUID rider, UUID driver, String from, String to, String actor) {
        publish(Topics.RIDE_EVENTS, rideId.toString(), EventTestSupport.rideEvent(UUID.randomUUID(), rideId, rider, driver, from, to, actor, FARE));
    }

    @Test
    void eachStepOfARideNotifiesTheRightPerson() {
        UUID rideId = id(), rider = id(), driver = id();

        ride(rideId, rider, driver, "SEARCHING_DRIVER", "DRIVER_ASSIGNED", "SYSTEM");
        ride(rideId, rider, driver, "DRIVER_ASSIGNED", "DRIVER_ACCEPTED", "DRIVER");
        ride(rideId, rider, driver, "DRIVER_ACCEPTED", "DRIVER_ARRIVED", "DRIVER");
        ride(rideId, rider, driver, "DRIVER_ARRIVED", "RIDE_STARTED", "DRIVER");
        ride(rideId, rider, driver, "RIDE_STARTED", "RIDE_COMPLETED", "DRIVER");

        assertThat(await(() -> typesFor(rider).size() >= 4 && typesFor(driver).size() >= 2, 40)).isTrue();
        assertThat(typesFor(rider)).containsExactlyInAnyOrder("RIDE_ACCEPTED", "DRIVER_ARRIVED", "TRIP_STARTED", "TRIP_COMPLETED");
        assertThat(typesFor(driver)).containsExactlyInAnyOrder("RIDE_OFFER", "TRIP_COMPLETED");
    }

    @Test
    void theOfferShowsWhereTheRideGoesAndWhatItPays() {
        UUID rideId = id(), rider = id(), driver = id();

        ride(rideId, rider, driver, "SEARCHING_DRIVER", "DRIVER_ASSIGNED", "SYSTEM");

        assertThat(await(() -> count("select count(*) from notifications where user_id = ?", driver) == 1, 40)).isTrue();
        String body = jdbc.queryForObject("select body from notifications where user_id = ?", String.class, driver);
        assertThat(body).contains("12 Lake Road").contains("90 Hill Street").contains("252.00");
        assertThat(count("select count(*) from notifications where user_id = ?", rider)).as("the rider is not told about offers").isZero();
    }

    @Test
    void cancellationsTellTheOtherPartyOnlyWhenItMatters() {
        UUID byDriver = id(), riderA = id(), driverA = id();
        UUID byRiderAfterAccept = id(), riderB = id(), driverB = id();
        UUID byRiderBeforeAccept = id(), riderC = id(), driverC = id();
        UUID bySystem = id(), riderD = id();

        ride(byDriver, riderA, driverA, "DRIVER_ACCEPTED", "CANCELLED", "DRIVER");
        ride(byRiderAfterAccept, riderB, driverB, "DRIVER_ACCEPTED", "CANCELLED", "RIDER");
        ride(byRiderBeforeAccept, riderC, driverC, "DRIVER_ASSIGNED", "CANCELLED", "RIDER");
        ride(bySystem, riderD, null, "SEARCHING_DRIVER", "CANCELLED", "SYSTEM");

        assertThat(await(() -> typesFor(riderA).size() == 1 && typesFor(driverB).size() == 1 && typesFor(riderD).size() == 1, 40)).isTrue();
        assertThat(typesFor(riderA)).containsExactly("RIDE_CANCELLED");   // the driver cancelled: tell the rider
        assertThat(typesFor(driverB)).containsExactly("RIDE_CANCELLED");  // the rider cancelled after accepting: tell the driver
        assertThat(typesFor(riderD)).containsExactly("NO_DRIVER_FOUND");  // nobody was found
        // a rider cancelling a still-unanswered offer needs no notification (the offer simply disappears)
        assertThat(await(() -> count("select count(*) from processed_events where consumer_group = 'notifications'") >= 4, 20)).isTrue();
        assertThat(typesFor(driverC)).isEmpty();
    }

    @Test
    void paymentResultsNotifyTheRiderAndTheDriver() {
        UUID rideId = id(), rider = id(), driver = id(), failedRide = id(), failedRider = id(), failedDriver = id();

        publish(Topics.PAYMENT_EVENTS, rideId.toString(),
                EventTestSupport.paymentEvent(id(), "payment.succeeded", rideId, rider, driver, FARE, null));
        publish(Topics.PAYMENT_EVENTS, failedRide.toString(),
                EventTestSupport.paymentEvent(id(), "payment.failed", failedRide, failedRider, failedDriver, FARE, "card_declined"));

        assertThat(await(() -> typesFor(rider).size() == 1 && typesFor(driver).size() == 1 && typesFor(failedRider).size() == 1, 40)).isTrue();
        assertThat(typesFor(rider)).containsExactly("PAYMENT_RECEIVED");
        assertThat(typesFor(driver)).containsExactly("EARNING_CREDITED");
        assertThat(typesFor(failedRider)).containsExactly("PAYMENT_FAILED");
        assertThat(jdbc.queryForObject("select body from notifications where user_id = ?", String.class, failedRider)).contains("card_declined");
        assertThat(typesFor(failedDriver)).as("the driver is not told about a failed charge").isEmpty();
    }

    @Test
    void driverDecisionsNotifyTheDriver() {
        UUID approved = id(), rejected = id();

        publish(Topics.DRIVER_EVENTS, approved.toString(), EventTestSupport.driverReviewedEvent(id(), approved, "APPROVED", null));
        publish(Topics.DRIVER_EVENTS, rejected.toString(), EventTestSupport.driverReviewedEvent(id(), rejected, "REJECTED", "Licence photo is unreadable"));

        assertThat(await(() -> typesFor(approved).size() == 1 && typesFor(rejected).size() == 1, 40)).isTrue();
        assertThat(typesFor(approved)).containsExactly("DRIVER_APPROVED");
        assertThat(typesFor(rejected)).containsExactly("DRIVER_REJECTED");
        assertThat(jdbc.queryForObject("select body from notifications where user_id = ?", String.class, rejected)).isEqualTo("Licence photo is unreadable");
    }

    @Test
    void aUserThisServiceHasNeverHeardOfStillGetsNotified() {
        // Users live in another service's database. Here a user is only an id, so there is nothing to look up and nothing
        // to fail: no foreign key, no call to another service.
        UUID unknownRider = id();

        ride(id(), unknownRider, id(), "DRIVER_ASSIGNED", "DRIVER_ACCEPTED", "DRIVER");

        assertThat(await(() -> typesFor(unknownRider).size() == 1, 40)).isTrue();
    }

    @Test
    void aDuplicateDeliveryCreatesNoSecondNotification() {
        UUID rideId = id(), rider = id();
        UUID eventId = id();
        String event = EventTestSupport.rideEvent(eventId, rideId, rider, id(), "DRIVER_ASSIGNED", "DRIVER_ACCEPTED", "DRIVER", FARE);

        publish(Topics.RIDE_EVENTS, rideId.toString(), event);
        publish(Topics.RIDE_EVENTS, rideId.toString(), event);   // the very same event, delivered again
        publish(Topics.RIDE_EVENTS, rideId.toString(), event);
        // a marker after the duplicates (same key, same partition): once it is handled, the duplicates were too
        UUID marker = id();
        publish(Topics.RIDE_EVENTS, rideId.toString(), EventTestSupport.rideEvent(marker, rideId, rider, id(), "DRIVER_ACCEPTED", "DRIVER_ARRIVED", "DRIVER", FARE));

        assertThat(await(() -> count("select count(*) from processed_events where event_id = ?", marker) == 1, 40)).isTrue();
        assertThat(typesFor(rider)).containsExactlyInAnyOrder("RIDE_ACCEPTED", "DRIVER_ARRIVED");   // not RIDE_ACCEPTED three times
        assertThat(count("select count(*) from processed_events where event_id = ?", eventId)).isEqualTo(1);
    }

    @Test
    void eventTypesItDoesNotKnowAreIgnoredNotFailed() {
        UUID rideId = id(), rider = id();
        publish(Topics.RIDE_EVENTS, rideId.toString(), "{\"eventId\":\"" + id() + "\",\"type\":\"ride.something-new\",\"version\":3,"
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"key\":\"" + rideId + "\",\"data\":{\"anything\":true}}");
        publish(Topics.RIDE_EVENTS, rideId.toString(), EventTestSupport.rideEvent(id(), rideId, rider, id(), "DRIVER_ASSIGNED", "DRIVER_ACCEPTED", "DRIVER", FARE));

        assertThat(await(() -> typesFor(rider).size() == 1, 40)).as("the unknown event did not block the next one").isTrue();
    }

    @Test
    void aBrokenMessageGoesToTheDeadLetterTopicAndDoesNotBlockTheNext() {
        String key = "poison-" + id();
        UUID rideId = id(), rider = id();

        publish(Topics.RIDE_EVENTS, key, "{ this is not an event");
        publish(Topics.RIDE_EVENTS, key, EventTestSupport.rideEvent(id(), rideId, rider, id(), "DRIVER_ASSIGNED", "DRIVER_ACCEPTED", "DRIVER", FARE));

        assertThat(await(() -> typesFor(rider).size() == 1, 40)).as("later events are still handled").isTrue();
        try (KafkaConsumer<String, String> dlt = EventTestSupport.consumer(bootstrap, "dlt-check-" + id(), Topics.deadLetterOf(Topics.RIDE_EVENTS))) {
            List<ConsumerRecord<String, String>> found = EventTestSupport.readUntil(dlt, r -> r.stream().anyMatch(x -> key.equals(x.key())), 40);
            ConsumerRecord<String, String> dead = found.stream().filter(r -> key.equals(r.key())).findFirst().orElseThrow();
            assertThat(dead.value()).isEqualTo("{ this is not an event");
            assertThat(new String(dead.headers().lastHeader("kafka_dlt-original-consumer-group").value())).isEqualTo("notifications");
        }
    }

    @Test
    void everyNotificationIsAlsoPushedToTheUsersBrowserThroughTheRedisChannel() {
        UUID rideId = id(), rider = id();
        List<String> pushed = new CopyOnWriteArrayList<>();
        RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(redisConnections);
        listener.addMessageListener((message, pattern) -> pushed.add(new String(message.getBody())), new ChannelTopic("realtime:events"));
        listener.afterPropertiesSet();
        listener.start();
        try {
            ride(rideId, rider, id(), "DRIVER_ASSIGNED", "DRIVER_ACCEPTED", "DRIVER");

            assertThat(await(() -> pushed.stream().anyMatch(p -> p.contains(rider.toString())), 40)).isTrue();
            String push = pushed.stream().filter(p -> p.contains(rider.toString())).findFirst().orElseThrow();
            // exactly the envelope the core's WebSocket bridge understands
            assertThat(push).contains("\"kind\":\"USER\"").contains("\"destination\":\"/queue/notifications\"")
                    .contains("\"type\":\"RIDE_ACCEPTED\"").contains("\"read\":false");
        } finally {
            listener.stop();
        }
    }
}
