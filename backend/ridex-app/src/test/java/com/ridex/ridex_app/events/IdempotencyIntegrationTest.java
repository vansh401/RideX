package com.ridex.ridex_app.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;
import com.ridex.ridex_app.support.AbstractEventTest;
import com.ridex.ridex_app.support.ApiClient.Response;

/**
 * Kafka delivers "at least once", so duplicates WILL happen (a consumer crashes after working but before confirming).
 * These tests deliver the very same event several times and prove it still has the effect of happening once.
 */
class IdempotencyIntegrationTest extends AbstractEventTest {

    private int notificationsOfType(String token, String type) {
        Response response = expect(200, api().get("/api/notifications?size=50", token));
        return (int) response.<List<String>>json("$.content[*].type").stream().filter(type::equals).count();
    }

    /** A harmless later event on the same key. It shares the partition, so once it is handled everything before it was too. */
    private void publishMarker(String rideId, UUID riderUserId) {
        String marker = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"ride.status-changed\",\"version\":1,\"occurredAt\":\""
                + Instant.now() + "\",\"key\":\"" + rideId + "\",\"data\":{\"rideId\":\"" + rideId + "\",\"riderUserId\":\"" + riderUserId
                + "\",\"driverUserId\":null,\"from\":\"REQUESTED\",\"to\":\"SEARCHING_DRIVER\",\"actor\":\"SYSTEM\",\"note\":null,\"at\":\""
                + Instant.now() + "\",\"pickupAddress\":\"a\",\"dropoffAddress\":\"b\",\"fareTotal\":100.00,\"currency\":\"INR\"}}";
        publish(Topics.RIDE_EVENTS, rideId, marker);
    }

    @Test
    void theSameEventDeliveredAgainAndAgainChangesNothing() {
        Journey journey = completeRide(70.0, 70.0);
        assertThat(awaitCaughtUp(40)).isTrue();
        String started = auditedEvent(journey.rideId(), "RIDE_STARTED");
        String eventId = JsonPath.read(started, "$.eventId");

        int notificationsBefore = notificationsOfType(journey.rider().token(), "TRIP_STARTED");
        int startedCountBefore = count("select coalesce(sum(event_count), 0) from ride_status_counts_daily where status = 'RIDE_STARTED'");
        assertThat(notificationsBefore).isEqualTo(1);

        // The same message, with the same event id, arrives three more times
        for (int i = 0; i < 3; i++) {
            publish(Topics.RIDE_EVENTS, journey.rideId(), started);
        }
        publishMarker(journey.rideId(), journey.rider().user().getId());
        assertThat(awaitCaughtUp(40)).as("all groups handled the duplicates and the marker").isTrue();

        assertThat(notificationsOfType(journey.rider().token(), "TRIP_STARTED")).as("no second notification").isEqualTo(1);
        assertThat(count("select coalesce(sum(event_count), 0) from ride_status_counts_daily where status = 'RIDE_STARTED'"))
                .as("the analytics count did not grow").isEqualTo(startedCountBefore);
        assertThat(count("select count(*) from event_audit_log where event_id = ?::uuid", eventId)).as("one audit row").isEqualTo(1);
        assertThat(count("select count(*) from processed_events where event_id = ?::uuid", eventId))
                .as("one 'handled' marker per group").isEqualTo(3);   // notifications, analytics, audit (payments ignores this type)
    }

    @Test
    void aGroupRecordsAnEventAsHandledOnlyTogetherWithItsWork() {
        // processed_events and the work commit in ONE transaction. A failure in between rolls both back, so the retry does
        // the work. Here: the notifications consumer fails for an unknown user, so it must NOT leave a "handled" marker.
        String rideId = UUID.randomUUID().toString();
        UUID stranger = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String event = "{\"eventId\":\"" + eventId + "\",\"type\":\"ride.status-changed\",\"version\":1,\"occurredAt\":\"" + Instant.now()
                + "\",\"key\":\"" + rideId + "\",\"data\":{\"rideId\":\"" + rideId + "\",\"riderUserId\":\"" + stranger
                + "\",\"driverUserId\":null,\"from\":\"DRIVER_ASSIGNED\",\"to\":\"DRIVER_ACCEPTED\",\"actor\":\"DRIVER\",\"note\":null,\"at\":\""
                + Instant.now() + "\",\"pickupAddress\":\"a\",\"dropoffAddress\":\"b\",\"fareTotal\":100.00,\"currency\":\"INR\"}}";

        publish(Topics.RIDE_EVENTS, rideId, event);

        assertThat(awaitTrue(() -> count("select count(*) from dead_letter_events where event_key = ? and failed_group = 'notifications'", rideId) == 1, 40)).isTrue();
        assertThat(count("select count(*) from processed_events where event_id = ? and consumer_group = 'notifications'", eventId))
                .as("a failed attempt leaves no trace, so a retry would really run").isZero();
        assertThat(count("select count(*) from processed_events where event_id = ? and consumer_group = 'audit'", eventId))
                .as("while the group that succeeded did record it").isEqualTo(1);
    }
}
