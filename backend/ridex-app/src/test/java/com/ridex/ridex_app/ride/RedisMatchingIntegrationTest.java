package com.ridex.ridex_app.ride;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.redis.DistributedLock;
import com.ridex.ridex_app.redis.RedisKeys;
import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;

/**
 * Matching with Redis GEO in front and PostgreSQL behind. Each test uses its own patch of map so
 * drivers left over by another test can never be "nearby".
 *
 * About 0.009 degrees of latitude is 1 km.
 */
class RedisMatchingIntegrationTest extends AbstractApiTest {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private DistributedLock locks;

    private Response request(Person rider, double lat, double lng) {
        return expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng)));
    }

    private void cleanUp(List<Person> riders, List<DriverFixture> drivers) {
        for (Person rider : riders) {
            Response current = api().get("/api/rides/current", rider.token());
            if (current.status() == 200) {
                api().post("/api/rides/" + current.id() + "/cancel", rider.token(), null);
            }
        }
        for (DriverFixture driver : drivers) {
            api().post("/api/drivers/me/availability/offline", driver.token(), null);
        }
    }

    @Test
    void theNearestDriverFromRedisGetsTheOffer() {
        Person admin = newAdmin();
        double lat = 20.0, lng = 70.0;
        DriverFixture farther = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.027, lng);   // ~3 km
        DriverFixture nearer = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);    // ~1 km
        Person rider = newRider();

        Response ride = request(rider, lat, lng);

        assertThat(ride.<String>json("$.status")).isEqualTo("DRIVER_ASSIGNED");
        assertThat(ride.<String>json("$.driver.userId")).isEqualTo(nearer.user().getId().toString());
        cleanUp(List.of(rider), List.of(nearer, farther));
    }

    @Test
    void aDriverOutsideTheSearchRadiusIsNeverOffered() {
        Person admin = newAdmin();
        double lat = 21.0, lng = 70.0;
        DriverFixture tooFar = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.09, lng);   // ~10 km, radius is 5
        Person rider = newRider();

        Response ride = request(rider, lat, lng);

        assertThat(ride.<String>json("$.status")).isEqualTo("SEARCHING_DRIVER");
        cleanUp(List.of(rider), List.of(tooFar));
    }

    @Test
    void aDriverWhoStoppedSendingHeartbeatsIsSkipped() {
        Person admin = newAdmin();
        double lat = 22.0, lng = 70.0;
        DriverFixture ghost = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);   // nearest, but gone
        DriverFixture alive = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.027, lng);
        Person rider = newRider();
        // "the ghost's app was closed a while ago": its heartbeat expired. The database still says online.
        redis.delete(RedisKeys.driverHeartbeat(UUID.fromString(ghost.profileId())));

        Response ride = request(rider, lat, lng);

        assertThat(ride.<String>json("$.driver.userId")).isEqualTo(alive.user().getId().toString());
        cleanUp(List.of(rider), List.of(ghost, alive));
    }

    @Test
    void aDriverBeingAssignedByAnotherRequestIsSkipped() {
        Person admin = newAdmin();
        double lat = 23.0, lng = 70.0;
        DriverFixture nearer = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        DriverFixture farther = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.027, lng);
        Person first = newRider();
        Person second = newRider();

        // Another server is in the middle of assigning the nearer driver: it holds that driver's Redis lock
        DistributedLock.Handle otherServer = locks.tryLock(
                RedisKeys.driverAssignmentLock(UUID.fromString(nearer.profileId())), Duration.ofSeconds(30)).orElseThrow();
        Response whileLocked = request(first, lat, lng);
        otherServer.release();

        assertThat(whileLocked.<String>json("$.driver.userId"))
                .as("the locked driver was skipped, the next nearest got the ride")
                .isEqualTo(farther.user().getId().toString());

        // and the lock was released after the transaction: the nearer driver is available again
        Response next = request(second, lat, lng);
        assertThat(next.<String>json("$.driver.userId")).isEqualTo(nearer.user().getId().toString());
        cleanUp(List.of(first, second), List.of(nearer, farther));
    }

    @Test
    void theAssignmentLockIsReleasedWhenTheRequestFinishes() {
        Person admin = newAdmin();
        double lat = 24.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        Person rider = newRider();

        request(rider, lat, lng);

        assertThat(redis.hasKey(RedisKeys.driverAssignmentLock(UUID.fromString(driver.profileId()))))
                .as("a finished assignment must not leave its lock behind")
                .isFalse();
        cleanUp(List.of(rider), List.of(driver));
    }

    @Test
    void locationHeartbeatsMoveTheDriverInRedisWithoutWritingToTheDatabase() {
        Person admin = newAdmin();
        double lat = 25.0, lng = 70.0;
        DriverFixture roaming = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.054, lng);   // ~6 km: out of range
        Person rider = newRider();
        String before = jdbc.queryForObject("select last_latitude::text from driver_profiles where id = ?::uuid",
                String.class, roaming.profileId());

        // The driver's app reports a new position right next to the pickup
        expect(204, api().put("/api/drivers/me/availability/location", roaming.token(),
                json("{'latitude':%s,'longitude':%s}", coord(lat + 0.001), coord(lng))));
        Response ride = request(rider, lat, lng);

        assertThat(ride.<String>json("$.driver.userId")).isEqualTo(roaming.user().getId().toString());
        String after = jdbc.queryForObject("select last_latitude::text from driver_profiles where id = ?::uuid",
                String.class, roaming.profileId());
        assertThat(after).as("heartbeats are not stored in PostgreSQL").isEqualTo(before);
        cleanUp(List.of(rider), List.of(roaming));
    }

    @Test
    void goingOfflineRemovesTheDriverFromMatching() {
        Person admin = newAdmin();
        double lat = 26.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        Person rider = newRider();

        expect(200, api().post("/api/drivers/me/availability/offline", driver.token(), null));
        Response ride = request(rider, lat, lng);

        assertThat(ride.<String>json("$.status")).isEqualTo("SEARCHING_DRIVER");
        assertThat(redis.opsForZSet().score(RedisKeys.DRIVER_GEO, driver.profileId())).isNull();
        cleanUp(List.of(rider), List.of(driver));
    }

    @Test
    void aDriverOfAnotherVehicleTypeIsFilteredByTheDatabaseStep() {
        Person admin = newAdmin();
        double lat = 27.0, lng = 70.0;
        DriverFixture bike = newOnlineDriver(admin, VehicleCategory.BIKE, lat + 0.009, lng);   // nearest, wrong type
        DriverFixture cab = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.027, lng);
        Person rider = newRider();

        Response ride = request(rider, lat, lng);

        // Redis only knows positions. "Is this a cab, approved, and free?" is answered by PostgreSQL.
        assertThat(ride.<String>json("$.driver.userId")).isEqualTo(cab.user().getId().toString());
        cleanUp(List.of(rider), List.of(bike, cab));
    }
}
