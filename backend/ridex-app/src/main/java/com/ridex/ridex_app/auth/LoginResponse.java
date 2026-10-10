package com.ridex.ridex_app.auth;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        UserSummary user
) {
}