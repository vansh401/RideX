package com.ridex.platform.contracts;

import java.math.BigDecimal;
import java.util.UUID;

/** The facts of "payment.succeeded" and "payment.failed" events (version 1). Schema: contracts/events/payment.v1.json */
public record PaymentEventData(
        UUID paymentId,
        UUID rideId,
        UUID riderUserId,
        UUID driverUserId,
        BigDecimal amount,
        String currency,
        String status,
        String failureReason,
        int attempts,
        String gatewayReference
) {
}
