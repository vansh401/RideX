package com.ridex.ridex_app.surge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs a surge cycle every few seconds. Switch off with ridex.surge.enabled=false (the tests do and call the engine themselves). */
@Component
@ConditionalOnProperty(name = "ridex.surge.enabled", havingValue = "true", matchIfMissing = true)
public class SurgeScheduler {

    private final SurgeEngine engine;

    public SurgeScheduler(SurgeEngine engine) {
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${ridex.surge.interval-ms:30000}")
    public void run() {
        engine.run();
    }
}
