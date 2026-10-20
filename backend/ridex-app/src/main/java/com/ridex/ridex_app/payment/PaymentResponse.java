package com.ridex.ridex_app.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID rideId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        int attempts,
        String failureReason,
        String gatewayReference,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getRideId(), p.getAmount(), p.getCurrency(), p.getStatus(),
                p.getAttempts(), p.getFailureReason(), p.getGatewayReference(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
