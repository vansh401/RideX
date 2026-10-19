package com.ridex.ridex_app.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisTokenStore implements TokenStore {

    // INCR then, only if this was the first increment, start the expiry. One script = one atomic
    // step, so a crash between "increment" and "expire" can never leave a counter that lives forever.
    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    @Override
    public Optional<String> take(String key) {
        return Optional.ofNullable(redis.opsForValue().getAndDelete(key));
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    @Override
    public void delete(String... keys) {
        redis.delete(List.of(keys));
    }

    @Override
    public long ttlSeconds(String key) {
        Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
        return ttl == null || ttl < 0 ? 0 : ttl;
    }

    @Override
    public long increment(String key, Duration ttl) {
        Long count = redis.execute(INCREMENT_WITH_TTL, List.of(key), String.valueOf(ttl.toMillis()));
        return count == null ? 0 : count;
    }
}
