package com.ridex.ridex_app.security;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.ridex.ridex_app.common.TooManyRequestsException;
import com.ridex.ridex_app.redis.RedisKeys;
import com.ridex.ridex_app.redis.TokenStore;

/**
 * Brute-force protection for login: after {@code loginMaxFailures} wrong passwords for the same
 * email from the same address, further attempts are refused until the window ends.
 *
 * Why the key is (email + address) and not the email alone: locking by email alone would let anyone
 * lock a victim out of their own account just by guessing wrong passwords for them. With the
 * address in the key, an attacker only locks THEMSELVES out. The trade-off (an attacker rotating
 * addresses is not stopped by this alone) is why the per-address limits in RateLimitFilter exist too.
 */
@Component
public class LoginAttemptLimiter {

    private final TokenStore store;
    private final RateLimitProperties properties;

    public LoginAttemptLimiter(TokenStore store, RateLimitProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /** Throws 429 if this email+address is currently locked out. */
    public void assertNotLocked(String email, String clientIp) {
        if (!properties.enabled()) {
            return;
        }
        String key = RedisKeys.loginFailures(email, clientIp);
        long failures = store.get(key).map(Long::parseLong).orElse(0L);
        if (failures >= properties.loginMaxFailures()) {
            long wait = Math.max(1, store.ttlSeconds(key));
            throw new TooManyRequestsException(
                    "Too many failed login attempts. Try again in " + ((wait + 59) / 60) + " minute(s).", wait);
        }
    }

    public void recordFailure(String email, String clientIp) {
        if (properties.enabled()) {
            store.increment(RedisKeys.loginFailures(email, clientIp),
                    Duration.ofMinutes(properties.loginLockoutMinutes()));
        }
    }

    /** A successful login forgets earlier mistakes. */
    public void clear(String email, String clientIp) {
        if (properties.enabled()) {
            store.delete(RedisKeys.loginFailures(email, clientIp));
        }
    }
}
