package com.ridex.ridex_app.ride;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ridex.platform.geo.SurgeZones;
import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;
import com.ridex.ridex_app.surge.SurgeEngine;

/**
 * Phase 14 on a ride: booking ahead, stops, the extras that travel in the event, surge measurement, and complaints about the ride.
 * Each test uses its own stretch of map (its own latitude), so drivers and demand of one test never leak into another.
 */
class RideFeaturesIntegrationTest extends AbstractApiTest {

    @Autowired
    private ScheduledRidePromoter promoter;
    @Autowired
    private RideDispatcher dispatcher;
    @Autowired
    private SurgeEngine surge;
    @Autowired
    private StringRedisTemplate redis;

    /** The plain request body with extra JSON members added before the closing brace. */
    private static String with(String category, double lat, double lng, String extra) {
        String base = rideBody(category, lat, lng);
        return base.substring(0, base.length() - 1) + "," + extra.replace('\'', '"') + "}";
    }

    private static String in(long minutes) {
        return Instant.now().plus(minutes, ChronoUnit.MINUTES).toString();
    }

    private String statusOf(String rideId, String token) {
        return api().get("/api/rides/" + rideId, token).json("$.status");
    }

    // ------------------------------------------------------------------ booking ahead

    @Test
    void aRideBookedAheadWaitsAsScheduledAndDoesNotOccupyTheRider() {
        Person rider = newRider();
        double lat = 20.0;

        Response booked = expect(201, api().post("/api/rides", rider.token(), with("CAB", lat, 70.0, "'scheduledFor':'" + in(60) + "'")));
        assertThat((String) booked.json("$.status")).isEqualTo("SCHEDULED");
        assertThat((String) booked.json("$.scheduledFor")).isNotBlank();
        assertThat((Object) booked.json("$.driver")).isNull();

        // the rider can still take a ride right now
        Response now = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat + 1, 70.0)));
        assertThat((String) now.json("$.status")).isEqualTo("SEARCHING_DRIVER");
        assertThat(statusOf(booked.id(), rider.token())).as("the booked ride is untouched").isEqualTo("SCHEDULED");
    }

    @Test
    void aBookingMustBeBetweenFifteenMinutesAndSevenDaysAheadAndRidersGetFiveOfThem() {
        Person rider = newRider();

        expect(400, api().post("/api/rides", rider.token(), with("CAB", 21.0, 70.0, "'scheduledFor':'" + in(5) + "'")));
        expect(400, api().post("/api/rides", rider.token(), with("CAB", 21.0, 70.0, "'scheduledFor':'" + in(8 * 24 * 60) + "'")));
        for (int i = 0; i < 5; i++) {
            expect(201, api().post("/api/rides", rider.token(), with("CAB", 21.0, 70.0, "'scheduledFor':'" + in(60 + i) + "'")));
        }
        expect(409, api().post("/api/rides", rider.token(), with("CAB", 21.0, 70.0, "'scheduledFor':'" + in(90) + "'")));
    }

    @Test
    void aBookedRideCanBeCancelledLikeAnyOther() {
        Person rider = newRider();
        String id = expect(201, api().post("/api/rides", rider.token(), with("CAB", 22.0, 70.0, "'scheduledFor':'" + in(60) + "'"))).id();

        Response cancelled = expect(200, api().post("/api/rides/" + id + "/cancel", rider.token(), json("{'reason':'plans changed'}")));

        assertThat((String) cancelled.json("$.status")).isEqualTo("CANCELLED");
    }

    @Test
    void whenItsTimeIsNearTheSearchStartsAndTheNoDriverTimeoutCountsFromThenNotFromTheBooking() {
        Person rider = newRider();
        String id = expect(201, api().post("/api/rides", rider.token(), with("CAB", 23.0, 70.0, "'scheduledFor':'" + in(60) + "'"))).id();
        // Time passes: the booking was made three days ago and the pickup is in five minutes
        jdbc.update("update rides set requested_at = now() - interval '3 days', scheduled_for = now() + interval '5 minutes' where id = ?::uuid", id);

        assertThat(promoter.promoteDue(Instant.now())).isEqualTo(1);
        dispatcher.runCycle();   // with the old "count from requested_at" rule the ride would be cancelled here as "no driver found"

        assertThat(statusOf(id, rider.token())).as("still searching: the clock started at promotion, not at booking").isEqualTo("SEARCHING_DRIVER");
        assertThat(promoter.promoteDue(Instant.now())).as("a second run promotes nothing again").isZero();
    }

    @Test
    void aPromoterThatRunsLateDoesNotGetTheRideCancelledTheMomentItStartsSearching() {
        Person rider = newRider();
        String id = expect(201, api().post("/api/rides", rider.token(), with("CAB", 23.5, 70.0, "'scheduledFor':'" + in(60) + "'"))).id();
        // the server was down: the pickup time passed eight minutes ago and only now is the ride found
        jdbc.update("update rides set scheduled_for = now() - interval '8 minutes' where id = ?::uuid", id);

        assertThat(promoter.promoteDue(Instant.now())).isEqualTo(1);
        dispatcher.runCycle();

        assertThat(statusOf(id, rider.token())).as("the search clock started when the search started").isEqualTo("SEARCHING_DRIVER");
    }

    @Test
    void aRideNotYetDueIsLeftAlone() {
        Person rider = newRider();
        String id = expect(201, api().post("/api/rides", rider.token(), with("CAB", 24.0, 70.0, "'scheduledFor':'" + in(120) + "'"))).id();

        promoter.promoteDue(Instant.now());

        assertThat(statusOf(id, rider.token())).isEqualTo("SCHEDULED");
    }

    @Test
    void aRiderStillOnAnotherRideIsNotInterruptedAndTheBookingIsCancelledAsMissedAfterTheGracePeriod() {
        Person rider = newRider();
        String booked = expect(201, api().post("/api/rides", rider.token(), with("CAB", 25.0, 70.0, "'scheduledFor':'" + in(60) + "'"))).id();
        expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", 25.5, 70.0)));   // busy right now (searching)
        jdbc.update("update rides set scheduled_for = now() + interval '5 minutes' where id = ?::uuid", booked);

        assertThat(promoter.promoteDue(Instant.now())).as("the rider is busy: wait").isZero();
        assertThat(statusOf(booked, rider.token())).isEqualTo("SCHEDULED");

        jdbc.update("update rides set scheduled_for = now() - interval '20 minutes' where id = ?::uuid", booked);
        promoter.promoteDue(Instant.now());

        assertThat(statusOf(booked, rider.token())).isEqualTo("CANCELLED");
        assertThat(api().get("/api/rides/" + booked, rider.token()).<String>json("$.cancelReason")).contains("Missed");
    }

    @Test
    void aBookedRideThatIsDueIsOfferedToANearbyDriverLikeAnyOtherRide() {
        Person admin = newAdmin();
        Person rider = newRider();
        double lat = 26.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.001, 70.0);
        String id = expect(201, api().post("/api/rides", rider.token(), with("CAB", lat, 70.0, "'scheduledFor':'" + in(60) + "'"))).id();
        jdbc.update("update rides set scheduled_for = now() + interval '5 minutes' where id = ?::uuid", id);

        promoter.promoteDue(Instant.now());
        dispatcher.runCycle();

        assertThat(statusOf(id, rider.token())).isEqualTo("DRIVER_ASSIGNED");
        expect(200, api().post("/api/rides/" + id + "/accept", driver.token(), null));
    }

    // ------------------------------------------------------------------ stops

    @Test
    void stopsAreKeptInOrderAndTheTripCannotEndBeforeAllAreVisited() {
        Person admin = newAdmin();
        Person rider = newRider();
        double lat = 27.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.001, 70.0);
        String stops = "'stops':[{'address':'First stop','location':{'latitude':" + coord(lat + 0.01) + ",'longitude':70.000000}},"
                + "{'address':'Second stop','location':{'latitude':" + coord(lat + 0.02) + ",'longitude':70.000000}}]";

        Response ride = expect(201, api().post("/api/rides", rider.token(), with("CAB", lat, 70.0, stops)));
        String id = ride.id();
        assertThat((List<?>) ride.json("$.stops")).hasSize(2);
        assertThat((String) ride.json("$.stops[0].address")).isEqualTo("First stop");

        expect(200, api().post("/api/rides/" + id + "/accept", driver.token(), null));
        expect(200, api().post("/api/rides/" + id + "/arrived", driver.token(), null));
        expect(409, api().post("/api/rides/" + id + "/stops/next", driver.token(), null));   // not started yet
        expect(200, api().post("/api/rides/" + id + "/start", driver.token(), null));

        Response beforeStops = expect(409, api().post("/api/rides/" + id + "/complete", driver.token(), null));
        assertThat((String) beforeStops.json("$.message")).contains("stops");
        expect(403, api().post("/api/rides/" + id + "/stops/next", rider.token(), null));
        Response first = expect(200, api().post("/api/rides/" + id + "/stops/next", driver.token(), null));
        assertThat((int) first.json("$.stopsDone")).isEqualTo(1);
        expect(200, api().post("/api/rides/" + id + "/stops/next", driver.token(), null));
        expect(409, api().post("/api/rides/" + id + "/stops/next", driver.token(), null));   // none left

        Response done = expect(200, api().post("/api/rides/" + id + "/complete", driver.token(), null));
        assertThat((String) done.json("$.status")).isEqualTo("RIDE_COMPLETED");
        assertThat((String) done.json("$.stops[1].arrivedAt")).isNotBlank();
    }

    @Test
    void atMostThreeStopsAreAccepted() {
        Person rider = newRider();
        String stop = "{'address':'S','location':{'latitude':28.010000,'longitude':70.000000}}";
        String four = "'stops':[" + String.join(",", stop, stop, stop, stop) + "]";

        expect(400, api().post("/api/rides", rider.token(), with("CAB", 28.0, 70.0, four)));
    }

    // ------------------------------------------------------------------ what travels in the event

    @Test
    void theRideEventCarriesTheCategoryTheZoneTheSurgeAndTheCouponForOtherServicesToUse() {
        Person rider = newRider();
        double lat = 29.0;

        String id = expect(201, api().post("/api/rides", rider.token(), with("AUTO", lat, 70.0, "'couponCode':'welcome50'"))).id();

        String payload = jdbc.queryForObject("select payload from outbox_events where event_key = ? and event_type = 'ride.status-changed' order by seq desc limit 1", String.class, id);
        assertThat(payload).contains("\"couponCode\":\"WELCOME50\"").contains("\"category\":\"AUTO\"").contains("\"pickupCell\":\"" + SurgeZones.cellOf(lat, 70.0) + "\"")
                .contains("\"surgeMultiplier\":");
    }

    // ------------------------------------------------------------------ surge measurement

    @Test
    void demandInAZoneRaisesItsMultiplierStepByStepAndItExpiresAndComesDownByItself() {
        double lat = 30.0;
        String cell = SurgeZones.cellOf(lat, 70.0);
        String key = SurgeZones.keyOf(cell);
        redis.delete(key);
        // six riders request a ride in the same zone and there is no driver near: demand 6, supply 0
        for (int i = 0; i < 6; i++) {
            expect(201, api().post("/api/rides", newRider().token(), rideBody("CAB", lat + i * 0.0001, 70.0)));
        }

        surge.recalculate(Instant.now());
        assertThat(redis.opsForValue().get(key)).as("one step up, not a jump to the target").isEqualTo("1.3");
        assertThat(redis.getExpire(key)).as("every multiplier expires by itself").isBetween(1L, 120L);
        assertThat(redis.opsForSet().isMember("surge:zones", cell)).isTrue();

        surge.recalculate(Instant.now());
        assertThat(redis.opsForValue().get(key)).isEqualTo("1.6");

        // the demand ends (an hour later the requests are outside the measuring window): the multiplier steps down and the key disappears
        Instant later = Instant.now().plus(1, ChronoUnit.HOURS);
        for (int i = 0; i < 4; i++) {
            surge.recalculate(later);
        }
        assertThat(redis.opsForValue().get(key)).as("1.6 - 4 x 0.2 = 0.8, so back to normal: no key").isNull();
        assertThat(redis.opsForSet().isMember("surge:zones", cell)).isFalse();
    }

    @Test
    void aZoneWithFewRequestsNeverSurges() {
        double lat = 31.0;
        String key = SurgeZones.keyOf(SurgeZones.cellOf(lat, 70.0));
        redis.delete(key);
        for (int i = 0; i < 3; i++) {
            expect(201, api().post("/api/rides", newRider().token(), rideBody("CAB", lat, 70.0)));
        }

        surge.recalculate(Instant.now());

        assertThat(redis.hasKey(key)).as("3 requests are below the minimum: noise, not demand").isFalse();
    }
}
