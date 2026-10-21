package com.ridex.payment.gateway;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/** Builds the protected gateway client from the settings in application.properties. */
@Configuration
@EnableConfigurationProperties({GatewayProperties.class, MockGatewayProperties.class})
public class GatewayConfig {

    @Bean
    CircuitBreaker gatewayCircuitBreaker(GatewayProperties p) {
        return CircuitBreaker.of("payment-gateway", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(p.circuitWindow())
                .minimumNumberOfCalls(p.circuitMinCalls())
                .failureRateThreshold(p.circuitFailureRate())
                .waitDurationInOpenState(Duration.ofMillis(p.circuitOpenMs()))
                .permittedNumberOfCallsInHalfOpenState(p.circuitHalfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)   // checked on the next call; no extra thread
                // Only "no answer" counts against the circuit. A decline is a normal answer.
                .recordExceptions(GatewayCallException.class)
                .build());
    }

    @Bean
    Retry gatewayRetry(GatewayProperties p) {
        return Retry.of("payment-gateway", RetryConfig.custom()
                .maxAttempts(p.retryMaxAttempts())
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(p.retryWaitMs())))
                // Retry only failures that may be temporary. An open circuit or a full bulkhead must NOT be retried:
                // hammering them defeats their purpose.
                .retryExceptions(GatewayCallException.class)
                .build());
    }

    @Bean
    Bulkhead gatewayBulkhead(GatewayProperties p) {
        return Bulkhead.of("payment-gateway", BulkheadConfig.custom()
                .maxConcurrentCalls(p.bulkheadMaxConcurrent())
                .maxWaitDuration(Duration.ZERO)   // do not queue: refuse at once
                .build());
    }

    @Bean
    PaymentGateway paymentGateway(GatewayProperties p, CircuitBreaker circuitBreaker, Retry retry, Bulkhead bulkhead) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(p.connectTimeoutMs())).build());
        factory.setReadTimeout(Duration.ofMillis(p.readTimeoutMs()));   // the TIMEOUT protection
        RestClient client = RestClient.builder().baseUrl(p.baseUrl()).requestFactory(factory).build();
        return new HttpPaymentGateway(client, circuitBreaker, retry, bulkhead);
    }
}
