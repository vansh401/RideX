package com.ridex.ridex_app.ride;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the promotion of booked rides every half minute. Switch off with ridex.scheduling.enabled=false (the tests do and call the promoter themselves). */
@Component
@ConditionalOnProperty(name = "ridex.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledRideScheduler {

    private final ScheduledRidePromoter promoter;

    public ScheduledRideScheduler(ScheduledRidePromoter promoter) {
        this.promoter = promoter;
    }

    @Scheduled(fixedDelayString = "${ridex.scheduling.interval-ms:30000}")
    public void run() {
        promoter.run();
    }
}
