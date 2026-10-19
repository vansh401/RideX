package com.ridex.ridex_app.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.auth.RefreshTokenService;
import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.driver.DriverAvailabilityService;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;
import com.ridex.ridex_app.user.UserStatus;

class AdminUserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final DriverAvailabilityService availabilityService = mock(DriverAvailabilityService.class);
    private final AdminUserService service = new AdminUserService(userRepository, refreshTokenService, availabilityService);

    private final UUID adminId = UUID.randomUUID();

    private User activeUser(Role role) {
        User user = new User("Asha Rao", "asha@example.com", "+919876543210", "hash", role);
        user.activate();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void suspendingAnActiveRiderBlocksThemAndEndsTheirSessions() {
        User rider = activeUser(Role.RIDER);

        AdminUserResponse response = service.suspend(adminId, rider.getId(), "Fraud report");

        assertThat(response.status()).isEqualTo(UserStatus.SUSPENDED);
        verify(refreshTokenService).revokeAllForUser(rider.getId());
        verify(availabilityService, never()).forceOffline(rider.getId());
    }

    @Test
    void suspendingADriverAlsoTakesThemOffline() {
        User driver = activeUser(Role.DRIVER);

        service.suspend(adminId, driver.getId(), "Unsafe driving");

        verify(availabilityService).forceOffline(driver.getId());
        verify(refreshTokenService).revokeAllForUser(driver.getId());
    }

    @Test
    void anAdminCannotSuspendThemselves() {
        User self = activeUser(Role.ADMIN);

        assertThatThrownBy(() -> service.suspend(self.getId(), self.getId(), "x"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("your own");
        assertThat(self.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void otherAdminsCannotBeSuspendedThroughThisEndpoint() {
        User otherAdmin = activeUser(Role.ADMIN);

        assertThatThrownBy(() -> service.suspend(adminId, otherAdmin.getId(), "x"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Admin accounts");
    }

    @Test
    void onlyActiveAccountsCanBeSuspended() {
        User pending = new User("Asha Rao", "asha@example.com", "+919876543210", "hash", Role.RIDER);
        when(userRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.suspend(adminId, pending.getId(), "x"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("active");
        verify(refreshTokenService, never()).revokeAllForUser(pending.getId());
    }

    @Test
    void reinstatingLiftsASuspension() {
        User rider = activeUser(Role.RIDER);
        rider.suspend();

        assertThat(service.reinstate(adminId, rider.getId()).status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void reinstatingCannotBeUsedToSkipEmailVerification() {
        User pending = new User("Asha Rao", "asha@example.com", "+919876543210", "hash", Role.RIDER);
        when(userRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.reinstate(adminId, pending.getId())).isInstanceOf(ConflictException.class);
        assertThat(pending.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
    }

    @Test
    void unknownUsersAreNotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.suspend(adminId, id, "x")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.reinstate(adminId, id)).isInstanceOf(ResourceNotFoundException.class);
    }
}
