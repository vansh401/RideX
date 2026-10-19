package com.ridex.ridex_app.driver;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VehicleRequest(

        @NotNull(message = "is required")
        VehicleCategory category,

        @NotBlank(message = "is required")
        @Size(max = 50, message = "must be at most 50 characters")
        String make,

        @NotBlank(message = "is required")
        @Size(max = 50, message = "must be at most 50 characters")
        String model,

        @NotBlank(message = "is required")
        @Size(max = 30, message = "must be at most 30 characters")
        String color,

        // Indian format such as KA 01 AB 1234. Spaces and hyphens are allowed on input and removed.
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Za-z]{2}[ -]?\\d{1,2}[ -]?[A-Za-z]{0,3}[ -]?\\d{4}$",
                 message = "must look like KA01AB1234")
        String registrationNumber,

        @NotNull(message = "is required")
        @Min(value = 2000, message = "must be 2000 or later")
        @Max(value = 2100, message = "is not a valid year")
        Integer manufactureYear,

        @NotNull(message = "is required")
        @Min(value = 1, message = "must be at least 1")
        @Max(value = 6, message = "must be at most 6")
        Integer seats
) {
}
