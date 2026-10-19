package com.ridex.ridex_app.security;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.ridex.ridex_app.redis.RedisKeys;

/**
 * FIXED-WINDOW counter. The first hit creates the key and starts its expiry; later hits in the
 * same window add one. When the key expires, the count starts again from zero.
 *
 * Fixed window vs sliding window: fixed is one key and one tiny script, very cheap, and easy to
 * reason about. Its weakness is the window edge: a client can send the full limit at the end of
 * one window and again at the start of the next, so up to twice the limit within a short moment.
 * A sliding window (a sorted set of timestamps, or two weighted counters) removes that at the cost
 * of more memory and work. For blocking password guessing and email spam, fixed is enough.
 *
 * If Redis is down this fails OPEN (allows the request): losing rate limiting for a while is
 * better than refusing every login.
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    // Returns {count, remainingMillis}. INCR and the expiry happen together, atomically.
    private static final DefaultRedisScript<List> HIT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return {count, redis.call('PTTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Decision hit(String bucket, String client, int limit, Duration window) {
        try {
            List<Long> result = (List<Long>) redis.execute(HIT,
                    List.of(RedisKeys.rateLimit(bucket, client)), String.valueOf(window.toMillis()));
            if (result == null || result.size() < 2) {
                return new Decision(true, 0);
            }
            long count = result.get(0);
            long remainingMillis = Math.max(result.get(1), 0);
            if (count > limit) {
                return new Decision(false, Math.max(1, (remainingMillis + 999) / 1000));
            }
            return new Decision(true, 0);
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing the request: {}", e.getClass().getSimpleName());
            return new Decision(true, 0);
        }
    }
}
