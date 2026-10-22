package com.ridex.ridex_app.ride;

import java.time.Instant;
import java.util.List;

import com.ridex.ridex_app.driver.VehicleCategory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Asking for a ride. Only category, pickup and dropoff are required; the rest is optional (Phase 14):
 *  couponCode    a discount code, applied when the ride is paid
 *  scheduledFor  book the ride ahead: the moment the rider wants to be picked up (between 15 minutes and 7 days from now)
 *  stops         up to 3 places to stop on the way, in order
 */
public record RideRequest(
        @NotNull(message = "is required")
        VehicleCategory category,
        @NotNull(message = "is required")
        @Valid
        PlaceInput pickup,
        @NotNull(message = "is required")
        @Valid
        PlaceInput dropoff,
        @Size(max = 40, message = "must be at most 40 characters")
        @Pattern(regexp = "[A-Za-z0-9_-]*", message = "may only contain letters, digits, - and _")
        String couponCode,
        Instant scheduledFor,
        @Size(max = 3, message = "at most 3 stops are allowed")
        @Valid
        List<PlaceInput> stops
) {
    /** The plain request without any extras. */
    public RideRequest(VehicleCategory category, PlaceInput pickup, PlaceInput dropoff) {
        this(category, pickup, dropoff, null, null, null);
    }

    public List<PlaceInput> stopsOrEmpty() {
        return stops == null ? List.of() : stops;
    }
}
