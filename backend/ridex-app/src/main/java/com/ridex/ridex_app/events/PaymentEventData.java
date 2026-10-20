package com.ridex.ridex_app.events;

import java.math.BigDecimal;
import java.util.UUID;

/** The facts of "payment.succeeded" and "payment.failed" events (version 1). */
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
