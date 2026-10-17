package com.ridex.ridex_app.driver;

import java.time.LocalDate;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record DriverProfileRequest(

        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9 -]{4,29}$",
                 message = "must be 5-30 letters, digits, spaces or hyphens")
        String licenseNumber,

        @NotNull(message = "is required")
        @Future(message = "must be a future date")
        LocalDate licenseExpiry
) {
}
