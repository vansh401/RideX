package com.ridex.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.ridex.platform.geo.SurgeZones;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Reads the surge multiplier of the zone a trip starts in. The CORE decides the multipliers (it knows how many rides are being requested
 * and how many drivers are free) and writes them to Redis with a short life; this service only READS them, so it stays stateless.
 *
 * The rule that matters most: a problem here must never make a price wrong or a quote fail. No key, an unreadable value, a value outside
 * the allowed range, Redis down: the answer is always "no surge" (1.00). A stuck or broken surge engine can therefore cost money but can
 * never overcharge riders; and because every stored multiplier expires by itself, a dead engine means prices fall back to normal.
 */
@Component
public class SurgeLookup {

    private static final Logger log = LoggerFactory.getLogger(SurgeLookup.class);
    static final BigDecimal NONE = new BigDecimal("1.00");

    private final StringRedisTemplate redis;
    private final BigDecimal max;
    private final Counter failures;

    @Autowired
    public SurgeLookup(StringRedisTemplate redis, @Value("${ridex.pricing.max-surge:3.0}") BigDecimal max, MeterRegistry metrics) {
        this.redis = redis;
        this.max = max;
        this.failures = Counter.builder("ridex.surge.lookup.failures").description("Surge lookups that failed (the price then has no surge)").register(metrics);
    }

    /** For tests and for callers that must not use surge. */
    public SurgeLookup() {
        this.redis = null;
        this.max = new BigDecimal("3.0");
        this.failures = null;
    }

    public BigDecimal multiplierFor(LatLng pickup) {
        if (redis == null) {
            return NONE;
        }
        try {
            String cell = SurgeZones.cellOf(pickup.latitude().doubleValue(), pickup.longitude().doubleValue());
            return sanitize(redis.opsForValue().get(SurgeZones.keyOf(cell)), max);
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("Surge lookup failed, pricing without surge: {}", e.getClass().getSimpleName());
            return NONE;
        }
    }

    /** Pure and tested on its own: whatever is stored, the result is between 1.00 and the cap, with two decimals. */
    static BigDecimal sanitize(String stored, BigDecimal cap) {
        if (stored == null || stored.isBlank()) {
            return NONE;
        }
        try {
            BigDecimal value = new BigDecimal(stored.trim());
            if (value.compareTo(BigDecimal.ONE) <= 0) {
                return NONE;
            }
            return value.min(cap).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return NONE;
        }
    }
}
