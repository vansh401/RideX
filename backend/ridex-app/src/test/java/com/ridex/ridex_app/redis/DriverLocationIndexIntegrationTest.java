package com.ridex.ridex_app.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ridex.ridex_app.support.AbstractIntegrationTest;

/**
 * Redis GEO as the "nearby drivers" index. Uses a spot in the middle of the sea so that no other
 * test's drivers can be inside the search radius.
 */
class DriverLocationIndexIntegrationTest extends AbstractIntegrationTest {

    // Roughly 0.009 degrees of latitude is 1 km. A degree of longitude is 111.3 km x cos(latitude): 96 km at -30 degrees.
    private static final double LAT = -30.0;
    private static final double LNG = -140.0;

    @Autowired
    private DriverLocationIndex index;

    @Autowired
    private StringRedisTemplate redis;

    // static: JUnit creates a new test object per test method, and these drivers must be the SAME every time,
    // or the drivers of earlier tests would stay in Redis and show up in later searches
    private static final UUID near = UUID.randomUUID();
    private static final UUID middle = UUID.randomUUID();
    private static final UUID far = UUID.randomUUID();
    private static final UUID outside = UUID.randomUUID();

    @BeforeEach
    void placeDrivers() {
        for (UUID id : List.of(near, middle, far, outside)) {
            index.remove(id);
        }
        index.update(far, LAT + 0.036, LNG);       // about 4 km north
        index.update(near, LAT + 0.009, LNG);      // about 1 km north
        index.update(middle, LAT, LNG + 0.0311);   // about 3 km east (a degree of longitude is shorter away from the equator)
        index.update(outside, LAT + 0.09, LNG);    // about 10 km north
    }

    @Test
    void nearbyDriversComeBackNearestFirstWithTheirDistance() {
        List<DriverLocationIndex.Nearby> found = index.findNearby(LAT, LNG, 5, 10);

        assertThat(found).extracting(DriverLocationIndex.Nearby::driverProfileId)
                .containsExactly(near, middle, far);
        assertThat(found.get(0).distanceKm()).isBetween(0.9, 1.1);
        assertThat(found.get(1).distanceKm()).isBetween(2.8, 3.2);
        assertThat(found.get(2).distanceKm()).isBetween(3.8, 4.2);
    }

    @Test
    void driversOutsideTheRadiusAreNotReturned() {
        List<UUID> within2km = index.findNearby(LAT, LNG, 2, 10).stream()
                .map(DriverLocationIndex.Nearby::driverProfileId).toList();

        assertThat(within2km).containsExactly(near);
    }

    @Test
    void theLimitKeepsTheClosestOnes() {
        assertThat(index.findNearby(LAT, LNG, 5, 2))
                .extracting(DriverLocationIndex.Nearby::driverProfileId)
                .containsExactly(near, middle);
    }

    @Test
    void aMovingDriverIsFoundAtTheirNewPosition() {
        index.update(far, LAT + 0.0045, LNG);   // far drives to 500 m from the search point

        assertThat(index.findNearby(LAT, LNG, 5, 10).get(0).driverProfileId()).isEqualTo(far);
    }

    @Test
    void aDriverWhoWentOfflineIsGone() {
        index.remove(near);

        assertThat(index.findNearby(LAT, LNG, 5, 10))
                .extracting(DriverLocationIndex.Nearby::driverProfileId)
                .doesNotContain(near);
        assertThat(index.lastSeen(near)).isEmpty();
    }

    @Test
    void aDriverWhoseHeartbeatExpiredIsIgnoredAndCleanedUp() {
        // Simulate "the driver closed the app 90 seconds ago": the heartbeat key is gone, the GEO entry is not
        redis.delete(RedisKeys.driverHeartbeat(near));

        List<UUID> found = index.findNearby(LAT, LNG, 5, 10).stream()
                .map(DriverLocationIndex.Nearby::driverProfileId).toList();

        assertThat(found).as("a stale driver must never be offered a ride").doesNotContain(near);
        assertThat(redis.opsForZSet().score(RedisKeys.DRIVER_GEO, near.toString()))
                .as("the search also removed the stale entry (lazy cleanup)")
                .isNull();
    }

    @Test
    void thePurgeJobRemovesEveryStaleDriver() {
        redis.delete(RedisKeys.driverHeartbeat(middle));
        redis.delete(RedisKeys.driverHeartbeat(far));

        int removed = index.purgeStale();

        assertThat(removed).isGreaterThanOrEqualTo(2);
        assertThat(redis.opsForZSet().score(RedisKeys.DRIVER_GEO, middle.toString())).isNull();
        assertThat(redis.opsForZSet().score(RedisKeys.DRIVER_GEO, far.toString())).isNull();
        assertThat(redis.opsForZSet().score(RedisKeys.DRIVER_GEO, near.toString())).isNotNull();
    }

    @Test
    void lastSeenIsTheMomentOfTheLatestHeartbeat() {
        assertThat(index.lastSeen(near)).isPresent();
        assertThat(index.lastSeen(near).orElseThrow())
                .isBetween(java.time.Instant.now().minusSeconds(5), java.time.Instant.now().plusSeconds(1));
    }
}
