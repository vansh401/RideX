package com.ridex.ridex_app.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

import com.ridex.ridex_app.ride.MatchingProperties;

/**
 * Driver positions in a Redis GEO index.
 *
 * How GEO works: GEOADD turns (longitude, latitude) into a single 52-bit number (a "geohash") and
 * stores the driver id in a SORTED SET with that number as the score. Nearby places have nearby
 * scores, so GEOSEARCH can find "everyone within 5 km" by looking at a handful of score ranges
 * instead of scanning every driver.
 *
 * Staleness: a sorted set member cannot expire by itself. So each driver also gets a heartbeat key
 * ({@code driver:hb:<id>}) that DOES expire. A driver in the GEO index without a heartbeat is
 * stale: it is ignored by searches and removed lazily, and by purgeStale() on a schedule.
 */
@Component
public class RedisDriverLocationIndex implements DriverLocationIndex {

    private final StringRedisTemplate redis;
    private final Duration heartbeatTtl;

    public RedisDriverLocationIndex(StringRedisTemplate redis, MatchingProperties properties) {
        this.redis = redis;
        this.heartbeatTtl = Duration.ofSeconds(properties.locationFreshnessSeconds());
    }

    @Override
    public void update(UUID driverProfileId, double latitude, double longitude) {
        // GEO takes (longitude, latitude), the opposite order to what people say aloud
        redis.opsForGeo().add(RedisKeys.DRIVER_GEO, new Point(longitude, latitude), driverProfileId.toString());
        redis.opsForValue().set(RedisKeys.driverHeartbeat(driverProfileId),
                String.valueOf(System.currentTimeMillis()), heartbeatTtl);
    }

    @Override
    public void remove(UUID driverProfileId) {
        redis.opsForZSet().remove(RedisKeys.DRIVER_GEO, driverProfileId.toString());
        redis.delete(RedisKeys.driverHeartbeat(driverProfileId));
    }

    @Override
    public List<Nearby> findNearby(double latitude, double longitude, double radiusKm, int limit) {
        GeoResults<GeoLocation<String>> results = redis.opsForGeo().search(
                RedisKeys.DRIVER_GEO,
                GeoReference.fromCoordinate(new Point(longitude, latitude)),
                new Distance(radiusKm, Metrics.KILOMETERS),
                // Nearest first. We fetch more than we need: some may turn out to be stale.
                GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(Math.max(limit * 2L, limit)));

        List<GeoResult<GeoLocation<String>>> found = results == null ? List.of() : results.getContent();
        if (found.isEmpty()) {
            return List.of();
        }

        List<String> heartbeatKeys = found.stream()
                .map(r -> RedisKeys.driverHeartbeat(UUID.fromString(r.getContent().getName())))
                .toList();
        List<String> heartbeats = redis.opsForValue().multiGet(heartbeatKeys);

        List<Nearby> fresh = new ArrayList<>();
        List<Object> stale = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            GeoResult<GeoLocation<String>> result = found.get(i);
            if (heartbeats != null && heartbeats.get(i) != null) {
                if (fresh.size() < limit) {
                    fresh.add(new Nearby(UUID.fromString(result.getContent().getName()),
                            result.getDistance().getValue()));
                }
            } else {
                stale.add(result.getContent().getName());
            }
        }
        if (!stale.isEmpty()) {
            redis.opsForZSet().remove(RedisKeys.DRIVER_GEO, stale.toArray());   // lazy cleanup
        }
        return fresh;
    }

    @Override
    public Optional<double[]> position(UUID driverProfileId) {
        if (redis.opsForValue().get(RedisKeys.driverHeartbeat(driverProfileId)) == null) {
            return Optional.empty();   // no heartbeat: the last position is too old to trust
        }
        List<Point> points = redis.opsForGeo().position(RedisKeys.DRIVER_GEO, driverProfileId.toString());
        if (points == null || points.isEmpty() || points.get(0) == null) {
            return Optional.empty();
        }
        // Redis stores (longitude, latitude); callers get {latitude, longitude}
        return Optional.of(new double[] {points.get(0).getY(), points.get(0).getX()});
    }

    @Override
    public Optional<Instant> lastSeen(UUID driverProfileId) {
        String millis = redis.opsForValue().get(RedisKeys.driverHeartbeat(driverProfileId));
        return millis == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(Long.parseLong(millis)));
    }

    @Override
    public int purgeStale() {
        Set<String> members = redis.opsForZSet().range(RedisKeys.DRIVER_GEO, 0, -1);
        if (members == null || members.isEmpty()) {
            return 0;
        }
        List<String> ids = new ArrayList<>(members);
        List<String> heartbeats = redis.opsForValue().multiGet(
                ids.stream().map(id -> RedisKeys.driverHeartbeat(UUID.fromString(id))).toList());
        List<Object> stale = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (heartbeats == null || heartbeats.get(i) == null) {
                stale.add(ids.get(i));
            }
        }
        if (stale.isEmpty()) {
            return 0;
        }
        Long removed = redis.opsForZSet().remove(RedisKeys.DRIVER_GEO, stale.toArray());
        return removed == null ? 0 : removed.intValue();
    }
}
