package com.ridex.ridex_app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.ridex.ridex_app.security.OpaqueTokens;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;
import com.ridex.ridex_app.user.UserStatus;

class EmailVerificationServiceTest {

    private final EmailVerificationTokenRepository tokenRepository =
            mock(EmailVerificationTokenRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final EmailVerificationService service =
            new EmailVerificationService(tokenRepository, userRepository, publisher, 15);

    private User pendingUser() {
        return new User("Asha Rao", "asha@example.com", "+919876543210", "hash", Role.RIDER);
    }

    private EmailVerificationToken storedToken(User user, String raw, Instant expiresAt) {
        EmailVerificationToken token =
                new EmailVerificationToken(user.getId(), OpaqueTokens.sha256(raw), expiresAt);
        when(tokenRepository.findByTokenHashForUpdate(OpaqueTokens.sha256(raw)))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return token;
    }

    @Test
    void issuedTokenIsStoredAsAHashAndTheRawTokenGoesInTheEvent() {
        User user = pendingUser();

        service.issueFor(user);

        ArgumentCaptor<EmailVerificationToken> saved = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(saved.capture());
        ArgumentCaptor<VerificationEmailRequested> event =
                ArgumentCaptor.forClass(VerificationEmailRequested.class);
        verify(publisher).publishEvent(event.capture());

        assertThat(event.getValue().toEmail()).isEqualTo("asha@example.com");
        assertThat(event.getValue().rawToken()).isNotEqualTo(saved.getValue().getTokenHash());
        assertThat(OpaqueTokens.sha256(event.getValue().rawToken()))
                .isEqualTo(saved.getValue().getTokenHash());
        assertThat(saved.getValue().getExpiresAt())
                .isAfter(Instant.now().plusSeconds(14 * 60))
                .isBefore(Instant.now().plusSeconds(16 * 60));
    }

    @Test
    void verifyActivatesAPendingAccountAndConsumesTheToken() {
        User user = pendingUser();
        EmailVerificationToken token = storedToken(user, "raw", Instant.now().plusSeconds(3600));

        service.verify("raw");

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(token.isUsed()).isTrue();
    }

    @Test
    void verifyDoesNotUnsuspendASuspendedAccount() {
        User user = pendingUser();
        user.suspend();
        EmailVerificationToken token = storedToken(user, "raw", Instant.now().plusSeconds(3600));

        service.verify("raw");

        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(token.isUsed()).isTrue();
    }

    @Test
    void verifyRejectsUnknownToken() {
        when(tokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify("nope"))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void verifyRejectsAnAlreadyUsedToken() {
        User user = pendingUser();
        EmailVerificationToken token = storedToken(user, "raw", Instant.now().plusSeconds(3600));
        token.markUsed(Instant.now());

        assertThatThrownBy(() -> service.verify("raw"))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void verifyRejectsAnExpiredTokenAndLeavesTheAccountPending() {
        User user = pendingUser();
        storedToken(user, "raw", Instant.now().minusSeconds(5));

        assertThatThrownBy(() -> service.verify("raw"))
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
    }

    @Test
    void resendForAPendingAccountInvalidatesOldLinksAndSendsANewOne() {
        User user = pendingUser();
        when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(user));

        service.resend(" Asha@Example.com ");

        verify(tokenRepository).invalidateAllForUser(any(), any(Instant.class));
        verify(publisher).publishEvent(any(VerificationEmailRequested.class));
    }

    @Test
    void resendForAnActiveAccountDoesNothing() {
        User user = pendingUser();
        user.activate();
        when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(user));

        service.resend("asha@example.com");

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void resendForAnUnknownEmailDoesNothing() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        service.resend("nobody@example.com");

        verify(publisher, never()).publishEvent(any(Object.class));
        verify(tokenRepository, never()).save(any());
    }
}
