package com.ridex.ridex_app.security;

import java.time.Duration;

/** Counts how often something happens in a time window and says whether it is too often. */
public interface RateLimiter {

    /**
     * @param allowed           false once the caller used up the limit for this window
     * @param retryAfterSeconds when a new window starts (0 when allowed)
     */
    record Decision(boolean allowed, long retryAfterSeconds) {
    }

    /** Counts one hit for {@code client} in {@code bucket} and decides. */
    Decision hit(String bucket, String client, int limit, Duration window);
}
