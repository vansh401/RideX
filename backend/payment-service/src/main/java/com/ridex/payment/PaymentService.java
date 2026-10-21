package com.ridex.payment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.payment.gateway.GatewayProperties;
import com.ridex.payment.gateway.GatewayUnavailableException;
import com.ridex.payment.gateway.PaymentGateway;
import com.ridex.platform.contracts.EventEnvelope;
import com.ridex.platform.contracts.PaymentEventData;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.kafka.OutboxService;
import com.ridex.platform.web.LogContext;
import com.ridex.platform.web.PageResponse;
import com.ridex.platform.web.Paging;
import com.ridex.platform.web.ResourceNotFoundException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Charges riders for completed rides and tells everybody the result (as an event).
 *
 * How "no double charging" is achieved, in layers:
 *  1. The consumer handles each event at most once in effect (processed_events).
 *  2. The database allows ONE payment per ride (unique ride_id).
 *  3. A payment that is not PENDING is never charged again by the automatic flow.
 *  4. Every charge carries an idempotency key; the gateway returns the original result for a repeated key. This is what
 *     makes it safe to try again after a timeout, when we do not know whether the first call went through.
 * Any single layer failing is caught by the next one.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository repository;
    private final PaymentGateway gateway;
    private final OutboxService outbox;
    private final GatewayProperties gatewayProperties;
    private final MeterRegistry metrics;

    public PaymentService(PaymentRepository repository, PaymentGateway gateway, OutboxService outbox, GatewayProperties gatewayProperties,
                          MeterRegistry metrics) {
        this.repository = repository;
        this.gateway = gateway;
        this.outbox = outbox;
        this.gatewayProperties = gatewayProperties;
        this.metrics = metrics;
        for (String outcome : new String[] {"succeeded", "failed", "gateway_unavailable"}) {
            Counter.builder("ridex.payments").description("Payment attempts by outcome").tag("outcome", outcome).register(metrics);
        }
    }

    /** Called when a ride completes. Safe to call twice for the same ride. */
    @Transactional
    public Payment chargeForCompletedRide(UUID rideId, UUID riderUserId, UUID driverUserId, BigDecimal amount, String currency) {
        Payment existing = repository.findByRideId(rideId).orElse(null);
        if (existing != null && existing.getStatus() != PaymentStatus.PENDING) {
            return existing;   // already charged or already failed: the automatic flow never charges twice
        }
        Payment payment = existing != null ? existing
                : repository.saveAndFlush(Payment.start(rideId, riderUserId, driverUserId, amount, currency, Instant.now()));
        attempt(payment);
        return payment;
    }

    /** The rider tries again after a failure. A new attempt number means a new idempotency key. */
    @Transactional
    public PaymentResponse retry(UUID riderUserId, UUID rideId) {
        Payment payment = repository.findByRideId(rideId)
                .filter(p -> p.getRiderUserId().equals(riderUserId))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        payment.retry(Instant.now());   // refuses anything but FAILED
        attempt(payment);
        return PaymentResponse.from(payment);
    }

    /** Another try for a payment that was waiting because the gateway did not answer. Same idempotency key as before. */
    @Transactional
    public void retryPending(UUID paymentId) {
        Payment payment = repository.findById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        attempt(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse forRide(UUID userId, boolean admin, UUID rideId) {
        return repository.findByRideId(rideId)
                .filter(p -> admin || p.getRiderUserId().equals(userId) || p.getDriverUserId().equals(userId))
                .map(PaymentResponse::from)
                // Not there yet (the ride just completed and the event is still travelling) looks like "not found"
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> list(PaymentStatus status, int page, int size) {
        Pageable pageable = Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(status == null ? repository.findAll(pageable) : repository.findByStatus(status, pageable),
                PaymentResponse::from);
    }

    private void attempt(Payment payment) {
        try (LogContext.Scope ignored = LogContext.ride(payment.getRideId())) {
            attemptCharge(payment);
        }
    }

    private void attemptCharge(Payment payment) {
        Instant now = Instant.now();
        PaymentGateway.ChargeResult result;
        try {
            result = gateway.charge(new PaymentGateway.ChargeRequest(
                    payment.getIdempotencyKey(), payment.getAmount(), payment.getCurrency(), "RideX ride " + payment.getRideId()));
        } catch (GatewayUnavailableException e) {
            metrics.counter("ridex.payments", "outcome", "gateway_unavailable").increment();
            // NO answer: we do not know whether the gateway charged. Do not guess. Wait, then ask again with the same key.
            boolean gaveUp = payment.recordGatewayOutage(now, Duration.ofMillis(gatewayProperties.outageBackoffMs()), gatewayProperties.maxOutages());
            log.warn("Gateway did not answer for payment {} (outage {}){}", payment.getId(), payment.getGatewayOutages(),
                    gaveUp ? ": giving up" : ": will retry");
            if (gaveUp) {
                publish(payment, EventEnvelope.PAYMENT_FAILED);
            }
            return;
        }
        if (result.success()) {
            payment.markSucceeded(result.reference(), now);
        } else {
            payment.markFailed(result.failureReason(), now);
        }
        metrics.counter("ridex.payments", "outcome", result.success() ? "succeeded" : "failed").increment();
        log.info("Payment {} for attempt {}", result.success() ? "succeeded" : "failed", payment.getAttempts());   // no amounts, no card or bank data
        publish(payment, result.success() ? EventEnvelope.PAYMENT_SUCCEEDED : EventEnvelope.PAYMENT_FAILED);
    }

    private void publish(Payment payment, String type) {
        // Saved in THIS transaction with the payment itself (outbox), published to Kafka afterwards
        outbox.append(Topics.PAYMENT_EVENTS, payment.getRideId().toString(), type,
                new PaymentEventData(payment.getId(), payment.getRideId(), payment.getRiderUserId(), payment.getDriverUserId(),
                        payment.getAmount(), payment.getCurrency(), payment.getStatus().name(), payment.getFailureReason(),
                        payment.getAttempts(), payment.getGatewayReference()));
    }
}
