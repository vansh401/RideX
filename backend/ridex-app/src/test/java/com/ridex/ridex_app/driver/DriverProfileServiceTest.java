package com.ridex.ridex_app.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.ForbiddenException;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserService;

class DriverProfileServiceTest {

    private final DriverProfileRepository repository = mock(DriverProfileRepository.class);
    private final UserService userService = mock(UserService.class);
    private final DriverProfileService service = new DriverProfileService(repository, userService);

    private final User user = new User("Ravi Kumar", "ravi@example.com", "+919812345678", "hash", Role.DRIVER);

    private DriverProfileRequest request(String licence) {
        return new DriverProfileRequest(licence, LocalDate.now().plusYears(2));
    }

    private void activeUser() {
        when(userService.getActiveUser(user.getId())).thenReturn(user);
    }

    @Test
    void createStoresANormalisedPendingProfile() {
        activeUser();
        when(repository.save(any(DriverProfile.class))).thenAnswer(i -> i.getArgument(0));

        DriverProfileResponse response = service.create(user.getId(), request("  dl-0420110012345 "));

        assertThat(response.licenseNumber()).isEqualTo("DL-0420110012345");
        assertThat(response.approvalStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
    }

    @Test
    void createRequiresAnActiveAccount() {
        when(userService.getActiveUser(user.getId())).thenThrow(new ForbiddenException("This account is not active"));

        assertThatThrownBy(() -> service.create(user.getId(), request("DL-12345")))
                .isInstanceOf(ForbiddenException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void aDriverCannotCreateTwoProfiles() {
        activeUser();
        when(repository.existsByUserId(user.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.create(user.getId(), request("DL-12345")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("You already have a driver profile");
    }

    @Test
    void aLicenceNumberCanOnlyBelongToOneDriver() {
        activeUser();
        when(repository.existsByLicenseNumber("DL-12345")).thenReturn(true);

        assertThatThrownBy(() -> service.create(user.getId(), request("dl-12345")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This licence number is already registered");
        verify(repository, never()).save(any());
    }

    @Test
    void getIsNotFoundBeforeAProfileExists() {
        when(repository.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(user.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateChangesTheDetailsOfAPendingProfile() {
        activeUser();
        DriverProfile profile = new DriverProfile(user, "DL-OLD-12345", LocalDate.now().plusYears(1));
        when(repository.findByUserId(user.getId())).thenReturn(Optional.of(profile));

        DriverProfileResponse response = service.update(user.getId(), request("dl-new-12345"));

        assertThat(response.licenseNumber()).isEqualTo("DL-NEW-12345");
    }

    @Test
    void updateKeepingTheSameLicenceNumberDoesNotTripTheUniquenessCheck() {
        activeUser();
        DriverProfile profile = new DriverProfile(user, "DL-12345", LocalDate.now().plusYears(1));
        when(repository.findByUserId(user.getId())).thenReturn(Optional.of(profile));
        when(repository.existsByLicenseNumber("DL-12345")).thenReturn(true);   // it is our own

        service.update(user.getId(), request("DL-12345"));

        verify(repository, never()).save(any());
    }
}
