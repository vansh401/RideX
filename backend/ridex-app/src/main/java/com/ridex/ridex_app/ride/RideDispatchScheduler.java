package com.ridex.ridex_app.ride;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the dispatch job every few seconds: expire unanswered offers, keep searching, give up on
 * searches that found nobody. Switch off with ridex.matching.scheduler.enabled=false (tests do).
 */
@Component
@ConditionalOnProperty(name = "ridex.matching.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class RideDispatchScheduler {

    private final RideDispatcher dispatcher;

    public RideDispatchScheduler(RideDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${ridex.matching.scheduler.interval-ms:5000}")
    public void run() {
        dispatcher.runCycle();
    }
}
