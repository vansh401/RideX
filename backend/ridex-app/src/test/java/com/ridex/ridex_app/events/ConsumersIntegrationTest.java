package com.ridex.ridex_app.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;
import com.ridex.ridex_app.support.AbstractEventTest;
import com.ridex.ridex_app.support.ApiClient.Response;

/**
 * One ride, four independent consumer groups. Each reads the same events and does its own job; none knows about the
 * others, and the ride code knows about none of them.
 */
class ConsumersIntegrationTest extends AbstractEventTest {

    private List<String> notificationTypes(String token) {
        Response response = expect(200, api().get("/api/notifications?size=50", token));
        return response.json("$.content[*].type");
    }

    @Test
    void theNotificationsGroupTellsTheRightPeopleAboutEachStep() {
        Journey journey = completeRide(60.0, 70.0);

        assertThat(awaitTrue(() -> notificationTypes(journey.rider().token()).contains("PAYMENT_RECEIVED"), 30)).isTrue();

        assertThat(notificationTypes(journey.rider().token())).contains(
                "RIDE_ACCEPTED", "DRIVER_ARRIVED", "TRIP_STARTED", "TRIP_COMPLETED", "PAYMENT_RECEIVED");
        assertThat(awaitTrue(() -> notificationTypes(journey.driver().token()).contains("EARNING_CREDITED"), 20)).isTrue();
        assertThat(notificationTypes(journey.driver().token())).contains("RIDE_OFFER", "TRIP_COMPLETED", "EARNING_CREDITED");
    }

    @Test
    void theAnalyticsGroupCountsEventsWithoutEverReadingTheRidesTable() {
        int before = count("select coalesce(sum(event_count), 0) from ride_status_counts_daily where status = 'RIDE_COMPLETED'");

        completeRide(61.0, 70.0);

        assertThat(awaitTrue(() -> count("select coalesce(sum(event_count), 0) from ride_status_counts_daily where status = 'RIDE_COMPLETED'")
                == before + 1, 30)).isTrue();
    }

    @Test
    void theAuditGroupKeepsEveryEventOfTheRideInOrderWithItsKafkaPosition() {
        Journey journey = completeRide(62.0, 70.0);

        assertThat(awaitTrue(() -> count("select count(*) from event_audit_log where event_key = ?", journey.rideId()) >= 7, 30))
                .as("6 ride events and the payment event").isTrue();

        List<String> types = jdbc.queryForList(
                "select event_type from event_audit_log where event_key = ? and topic = 'ride-events' order by offset_no", String.class, journey.rideId());
        assertThat(types).hasSize(6).containsOnly("ride.status-changed");
        List<Integer> partitions = jdbc.queryForList(
                "select distinct partition_no from event_audit_log where event_key = ? and topic = 'ride-events'", Integer.class, journey.rideId());
        assertThat(partitions).hasSize(1);
    }

    @Test
    void eachGroupKeepsItsOwnRecordOfWhatItHandled() {
        Journey journey = completeRide(63.0, 70.0);
        assertThat(awaitCaughtUp(40)).isTrue();

        String completed = auditedEvent(journey.rideId(), "RIDE_COMPLETED");
        String eventId = JsonPath.read(completed, "$.eventId");

        List<String> groups = jdbc.queryForList(
                "select consumer_group from processed_events where event_id = ?::uuid order by consumer_group", String.class, eventId);
        // This event was handled by four groups, each independently
        assertThat(groups).containsExactly("analytics", "audit", "notifications", "payments");
    }

    @Test
    void everyGroupIsFullyCaughtUpAfterTheEventsAreHandled() {
        completeRide(64.0, 70.0);

        assertThat(awaitCaughtUp(40)).as("lag 0 in every group and nothing waiting in the outbox").isTrue();
        assertThat(lagService.lag()).extracting(ConsumerLagService.Lag::group)
                .contains("analytics", "audit", "notifications", "payments", "dead-letters");
    }

    @Test
    void aSlowOrBrokenGroupDoesNotStopTheOthers() {
        // The notifications group fails for this event (the user does not exist), but analytics, audit and the ride itself
        // are unaffected: groups are independent.
        String rideId = java.util.UUID.randomUUID().toString();
        String event = "{\"eventId\":\"" + java.util.UUID.randomUUID() + "\",\"type\":\"ride.status-changed\",\"version\":1,"
                + "\"occurredAt\":\"" + java.time.Instant.now() + "\",\"key\":\"" + rideId + "\",\"data\":{\"rideId\":\"" + rideId
                + "\",\"riderUserId\":\"" + java.util.UUID.randomUUID() + "\",\"driverUserId\":null,\"from\":\"DRIVER_ACCEPTED\","
                + "\"to\":\"DRIVER_ARRIVED\",\"actor\":\"DRIVER\",\"note\":null,\"at\":\"" + java.time.Instant.now()
                + "\",\"pickupAddress\":\"a\",\"dropoffAddress\":\"b\",\"fareTotal\":100.00,\"currency\":\"INR\"}}";

        publish(Topics.RIDE_EVENTS, rideId, event);

        assertThat(awaitTrue(() -> count("select count(*) from event_audit_log where event_key = ?", rideId) == 1, 30))
                .as("audit handled it").isTrue();
        assertThat(awaitTrue(() -> count("select count(*) from dead_letter_events where event_key = ? and failed_group = 'notifications'", rideId) == 1, 40))
                .as("while notifications gave up on it and parked it").isTrue();
    }
}
