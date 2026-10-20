package com.ridex.ridex_app.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.ridex.ridex_app.common.ConflictException;

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
 * States:  PENDING (a charge is being attempted)  to  SUCCEEDED (money taken, final)  or  FAILED (declined).
 * A FAILED payment can be retried (back to PENDING, with a new attempt number). A SUCCEEDED payment can never be
 * charged again: that rule lives here, in the entity, so no caller can break it.
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
        this.updatedAt = now;
    }

    public void markFailed(String reason, Instant now) {
        if (status == PaymentStatus.SUCCEEDED) {
            throw new ConflictException("A payment that succeeded cannot fail");
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = now;
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
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
