package com.ridex.ridex_app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.ridex.ridex_app.redis.RedisKeys;
import com.ridex.ridex_app.security.OpaqueTokens;
import com.ridex.ridex_app.support.InMemoryTokenStore;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;
import com.ridex.ridex_app.user.UserStatus;

class EmailVerificationServiceTest {

    private final InMemoryTokenStore store = new InMemoryTokenStore();
    private final EmailOtpService otpService = new EmailOtpService(store, 10, 5);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final EmailVerificationService service =
            new EmailVerificationService(store, otpService, userRepository, publisher, 15);

    private User pendingUser() {
        User user = new User("Asha Rao", "asha@example.com", "+919876543210", "hash", Role.RIDER);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(user));
        return user;
    }

    private VerificationEmailRequested issue(User user) {
        service.issueFor(user);
        ArgumentCaptor<VerificationEmailRequested> event = ArgumentCaptor.forClass(VerificationEmailRequested.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publishEvent(event.capture());
        return event.getValue();
    }

    @Test
    void theTokenIsStoredOnlyAsAHashAndTheRawTokenGoesInTheEvent() {
        User user = pendingUser();

        VerificationEmailRequested event = issue(user);

        assertThat(event.toEmail()).isEqualTo("asha@example.com");
        assertThat(event.otp()).matches("\\d{6}");
        assertThat(store.contains(RedisKeys.verifyToken(event.rawToken()))).isFalse();   // raw value is not a key
        assertThat(store.contains(RedisKeys.verifyToken(OpaqueTokens.sha256(event.rawToken())))).isTrue();
    }

    @Test
    void theTokenExpiresByItself() {
        User user = pendingUser();
        VerificationEmailRequested event = issue(user);

        store.advance(Duration.ofMinutes(16));

        assertThatThrownBy(() -> service.verify(event.rawToken()))
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
    }

    @Test
    void verifyActivatesAPendingAccountAndTheTokenWorksOnlyOnce() {
        User user = pendingUser();
        VerificationEmailRequested event = issue(user);

        service.verify(event.rawToken());

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThatThrownBy(() -> service.verify(event.rawToken()))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void verifyDoesNotUnsuspendASuspendedAccount() {
        User user = pendingUser();
        VerificationEmailRequested event = issue(user);
        user.suspend();

        service.verify(event.rawToken());

        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    void verifyRejectsUnknownToken() {
        assertThatThrownBy(() -> service.verify("nope"))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void aNewerLinkReplacesTheOlderOne() {
        User user = pendingUser();
        VerificationEmailRequested first = issue(user);
        service.resend("asha@example.com");
        ArgumentCaptor<VerificationEmailRequested> events = ArgumentCaptor.forClass(VerificationEmailRequested.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(events.capture());
        VerificationEmailRequested second = events.getAllValues().get(1);

        assertThatThrownBy(() -> service.verify(first.rawToken()))
                .isInstanceOf(InvalidVerificationTokenException.class);
        service.verify(second.rawToken());
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    // ---------- the 6-digit code ----------

    @Test
    void theCorrectCodeActivatesTheAccountAndKillsTheLink() {
        User user = pendingUser();
        VerificationEmailRequested event = issue(user);

        service.verifyWithCode(" Asha@Example.com ", event.otp());

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThatThrownBy(() -> service.verify(event.rawToken()))
                .as("after verifying by code, the link must stop working")
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void aWrongCodeIsRefusedAndTheAccountStaysPending() {
        User user = pendingUser();
        VerificationEmailRequested event = issue(user);
        String wrong = event.otp().equals("000000") ? "111111" : "000000";

        assertThatThrownBy(() -> service.verifyWithCode("asha@example.com", wrong))
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
    }

    @Test
    void afterTooManyWrongGuessesEvenTheCorrectCodeIsDead() {
        User user = pendingUser();
        VerificationEmailRequested event = issue(user);
        String wrong = event.otp().equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.verifyWithCode("asha@example.com", wrong))
                    .isInstanceOf(InvalidVerificationTokenException.class);
        }

        assertThatThrownBy(() -> service.verifyWithCode("asha@example.com", event.otp()))
                .as("the 6th attempt must fail even with the right code: it was locked by the guessing")
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
    }

    @Test
    void theCodeExpires() {
        User user = pendingUser();
        VerificationEmailRequested event = issue(user);

        store.advance(Duration.ofMinutes(11));

        assertThatThrownBy(() -> service.verifyWithCode("asha@example.com", event.otp()))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void anUnknownEmailGetsTheSameErrorAsAWrongCode() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyWithCode("nobody@example.com", "123456"))
                .isInstanceOf(InvalidVerificationTokenException.class)
                .hasMessage(EmailVerificationService.INVALID_CODE);
    }

    // ---------- resend ----------

    @Test
    void resendForAnActiveAccountDoesNothing() {
        User user = pendingUser();
        user.activate();

        service.resend("asha@example.com");

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void resendForAnUnknownEmailDoesNothing() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        service.resend("nobody@example.com");

        verify(publisher, never()).publishEvent(any(Object.class));
        assertThat(store.size()).isZero();
    }
}
