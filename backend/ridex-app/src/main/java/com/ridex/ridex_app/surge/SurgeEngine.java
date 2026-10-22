package com.ridex.ridex_app.surge;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.ridex.platform.geo.SurgeZones;
import com.ridex.ridex_app.redis.DistributedLock;
import com.ridex.ridex_app.redis.DriverLocationIndex;
import com.ridex.ridex_app.ride.RideRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Measures demand and supply per zone every half minute and publishes the surge multipliers for the pricing service to read.
 *
 * WHAT IT WRITES. {@code surge:<zone>} = "1.5" in Redis, with a short expiry (about four cycles), only for zones above 1.0. Zones at 1.0 have
 * no key at all. The pricing service reads these; it never writes. A set {@code surge:zones} lists the zones that have a key, so the next cycle
 * knows which ones to bring back down (it cannot ask Redis "which keys start with surge:" without scanning everything).
 *
 * WHY IT IS SAFE TO FAIL. Every multiplier EXPIRES BY ITSELF. If this engine stops (a crash, a deploy, Redis trouble), the keys vanish within a
 * couple of minutes and prices fall back to normal, instead of staying high for ever because nobody is there to lower them. A stuck-high surge is
 * the expensive failure of this feature (angry riders, bad press); this design makes it impossible.
 *
 * ONE RUNNER AT A TIME. With several core pods, only the one that gets the distributed lock runs a cycle: if every pod applied "move one step
 * toward the target" the ramp would be several times faster than intended.
 */
@Component
public class SurgeEngine {

    private static final Logger log = LoggerFactory.getLogger(SurgeEngine.class);
    static final String ZONES_KEY = "surge:zones";

    private final RideRepository rides;
    private final DriverLocationIndex drivers;
    private final StringRedisTemplate redis;
    private final DistributedLock lock;
    private final SurgePolicy policy;
    private final Duration window;
    private final Duration ttl;
    private volatile BigDecimal highest = BigDecimal.ONE;
    private volatile int activeZones = 0;

    public SurgeEngine(RideRepository rides, DriverLocationIndex drivers, StringRedisTemplate redis, DistributedLock lock, MeterRegistry metrics,
                       @Value("${ridex.surge.window-minutes:10}") int windowMinutes,
                       @Value("${ridex.surge.min-demand:5}") int minDemand,
                       @Value("${ridex.surge.max:3.0}") BigDecimal max,
                       @Value("${ridex.surge.ttl-seconds:120}") int ttlSeconds) {
        this.rides = rides;
        this.drivers = drivers;
        this.redis = redis;
        this.lock = lock;
        this.policy = new SurgePolicy(minDemand, max);
        this.window = Duration.ofMinutes(windowMinutes);
        this.ttl = Duration.ofSeconds(ttlSeconds);
        Gauge.builder("ridex.surge.zones.active", this, e -> e.activeZones).description("Zones with a surge multiplier above 1.0").strongReference(true).register(metrics);
        Gauge.builder("ridex.surge.multiplier.max", this, e -> e.highest.doubleValue()).description("The highest surge multiplier right now").strongReference(true).register(metrics);
    }

    /** One locked cycle (called by SurgeScheduler every half minute). */
    public void run() {
        Optional<DistributedLock.Handle> handle = Optional.empty();
        try {
            handle = lock.tryLock("surge-engine", Duration.ofSeconds(25));
            if (handle.isPresent()) {
                recalculate(Instant.now());
            }
        } catch (RuntimeException e) {
            log.warn("The surge cycle failed (multipliers expire by themselves): {}", e.getClass().getSimpleName());
        } finally {
            handle.ifPresent(DistributedLock.Handle::release);
        }
    }

    /** One cycle. Public so tests can run it directly with a chosen "now". */
    public void recalculate(Instant now) {
        Map<String, Integer> demand = new HashMap<>();
        for (Object[] row : rides.countRecentRequestsByCell(now.minus(window))) {
            demand.put((String) row[0], ((Number) row[1]).intValue());
        }
        Set<String> previously = redis.opsForSet().members(ZONES_KEY);
        Set<String> zones = new HashSet<>(demand.keySet());
        if (previously != null) {
            zones.addAll(previously);   // zones with a multiplier above 1.0 must be looked at again, to come down
        }
        Set<String> stillSurging = new HashSet<>();
        BigDecimal max = BigDecimal.ONE;
        for (String zone : zones) {
            BigDecimal current = read(zone);
            int zoneDemand = demand.getOrDefault(zone, 0);
            BigDecimal target = policy.target(zoneDemand, zoneDemand < 1 ? 0 : supplyIn(zone));
            BigDecimal next = policy.next(current, target);
            if (next.compareTo(BigDecimal.ONE) > 0) {
                redis.opsForValue().set(SurgeZones.keyOf(zone), next.toPlainString(), ttl);
                redis.opsForSet().add(ZONES_KEY, zone);
                stillSurging.add(zone);
                max = max.max(next);
            } else {
                redis.delete(SurgeZones.keyOf(zone));
                redis.opsForSet().remove(ZONES_KEY, zone);
            }
            if (next.compareTo(current) != 0) {
                log.info("Surge in zone {}: {} -> {} ({} requests in {} minutes)", zone, current, next, zoneDemand, window.toMinutes());
            }
        }
        this.activeZones = stillSurging.size();
        this.highest = max;
    }

    private int supplyIn(String zone) {
        double[] center = SurgeZones.centerOf(zone);
        return drivers.findNearby(center[0], center[1], SurgeZones.coverRadiusKm(), 50).size();
    }

    private BigDecimal read(String zone) {
        String stored = redis.opsForValue().get(SurgeZones.keyOf(zone));
        try {
            return stored == null ? BigDecimal.ONE : new BigDecimal(stored);
        } catch (NumberFormatException e) {
            return BigDecimal.ONE;
        }
    }
}
