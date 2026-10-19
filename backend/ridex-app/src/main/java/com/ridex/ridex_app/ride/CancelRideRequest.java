package com.ridex.ridex_app.ride;

import jakarta.validation.constraints.Size;

/** The reason is optional but recommended; it is kept in the ride's audit trail. */
public record CancelRideRequest(
        @Size(max = 255, message = "must be at most 255 characters")
        String reason
) {
}
