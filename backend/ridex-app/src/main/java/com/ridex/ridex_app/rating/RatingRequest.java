package com.ridex.ridex_app.rating;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RatingRequest(
        @NotNull(message = "is required")
        @Min(value = 1, message = "must be between 1 and 5")
        @Max(value = 5, message = "must be between 1 and 5")
        Integer stars,

        @Size(max = 500, message = "must be at most 500 characters")
        String comment
) {
}
