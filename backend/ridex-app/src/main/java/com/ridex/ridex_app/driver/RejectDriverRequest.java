package com.ridex.ridex_app.driver;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectDriverRequest(
        @NotBlank(message = "is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String reason
) {
}
