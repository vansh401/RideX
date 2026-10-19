package com.ridex.ridex_app.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.security.AccessTokenRevocations;
import com.ridex.ridex_app.security.OpaqueTokens;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository repository;
    private final AccessTokenRevocations accessTokenRevocations;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository,
                               AccessTokenRevocations accessTokenRevocations,
                               @Value("${ridex.jwt.refresh-token-ttl-days}") long ttlDays) {
        this.repository = repository;
        this.accessTokenRevocations = accessTokenRevocations;
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
        // Refresh tokens are in the database, but access tokens are stateless: cancel them in Redis too,
        // otherwise this user keeps working for up to 15 more minutes (the Step 2.5 gap).
        accessTokenRevocations.revokeAllFor(userId);
    }

    private IssuedToken create(UUID userId, UUID familyId, Instant now) {
        String raw = OpaqueTokens.generate();
        Instant expiresAt = now.plus(ttl);
        repository.save(new RefreshToken(userId, familyId, hash(raw), expiresAt));
        return new IssuedToken(raw, expiresAt);
    }

    static String hash(String rawToken) {
        return OpaqueTokens.sha256(rawToken);
    }
}
