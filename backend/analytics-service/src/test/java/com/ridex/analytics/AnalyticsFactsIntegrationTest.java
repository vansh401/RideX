package com.ridex.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ridex.platform.contracts.Topics;
import com.ridex.platform.testing.EventTestSupport;
import com.ridex.platform.testing.TestTokens;

/** The facts the charts are built from, the admin chart endpoints, and the fraud signals, driven by the events the other services would publish. */
class AnalyticsFactsIntegrationTest extends AnalyticsTestBase {

    private Http http;
    private String admin;

    @BeforeEach
    void client() {
        http = new Http(port);
        admin = TestTokens.token(id(), "ADMIN");
    }

    private void ride(UUID ride, UUID rider, UUID driver, String from, String to, String actor, String fare, String category, String cell, Instant at) {
        publish(Topics.RIDE_EVENTS, ride.toString(), EventTestSupport.rideEventAt(id(), ride, rider, driver, from, to, actor, fare == null ? null : new BigDecimal(fare), category, cell, at));
    }

    /** A whole ride: searching, accepted, completed (the pieces the facts need). */
    private UUID completedRide(UUID rider, UUID driver, String fare, String category, String cell, Instant requested, long secondsToAccept, long tripSeconds) {
        UUID ride = id();
        ride(ride, rider, null, "REQUESTED", "SEARCHING_DRIVER", "SYSTEM", fare, category, cell, requested);
        ride(ride, rider, driver, "DRIVER_ASSIGNED", "DRIVER_ACCEPTED", "DRIVER", fare, category, cell, requested.plusSeconds(secondsToAccept));
        ride(ride, rider, driver, "RIDE_STARTED", "RIDE_COMPLETED", "DRIVER", fare, category, cell, requested.plusSeconds(secondsToAccept + tripSeconds));
        return ride;
    }

    private boolean factStatus(UUID ride, String status) {
        return await(() -> status.equals(jdbc.queryForList("select final_status from ride_facts where ride_id = ?", String.class, ride).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null)), 40);
    }

    private int signals(String rule, String subject) {
        return count("select count(*) from fraud_signals where rule = ? and subject_id = ?", rule, subject);
    }

    // ------------------------------------------------------------------ facts

    @Test
    void aRidesEventsBuildOneFactRowThatKeepsWhatEarlierEventsKnew() {
        UUID rider = id(), driver = id();
        Instant t = Instant.now().minus(30, ChronoUnit.MINUTES);

        UUID ride = completedRide(rider, driver, "252.00", "CAB", "c100_200", t, 45, 900);

        assertThat(factStatus(ride, "RIDE_COMPLETED")).isTrue();
        var row = jdbc.queryForMap("select * from ride_facts where ride_id = ?", ride);
        assertThat(row.get("category")).isEqualTo("CAB");
        assertThat(row.get("pickup_cell")).isEqualTo("c100_200");
        assertThat(row.get("driver_user_id")).isEqualTo(driver);
        assertThat((BigDecimal) row.get("fare")).isEqualByComparingTo("252.00");
        assertThat(row.get("accepted_at")).isNotNull();
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(count("select count(*) from ride_facts where ride_id = ?", ride)).isEqualTo(1);
    }

    @Test
    void aCancellationRecordsWhoCancelled() {
        UUID rider = id(), ride = id();
        Instant t = Instant.now().minus(20, ChronoUnit.MINUTES);

        ride(ride, rider, null, "REQUESTED", "SEARCHING_DRIVER", "SYSTEM", "180.00", "AUTO", "c1_1", t);
        ride(ride, rider, null, "SEARCHING_DRIVER", "CANCELLED", "RIDER", "180.00", "AUTO", "c1_1", t.plusSeconds(30));

        assertThat(factStatus(ride, "CANCELLED")).isTrue();
        assertThat(jdbc.queryForObject("select cancelled_by from ride_facts where ride_id = ?", String.class, ride)).isEqualTo("RIDER");
    }

    @Test
    void paymentsAddUpWithCouponsAndRefunds() {
        UUID rider = id(), driver = id(), ride = id();
        Instant t = Instant.now().minus(10, ChronoUnit.MINUTES);

        publish(Topics.PAYMENT_EVENTS, ride.toString(), EventTestSupport.paymentEventFull(id(), "payment.succeeded", ride, rider, driver, new BigDecimal("252.00"), new BigDecimal("50.00"), "GATEWAY", null, t));
        publish(Topics.PAYMENT_EVENTS, ride.toString(), EventTestSupport.paymentEventFull(id(), "payment.refunded", ride, rider, driver, new BigDecimal("252.00"), new BigDecimal("50.00"), "GATEWAY", new BigDecimal("80.00"), t.plusSeconds(60)));

        assertThat(await(() -> count("select count(*) from payment_facts where ride_id = ? and refunded = 80", ride) == 1, 40)).isTrue();
        var row = jdbc.queryForMap("select * from payment_facts where ride_id = ?", ride);
        assertThat((BigDecimal) row.get("discount")).isEqualByComparingTo("50.00");
        assertThat(row.get("method")).isEqualTo("GATEWAY");
    }

    // ------------------------------------------------------------------ the charts

    @Test
    void theChartEndpointsAggregateTheFactsAndAreForAdministratorsOnly() {
        // an isolated stretch: rides in the future of other tests' data are impossible, so compare before and after
        long before = http.get("/api/admin/analytics/overview?days=1", admin).<Integer>json("$.ridesCompleted").longValue();
        UUID rider = id(), driver = id();
        Instant t = Instant.now().minus(5, ChronoUnit.MINUTES);
        UUID a = completedRide(rider, driver, "300.00", "CAB", "c777_888", t, 30, 600);
        UUID b = completedRide(id(), driver, "100.00", "BIKE", "c777_888", t.plusSeconds(10), 20, 400);
        assertThat(factStatus(a, "RIDE_COMPLETED") && factStatus(b, "RIDE_COMPLETED")).isTrue();

        Http.Response overview = http.get("/api/admin/analytics/overview?days=1", admin);
        assertThat(overview.status()).isEqualTo(200);
        assertThat(overview.<Integer>json("$.ridesCompleted").longValue()).isEqualTo(before + 2);
        assertThat(http.get("/api/admin/analytics/daily?days=7", admin).<List<?>>json("$")).as("a point for every day").hasSize(7);
        assertThat(http.get("/api/admin/analytics/hours?days=7", admin).<List<?>>json("$")).hasSize(24);
        Http.Response categories = http.get("/api/admin/analytics/categories?days=1", admin);
        assertThat(categories.<List<String>>json("$[*].category")).contains("CAB", "BIKE");
        Http.Response zones = http.get("/api/admin/analytics/zones?days=1&limit=50", admin);
        assertThat(zones.<List<String>>json("$[*].zone")).contains("c777_888");
        assertThat(http.get("/api/admin/analytics/top-drivers?days=1&limit=50", admin).<List<String>>json("$[*].driverUserId")).contains(driver.toString());

        assertThat(http.get("/api/admin/analytics/overview", TestTokens.token(id(), "RIDER")).status()).isEqualTo(403);
        assertThat(http.get("/api/admin/analytics/overview", null).status()).isEqualTo(401);
        assertThat(http.get("/api/admin/analytics/overview?days=1000", admin).status()).as("a window beyond a year is refused").isEqualTo(400);
    }

    // ------------------------------------------------------------------ fraud signals

    @Test
    void aRiderWhoCancelsFourRidesAfterDriversAcceptedRaisesOneSignalForTheBurst() {
        UUID rider = id(), driver = id();
        Instant t = Instant.now().minus(30, ChronoUnit.MINUTES);
        for (int i = 0; i < 5; i++) {
            UUID ride = id();
            ride(ride, rider, null, "REQUESTED", "SEARCHING_DRIVER", "SYSTEM", "150.00", "CAB", "c5_5", t.plusSeconds(i * 100));
            ride(ride, rider, driver, "DRIVER_ASSIGNED", "DRIVER_ACCEPTED", "DRIVER", "150.00", "CAB", "c5_5", t.plusSeconds(i * 100 + 20));
            ride(ride, rider, driver, "DRIVER_ACCEPTED", "CANCELLED", "RIDER", "150.00", "CAB", "c5_5", t.plusSeconds(i * 100 + 40));
        }

        assertThat(await(() -> signals("RIDER_CANCEL_BURST", rider.toString()) >= 1, 40)).isTrue();
        EventTestSupport.awaitTrue(() -> false, 3);
        assertThat(signals("RIDER_CANCEL_BURST", rider.toString())).as("the fourth and the fifth cancellation are one burst: one signal").isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from fraud_signals where rule = 'RIDER_CANCEL_BURST' and subject_id = ?", String.class, rider.toString())).isEqualTo("NEW");
    }

    @Test
    void aTripCompletedSixtySecondsAfterAcceptanceIsFlaggedAndNormalTripsAreNot() {
        UUID rider = id(), driver = id();
        Instant t = Instant.now().minus(30, ChronoUnit.MINUTES);

        UUID fake = completedRide(rider, driver, "400.00", "CAB", "c6_6", t, 10, 50);                 // accepted to completed: 60 s
        UUID normal = completedRide(id(), id(), "400.00", "CAB", "c6_6", t, 40, 900);

        assertThat(await(() -> signals("INSTANT_COMPLETION", fake.toString()) == 1, 40)).isTrue();
        assertThat(factStatus(normal, "RIDE_COMPLETED")).isTrue();
        assertThat(signals("INSTANT_COMPLETION", normal.toString())).isZero();
    }

    @Test
    void theSameRiderAndDriverTogetherThreeTimesInADayIsAPairSignal() {
        UUID rider = id(), driver = id();
        Instant t = Instant.now().minus(60, ChronoUnit.MINUTES);

        for (int i = 0; i < 3; i++) {
            completedRide(rider, driver, "200.00", "CAB", "c7_7", t.plusSeconds(i * 600), 60, 300);
        }

        assertThat(await(() -> signals("REPEAT_PAIR", rider + ":" + driver) == 1, 40)).isTrue();
    }

    @Test
    void couponsOnThreeRidesInADayAndHeavyRefundsAreSignalsToo() {
        UUID rider = id(), driver = id();
        Instant t = Instant.now().minus(60, ChronoUnit.MINUTES);

        for (int i = 0; i < 3; i++) {
            UUID ride = id();
            publish(Topics.PAYMENT_EVENTS, ride.toString(), EventTestSupport.paymentEventFull(id(), "payment.succeeded", ride, rider, driver, new BigDecimal("200.00"), new BigDecimal("50.00"), "GATEWAY", null, t.plusSeconds(i * 60)));
            publish(Topics.PAYMENT_EVENTS, ride.toString(), EventTestSupport.paymentEventFull(id(), "payment.refunded", ride, rider, driver, new BigDecimal("200.00"), new BigDecimal("50.00"), "GATEWAY", new BigDecimal("100.00"), t.plusSeconds(i * 60 + 30)));
        }

        assertThat(await(() -> signals("COUPON_ABUSE", rider.toString()) == 1, 40)).isTrue();
        assertThat(await(() -> signals("REFUND_HEAVY", rider.toString()) == 1, 40)).as("3 refunds of 100 against 450 paid").isTrue();
    }

    @Test
    void anAdministratorReviewsSignalsAndSeesTheRiskOfAPerson() {
        UUID rider = id(), driver = id();
        Instant t = Instant.now().minus(30, ChronoUnit.MINUTES);
        UUID fake = completedRide(rider, driver, "500.00", "CAB", "c8_8", t, 5, 40);
        assertThat(await(() -> signals("INSTANT_COMPLETION", fake.toString()) == 1, 40)).isTrue();
        long id = jdbc.queryForObject("select id from fraud_signals where rule = 'INSTANT_COMPLETION' and subject_id = ?", Long.class, fake.toString());

        Http.Response queue = http.get("/api/admin/fraud/signals?status=NEW&rule=INSTANT_COMPLETION&size=100", admin);
        assertThat(queue.<List<Integer>>json("$.content[*].id")).contains((int) id);
        Http.Response decided = http.post("/api/admin/fraud/signals/" + id + "/review", admin, "{\"status\":\"CONFIRMED\"}");
        assertThat(decided.status()).isEqualTo(200);
        assertThat((String) decided.json("$.status")).isEqualTo("CONFIRMED");
        assertThat(http.post("/api/admin/fraud/signals/" + id + "/review", admin, "{\"status\":\"MAYBE\"}").status()).isEqualTo(400);
        assertThat(http.get("/api/admin/fraud/summary", admin).<List<String>>json("$[*].rule")).contains("INSTANT_COMPLETION");
        assertThat(http.get("/api/admin/fraud/signals", TestTokens.token(id(), "DRIVER")).status()).isEqualTo(403);
        assertThat(http.get("/api/admin/fraud/users/" + fake, admin).status()).isEqualTo(200);
    }

    @Test
    void replayingTheSameEventsCreatesNeitherDuplicateFactsNorDuplicateSignals() {
        UUID rider = id(), driver = id();
        Instant t = Instant.now().minus(30, ChronoUnit.MINUTES);
        UUID ride = id();
        UUID e1 = id(), e2 = id(), e3 = id();
        for (int round = 0; round < 2; round++) {   // the same three events, published twice
            publish(Topics.RIDE_EVENTS, ride.toString(), EventTestSupport.rideEventAt(e1, ride, rider, null, "REQUESTED", "SEARCHING_DRIVER", "SYSTEM", new BigDecimal("400.00"), "CAB", "c9_9", t));
            publish(Topics.RIDE_EVENTS, ride.toString(), EventTestSupport.rideEventAt(e2, ride, rider, driver, "DRIVER_ASSIGNED", "DRIVER_ACCEPTED", "DRIVER", new BigDecimal("400.00"), "CAB", "c9_9", t.plusSeconds(5)));
            publish(Topics.RIDE_EVENTS, ride.toString(), EventTestSupport.rideEventAt(e3, ride, rider, driver, "RIDE_STARTED", "RIDE_COMPLETED", "DRIVER", new BigDecimal("400.00"), "CAB", "c9_9", t.plusSeconds(40)));
        }

        assertThat(await(() -> signals("INSTANT_COMPLETION", ride.toString()) == 1, 40)).isTrue();
        EventTestSupport.awaitTrue(() -> false, 3);
        assertThat(count("select count(*) from ride_facts where ride_id = ?", ride)).isEqualTo(1);
        assertThat(signals("INSTANT_COMPLETION", ride.toString())).isEqualTo(1);
    }
}
