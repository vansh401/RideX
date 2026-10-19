package com.ridex.ridex_app.redis;

import java.time.Duration;
import java.util.Optional;

/**
 * A lock that works across several application servers (a Java {@code synchronized} or
 * {@code ReentrantLock} only protects ONE server's memory).
 */
public interface DistributedLock {

    /** Tries once, never waits. Empty means somebody else holds the lock right now. */
    Optional<Handle> tryLock(String key, Duration leaseTime);

    interface Handle {
        /** Releases the lock only if this handle still owns it (it may have expired meanwhile). */
        boolean release();
    }
}
