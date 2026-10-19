package com.ridex.ridex_app.redis;

import java.util.UUID;

/**
 * Every Redis key the application uses, in one place.
 *
 * Redis has no tables or schema: a "key" is just a string, so a naming convention is the only
 * structure. We use colon-separated namespaces ("verify:token:...") so keys never collide and you
 * can look at them in redis-cli with SCAN MATCH verify:*.
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    // ----- one-time tokens (value = user id; they expire by themselves) -----
    public static String verifyToken(String tokenHash) {
        return "verify:token:" + tokenHash;
    }

    /** Points at the user's CURRENT verification token, so a newer link replaces the older one. */
    public static String verifyUser(UUID userId) {
        return "verify:user:" + userId;
    }

    public static String resetToken(String tokenHash) {
        return "reset:token:" + tokenHash;
    }

    public static String resetUser(UUID userId) {
        return "reset:user:" + userId;
    }

    // ----- one-time codes (OTP) -----
    public static String otp(UUID userId) {
        return "otp:verify:" + userId;
    }

    public static String otpAttempts(UUID userId) {
        return "otp:attempts:" + userId;
    }

    // ----- driver locations -----
    /** One GEO index (a sorted set underneath) holding every online driver's position. */
    public static final String DRIVER_GEO = "drivers:geo";

    /** Heartbeat: exists only while the driver keeps sending locations. Its expiry means "gone". */
    public static String driverHeartbeat(UUID driverProfileId) {
        return "driver:hb:" + driverProfileId;
    }

    // ----- locks -----
    public static String driverAssignmentLock(UUID driverProfileId) {
        return "lock:driver-assign:" + driverProfileId;
    }

    // ----- rate limiting -----
    public static String rateLimit(String bucket, String client) {
        return "rl:" + bucket + ":" + client;
    }

    public static String loginFailures(String email, String clientIp) {
        return "rl:login-fail:" + email + "|" + clientIp;
    }

    // ----- access-token revocation -----
    /** Value = the moment (epoch millis) before which this user's access tokens are no longer valid. */
    public static String revokedUser(UUID userId) {
        return "revoked:user:" + userId;
    }

    public static String revokedToken(String jti) {
        return "revoked:jti:" + jti;
    }
}
