package com.ridex.ridex_app.rider;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SavedPlaceRequest(

        @NotBlank(message = "is required")
        @Size(max = 30, message = "must be at most 30 characters")
        String label,

        @NotBlank(message = "is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String address,

        @NotNull(message = "is required")
        @DecimalMin(value = "-90.0", message = "must be between -90 and 90")
        @DecimalMax(value = "90.0", message = "must be between -90 and 90")
        @Digits(integer = 2, fraction = 6, message = "use at most 6 decimal places")
        BigDecimal latitude,

        @NotNull(message = "is required")
        @DecimalMin(value = "-180.0", message = "must be between -180 and 180")
        @DecimalMax(value = "180.0", message = "must be between -180 and 180")
        @Digits(integer = 3, fraction = 6, message = "use at most 6 decimal places")
        BigDecimal longitude
) {
}
