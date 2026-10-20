package com.ridex.ridex_app.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The facts of a "ride.status-changed" event (version 1). */
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
        String currency
) {
}
