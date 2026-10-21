package com.ridex.platform.security;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Asks the shared Redis whether an access token was cancelled (suspended user, logout, password reset). The core
 * application writes these entries; every service only READS them, so a suspension takes effect everywhere at once.
 * The key names are part of the contract between the core and the services.
 *
 * If Redis is unreachable this FAILS OPEN (the token is accepted, with a warning): a Redis outage must not take every
 * service down, and the exposure is bounded by the 15-minute token lifetime.
 */
public class RevocationChecker {

    private static final Logger log = LoggerFactory.getLogger(RevocationChecker.class);

    private final StringRedisTemplate redis;

    public RevocationChecker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @param issuedAtMillis when the token was created, with millisecond precision (the "iat_ms" claim) */
    public boolean isRevoked(UUID userId, String tokenId, long issuedAtMillis) {
        if (redis == null) {
            return false;
        }
        try {
            List<String> values = redis.opsForValue().multiGet(List.of("revoked:user:" + userId, "revoked:jti:" + tokenId));
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
