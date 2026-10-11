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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.ridex.ridex_app.security.OpaqueTokens;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;

class PasswordResetServiceTest {

    private final PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final PasswordResetService service = new PasswordResetService(
            tokenRepository, userRepository, passwordEncoder, refreshTokenService, publisher, 30);

    private User activeUser() {
        User user = new User("Asha Rao", "asha@example.com", "+919876543210",
                passwordEncoder.encode("OldSecret1"), Role.RIDER);
        user.activate();
        return user;
    }

    private PasswordResetToken storedToken(User user, String raw, Instant expiresAt) {
        PasswordResetToken token = new PasswordResetToken(user.getId(), OpaqueTokens.sha256(raw), expiresAt);
        when(tokenRepository.findByTokenHashForUpdate(OpaqueTokens.sha256(raw)))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return token;
    }

    // ---------- requesting a reset ----------

    @Test
    void activeAccountGetsAHashedTokenAndAnEmailEvent() {
        User user = activeUser();
        when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(user));

        service.requestReset(" Asha@Example.com ");

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).invalidateAllForUser(any(), any(Instant.class));
        verify(tokenRepository).save(saved.capture());
        ArgumentCaptor<PasswordResetEmailRequested> event =
                ArgumentCaptor.forClass(PasswordResetEmailRequested.class);
        verify(publisher).publishEvent(event.capture());

        assertThat(event.getValue().toEmail()).isEqualTo("asha@example.com");
        assertThat(OpaqueTokens.sha256(event.getValue().rawToken()))
                .isEqualTo(saved.getValue().getTokenHash());
        assertThat(saved.getValue().getExpiresAt())
                .isAfter(Instant.now().plusSeconds(29 * 60))
                .isBefore(Instant.now().plusSeconds(31 * 60));
    }

    @Test
    void unknownEmailDoesNothing() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        service.requestReset("nobody@example.com");

        verify(tokenRepository, never()).save(any());
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
        when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(user));

        service.requestReset("asha@example.com");

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    // ---------- using the link ----------

    @Test
    void resetChangesThePasswordConsumesTheTokenAndEndsAllSessions() {
        User user = activeUser();
        PasswordResetToken token = storedToken(user, "raw", Instant.now().plusSeconds(600));

        service.resetPassword("raw", "NewSecret2");

        assertThat(passwordEncoder.matches("NewSecret2", user.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("OldSecret1", user.getPasswordHash())).isFalse();
        assertThat(token.isUsed()).isTrue();
        verify(refreshTokenService).revokeAllForUser(user.getId());
    }

    @Test
    void unknownTokenIsRejected() {
        when(tokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword("nope", "NewSecret2"))
                .isInstanceOf(InvalidResetTokenException.class);
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    @Test
    void usedTokenIsRejectedAndPasswordIsUntouched() {
        User user = activeUser();
        String oldHash = user.getPasswordHash();
        PasswordResetToken token = storedToken(user, "raw", Instant.now().plusSeconds(600));
        token.markUsed(Instant.now());

        assertThatThrownBy(() -> service.resetPassword("raw", "NewSecret2"))
                .isInstanceOf(InvalidResetTokenException.class);
        assertThat(user.getPasswordHash()).isEqualTo(oldHash);
    }

    @Test
    void expiredTokenIsRejectedAndPasswordIsUntouched() {
        User user = activeUser();
        String oldHash = user.getPasswordHash();
        storedToken(user, "raw", Instant.now().minusSeconds(5));

        assertThatThrownBy(() -> service.resetPassword("raw", "NewSecret2"))
                .isInstanceOf(InvalidResetTokenException.class);
        assertThat(user.getPasswordHash()).isEqualTo(oldHash);
    }

    @Test
    void suspendedUserCannotResetWithAnEarlierLink() {
        User user = activeUser();
        String oldHash = user.getPasswordHash();
        storedToken(user, "raw", Instant.now().plusSeconds(600));
        user.suspend();

        assertThatThrownBy(() -> service.resetPassword("raw", "NewSecret2"))
                .isInstanceOf(InvalidResetTokenException.class);
        assertThat(user.getPasswordHash()).isEqualTo(oldHash);
    }
}
