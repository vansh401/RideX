package com.ridex.ridex_app.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.support.AbstractEventTest;
import com.ridex.ridex_app.support.ApiClient.Response;

/** The admin window into the event system. */
class AdminEventsIntegrationTest extends AbstractEventTest {

    @Test
    void onlyAdminsMayLookAtTheEventSystem() {
        Person admin = newAdmin();
        Person rider = newRider();
        for (String path : List.of("/api/admin/events/audit", "/api/admin/events/dead-letters", "/api/admin/events/lag",
                "/api/admin/events/outbox", "/api/admin/analytics/ride-status-per-day")) {
            assertThat(api().get(path, null).status()).as(path + " anonymous").isEqualTo(401);
            assertThat(api().get(path, rider.token()).status()).as(path + " rider").isEqualTo(403);
            assertThat(api().get(path, admin.token()).status()).as(path + " admin").isEqualTo(200);
        }
    }

    @Test
    void theAuditLogCanBeFilteredByRideAndShowsTheEventsInOrder() {
        Journey journey = completeRide(65.0, 70.0);
        Person admin = newAdmin();
        assertThat(awaitTrue(() -> count("select count(*) from event_audit_log where event_key = ?", journey.rideId()) >= 7, 40)).isTrue();

        Response audit = expect(200, api().get("/api/admin/events/audit?key=" + journey.rideId() + "&size=50", admin.token()));

        assertThat(audit.<Integer>json("$.totalElements")).isGreaterThanOrEqualTo(7);
        List<String> types = audit.json("$.content[*].type");
        assertThat(types).contains("ride.status-changed", "payment.succeeded");
        List<Integer> partitions = audit.json("$.content[?(@.topic=='ride-events')].partition");
        assertThat(partitions.stream().distinct()).as("one ride, one partition").hasSize(1);
    }

    @Test
    void theLagEndpointReportsEveryGroupAndReachesZero() {
        completeRide(66.0, 70.0);
        Person admin = newAdmin();
        assertThat(awaitCaughtUp(40)).isTrue();

        Response lag = expect(200, api().get("/api/admin/events/lag", admin.token()));

        assertThat(lag.<List<String>>json("$[*].group")).contains("notifications", "analytics", "audit", "payments", "dead-letters");
        assertThat(lag.<List<Integer>>json("$[*].lag")).containsOnly(0);
        assertThat(expect(200, api().get("/api/admin/events/outbox", admin.token())).<Integer>json("$.pending")).isZero();
    }

    @Test
    void deadLettersAreVisibleToAdmins() {
        Person admin = newAdmin();
        String key = "admin-view-" + java.util.UUID.randomUUID();
        publish(Topics.RIDE_EVENTS, key, "not an event");
        assertThat(awaitTrue(() -> count("select count(*) from dead_letter_events where event_key = ?", key) >= 1, 40)).isTrue();

        Response dead = expect(200, api().get("/api/admin/events/dead-letters?size=100", admin.token()));

        assertThat(dead.<List<String>>json("$.content[*].key")).contains(key);
    }

    @Test
    void theAnalyticsEndpointReturnsDailyCountsBuiltFromEvents() {
        completeRide(67.0, 70.0);
        Person admin = newAdmin();
        assertThat(awaitCaughtUp(40)).isTrue();

        Response perDay = expect(200, api().get("/api/admin/analytics/ride-status-per-day?days=7", admin.token()));

        assertThat(perDay.<List<String>>json("$[*].status")).contains("RIDE_COMPLETED", "SEARCHING_DRIVER");
        assertThat(perDay.<List<Integer>>json("$[?(@.status=='RIDE_COMPLETED')].count").stream().mapToInt(Integer::intValue).sum())
                .isGreaterThanOrEqualTo(1);
    }
}
