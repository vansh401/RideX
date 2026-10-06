package com.ridex.ridex_app.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank(message = "is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String fullName,

        @NotBlank(message = "is required")
        @Email(message = "must be a valid email address")
        @Size(max = 255, message = "must be at most 255 characters")
        String email,

        @NotBlank(message = "is required")
        @Pattern(regexp = "^\\+?[0-9]{10,15}$",
                 message = "must be 10-15 digits with an optional leading +")
        String phone,

        @NotBlank(message = "is required")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                 message = "must contain at least one letter and one digit")
        String password,

        @NotNull(message = "is required")
        RegistrationRole role
) {
}