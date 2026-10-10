package com.ridex.ridex_app.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(

        @NotBlank(message = "is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String email,

        @NotBlank(message = "is required")
        @Size(max = 72, message = "must be at most 72 characters")
        String password
) {
}