package com.ridex.ridex_app.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * The classic single-Redis lock: {@code SET key token NX PX lease}.
 *
 * - NX: set only if the key does not exist, so exactly one caller wins.
 * - PX: the key expires, so a crashed server cannot hold the lock forever (the "lease").
 * - token: a random value that identifies the owner. Releasing deletes the key ONLY if it still
 *   holds our token. Without that check, a slow owner whose lease already expired would delete the
 *   lock that someone else has since taken.
 *
 * Limits (say them in interviews): the lease can expire while the owner is still working (a long
 * garbage collection pause), and a single Redis is a single point of failure. That is why the
 * database stays the final authority (row locks and unique indexes), and this lock is the cheap
 * first line that stops most collisions early.
 */
@Component
public class RedisDistributedLock implements DistributedLock {

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<Handle> tryLock(String key, Duration leaseTime) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, leaseTime);
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        return Optional.of(() -> {
            Long removed = redis.execute(RELEASE, List.of(key), token);
            return removed != null && removed > 0;
        });
    }
}
