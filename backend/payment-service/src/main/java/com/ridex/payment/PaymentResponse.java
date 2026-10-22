package com.ridex.payment;

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
        Instant updatedAt,
        String method,
        String couponCode,
        BigDecimal discount,
        BigDecimal netAmount,
        BigDecimal refundedAmount
) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getRideId(), p.getAmount(), p.getCurrency(), p.getStatus(),
                p.getAttempts(), p.getFailureReason(), p.getGatewayReference(), p.getCreatedAt(), p.getUpdatedAt(),
                p.getMethod(), p.getCouponCode(), p.getDiscount(), p.netAmount(), p.getRefundedAmount());
    }
}
