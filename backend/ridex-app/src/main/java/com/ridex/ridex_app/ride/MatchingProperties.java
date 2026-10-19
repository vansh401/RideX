package com.ridex.ridex_app.ride;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Dispatch settings (application.properties, prefix ridex.matching). */
@ConfigurationProperties(prefix = "ridex.matching")
public record MatchingProperties(
        /** Only drivers within this distance of the pickup are considered. */
        double radiusKm,
        /** How long a driver has to answer an offer before it moves to the next driver. */
        int offerTimeoutSeconds,
        /** After this long without any driver accepting, the ride is cancelled (no driver found). */
        int searchTimeoutSeconds,
        /** A driver whose last location is older than this is treated as gone. */
        int locationFreshnessSeconds
) {
}
