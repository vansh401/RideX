package com.ridex.ridex_app.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.security.OpaqueTokens;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;
import com.ridex.ridex_app.user.UserStatus;

@Service
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration ttl;

    public EmailVerificationService(EmailVerificationTokenRepository tokenRepository,
                                    UserRepository userRepository,
                                    ApplicationEventPublisher eventPublisher,
                                    @Value("${ridex.verification.token-ttl-minutes}") long ttlMinutes) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /** Creates a token for the user and asks for the email to be sent after commit. */
    @Transactional
    public void issueFor(User user) {
        String raw = OpaqueTokens.generate();
        tokenRepository.save(new EmailVerificationToken(
                user.getId(), OpaqueTokens.sha256(raw), Instant.now().plus(ttl)));
        eventPublisher.publishEvent(
                new VerificationEmailRequested(user.getEmail(), user.getFullName(), raw));
    }

    @Transactional
    public void verify(String rawToken) {
        Instant now = Instant.now();
        EmailVerificationToken token = tokenRepository
                .findByTokenHashForUpdate(OpaqueTokens.sha256(rawToken))
                .orElseThrow(() -> new InvalidVerificationTokenException(
                        "This verification link is invalid"));

        if (token.isUsed()) {
            throw new InvalidVerificationTokenException("This verification link was already used");
        }
        if (token.isExpired(now)) {
            throw new InvalidVerificationTokenException(
                    "This verification link has expired. Please request a new one");
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new InvalidVerificationTokenException(
                        "This verification link is invalid"));

        token.markUsed(now);
        // Only a pending account becomes active: verifying must never un-suspend someone
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.activate();
        }
    }

    /**
     * Sends a fresh link if the email belongs to a pending account. Says nothing about whether
     * the email exists, so it cannot be used to discover registered addresses.
     */
    @Transactional
    public void resend(String email) {
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        userRepository.findByEmail(normalised)
                .filter(user -> user.getStatus() == UserStatus.PENDING_VERIFICATION)
                .ifPresent(user -> {
                    tokenRepository.invalidateAllForUser(user.getId(), Instant.now());
                    issueFor(user);
                });
    }
}
