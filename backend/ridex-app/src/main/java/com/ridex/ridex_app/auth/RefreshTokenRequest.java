package com.ridex.ridex_app.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshTokenRequest(
        @NotBlank(message = "refreshToken is required")
        @Size(max = 200, message = "refreshToken is too long")
        String refreshToken
) {
}
