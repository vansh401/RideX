package com.ridex.ridex_app.ride;

import com.ridex.ridex_app.driver.VehicleCategory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record RideRequest(
        @NotNull(message = "is required")
        VehicleCategory category,

        @NotNull(message = "is required")
        @Valid
        PlaceInput pickup,

        @NotNull(message = "is required")
        @Valid
        PlaceInput dropoff
) {
}
