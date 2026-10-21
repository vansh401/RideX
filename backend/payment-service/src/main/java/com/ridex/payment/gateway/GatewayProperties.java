package com.ridex.payment.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings of the gateway client (application.properties, prefix ridex.payments.gateway). */
@ConfigurationProperties(prefix = "ridex.payments.gateway")
public record GatewayProperties(
        String baseUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        int retryMaxAttempts,
        long retryWaitMs,
        int circuitWindow,
        int circuitMinCalls,
        float circuitFailureRate,
        long circuitOpenMs,
        int circuitHalfOpenCalls,
        int bulkheadMaxConcurrent,
        int maxOutages,
        long outageBackoffMs
) {
}
