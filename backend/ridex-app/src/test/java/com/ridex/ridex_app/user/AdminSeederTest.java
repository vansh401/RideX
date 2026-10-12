package com.ridex.ridex_app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminSeederTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AdminSeeder seeder(String email, String password) {
        return new AdminSeeder(userRepository, passwordEncoder, email, password, "RideX Admin", "+910000000000");
    }

    @Test
    void createsAnActiveAdminWithAHashedPassword() {
        seeder(" Admin@Example.com ", "a-long-admin-password1").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User admin = saved.getValue();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(admin.getEmail()).isEqualTo("admin@example.com");
        assertThat(admin.getPasswordHash()).isNotEqualTo("a-long-admin-password1");
        assertThat(passwordEncoder.matches("a-long-admin-password1", admin.getPasswordHash())).isTrue();
    }

    @Test
    void doesNothingWithoutConfiguredCredentials() {
        seeder("", "").run(null);
        seeder("admin@example.com", "").run(null);
        seeder("", "a-long-admin-password1").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void doesNothingWhenAnAdminAlreadyExists() {
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(true);

        seeder("admin@example.com", "a-long-admin-password1").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void acceptsAPasswordThatMeetsTheRegistrationPolicy() {
        seeder("admin@example.com", "Admin@123").run(null);

        verify(userRepository).save(any(User.class));
    }

    @Test
    void refusesAPasswordThatBreaksTheRegistrationPolicy() {
        for (String weak : new String[] {"short1", "onlyletters", "12345678"}) {
            assertThatThrownBy(() -> seeder("admin@example.com", weak).run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ADMIN_PASSWORD");
        }
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesToPromoteAnExistingAccount() {
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(true);

        assertThatThrownBy(() -> seeder("admin@example.com", "a-long-admin-password1").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to promote");
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesAPhoneNumberThatIsAlreadyTaken() {
        when(userRepository.existsByPhone("+910000000000")).thenReturn(true);

        assertThatThrownBy(() -> seeder("admin@example.com", "a-long-admin-password1").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PHONE");
        verify(userRepository, never()).save(any());
    }
}
