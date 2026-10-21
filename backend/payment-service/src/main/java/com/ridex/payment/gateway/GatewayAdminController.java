package com.ridex.payment.gateway;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * An admin's view of the gateway protections, and a way to make the pretend gateway misbehave so they can be watched
 * working (the admin Payments page uses this).
 */
@RestController
@RequestMapping("/api/admin/payments/gateway")
public class GatewayAdminController {

    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final MockGateway mock;

    public GatewayAdminController(CircuitBreaker circuitBreaker, Bulkhead bulkhead, MockGateway mock) {
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
        this.mock = mock;
    }

    @GetMapping
    public Map<String, Object> status() {
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("circuitState", circuitBreaker.getState().name());
        body.put("failureRatePercent", metrics.getFailureRate() < 0 ? null : metrics.getFailureRate());
        body.put("bufferedCalls", metrics.getNumberOfBufferedCalls());
        body.put("failedCalls", metrics.getNumberOfFailedCalls());
        body.put("callsRefusedByCircuit", metrics.getNumberOfNotPermittedCalls());
        body.put("bulkheadAvailableCalls", bulkhead.getMetrics().getAvailableConcurrentCalls());
        body.put("bulkheadMaxCalls", bulkhead.getMetrics().getMaxAllowedConcurrentCalls());
        body.put("chaos", mock.chaos());
        body.put("callsReceivedByGateway", mock.calls());
        return body;
    }

    /** Make the pretend gateway fail, hang or answer too late. */
    @PostMapping("/chaos")
    public MockGateway.Chaos chaos(@RequestBody MockGateway.Chaos chaos) {
        mock.setChaos(new MockGateway.Chaos(chaos.mode() == null ? MockGateway.ChaosMode.NORMAL : chaos.mode(), Math.max(0, chaos.delayMs())));
        return mock.chaos();
    }

    /** Close the circuit by hand (an operator who knows the gateway is healthy again). */
    @PostMapping("/circuit-reset")
    public Map<String, String> reset() {
        circuitBreaker.reset();
        return Map.of("circuitState", circuitBreaker.getState().name());
    }
}
