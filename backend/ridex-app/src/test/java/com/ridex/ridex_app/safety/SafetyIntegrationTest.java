package com.ridex.ridex_app.safety;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ridex.platform.geo.SurgeZones;
import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;

/**
 * Phase 15 safety, through the real API: the trip link, SOS and chat. Nothing listens for email in tests, which is itself part of the
 * check: a safety alert must still be stored and shown when the mail server is down.
 */
class SafetyIntegrationTest extends AbstractApiTest {

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private com.ridex.ridex_app.rider.RiderProfileService riderProfileService;

    private record Trip(String rideId, Person rider, DriverFixture driver, Person admin) {
    }

    /** A ride whose driver has accepted (the driver is on the way). */
    private Trip acceptedTrip(double lat) {
        Person admin = newAdmin();
        Person rider = newRider();
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.001, 72.0);
        String id = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, 72.0))).id();
        expect(200, api().post("/api/rides/" + id + "/accept", driver.token(), null));
        return new Trip(id, rider, driver, admin);
    }

    private void complete(Trip trip) {
        expect(200, api().post("/api/rides/" + trip.rideId() + "/arrived", trip.driver().token(), null));
        expect(200, api().post("/api/rides/" + trip.rideId() + "/start", trip.driver().token(), null));
        expect(200, api().post("/api/rides/" + trip.rideId() + "/complete", trip.driver().token(), null));
    }

    // ------------------------------------------------------------------ SOS

    @Test
    void anSosIsStoredShownToAdministratorsAndPressingTwiceIsOneEmergency() {
        Trip trip = acceptedTrip(50.0);

        Response first = expect(201, api().post("/api/rides/" + trip.rideId() + "/sos", trip.rider().token(), json("{'latitude':12.97,'longitude':77.59}")));
        Response again = expect(201, api().post("/api/rides/" + trip.rideId() + "/sos", trip.rider().token(), json("{'latitude':12.98,'longitude':77.60}")));
        assertThat((String) first.json("$.status")).isEqualTo("OPEN");
        assertThat((String) again.json("$.id")).as("the same emergency").isEqualTo(first.json("$.id"));

        Response list = expect(200, api().get("/api/admin/sos?status=OPEN&size=100", trip.admin().token()));
        assertThat(list.body()).contains((String) first.json("$.id"));
        assertThat((String) first.json("$.raisedByRole")).isEqualTo("RIDER");
        // the driver may raise one too: a second, separate alert for the same ride
        Response driverAlert = expect(201, api().post("/api/rides/" + trip.rideId() + "/sos", trip.driver().token(), null));
        assertThat((String) driverAlert.json("$.id")).isNotEqualTo((String) first.json("$.id"));
        assertThat((String) driverAlert.json("$.raisedByRole")).isEqualTo("DRIVER");
    }

    @Test
    void anAdministratorAcknowledgesThenResolvesAndCannotReopenAResolvedAlert() {
        Trip trip = acceptedTrip(51.0);
        String id = expect(201, api().post("/api/rides/" + trip.rideId() + "/sos", trip.rider().token(), null)).json("$.id");

        assertThat((String) expect(200, api().post("/api/admin/sos/" + id + "/acknowledge", trip.admin().token(), null)).json("$.status")).isEqualTo("ACKNOWLEDGED");
        Response resolved = expect(200, api().post("/api/admin/sos/" + id + "/resolve", trip.admin().token(), json("{'note':'Called the rider, all safe'}")));
        assertThat((String) resolved.json("$.status")).isEqualTo("RESOLVED");
        assertThat((String) resolved.json("$.note")).contains("all safe");
        expect(409, api().post("/api/admin/sos/" + id + "/resolve", trip.admin().token(), null));
        expect(403, api().post("/api/admin/sos/" + id + "/resolve", trip.rider().token(), null));
        expect(403, api().get("/api/admin/sos", trip.rider().token()));
    }

    @Test
    void onlyThePeopleOnAnActiveRideCanRaiseAnSos() {
        Trip trip = acceptedTrip(52.0);

        expect(404, api().post("/api/rides/" + trip.rideId() + "/sos", newRider().token(), null));
        expect(403, api().post("/api/rides/" + trip.rideId() + "/sos", trip.admin().token(), null));
        expect(401, api().post("/api/rides/" + trip.rideId() + "/sos", null, null));
        complete(trip);
        expect(409, api().post("/api/rides/" + trip.rideId() + "/sos", trip.rider().token(), null));
    }

    @Test
    void theEmergencyContactCanHaveAnEmailAndItIsValidated() {
        Person rider = newRider();
        riderProfileService.createFor(rider.user());

        Response saved = expect(200, api().put("/api/riders/me", rider.token(),
                json("{'emergencyContactName':'Asha','emergencyContactPhone':'+919800000000','emergencyContactEmail':'asha@example.com'}")));
        assertThat((String) saved.json("$.emergencyContact.email")).isEqualTo("asha@example.com");
        expect(400, api().put("/api/riders/me", rider.token(),
                json("{'emergencyContactName':'Asha','emergencyContactPhone':'+919800000000','emergencyContactEmail':'not-an-email'}")));
        Response cleared = expect(200, api().put("/api/riders/me", rider.token(), json("{}")));
        assertThat((Object) cleared.json("$.emergencyContact")).isNull();
    }

    // ------------------------------------------------------------------ the trip link

    @Test
    void aSharedTripCanBeFollowedWithoutLoginAndShowsOnlyWhatAFriendNeeds() {
        Trip trip = acceptedTrip(53.0);

        Response link = expect(200, api().post("/api/rides/" + trip.rideId() + "/share", trip.rider().token(), null));
        String token = link.json("$.token");
        assertThat((String) link.json("$.url")).endsWith("/trip/" + token);
        assertThat((String) expect(200, api().post("/api/rides/" + trip.rideId() + "/share", trip.rider().token(), null)).json("$.token")).as("one link per ride").isEqualTo(token);

        Response view = expect(200, api().get("/api/public/trips/" + token, null));
        assertThat((String) view.json("$.status")).isEqualTo("DRIVER_ACCEPTED");
        assertThat((String) view.json("$.driverFirstName")).isNotBlank().doesNotContain(" ");
        assertThat((String) view.json("$.vehicle.registrationNumber")).isNotBlank();
        // nothing that identifies or reaches the people
        assertThat(view.body()).doesNotContain(trip.rider().user().getEmail()).doesNotContain(trip.driver().user().getEmail())
                .doesNotContain("phone").doesNotContain(trip.rider().user().getPhone());
        // the driver can share too, a stranger cannot
        expect(200, api().post("/api/rides/" + trip.rideId() + "/share", trip.driver().token(), null));
        expect(404, api().post("/api/rides/" + trip.rideId() + "/share", newRider().token(), null));
    }

    @Test
    void anUnknownOrExpiredLinkLooksTheSameAndAFinishedRideCannotBeSharedAnyMore() {
        Trip trip = acceptedTrip(54.0);
        String token = expect(200, api().post("/api/rides/" + trip.rideId() + "/share", trip.rider().token(), null)).json("$.token");

        expect(404, api().get("/api/public/trips/does-not-exist-does-not-exist", null));
        expect(404, api().get("/api/public/trips/short", null));
        jdbc.update("update ride_shares set expires_at = now() - interval '1 minute' where token = ?", token);
        expect(404, api().get("/api/public/trips/" + token, null));

        complete(trip);
        expect(409, api().post("/api/rides/" + trip.rideId() + "/share", trip.rider().token(), null));
    }

    // ------------------------------------------------------------------ chat

    @Test
    void theTwoPeopleOnARideCanChatAndNobodyElseCanReadOrWrite() {
        Trip trip = acceptedTrip(55.0);
        String path = "/api/rides/" + trip.rideId() + "/messages";

        expect(201, api().post(path, trip.rider().token(), json("{'body':'I am at the blue gate'}")));
        expect(201, api().post(path, trip.driver().token(), json("{'body':'Two minutes away'}")));

        Response asRider = expect(200, api().get(path, trip.rider().token()));
        assertThat((String) asRider.json("$[0].body")).isEqualTo("I am at the blue gate");
        assertThat((Boolean) asRider.json("$[0].mine")).isTrue();
        assertThat((String) asRider.json("$[1].senderRole")).isEqualTo("DRIVER");
        assertThat((Boolean) asRider.json("$[1].mine")).isFalse();
        assertThat((Boolean) expect(200, api().get(path, trip.driver().token())).json("$[1].mine")).as("mine is relative to the reader").isTrue();

        expect(404, api().get(path, newRider().token()));
        expect(404, api().post(path, newRider().token(), json("{'body':'hello'}")));
        expect(403, api().get(path, trip.admin().token()));
        expect(400, api().post(path, trip.rider().token(), json("{'body':'   '}")));
        expect(400, api().post(path, trip.rider().token(), json("{'body':'" + "x".repeat(501) + "'}")));
    }

    @Test
    void thereIsNoOneToChatWithBeforeADriverAccepts() {
        Person rider = newRider();
        String id = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", 56.0, 72.0))).id();

        expect(409, api().post("/api/rides/" + id + "/messages", rider.token(), json("{'body':'hello?'}")));
    }

    // ------------------------------------------------------------------ drivers' busy areas, and the internal contact lookup

    @Test
    void driversSeeTheBusyAreasAndOnlyThoseAboveNormalPrice() {
        String zone = SurgeZones.cellOf(57.0, 72.0);
        redis.opsForSet().add("surge:zones", zone);
        redis.opsForValue().set(SurgeZones.keyOf(zone), "1.8");
        Person admin = newAdmin();
        DriverFixture driver = newApprovedDriver(admin, VehicleCategory.CAB);

        Response zones = expect(200, api().get("/api/drivers/surge-zones", driver.token()));
        assertThat(zones.body()).contains(zone).contains("1.8");
        expect(403, api().get("/api/drivers/surge-zones", newRider().token()));
        redis.opsForSet().remove("surge:zones", zone);
        redis.delete(SurgeZones.keyOf(zone));
    }

    @Test
    void theContactLookupIsForOtherServicesOnly() {
        Person rider = newRider();

        expect(403, api().get("/internal/users/" + rider.user().getId() + "/contact", rider.token()));
        expect(401, api().get("/internal/users/" + rider.user().getId() + "/contact", null));
    }
}
