package com.ridex.ridex_app.auth;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.redis.RedisKeys;
import com.ridex.ridex_app.redis.TokenStore;
import com.ridex.ridex_app.security.OpaqueTokens;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;
import com.ridex.ridex_app.user.UserStatus;

/**
 * Email verification with a single-use link token OR a 6-digit code. Both live in Redis, not in
 * PostgreSQL: they are short-lived secrets, so "disappear by themselves after N minutes" (a TTL) is
 * exactly the behaviour we want, with no cleanup job and no expired rows piling up.
 *
 * What changed from the database version: "expired" and "already used" now look the same (the key is
 * simply gone), so the error message covers both.
 */
@Service
public class EmailVerificationService {

    static final String INVALID_LINK = "This verification link is invalid, expired or was already used";
    static final String INVALID_CODE = "This code is invalid or has expired";

    private final TokenStore store;
    private final EmailOtpService otpService;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration ttl;

    public EmailVerificationService(TokenStore store,
                                    EmailOtpService otpService,
                                    UserRepository userRepository,
                                    ApplicationEventPublisher eventPublisher,
                                    @Value("${ridex.verification.token-ttl-minutes}") long ttlMinutes) {
        this.store = store;
        this.otpService = otpService;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /** Creates a link token and a code for the user and asks for the email to be sent after commit. */
    @Transactional
    public void issueFor(User user) {
        // Only the newest link works: remove the token the user's previous email carried
        store.get(RedisKeys.verifyUser(user.getId()))
                .ifPresent(oldHash -> store.delete(RedisKeys.verifyToken(oldHash)));

        String raw = OpaqueTokens.generate();
        String hash = OpaqueTokens.sha256(raw);
        store.put(RedisKeys.verifyToken(hash), user.getId().toString(), ttl);
        store.put(RedisKeys.verifyUser(user.getId()), hash, ttl);

        String otp = otpService.issue(user.getId());
        eventPublisher.publishEvent(
                new VerificationEmailRequested(user.getEmail(), user.getFullName(), raw, otp));
    }

    @Transactional
    public void verify(String rawToken) {
        // take() reads and deletes in one atomic step: of two simultaneous clicks, only one wins
        String userId = store.take(RedisKeys.verifyToken(OpaqueTokens.sha256(rawToken)))
                .orElseThrow(() -> new InvalidVerificationTokenException(INVALID_LINK));

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new InvalidVerificationTokenException(INVALID_LINK));
        activate(user);
    }

    /** Verifies with the 6-digit code instead of the link. Unknown email and wrong code look the same. */
    @Transactional
    public void verifyWithCode(String email, String code) {
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(normalised)
                .filter(found -> found.getStatus() == UserStatus.PENDING_VERIFICATION)
                .orElseThrow(() -> new InvalidVerificationTokenException(INVALID_CODE));
        if (!otpService.verify(user.getId(), code)) {
            throw new InvalidVerificationTokenException(INVALID_CODE);
        }
        activate(user);
    }

    private void activate(User user) {
        // Only a pending account becomes active: verifying must never un-suspend someone
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.activate();
        }
        // Whichever way they verified, the other way must stop working
        store.get(RedisKeys.verifyUser(user.getId()))
                .ifPresent(hash -> store.delete(RedisKeys.verifyToken(hash)));
        store.delete(RedisKeys.verifyUser(user.getId()));
        otpService.discard(user.getId());
    }

    /**
     * Sends a fresh link and code if the email belongs to a pending account. Says nothing about
     * whether the email exists, so it cannot be used to discover registered addresses.
     */
    @Transactional
    public void resend(String email) {
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        userRepository.findByEmail(normalised)
                .filter(user -> user.getStatus() == UserStatus.PENDING_VERIFICATION)
                .ifPresent(this::issueFor);
    }
}
