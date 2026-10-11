package com.ridex.ridex_app.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(

        @NotBlank(message = "is required")
        @Size(max = 200, message = "is too long")
        String token,

        // Same rules as registration
        @NotBlank(message = "is required")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                 message = "must contain at least one letter and one digit")
        String newPassword
) {
}
