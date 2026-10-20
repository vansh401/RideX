package com.ridex.ridex_app.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.common.Paging;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.events.EventEnvelope;
import com.ridex.ridex_app.events.OutboxService;
import com.ridex.ridex_app.events.PaymentEventData;
import com.ridex.ridex_app.events.Topics;
import com.ridex.ridex_app.ride.ActorRole;

/**
 * Charges riders for completed rides and tells everybody the result (as an event).
 *
 * How "no double charging" is achieved, in layers:
 *  1. The consumer handles each event at most once in effect (ProcessedEvents).
 *  2. The database allows ONE payment per ride (unique ride_id).
 *  3. A payment that is not PENDING is never charged again by the automatic flow.
 *  4. Every charge carries an idempotency key; the gateway returns the original result for a repeated key.
 * Any single layer failing is caught by the next one.
 */
@Service
public class PaymentService {

    private final PaymentRepository repository;
    private final PaymentGateway gateway;
    private final OutboxService outbox;

    public PaymentService(PaymentRepository repository, PaymentGateway gateway, OutboxService outbox) {
        this.repository = repository;
        this.gateway = gateway;
        this.outbox = outbox;
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

    @Transactional(readOnly = true)
    public PaymentResponse forRide(UUID userId, ActorRole role, UUID rideId) {
        return repository.findByRideId(rideId)
                .filter(p -> role == ActorRole.ADMIN || p.getRiderUserId().equals(userId) || p.getDriverUserId().equals(userId))
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
        PaymentGateway.ChargeResult result = gateway.charge(new PaymentGateway.ChargeRequest(
                payment.getIdempotencyKey(), payment.getAmount(), payment.getCurrency(), "RideX ride " + payment.getRideId()));
        Instant now = Instant.now();
        if (result.success()) {
            payment.markSucceeded(result.reference(), now);
        } else {
            payment.markFailed(result.failureReason(), now);
        }
        // Saved in THIS transaction with the payment itself (outbox), published to Kafka afterwards
        outbox.append(Topics.PAYMENT_EVENTS, payment.getRideId().toString(),
                result.success() ? EventEnvelope.PAYMENT_SUCCEEDED : EventEnvelope.PAYMENT_FAILED,
                new PaymentEventData(payment.getId(), payment.getRideId(), payment.getRiderUserId(), payment.getDriverUserId(),
                        payment.getAmount(), payment.getCurrency(), payment.getStatus().name(), payment.getFailureReason(),
                        payment.getAttempts(), payment.getGatewayReference()));
    }
}
