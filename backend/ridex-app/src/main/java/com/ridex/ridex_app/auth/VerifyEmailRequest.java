package com.ridex.ridex_app.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyEmailRequest(
        @NotBlank(message = "token is required")
        @Size(max = 200, message = "token is too long")
        String token
) {
}
