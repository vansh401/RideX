package com.ridex.ridex_app.auth;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.redis.RedisKeys;
import com.ridex.ridex_app.redis.TokenStore;
import com.ridex.ridex_app.security.OpaqueTokens;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;
import com.ridex.ridex_app.user.UserStatus;

/** Password reset links. The token lives in Redis and expires by itself; see EmailVerificationService. */
@Service
public class PasswordResetService {

    private static final String INVALID_LINK = "This password reset link is invalid or has expired";

    private final TokenStore store;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration ttl;

    public PasswordResetService(TokenStore store,
                                UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                RefreshTokenService refreshTokenService,
                                ApplicationEventPublisher eventPublisher,
                                @Value("${ridex.password-reset.token-ttl-minutes}") long ttlMinutes) {
        this.store = store;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.eventPublisher = eventPublisher;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * Sends a reset link if the email belongs to an active account, and says nothing about
     * whether it does, so it cannot be used to discover registered addresses. Pending accounts
     * must verify their email first; suspended accounts get nothing.
     */
    @Transactional
    public void requestReset(String email) {
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        userRepository.findByEmail(normalised)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .ifPresent(this::issueToken);
    }

    private void issueToken(User user) {
        // only the newest link works
        store.get(RedisKeys.resetUser(user.getId()))
                .ifPresent(oldHash -> store.delete(RedisKeys.resetToken(oldHash)));

        String raw = OpaqueTokens.generate();
        String hash = OpaqueTokens.sha256(raw);
        store.put(RedisKeys.resetToken(hash), user.getId().toString(), ttl);
        store.put(RedisKeys.resetUser(user.getId()), hash, ttl);
        eventPublisher.publishEvent(
                new PasswordResetEmailRequested(user.getEmail(), user.getFullName(), raw));
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        // Atomic read-and-delete: the link works exactly once, even if clicked twice at the same instant
        String userId = store.take(RedisKeys.resetToken(OpaqueTokens.sha256(rawToken)))
                .orElseThrow(() -> new InvalidResetTokenException(INVALID_LINK));

        User user = userRepository.findById(UUID.fromString(userId))
                .filter(found -> found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new InvalidResetTokenException(INVALID_LINK));

        user.changePasswordHash(passwordEncoder.encode(newPassword));
        store.delete(RedisKeys.resetUser(user.getId()));

        // Whoever held the old password (or a stolen session) must not stay logged in: this ends the
        // refresh tokens AND, through Redis, the access tokens that are still within their 15 minutes
        refreshTokenService.revokeAllForUser(user.getId());
    }
}
