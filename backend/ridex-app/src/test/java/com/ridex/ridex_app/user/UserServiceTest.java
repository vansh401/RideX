package com.ridex.ridex_app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.common.ForbiddenException;
import com.ridex.ridex_app.common.ResourceNotFoundException;

class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserService userService = new UserService(userRepository);

    private User user() {
        return new User("Asha Rao", "asha@example.com", "+919876543210", "hash", Role.RIDER);
    }

    @Test
    void returnsActiveUser() {
        User user = user();
        user.activate();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(userService.getActiveUser(user.getId())).isSameAs(user);
    }

    @Test
    void unknownUserIsNotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getActiveUser(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void suspendedUserIsStoppedEvenIfTheirTokenIsStillValid() {
        User user = user();
        user.activate();
        user.suspend();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.getActiveUser(user.getId()))
                .isInstanceOf(ForbiddenException.class);
    }
}
