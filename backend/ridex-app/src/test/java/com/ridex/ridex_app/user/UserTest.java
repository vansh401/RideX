package com.ridex.ridex_app.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void newUserIsPendingVerificationAndEmailIsNormalised() {
        User user = new User(
                "  Asha Rao ",
                "  Asha.Rao@Example.COM ",
                "+919876543210",
                "not-a-real-hash",
                Role.RIDER);

        assertThat(user.getId()).isNotNull();
        assertThat(user.getFullName()).isEqualTo("Asha Rao");
        assertThat(user.getEmail()).isEqualTo("asha.rao@example.com");
        assertThat(user.getRole()).isEqualTo(Role.RIDER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(user.getCreatedAt()).isNotNull();
    }
}