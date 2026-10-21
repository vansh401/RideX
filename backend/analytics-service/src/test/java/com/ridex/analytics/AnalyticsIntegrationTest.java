package com.ridex.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ridex.platform.contracts.Topics;
import com.ridex.platform.testing.EventTestSupport;
import com.ridex.platform.testing.TestTokens;

/**
 * The analytics service is built only from events. The tests play the part of the other services by publishing the events
 * they would publish, then check what the service learned, and what an admin can see.
 */
class AnalyticsIntegrationTest extends AnalyticsTestBase {

    private static final BigDecimal FARE = new BigDecimal("252.00");

    private Http http;

    @BeforeEach
    void client() {
        http = new Http(port);
    }

    private void ride(UUID eventId, UUID rideId, String from, String to) {
        publish(Topics.RIDE_EVENTS, rideId.toString(), EventTestSupport.rideEvent(eventId, rideId, id(), id(), from, to, "SYSTEM", FARE));
    }

    private int auditRows(UUID rideId) {
        return count("select count(*) from event_audit_log where event_key = ?", rideId.toString());
    }

    // ------------------------------------------------------------------ audit

    @Test
    void everyEventFromEveryTopicEndsUpInTheAuditLogExactlyOnce() {
        UUID rideId = id(), rider = id(), driver = id(), eventId = id();

        publish(Topics.RIDE_EVENTS, rideId.toString(), EventTestSupport.rideEvent(eventId, rideId, rider, driver, "SEARCHING_DRIVER", "DRIVER_ASSIGNED", "SYSTEM", FARE));
        publish(Topics.RIDE_EVENTS, rideId.toString(), EventTestSupport.rideEvent(eventId, rideId, rider, driver, "SEARCHING_DRIVER", "DRIVER_ASSIGNED", "SYSTEM", FARE));   // duplicate delivery
        publish(Topics.PAYMENT_EVENTS, rideId.toString(), EventTestSupport.paymentEvent(id(), "payment.succeeded", rideId, rider, driver, FARE, null));
        UUID driverUser = id();
        publish(Topics.DRIVER_EVENTS, driverUser.toString(), EventTestSupport.driverReviewedEvent(id(), driverUser, "APPROVED", null));

        assertThat(await(() -> auditRows(rideId) >= 2 && count("select count(*) from event_audit_log where event_key = ?", driverUser.toString()) == 1, 40)).isTrue();
        EventTestSupport.awaitTrue(() -> false, 2);
        assertThat(auditRows(rideId)).as("the duplicate (same event id) is recorded once").isEqualTo(2);
    }

    @Test
    void theAuditEndpointFiltersByKeyAndKeepsTheOrderOfOneRide() {
        UUID rideId = id();
        ride(id(), rideId, "SEARCHING_DRIVER", "DRIVER_ASSIGNED");
        ride(id(), rideId, "DRIVER_ASSIGNED", "DRIVER_ACCEPTED");
        ride(id(), rideId, "DRIVER_ACCEPTED", "DRIVER_ARRIVED");
        assertThat(await(() -> auditRows(rideId) == 3, 40)).isTrue();

        var audit = http.get("/api/admin/events/audit?key=" + rideId + "&size=50", TestTokens.token(id(), "ADMIN"));

        assertThat(audit.status()).isEqualTo(200);
        assertThat(audit.<Integer>json("$.totalElements")).isEqualTo(3);
        List<Integer> partitions = audit.json("$.content[*].partition");
        assertThat(partitions.stream().distinct()).as("one ride, one partition: that is what keeps its events in order").hasSize(1);
        List<Integer> offsets = audit.json("$.content[*].offset");
        assertThat(offsets).isSortedAccordingTo(java.util.Comparator.reverseOrder());   // newest first
    }

    // ------------------------------------------------------------------ analytics

    @Test
    void dailyCountsAreBuiltFromRideAndPaymentEvents() {
        UUID rideId = id(), rider = id(), driver = id();
        int before = count("select coalesce(sum(event_count), 0) from ride_status_counts_daily where status = 'RIDE_COMPLETED'");
        int paidBefore = count("select coalesce(sum(event_count), 0) from payment_outcomes_daily where outcome = 'SUCCEEDED'");

        ride(id(), rideId, "RIDE_STARTED", "RIDE_COMPLETED");
        publish(Topics.PAYMENT_EVENTS, rideId.toString(), EventTestSupport.paymentEvent(id(), "payment.succeeded", rideId, rider, driver, FARE, null));

        assertThat(await(() -> count("select coalesce(sum(event_count), 0) from ride_status_counts_daily where status = 'RIDE_COMPLETED'") == before + 1
                && count("select coalesce(sum(event_count), 0) from payment_outcomes_daily where outcome = 'SUCCEEDED'") == paidBefore + 1, 40)).isTrue();

        String admin = TestTokens.token(id(), "ADMIN");
        var perDay = http.get("/api/admin/analytics/ride-status-per-day?days=7", admin);
        assertThat(perDay.<List<String>>json("$[*].status")).contains("RIDE_COMPLETED");
        var payments = http.get("/api/admin/analytics/payments-per-day?days=7", admin);
        assertThat(payments.status()).isEqualTo(200);
        assertThat(payments.<List<String>>json("$[*].outcome")).contains("SUCCEEDED");
    }

    @Test
    void anEventTypeThisServiceDoesNotKnowIsIgnoredWithoutFailing() {
        UUID rideId = id();
        publish(Topics.RIDE_EVENTS, rideId.toString(),
                "{\"eventId\":\"" + id() + "\",\"type\":\"ride.something-new\",\"version\":1,\"occurredAt\":\"2026-01-01T10:00:00Z\",\"key\":\"" + rideId + "\",\"data\":{}}");

        assertThat(await(() -> auditRows(rideId) == 1, 40)).as("the audit log keeps it, whatever it is").isTrue();
        assertThat(count("select count(*) from dead_letter_events where event_key = ?", rideId.toString())).isZero();
    }

    // ------------------------------------------------------------------ dead letters

    @Test
    void aBrokenMessageIsParkedAsADeadLetterAndVisibleToAdmins() {
        String key = "broken-" + UUID.randomUUID();
        publish(Topics.RIDE_EVENTS, key, "this is not an event");

        assertThat(await(() -> count("select count(*) from dead_letter_events where event_key = ?", key) >= 1, 40)).isTrue();

        var dead = http.get("/api/admin/events/dead-letters?size=100", TestTokens.token(id(), "ADMIN"));
        assertThat(dead.<List<String>>json("$.content[*].key")).contains(key);
    }

    // ------------------------------------------------------------------ lag and access

    @Test
    void theLagEndpointReportsEveryGroupOfThePlatformAndOurOwnReachZero() {
        UUID rideId = id();
        ride(id(), rideId, "SEARCHING_DRIVER", "DRIVER_ASSIGNED");
        assertThat(await(() -> auditRows(rideId) == 1, 40)).isTrue();

        var lag = http.get("/api/admin/events/lag", TestTokens.token(id(), "ADMIN"));

        assertThat(lag.status()).isEqualTo(200);
        assertThat(lag.<List<String>>json("$[*].group")).contains("notifications", "analytics", "audit", "payments", "ride-payment-saga", "dead-letters");
        assertThat(await(() -> http.get("/api/admin/events/lag", TestTokens.token(id(), "ADMIN"))
                .<List<Integer>>json("$[?(@.group=='audit' || @.group=='analytics')].lag").stream().allMatch(l -> l == 0), 30)).isTrue();
    }

    @Test
    void onlyAdminsMayLookAndOnlyWithAValidToken() {
        UUID user = id();
        for (String path : List.of("/api/admin/events/audit", "/api/admin/events/dead-letters", "/api/admin/events/lag",
                "/api/admin/analytics/ride-status-per-day", "/api/admin/analytics/payments-per-day")) {
            assertThat(http.get(path, null).status()).as(path + " anonymous").isEqualTo(401);
            assertThat(http.get(path, TestTokens.forgedToken(user, "ADMIN")).status()).as(path + " forged admin token").isEqualTo(401);
            assertThat(http.get(path, TestTokens.token(user, "RIDER")).status()).as(path + " rider").isEqualTo(403);
            assertThat(http.get(path, TestTokens.token(user, "ADMIN")).status()).as(path + " admin").isEqualTo(200);
        }
    }
}
