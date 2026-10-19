package com.ridex.ridex_app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Rate limiting settings (application.properties, prefix ridex.rate-limit). */
@ConfigurationProperties(prefix = "ridex.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        /** Every /api/auth/** call, per client address. */
        int authLimit,
        int authWindowSeconds,
        /** Endpoints that send email (register, forgot-password, resend-verification, verify-otp). */
        int sensitiveLimit,
        int sensitiveWindowSeconds,
        /** Every other /api/** call. */
        int apiLimit,
        int apiWindowSeconds,
        /** Wrong passwords for one email from one address before a lockout starts. */
        int loginMaxFailures,
        int loginLockoutMinutes
) {
}
