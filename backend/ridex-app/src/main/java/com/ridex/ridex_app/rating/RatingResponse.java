package com.ridex.ridex_app.rating;

import java.time.Instant;
import java.util.UUID;

import com.ridex.ridex_app.ride.ActorRole;

public record RatingResponse(
        UUID id,
        UUID rideId,
        ActorRole raterRole,
        int stars,
        String comment,
        Instant createdAt
) {
    public static RatingResponse from(Rating rating) {
        return new RatingResponse(rating.getId(), rating.getRideId(), rating.getRaterRole(),
                rating.getStars(), rating.getComment(), rating.getCreatedAt());
    }
}
