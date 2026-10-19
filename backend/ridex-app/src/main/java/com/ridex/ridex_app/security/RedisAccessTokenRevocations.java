package com.ridex.ridex_app.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.ridex.ridex_app.redis.RedisKeys;

@Component
public class RedisAccessTokenRevocations implements AccessTokenRevocations {

    private static final Logger log = LoggerFactory.getLogger(RedisAccessTokenRevocations.class);

    private final StringRedisTemplate redis;
    // A user-level entry only has to outlive the longest-lived token it can cancel
    private final Duration userEntryTtl;

    public RedisAccessTokenRevocations(StringRedisTemplate redis,
                                       @Value("${ridex.jwt.access-token-ttl-minutes}") long accessTokenTtlMinutes) {
        this.redis = redis;
        this.userEntryTtl = Duration.ofMinutes(accessTokenTtlMinutes).plusMinutes(1);
    }

    @Override
    public void revokeAllFor(UUID userId) {
        redis.opsForValue().set(RedisKeys.revokedUser(userId),
                String.valueOf(System.currentTimeMillis()), userEntryTtl);
    }

    @Override
    public void revoke(String jti, Instant tokenExpiresAt) {
        Duration remaining = Duration.between(Instant.now(), tokenExpiresAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return;   // already expired: nothing to cancel
        }
        redis.opsForValue().set(RedisKeys.revokedToken(jti), "1", remaining);
    }

    /**
     * If Redis is unreachable this FAILS OPEN (the token is accepted, with a warning in the log).
     * The alternative, failing closed, would make a Redis outage take the whole API down. The exposure
     * is bounded: access tokens live only 15 minutes, and every write endpoint re-checks the
     * account in PostgreSQL anyway.
     */
    @Override
    public boolean isRevoked(UUID userId, String jti, long issuedAtMillis) {
        try {
            List<String> values = redis.opsForValue().multiGet(
                    List.of(RedisKeys.revokedUser(userId), RedisKeys.revokedToken(jti)));
            if (values == null) {
                return false;
            }
            if (values.get(1) != null) {
                return true;
            }
            String cutoff = values.get(0);
            return cutoff != null && issuedAtMillis <= Long.parseLong(cutoff);
        } catch (RuntimeException e) {
            log.warn("Could not check token revocation (Redis unavailable?): {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
