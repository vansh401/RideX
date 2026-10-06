package com.ridex.ridex_app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;
import com.ridex.ridex_app.user.UserStatus;

class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final AuthService authService = new AuthService(userRepository, passwordEncoder);

    private RegisterRequest request() {
        return new RegisterRequest(
                "Asha Rao", "Asha@Example.com", "+919876543210",
                "Secret123", RegistrationRole.RIDER);
    }

    @Test
    void registerHashesPasswordAndNormalisesEmail() {
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        User user = authService.register(request());

        assertThat(user.getEmail()).isEqualTo("asha@example.com");
        assertThat(user.getPasswordHash()).isNotEqualTo("Secret123");
        assertThat(passwordEncoder.matches("Secret123", user.getPasswordHash())).isTrue();
        assertThat(user.getRole()).isEqualTo(Role.RIDER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("asha@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Email is already registered");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void registerRejectsDuplicatePhone() {
        when(userRepository.existsByPhone("+919876543210")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Phone number is already registered");
        verify(userRepository, never()).save(any(User.class));
    }
}