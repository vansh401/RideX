package com.ridex.ridex_app.ride;

import com.ridex.ridex_app.common.LatLng;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A pickup or destination in a request: a readable address plus the coordinates. */
public record PlaceInput(

        @NotBlank(message = "is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String address,

        @NotNull(message = "is required")
        @Valid
        LatLng location
) {
}
