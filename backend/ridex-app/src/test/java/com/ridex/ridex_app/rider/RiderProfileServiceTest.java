package com.ridex.ridex_app.rider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ridex.ridex_app.common.ForbiddenException;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserService;

class RiderProfileServiceTest {

    private final RiderProfileRepository repository = mock(RiderProfileRepository.class);
    private final UserService userService = mock(UserService.class);
    private final RiderProfileService service = new RiderProfileService(repository, userService);

    private final User user = new User("Asha Rao", "asha@example.com", "+919876543210", "hash", Role.RIDER);
    private final RiderProfile profile = new RiderProfile(user);

    private void profileExists() {
        when(repository.findByUserId(user.getId())).thenReturn(Optional.of(profile));
    }

    @Test
    void createForStoresAProfileOwnedByTheUser() {
        service.createFor(user);

        ArgumentCaptor<RiderProfile> saved = ArgumentCaptor.forClass(RiderProfile.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSavedPlaces()).isEmpty();
    }

    @Test
    void getReturnsTheProfileWithItsPlaces() {
        profile.addPlace("Home", "22 Windsor Lane", new BigDecimal("12.97"), new BigDecimal("77.59"));
        profileExists();

        RiderProfileResponse response = service.get(user.getId());

        assertThat(response.emergencyContact()).isNull();
        assertThat(response.savedPlaces()).extracting(SavedPlaceResponse::label).containsExactly("Home");
    }

    @Test
    void getIsNotFoundWithoutAProfile() {
        when(repository.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(user.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updatingTheEmergencyContactShowsItInTheResponse() {
        profileExists();

        RiderProfileResponse response =
                service.updateEmergencyContact(user.getId(), new UpdateRiderProfileRequest("Meera Nair", "+919811122233"));

        assertThat(response.emergencyContact().name()).isEqualTo("Meera Nair");
        assertThat(response.emergencyContact().phone()).isEqualTo("+919811122233");
    }

    @Test
    void writesAreRefusedForAnAccountThatIsNoLongerActive() {
        when(userService.getActiveUser(user.getId())).thenThrow(new ForbiddenException("This account is not active"));

        assertThatThrownBy(() -> service.addPlace(user.getId(),
                new SavedPlaceRequest("Home", "22 Windsor Lane", new BigDecimal("12.97"), new BigDecimal("77.59"))))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.removePlace(user.getId(), UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
        verify(repository, never()).findByUserId(any());
    }

    @Test
    void addingAPlaceReturnsIt() {
        profileExists();

        SavedPlaceResponse response = service.addPlace(user.getId(),
                new SavedPlaceRequest(" Work ", "88 Madison Ave", new BigDecimal("12.9"), new BigDecimal("77.6")));

        assertThat(response.label()).isEqualTo("Work");
        assertThat(profile.getSavedPlaces()).hasSize(1);
    }

    @Test
    void removingAPlaceOnlyLooksInTheCallersOwnProfile() {
        profileExists();

        assertThatThrownBy(() -> service.removePlace(user.getId(), UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
