package com.ridex.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The analytics service: everything that is learned by WATCHING events rather than by taking part in a ride. It keeps the
 * audit log, the daily counts and the dead letters, and exposes them to admins.
 *
 * It is the easiest kind of service to split off: it only listens, so if it is down nothing else notices, and when it
 * comes back it catches up from where it stopped (its consumer group remembers the position).
 */
@SpringBootApplication
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
