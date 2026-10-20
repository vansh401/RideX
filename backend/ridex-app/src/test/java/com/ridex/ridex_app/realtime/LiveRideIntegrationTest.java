package com.ridex.ridex_app.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;
import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;
import com.ridex.ridex_app.support.StompTestClient;

/**
 * A whole ride seen through the WebSocket: the driver gets the offer at once, the rider hears about every
 * step, and the driver's position travels driver app, then Redis, then the rider's screen.
 */
class LiveRideIntegrationTest extends AbstractApiTest {

    private final List<StompTestClient> open = new ArrayList<>();

    private StompTestClient listener(String token, String... destinations) {
        StompTestClient client = StompTestClient.connect(serverPort(), token);
        open.add(client);
        assertThat(client.awaitConnected(5)).isTrue();
        for (String destination : destinations) {
            client.subscribe(destination);
        }
        return client;
    }

    @AfterEach
    void closeAll() {
        open.forEach(StompTestClient::close);
    }

    private static String field(String json, String path) {
        return JsonPath.read(json, path);
    }

    // ------------------------------------------------------------------ status push

    @Test
    void everyStepOfARideIsPushedToTheRiderAndTheDriver() {
        Person admin = newAdmin();
        double lat = 34.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        Person rider = newRider();
        StompTestClient riderLive = listener(rider.token(), "/user/queue/ride-updates");
        StompTestClient driverLive = listener(driver.token(), "/user/queue/ride-updates", "/user/queue/offers");

        String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng))).id();

        // rider: first "searching", then "a driver was offered the ride"
        assertThat(field(riderLive.next("/user/queue/ride-updates", 5), "$.status")).isEqualTo("SEARCHING_DRIVER");
        assertThat(field(riderLive.next("/user/queue/ride-updates", 5), "$.status")).isEqualTo("DRIVER_ASSIGNED");
        // driver: the offer shows up immediately, with what is needed to decide
        String offer = driverLive.next("/user/queue/offers", 5);
        assertThat(offer).isNotNull();
        assertThat(field(offer, "$.rideId")).isEqualTo(rideId);
        assertThat(field(offer, "$.pickupAddress")).isEqualTo("Pickup point");
        assertThat(JsonPath.<Boolean>read(offer, "$.withdrawn")).isFalse();
        assertThat(field(offer, "$.expiresAt")).isNotBlank();

        expect(200, api().post("/api/rides/" + rideId + "/accept", driver.token(), null));
        assertThat(field(riderLive.next("/user/queue/ride-updates", 5), "$.status")).isEqualTo("DRIVER_ACCEPTED");

        expect(200, api().post("/api/rides/" + rideId + "/arrived", driver.token(), null));
        assertThat(field(riderLive.next("/user/queue/ride-updates", 5), "$.status")).isEqualTo("DRIVER_ARRIVED");

        expect(200, api().post("/api/rides/" + rideId + "/start", driver.token(), null));
        assertThat(field(riderLive.next("/user/queue/ride-updates", 5), "$.status")).isEqualTo("RIDE_STARTED");

        expect(200, api().post("/api/rides/" + rideId + "/complete", driver.token(), null));
        String done = riderLive.next("/user/queue/ride-updates", 5);
        assertThat(field(done, "$.status")).isEqualTo("RIDE_COMPLETED");
        assertThat(field(done, "$.previousStatus")).isEqualTo("RIDE_STARTED");

        // the push is only a hint: it carries no ride details, no phone numbers
        assertThat(done).doesNotContain("phone").doesNotContain("fare");
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }

    @Test
    void aBystanderHearsNothingAboutSomeoneElsesRide() {
        Person admin = newAdmin();
        double lat = 35.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        Person rider = newRider();
        Person bystander = newRider();
        StompTestClient bystanderLive = listener(bystander.token(), "/user/queue/ride-updates", "/user/queue/notifications");

        String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng))).id();
        api().post("/api/rides/" + rideId + "/accept", driver.token(), null);

        assertThat(bystanderLive.next("/user/queue/ride-updates", 2)).isNull();
        assertThat(bystanderLive.next("/user/queue/notifications", 1)).isNull();
        api().post("/api/rides/" + rideId + "/cancel", rider.token(), null);
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }

    // ------------------------------------------------------------------ offers: A, then B

    @Test
    void whenTheFirstDriverDeclinesTheOfferMovesToTheNextAndDisappearsFromTheFirst() {
        Person admin = newAdmin();
        double lat = 36.0, lng = 70.0;
        DriverFixture first = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);    // nearest
        DriverFixture second = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.027, lng);
        Person rider = newRider();
        StompTestClient firstLive = listener(first.token(), "/user/queue/offers");
        StompTestClient secondLive = listener(second.token(), "/user/queue/offers");

        String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng))).id();

        assertThat(field(firstLive.next("/user/queue/offers", 5), "$.rideId")).isEqualTo(rideId);
        assertThat(secondLive.next("/user/queue/offers", 1)).as("the farther driver is not asked yet").isNull();

        expect(204, api().post("/api/rides/" + rideId + "/reject", first.token(), null));

        String withdrawn = firstLive.next("/user/queue/offers", 5);
        assertThat(JsonPath.<Boolean>read(withdrawn, "$.withdrawn")).as("A's screen drops the offer").isTrue();
        String offerToB = secondLive.next("/user/queue/offers", 5);
        assertThat(field(offerToB, "$.rideId")).as("B is asked next").isEqualTo(rideId);
        assertThat(JsonPath.<Boolean>read(offerToB, "$.withdrawn")).isFalse();

        cleanUpRide(rideId, rider);
        api().post("/api/drivers/me/availability/offline", first.token(), null);
        api().post("/api/drivers/me/availability/offline", second.token(), null);
    }

    @Test
    void anOfferIsWithdrawnWhenTheRiderCancelsBeforeAnyAnswer() {
        Person admin = newAdmin();
        double lat = 37.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        Person rider = newRider();
        StompTestClient driverLive = listener(driver.token(), "/user/queue/offers");
        String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng))).id();
        assertThat(driverLive.next("/user/queue/offers", 5)).isNotNull();

        expect(200, api().post("/api/rides/" + rideId + "/cancel", rider.token(), null));

        String withdrawn = driverLive.next("/user/queue/offers", 5);
        assertThat(JsonPath.<Boolean>read(withdrawn, "$.withdrawn")).isTrue();
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }

    // ------------------------------------------------------------------ driver location streaming

    @Test
    void theDriversStreamedPositionReachesTheRiderAndTheRestEndpoint() {
        Person admin = newAdmin();
        double lat = 38.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        Person rider = newRider();
        String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng))).id();
        expect(200, api().post("/api/rides/" + rideId + "/accept", driver.token(), null));

        StompTestClient riderLive = listener(rider.token(), "/topic/rides/" + rideId + "/location");
        StompTestClient driverApp = listener(driver.token(), "/user/queue/errors");

        // the driver app streams its position over the WebSocket
        driverApp.send("/app/driver/location", json("{'latitude':%s,'longitude':%s}", coord(lat + 0.004), coord(lng)));

        String update = riderLive.next("/topic/rides/" + rideId + "/location", 5);
        assertThat(update).isNotNull();
        assertThat(JsonPath.<Number>read(update, "$.latitude").doubleValue()).isEqualTo(lat + 0.004);
        assertThat(field(update, "$.rideId")).isEqualTo(rideId);

        // and a rider who opens the page later can fetch the last known position over REST
        Response last = expect(200, api().get("/api/rides/" + rideId + "/driver-location", rider.token()));
        // Redis GEO stores a 52-bit approximation of a position (accurate to about a millimetre), not the exact decimal
        assertThat(last.<Number>json("$.latitude").doubleValue()).isCloseTo(lat + 0.004, org.assertj.core.data.Offset.offset(0.00001));

        cleanUpRide(rideId, rider);
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }

    @Test
    void positionsFromADriverWhoIsNotOnATripGoToRedisButNotToAnyTopic() {
        Person admin = newAdmin();
        double lat = 39.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat, lng);
        StompTestClient driverApp = listener(driver.token(), "/user/queue/errors");

        driverApp.send("/app/driver/location", json("{'latitude':%s,'longitude':%s}", coord(lat + 0.001), coord(lng)));

        assertThat(driverApp.next("/user/queue/errors", 2)).as("accepted: no error").isNull();
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }

    @Test
    void anInvalidPositionGetsAnErrorOnTheSendersOwnQueue() {
        Person admin = newAdmin();
        double lat = 40.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat, lng);
        StompTestClient driverApp = listener(driver.token(), "/user/queue/errors");

        driverApp.send("/app/driver/location", "{\"latitude\":123.0,\"longitude\":70.0}");

        assertThat(driverApp.next("/user/queue/errors", 5)).isNotNull().contains("message");
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }

    @Test
    void aDriverWhoIsOfflineIsToldToGoOnlineFirst() {
        Person admin = newAdmin();
        DriverFixture driver = newApprovedDriver(admin, VehicleCategory.CAB);   // approved but never went online
        StompTestClient driverApp = listener(driver.token(), "/user/queue/errors");

        driverApp.send("/app/driver/location", json("{'latitude':%s,'longitude':%s}", coord(41.0), coord(70.0)));

        assertThat(driverApp.next("/user/queue/errors", 5)).contains("Go online");
    }

    @Test
    void theDriverLocationEndpointIsOnlyForParticipantsAndOnlyWhileOnTheWay() {
        Person admin = newAdmin();
        double lat = 42.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        Person rider = newRider();
        Person stranger = newRider();
        String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng))).id();

        // offered but not yet accepted: nothing to show
        assertThat(api().get("/api/rides/" + rideId + "/driver-location", rider.token()).status()).isEqualTo(204);
        expect(200, api().post("/api/rides/" + rideId + "/accept", driver.token(), null));
        assertThat(api().get("/api/rides/" + rideId + "/driver-location", rider.token()).status()).isEqualTo(200);
        assertThat(api().get("/api/rides/" + rideId + "/driver-location", stranger.token()).status())
                .as("someone else's ride looks like it does not exist").isEqualTo(404);

        cleanUpRide(rideId, rider);
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }

    // ------------------------------------------------------------------ notifications

    @Test
    void notificationsAreStoredAndPushedLive() {
        Person admin = newAdmin();
        double lat = 43.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        Person rider = newRider();
        StompTestClient riderLive = listener(rider.token(), "/user/queue/notifications");
        StompTestClient driverLive = listener(driver.token(), "/user/queue/notifications");

        String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng))).id();
        // Notifications now travel through Kafka (outbox, topic, consumer), so they arrive a moment later than the instant WebSocket push
        String offerNote = driverLive.next("/user/queue/notifications", 20);
        assertThat(field(offerNote, "$.type")).isEqualTo("RIDE_OFFER");
        assertThat(field(offerNote, "$.title")).isEqualTo("New ride request");
        assertThat(JsonPath.<Boolean>read(offerNote, "$.read")).isFalse();

        expect(200, api().post("/api/rides/" + rideId + "/accept", driver.token(), null));
        String accepted = riderLive.next("/user/queue/notifications", 20);
        assertThat(field(accepted, "$.type")).isEqualTo("RIDE_ACCEPTED");

        // ...and they are stored, so a reload still shows them
        Response list = expect(200, api().get("/api/notifications", rider.token()));
        assertThat(list.<List<String>>json("$.content[*].type")).contains("RIDE_ACCEPTED");
        assertThat(awaitUnread(rider, 1)).isTrue();

        cleanUpRide(rideId, rider);
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }

    @Test
    void readingNotificationsUpdatesTheUnreadCountAndOnlyTheOwnerMayDoIt() {
        Person admin = newAdmin();
        double lat = 44.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        Person rider = newRider();
        Person other = newRider();
        String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng))).id();
        api().post("/api/rides/" + rideId + "/accept", driver.token(), null);
        api().post("/api/rides/" + rideId + "/arrived", driver.token(), null);
        // Notifications are created by a Kafka consumer a moment after the ride changes: wait for both
        assertThat(awaitUnread(rider, 2)).isTrue();
        String firstId = expect(200, api().get("/api/notifications", rider.token())).json("$.content[0].id");

        expect(404, api().post("/api/notifications/" + firstId + "/read", other.token(), null));   // not theirs
        assertThat(expect(200, api().get("/api/notifications/unread-count", rider.token())).<Integer>json("$.count")).isEqualTo(2);

        expect(204, api().post("/api/notifications/" + firstId + "/read", rider.token(), null));
        assertThat(expect(200, api().get("/api/notifications/unread-count", rider.token())).<Integer>json("$.count")).isEqualTo(1);

        assertThat(expect(200, api().post("/api/notifications/read-all", rider.token(), null)).<Integer>json("$.updated")).isEqualTo(1);
        assertThat(expect(200, api().get("/api/notifications/unread-count", rider.token())).<Integer>json("$.count")).isZero();
        assertThat(expect(200, api().get("/api/notifications", other.token())).<List<Object>>json("$.content"))
                .as("another user's list stays empty").isEmpty();

        cleanUpRide(rideId, rider);
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }

    @Test
    void aNewlyApprovedDriverIsNotified() {
        Person admin = newAdmin();

        DriverFixture driver = newApprovedDriver(admin, VehicleCategory.CAB);   // an admin approved them through the API

        Response list = expect(200, api().get("/api/notifications", driver.token()));
        assertThat(list.<List<String>>json("$.content[*].type")).contains("DRIVER_APPROVED");
    }

    @Test
    void anUnauthenticatedCallerCannotReadNotifications() {
        assertThat(api().get("/api/notifications", null).status()).isEqualTo(401);
        assertThat(api().get("/api/notifications/unread-count", null).status()).isEqualTo(401);
    }

    private boolean awaitUnread(Person person, int expected) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Response count = api().get("/api/notifications/unread-count", person.token());
            if (count.status() == 200 && count.<Integer>json("$.count") == expected) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void cleanUpRide(String rideId, Person rider) {
        api().post("/api/rides/" + rideId + "/cancel", rider.token(), null);
    }
}
