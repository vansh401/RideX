package com.ridex.ridex_app.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository,
                               @Value("${ridex.jwt.refresh-token-ttl-days}") long ttlDays) {
        this.repository = repository;
        this.ttl = Duration.ofDays(ttlDays);
    }

    /** The raw token value is shown to the client once; only its hash is stored. */
    public record IssuedToken(String value, Instant expiresAt) {
    }

    public record Rotation(UUID userId, IssuedToken newToken) {
    }

    /** Starts a new session (a new token family), e.g. at login. */
    @Transactional
    public IssuedToken issueForNewSession(UUID userId) {
        return create(userId, UUID.randomUUID(), Instant.now());
    }

    /**
     * Exchanges a valid token for a fresh one in the same family. The old token stops working.
     * Presenting an already-used token revokes the whole family. The exception must not roll
     * that revocation back, hence noRollbackFor.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String rawToken) {
        Instant now = Instant.now();
        RefreshToken current = repository.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token"));

        if (current.isRevoked()) {
            log.warn("Refresh token reuse detected: revoking family {} of user {}",
                    current.getFamilyId(), current.getUserId());
            repository.revokeFamily(current.getFamilyId(), now);
            throw new InvalidRefreshTokenException("Invalid refresh token");
        }
        if (current.isExpired(now)) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        current.revoke(now);
        IssuedToken next = create(current.getUserId(), current.getFamilyId(), now);
        return new Rotation(current.getUserId(), next);
    }

    /** Logout: ends the whole session. Unknown tokens are ignored on purpose. */
    @Transactional
    public void revokeSessionOf(String rawToken) {
        repository.findByTokenHashForUpdate(hash(rawToken))
                .ifPresent(token -> repository.revokeFamily(token.getFamilyId(), Instant.now()));
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        repository.revokeAllForUser(userId, Instant.now());
    }

    private IssuedToken create(UUID userId, UUID familyId, Instant now) {
        byte[] bytes = new byte[32];            // 256 bits of randomness
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(ttl);
        repository.save(new RefreshToken(userId, familyId, hash(raw), expiresAt));
        return new IssuedToken(raw, expiresAt);
    }

    // SHA-256 is enough here: the token is 256 random bits, so it cannot be brute-forced,
    // and a deterministic hash lets us look it up. (BCrypt is for low-entropy passwords.)
    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
