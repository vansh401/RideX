package com.ridex.ridex_app.redis;

import java.time.Duration;
import java.util.Optional;

/**
 * Short-lived secrets (verification and reset tokens, one-time codes) and expiring counters that
 * must disappear on their own. The real implementation is Redis; unit tests use an in-memory fake.
 */
public interface TokenStore {

    /** Stores the value and makes it vanish after {@code ttl}. Overwrites any existing value. */
    void put(String key, String value, Duration ttl);

    /**
     * Reads AND deletes in one atomic step (Redis GETDEL). If two requests present the same
     * single-use token at the same instant, exactly one of them gets the value; the other gets
     * empty. That is what makes "use once" safe without any locking.
     */
    Optional<String> take(String key);

    Optional<String> get(String key);

    void delete(String... keys);

    /** Seconds until the key expires; 0 when it does not exist. */
    long ttlSeconds(String key);

    /** Adds one to a counter; the counter expires {@code ttl} after its FIRST increment. */
    long increment(String key, Duration ttl);
}
