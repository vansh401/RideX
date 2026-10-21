package com.ridex.ridex_app.pricing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where the pricing service is and how patient we are with it (application.properties, prefix ridex.pricing). */
@ConfigurationProperties(prefix = "ridex.pricing")
public record PricingProperties(
        String baseUrl,
        String serviceToken,
        int connectTimeoutMs,
        int readTimeoutMs,
        int retryMaxAttempts,
        long retryWaitMs,
        int circuitWindow,
        int circuitMinCalls,
        float circuitFailureRate,
        long circuitOpenMs
) {
}
