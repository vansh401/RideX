package com.ridex.ridex_app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ridex.ridex_app.redis.DriverLocationIndex;

/**
 * Removes drivers from the location index when their heartbeat has expired (they closed the app or
 * lost signal). Searches already ignore such drivers; this keeps the index from growing forever.
 */
@Component
@ConditionalOnProperty(name = "ridex.matching.scheduler.enabled", havingValue = "true", matchIfMissing = true)
class StaleDriverCleanup {

    private static final Logger log = LoggerFactory.getLogger(StaleDriverCleanup.class);

    private final DriverLocationIndex index;

    StaleDriverCleanup(DriverLocationIndex index) {
        this.index = index;
    }

    @Scheduled(fixedDelayString = "${ridex.matching.stale-cleanup-interval-ms:60000}")
    void run() {
        try {
            int removed = index.purgeStale();
            if (removed > 0) {
                log.info("Removed {} stale drivers from the location index", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Stale driver cleanup skipped: {}", e.getClass().getSimpleName());
        }
    }
}
