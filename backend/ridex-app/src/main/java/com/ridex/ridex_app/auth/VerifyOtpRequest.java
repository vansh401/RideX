package com.ridex.ridex_app.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyOtpRequest(
        @NotBlank(message = "email is required")
        @Email(message = "must be a valid email")
        @Size(max = 255, message = "email is too long")
        String email,

        @NotBlank(message = "code is required")
        @Pattern(regexp = "\\d{6}", message = "must be 6 digits")
        String code
) {
}
