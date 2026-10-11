package com.ridex.ridex_app.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.security.OpaqueTokens;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;
import com.ridex.ridex_app.user.UserStatus;

@Service
public class PasswordResetService {

    private static final String INVALID_LINK = "This password reset link is invalid or has expired";

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration ttl;

    public PasswordResetService(PasswordResetTokenRepository tokenRepository,
                                UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                RefreshTokenService refreshTokenService,
                                ApplicationEventPublisher eventPublisher,
                                @Value("${ridex.password-reset.token-ttl-minutes}") long ttlMinutes) {
        this.tokenRepository = tokenRepository;
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
        Instant now = Instant.now();
        tokenRepository.invalidateAllForUser(user.getId(), now);   // only the newest link works

        String raw = OpaqueTokens.generate();
        tokenRepository.save(new PasswordResetToken(
                user.getId(), OpaqueTokens.sha256(raw), now.plus(ttl)));
        eventPublisher.publishEvent(
                new PasswordResetEmailRequested(user.getEmail(), user.getFullName(), raw));
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        Instant now = Instant.now();
        PasswordResetToken token = tokenRepository
                .findByTokenHashForUpdate(OpaqueTokens.sha256(rawToken))
                .orElseThrow(() -> new InvalidResetTokenException(INVALID_LINK));

        if (token.isUsed() || token.isExpired(now)) {
            throw new InvalidResetTokenException(INVALID_LINK);
        }

        User user = userRepository.findById(token.getUserId())
                .filter(found -> found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new InvalidResetTokenException(INVALID_LINK));

        user.changePasswordHash(passwordEncoder.encode(newPassword));
        token.markUsed(now);
        tokenRepository.invalidateAllForUser(user.getId(), now);

        // Whoever held the old password (or a stolen session) must not stay logged in
        refreshTokenService.revokeAllForUser(user.getId());
    }
}
