package com.ridex.payment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.ridex.platform.web.ConflictException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The payment for one ride.
 *
 * States:  PENDING (a charge is being attempted)  to  SUCCEEDED (money taken, final)  or  FAILED (declined, or the
 * gateway stayed unavailable too long). A FAILED payment can be retried (back to PENDING, with a new attempt number).
 * A SUCCEEDED payment can never be charged again: that rule lives here, in the entity, so no caller can break it.
 *
 * Technical trouble is different from a decline: while the gateway is unreachable the payment stays PENDING with a
 * "next attempt" time, and is tried again with the SAME idempotency key (see recordGatewayOutage).
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "ride_id", nullable = false, updatable = false)
    private UUID rideId;

    @Column(name = "rider_user_id", nullable = false, updatable = false)
    private UUID riderUserId;

    @Column(name = "driver_user_id", nullable = false, updatable = false)
    private UUID driverUserId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "gateway_reference")
    private String gatewayReference;

    @Column(name = "failure_reason")
    private String failureReason;

    /** WALLET or GATEWAY: how the rider actually paid (set when the payment succeeds). */
    @Column(name = "method")
    private String method;
    @Column(name = "coupon_code")
    private String couponCode;
    /** What a coupon took off [amount]. The rider is charged [amount - discount]; the company pays the discount. */
    @Column(nullable = false)
    private BigDecimal discount = BigDecimal.ZERO;
    @Column(name = "refunded_amount", nullable = false)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(name = "gateway_outages", nullable = false)
    private int gatewayOutages;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    // Required by JPA; not for application code
    protected Payment() {
    }

    public static Payment start(UUID rideId, UUID riderUserId, UUID driverUserId, BigDecimal amount, String currency, Instant now) {
        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.rideId = rideId;
        payment.riderUserId = riderUserId;
        payment.driverUserId = driverUserId;
        payment.amount = amount;
        payment.currency = currency;
        payment.status = PaymentStatus.PENDING;
        payment.attempts = 1;
        payment.idempotencyKey = keyFor(rideId, 1);
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    /** The key sent to the gateway. A NEW attempt gets a new key; the SAME attempt always sends the same key. */
    public static String keyFor(UUID rideId, int attempt) {
        return "ride-" + rideId + "-attempt-" + attempt;
    }

    public void markSucceeded(String gatewayReference, Instant now) {
        if (status == PaymentStatus.SUCCEEDED) {
            return;   // already done: never an error, never a second charge
        }
        this.status = PaymentStatus.SUCCEEDED;
        this.gatewayReference = gatewayReference;
        this.failureReason = null;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    public void markFailed(String reason, Instant now) {
        if (status == PaymentStatus.SUCCEEDED) {
            throw new ConflictException("A payment that succeeded cannot fail");
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    /**
     * The gateway gave NO answer (timeout, error, circuit open). Nothing is known about whether it charged. The payment
     * stays PENDING and is tried again later with the SAME key (so a charge that did go through is not repeated). After
     * {@code maxOutages} such failures in a row we give up and mark it FAILED.
     *
     * @return true if we gave up (the payment is now FAILED)
     */
    public boolean recordGatewayOutage(Instant now, Duration backoff, int maxOutages) {
        if (status != PaymentStatus.PENDING) {
            throw new ConflictException("Only a pending payment can be waiting for the gateway");
        }
        this.gatewayOutages += 1;
        if (gatewayOutages >= maxOutages) {
            markFailed("The payment provider is not available right now. Please try again later", now);
            return true;
        }
        // wait longer after every outage: 1x, 2x, 3x the base delay
        this.nextAttemptAt = now.plus(backoff.multipliedBy(gatewayOutages));
        this.updatedAt = now;
        return false;
    }

    /** Only a FAILED payment may be tried again. */
    public void retry(Instant now) {
        if (status != PaymentStatus.FAILED) {
            throw new ConflictException("Only a failed payment can be retried");
        }
        this.status = PaymentStatus.PENDING;
        this.attempts += 1;
        this.idempotencyKey = keyFor(rideId, attempts);
        this.failureReason = null;
        this.gatewayOutages = 0;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    /** A coupon was redeemed for this ride. Only while the payment is still open: a paid ride cannot get cheaper afterwards. */
    public void applyCoupon(String code, BigDecimal discount) {
        if (status == PaymentStatus.SUCCEEDED) {
            throw new ConflictException("A payment that succeeded cannot get a coupon");
        }
        if (discount.signum() < 0 || discount.compareTo(amount) > 0) {
            throw new IllegalArgumentException("a discount is between zero and the fare");
        }
        this.couponCode = code;
        this.discount = discount;
    }

    /** What the rider really pays: the fare minus the coupon. */
    public BigDecimal netAmount() {
        return amount.subtract(discount);
    }

    public void paidWith(String method) {
        this.method = method;
    }

    /** How much can still be given back: what the rider paid, minus what was already refunded. */
    public BigDecimal refundable() {
        return status == PaymentStatus.SUCCEEDED ? netAmount().subtract(refundedAmount) : BigDecimal.ZERO;
    }

    /** A refund that could not be completed gives its reserved amount back, so it can be asked for again. */
    public void undoRefund(BigDecimal refund, Instant now) {
        this.refundedAmount = refundedAmount.subtract(refund).max(BigDecimal.ZERO);
        this.updatedAt = now;
    }

    /** The rule that a refund can never exceed what was paid lives here, so no caller can break it. */
    public void recordRefund(BigDecimal refund, Instant now) {
        if (refund.signum() <= 0 || refund.compareTo(refundable()) > 0) {
            throw new ConflictException("A refund must be more than zero and at most " + refundable() + " (what is left of what was paid)");
        }
        this.refundedAmount = refundedAmount.add(refund);
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getRideId() { return rideId; }
    public UUID getRiderUserId() { return riderUserId; }
    public UUID getDriverUserId() { return driverUserId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getGatewayReference() { return gatewayReference; }
    public String getFailureReason() { return failureReason; }
    public int getGatewayOutages() { return gatewayOutages; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getMethod() { return method; }
    public String getCouponCode() { return couponCode; }
    public BigDecimal getDiscount() { return discount; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
}
