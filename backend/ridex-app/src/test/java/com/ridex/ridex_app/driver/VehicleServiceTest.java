package com.ridex.ridex_app.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserService;

class VehicleServiceTest {

    private final VehicleRepository vehicleRepository = mock(VehicleRepository.class);
    private final DriverProfileRepository profileRepository = mock(DriverProfileRepository.class);
    private final UserService userService = mock(UserService.class);
    private final VehicleService service = new VehicleService(vehicleRepository, profileRepository, userService);

    private final User user = new User("Ravi Kumar", "ravi@example.com", "+919812345678", "hash", Role.DRIVER);
    private final DriverProfile profile = new DriverProfile(user, "DL-12345", LocalDate.now().plusYears(2));

    private VehicleRequest request(String registration, VehicleCategory category, int seats) {
        return new VehicleRequest(category, "Maruti", "Dzire", "White", registration, 2021, seats);
    }

    private void profileExists() {
        when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));
        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Vehicle vehicle(String registration) {
        return new Vehicle(profile, VehicleCategory.CAB, "Maruti", "Dzire", "White", registration, 2021, 4);
    }

    @Test
    void registrationNumbersAreNormalised() {
        assertThat(VehicleService.normalise("ka 01-ab 1234")).isEqualTo("KA01AB1234");
        assertThat(VehicleService.normalise("DL8CAF5030")).isEqualTo("DL8CAF5030");
    }

    @Test
    void theFirstVehicleBecomesActive() {
        profileExists();
        when(vehicleRepository.countByDriverProfileId(profile.getId())).thenReturn(0L);

        VehicleResponse response = service.add(user.getId(), request("ka 01 ab 1234", VehicleCategory.CAB, 4));

        assertThat(response.registrationNumber()).isEqualTo("KA01AB1234");
        assertThat(response.active()).isTrue();
    }

    @Test
    void laterVehiclesStartInactive() {
        profileExists();
        when(vehicleRepository.countByDriverProfileId(profile.getId())).thenReturn(1L);

        assertThat(service.add(user.getId(), request("KA02CD5678", VehicleCategory.AUTO, 3)).active()).isFalse();
    }

    @Test
    void aDriverCanRegisterAtMostThreeVehicles() {
        profileExists();
        when(vehicleRepository.countByDriverProfileId(profile.getId())).thenReturn(3L);

        assertThatThrownBy(() -> service.add(user.getId(), request("KA02CD5678", VehicleCategory.AUTO, 3)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("at most 3");
        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void registrationNumbersAreUnique() {
        profileExists();
        when(vehicleRepository.existsByRegistrationNumber("KA01AB1234")).thenReturn(true);

        assertThatThrownBy(() -> service.add(user.getId(), request("KA 01 AB 1234", VehicleCategory.CAB, 4)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void seatsMustFitTheCategory() {
        profileExists();

        assertThatThrownBy(() -> service.add(user.getId(), request("KA01AB1234", VehicleCategory.BIKE, 2)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("at most 1");
    }

    @Test
    void theManufactureYearCannotBeInTheFarFuture() {
        profileExists();
        VehicleRequest request = new VehicleRequest(VehicleCategory.CAB, "A", "B", "Red", "KA01AB1234",
                Year.now().getValue() + 5, 4);

        assertThatThrownBy(() -> service.add(user.getId(), request)).isInstanceOf(ConflictException.class);
    }

    @Test
    void needsADriverProfileFirst() {
        when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add(user.getId(), request("KA01AB1234", VehicleCategory.CAB, 4)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void activatingOneVehicleSwitchesOffTheCurrentOne() {
        profileExists();
        Vehicle current = vehicle("KA01AB1234");
        current.activate();
        Vehicle next = vehicle("KA02CD5678");
        when(vehicleRepository.findByIdAndDriverProfileId(next.getId(), profile.getId())).thenReturn(Optional.of(next));
        when(vehicleRepository.findByDriverProfileIdAndActiveTrue(profile.getId())).thenReturn(Optional.of(current));

        VehicleResponse response = service.activate(user.getId(), next.getId());

        assertThat(current.isActive()).isFalse();
        assertThat(response.active()).isTrue();
        verify(vehicleRepository).flush();   // old row switched off in the database first
    }

    @Test
    void theRegistrationNumberCannotBeChangedByAnUpdate() {
        profileExists();
        Vehicle existing = vehicle("KA01AB1234");
        when(vehicleRepository.findByIdAndDriverProfileId(existing.getId(), profile.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(user.getId(), existing.getId(), request("KA09ZZ9999", VehicleCategory.CAB, 4)))
                .isInstanceOf(ConflictException.class);
        // but correcting the colour keeps working
        VehicleRequest sameRegistration = new VehicleRequest(VehicleCategory.CAB, "Maruti", "Dzire", "Black", "ka 01 ab 1234", 2021, 4);
        assertThat(service.update(user.getId(), existing.getId(), sameRegistration).color()).isEqualTo("Black");
    }

    @Test
    void aVehicleOfAnotherDriverIsNotFound() {
        profileExists();
        when(vehicleRepository.findByIdAndDriverProfileId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(user.getId(), UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.activate(user.getId(), UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listReturnsTheDriversVehicles() {
        profileExists();
        when(vehicleRepository.findByDriverProfileIdOrderByCreatedAtAsc(profile.getId()))
                .thenReturn(List.of(vehicle("KA01AB1234")));

        assertThat(service.list(user.getId())).extracting(VehicleResponse::registrationNumber).containsExactly("KA01AB1234");
    }
}
