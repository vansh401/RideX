package com.ridex.ridex_app.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SuspendUserRequest(
        @NotBlank(message = "is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String reason
) {
}
