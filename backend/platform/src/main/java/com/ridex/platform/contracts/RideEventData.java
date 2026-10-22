package com.ridex.platform.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The facts of a "ride.status-changed" event (version 1). Schema: contracts/events/ride.status-changed.v1.json */
public record RideEventData(
        UUID rideId,
        UUID riderUserId,
        UUID driverUserId,
        String from,
        String to,
        String actor,
        String note,
        Instant at,
        String pickupAddress,
        String dropoffAddress,
        BigDecimal fareTotal,
        String currency,
        /** Added in Phase 14, all optional (older events and older consumers are unaffected): */
        String category,
        String pickupCell,
        BigDecimal surgeMultiplier,
        String couponCode
) {
}
