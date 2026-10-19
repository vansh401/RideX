package com.ridex.ridex_app.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ridex.ridex_app.redis.RedisKeys;
import com.ridex.ridex_app.redis.TokenStore;
import com.ridex.ridex_app.security.OpaqueTokens;

/**
 * A 6-digit one-time code (OTP) as an alternative to the emailed link, e.g. for someone who opens
 * the email on a different device than the one running the app.
 *
 * Six digits is only a million possibilities, so unlike the long random link token it CAN be
 * guessed. Three things make that safe:
 * 1. it expires quickly (a few minutes), by itself, because it lives in Redis with a TTL;
 * 2. at most {@code maxAttempts} wrong guesses are allowed per code; then the code is destroyed;
 * 3. the address-level rate limit (RateLimitFilter) slows guessing across many accounts.
 */
@Service
public class EmailOtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TokenStore store;
    private final Duration ttl;
    private final int maxAttempts;

    public EmailOtpService(TokenStore store,
                           @Value("${ridex.verification.otp-ttl-minutes}") long ttlMinutes,
                           @Value("${ridex.verification.otp-max-attempts}") int maxAttempts) {
        this.store = store;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.maxAttempts = maxAttempts;
    }

    /** Creates a fresh code (replacing any earlier one) and returns it so it can be emailed. */
    public String issue(UUID userId) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        store.put(RedisKeys.otp(userId), digest(userId, code), ttl);
        store.delete(RedisKeys.otpAttempts(userId));   // a new code starts with a clean slate
        return code;
    }

    /**
     * True only if the code is correct, unexpired and unused. A correct code is consumed. Only the
     * hash of the code is stored, so reading Redis does not reveal it.
     */
    public boolean verify(UUID userId, String code) {
        long attempts = store.increment(RedisKeys.otpAttempts(userId), ttl);
        if (attempts > maxAttempts) {
            store.delete(RedisKeys.otp(userId));   // too many guesses: this code is dead, ask for a new one
            return false;
        }
        String stored = store.get(RedisKeys.otp(userId)).orElse(null);
        if (stored == null) {
            return false;
        }
        // Constant-time comparison, so response time does not leak how many characters matched
        boolean matches = MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8), digest(userId, code).getBytes(StandardCharsets.UTF_8));
        if (matches) {
            store.delete(RedisKeys.otp(userId), RedisKeys.otpAttempts(userId));
        }
        return matches;
    }

    public void discard(UUID userId) {
        store.delete(RedisKeys.otp(userId), RedisKeys.otpAttempts(userId));
    }

    private static String digest(UUID userId, String code) {
        return OpaqueTokens.sha256(userId + ":" + code);
    }
}
