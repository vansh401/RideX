package com.ridex.ridex_app.ride;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * "This ride just changed status." Published (inside the database transaction) every time a ride is
 * saved after a change. Other modules listen: the realtime module pushes it to the browsers, the
 * notification module stores a notification. The ride module itself knows nothing about WebSockets.
 *
 * {@code driverUserId} is the driver involved at that moment: the driver who was just offered the ride, who
 * accepted it, or (for a decline, timeout or cancellation) the driver it was taken away from.
 */
public record RideStatusChanged(
        UUID rideId,
        UUID riderUserId,
        UUID driverUserId,
        RideStatus from,
        RideStatus to,
        ActorRole actor,
        String note,
        Instant at,
        String pickupAddress,
        String dropoffAddress,
        BigDecimal fareTotal,
        String currency,
        OfferSummary offer
) {

    /** What a driver needs to see on an incoming offer. Only present when {@code to == DRIVER_ASSIGNED}. */
    public record OfferSummary(
            String category,
            String pickupAddress,
            String dropoffAddress,
            BigDecimal distanceKm,
            BigDecimal totalFare,
            Instant expiresAt
    ) {
    }

    public RideStatusChanged withOffer(UUID offeredDriverUserId, OfferSummary summary) {
        return new RideStatusChanged(rideId, riderUserId, offeredDriverUserId, from, to, actor, note, at,
                pickupAddress, dropoffAddress, fareTotal, currency, summary);
    }
}
