package com.ridex.ridex_app.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Random, single-purpose tokens (refresh, email verification, ...). The raw value goes to the
 * user; only its SHA-256 is stored.
 *
 * SHA-256 is enough because the token is 256 random bits, so it cannot be brute-forced, and a
 * deterministic hash lets us look it up. (BCrypt is for low-entropy passwords.)
 */
public final class OpaqueTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OpaqueTokens() {
    }

    /** 256 bits of randomness, URL-safe (43 characters). */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
