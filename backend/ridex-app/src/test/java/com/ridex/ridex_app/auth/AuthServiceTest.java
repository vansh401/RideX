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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.ForbiddenException;
import com.ridex.ridex_app.jwt.JwtService;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;
import com.ridex.ridex_app.user.UserStatus;

class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtService jwtService = mock(JwtService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final AuthService authService =
            new AuthService(userRepository, passwordEncoder, jwtService, refreshTokenService);

    private static RefreshTokenService.IssuedToken issued(String value) {
        return new RefreshTokenService.IssuedToken(value, Instant.now().plusSeconds(3600));
    }

    private RegisterRequest registerRequest() {
        return new RegisterRequest(
                "Asha Rao", "Asha@Example.com", "+919876543210",
                "Secret123", RegistrationRole.RIDER);
    }

    private User activeUser() {
        User user = new User("Asha Rao", "asha@example.com", "+919876543210",
                passwordEncoder.encode("Secret123"), Role.RIDER);
        user.activate();
        return user;
    }

    // ---------- registration ----------

    @Test
    void registerHashesPasswordAndNormalisesEmail() {
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        User user = authService.register(registerRequest());

        assertThat(user.getEmail()).isEqualTo("asha@example.com");
        assertThat(user.getPasswordHash()).isNotEqualTo("Secret123");
        assertThat(passwordEncoder.matches("Secret123", user.getPasswordHash())).isTrue();
        assertThat(user.getRole()).isEqualTo(Role.RIDER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("asha@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Email is already registered");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void registerRejectsDuplicatePhone() {
        when(userRepository.existsByPhone("+919876543210")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Phone number is already registered");
        verify(userRepository, never()).save(any(User.class));
    }

    // ---------- login ----------

    @Test
    void loginReturnsTokenForCorrectCredentials() {
        User user = activeUser();
        when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(user));
        when(jwtService.createAccessToken(user))
                .thenReturn(new JwtService.AccessToken("token-value", Instant.now().plusSeconds(900), 900));
        when(refreshTokenService.issueForNewSession(user.getId())).thenReturn(issued("refresh-value"));

        LoginResponse response = authService.login(new LoginRequest("Asha@Example.com", "Secret123"));

        assertThat(response.accessToken()).isEqualTo("token-value");
        assertThat(response.refreshToken()).isEqualTo("refresh-value");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(900);
        assertThat(response.user().email()).isEqualTo("asha@example.com");
        assertThat(response.user().role()).isEqualTo(Role.RIDER);
    }

    @Test
    void loginRejectsWrongPassword() {
        when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(activeUser()));

        assertThatThrownBy(() -> authService.login(new LoginRequest("asha@example.com", "WrongPass1")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");
        verify(jwtService, never()).createAccessToken(any(User.class));
    }

    @Test
    void loginRejectsUnknownEmailWithTheSameError() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "Secret123")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid email or password");
        verify(jwtService, never()).createAccessToken(any(User.class));
    }

    @Test
    void loginRejectsUnverifiedAccountOnlyAfterPasswordIsCorrect() {
        User pending = new User("Asha Rao", "asha@example.com", "+919876543210",
                passwordEncoder.encode("Secret123"), Role.RIDER);
        when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> authService.login(new LoginRequest("asha@example.com", "Secret123")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Please verify your email before logging in");

        // With a wrong password the caller learns nothing about the account's status
        assertThatThrownBy(() -> authService.login(new LoginRequest("asha@example.com", "WrongPass1")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void loginRejectsSuspendedAccount() {
        User user = activeUser();
        user.suspend();
        when(userRepository.findByEmail("asha@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("asha@example.com", "Secret123")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This account has been suspended");
    }

    // ---------- refresh / logout ----------

    @Test
    void refreshReturnsNewTokensAndUsesTheCurrentRoleFromTheDatabase() {
        User user = activeUser();
        when(refreshTokenService.rotate("old-refresh"))
                .thenReturn(new RefreshTokenService.Rotation(user.getId(), issued("new-refresh")));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(jwtService.createAccessToken(user))
                .thenReturn(new JwtService.AccessToken("new-access", Instant.now().plusSeconds(900), 900));

        LoginResponse response = authService.refresh("old-refresh");

        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        assertThat(response.user().email()).isEqualTo("asha@example.com");
    }

    @Test
    void refreshRejectsAndRevokesEverythingWhenTheUserIsSuspended() {
        User user = activeUser();
        user.suspend();
        when(refreshTokenService.rotate("old-refresh"))
                .thenReturn(new RefreshTokenService.Rotation(user.getId(), issued("new-refresh")));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.refresh("old-refresh"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenService).revokeAllForUser(user.getId());
        verify(jwtService, never()).createAccessToken(any(User.class));
    }

    @Test
    void refreshPropagatesInvalidTokenErrors() {
        when(refreshTokenService.rotate("bad"))
                .thenThrow(new InvalidRefreshTokenException("Invalid refresh token"));

        assertThatThrownBy(() -> authService.refresh("bad"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void logoutRevokesTheSession() {
        authService.logout("some-refresh");

        verify(refreshTokenService).revokeSessionOf("some-refresh");
    }
}