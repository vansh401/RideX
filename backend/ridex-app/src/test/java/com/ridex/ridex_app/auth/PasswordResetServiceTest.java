package com.ridex.ridex_app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.ridex.ridex_app.redis.RedisKeys;
import com.ridex.ridex_app.security.OpaqueTokens;
import com.ridex.ridex_app.support.InMemoryTokenStore;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;

class PasswordResetServiceTest {

    private final InMemoryTokenStore store = new InMemoryTokenStore();
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final PasswordResetService service = new PasswordResetService(
            store, userRepository, passwordEncoder, refreshTokenService, publisher, 30);

    private User activeUser() {
        User user = new User("Asha Rao", "asha@example.com", "+919876543210",
                passwordEncoder.encode("OldSecret1"), Role.RIDER);
        user.activate();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(user));
        return user;
    }

    private List<String> emailedTokens() {
        ArgumentCaptor<PasswordResetEmailRequested> event = ArgumentCaptor.forClass(PasswordResetEmailRequested.class);
        verify(publisher, atLeastOnce()).publishEvent(event.capture());
        return event.getAllValues().stream().map(PasswordResetEmailRequested::rawToken).toList();
    }

    // ---------- requesting a reset ----------

    @Test
    void activeAccountGetsAHashedTokenAndAnEmailEvent() {
        activeUser();

        service.requestReset(" Asha@Example.com ");

        ArgumentCaptor<PasswordResetEmailRequested> event =
                ArgumentCaptor.forClass(PasswordResetEmailRequested.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue().toEmail()).isEqualTo("asha@example.com");
        String raw = event.getValue().rawToken();
        assertThat(store.contains(RedisKeys.resetToken(raw))).as("the raw token is never a key").isFalse();
        assertThat(store.contains(RedisKeys.resetToken(OpaqueTokens.sha256(raw)))).isTrue();
    }

    @Test
    void unknownEmailDoesNothing() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        service.requestReset("nobody@example.com");

        assertThat(store.size()).isZero();
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void pendingAccountDoesNothing() {
        User pending = new User("Asha Rao", "asha@example.com", "+919876543210", "hash", Role.RIDER);
        when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(pending));

        service.requestReset("asha@example.com");

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void suspendedAccountDoesNothing() {
        User user = activeUser();
        user.suspend();

        service.requestReset("asha@example.com");

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    // ---------- using the link ----------

    @Test
    void resetChangesThePasswordEndsAllSessionsAndTheTokenWorksOnlyOnce() {
        User user = activeUser();
        service.requestReset("asha@example.com");
        String raw = emailedTokens().get(0);

        service.resetPassword(raw, "NewSecret2");

        assertThat(passwordEncoder.matches("NewSecret2", user.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("OldSecret1", user.getPasswordHash())).isFalse();
        verify(refreshTokenService).revokeAllForUser(user.getId());
        assertThatThrownBy(() -> service.resetPassword(raw, "Another3"))
                .isInstanceOf(InvalidResetTokenException.class);
    }

    @Test
    void unknownTokenIsRejected() {
        assertThatThrownBy(() -> service.resetPassword("nope", "NewSecret2"))
                .isInstanceOf(InvalidResetTokenException.class);
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    @Test
    void expiredTokenIsRejectedAndPasswordIsUntouched() {
        User user = activeUser();
        String oldHash = user.getPasswordHash();
        service.requestReset("asha@example.com");
        String raw = emailedTokens().get(0);

        store.advance(Duration.ofMinutes(31));

        assertThatThrownBy(() -> service.resetPassword(raw, "NewSecret2"))
                .isInstanceOf(InvalidResetTokenException.class);
        assertThat(user.getPasswordHash()).isEqualTo(oldHash);
    }

    @Test
    void aNewerLinkReplacesTheOlderOne() {
        activeUser();
        service.requestReset("asha@example.com");
        service.requestReset("asha@example.com");
        String first = emailedTokens().get(0);

        assertThatThrownBy(() -> service.resetPassword(first, "NewSecret2"))
                .isInstanceOf(InvalidResetTokenException.class);
    }

    @Test
    void suspendedUserCannotResetWithAnEarlierLink() {
        User user = activeUser();
        String oldHash = user.getPasswordHash();
        service.requestReset("asha@example.com");
        String raw = emailedTokens().get(0);
        user.suspend();

        assertThatThrownBy(() -> service.resetPassword(raw, "NewSecret2"))
                .isInstanceOf(InvalidResetTokenException.class);
        assertThat(user.getPasswordHash()).isEqualTo(oldHash);
    }
}
