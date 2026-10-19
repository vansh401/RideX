package com.ridex.ridex_app.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserService;

class DriverAvailabilityServiceTest {

    private final DriverProfileRepository profileRepository = mock(DriverProfileRepository.class);
    private final VehicleRepository vehicleRepository = mock(VehicleRepository.class);
    private final UserService userService = mock(UserService.class);
    private final DriverAvailabilityService service =
            new DriverAvailabilityService(profileRepository, vehicleRepository, userService);

    private final User user = new User("Ravi Kumar", "ravi@example.com", "+919812345678", "hash", Role.DRIVER);
    private final DriverProfile profile = new DriverProfile(user, "DL-12345", LocalDate.now().plusYears(2));
    private final LatLng position = new LatLng(new BigDecimal("12.971599"), new BigDecimal("77.594566"));

    private void profileExists() {
        when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));
    }

    private void hasActiveVehicle(boolean has) {
        Vehicle vehicle = new Vehicle(profile, VehicleCategory.CAB, "Maruti", "Dzire", "White", "KA01AB1234", 2021, 4);
        when(vehicleRepository.findByDriverProfileIdAndActiveTrue(profile.getId()))
                .thenReturn(has ? Optional.of(vehicle) : Optional.empty());
    }

    @Test
    void anApprovedDriverWithAnActiveVehicleCanGoOnline() {
        profileExists();
        hasActiveVehicle(true);
        profile.approve(java.util.UUID.randomUUID(), Instant.now());

        AvailabilityResponse response = service.goOnline(user.getId(), position);

        assertThat(response.online()).isTrue();
        assertThat(response.lastLatitude()).isEqualByComparingTo("12.971599");
        assertThat(response.lastSeenAt()).isNotNull();
    }

    @Test
    void aDriverWhoIsNotApprovedCannotGoOnline() {
        profileExists();
        hasActiveVehicle(true);

        assertThatThrownBy(() -> service.goOnline(user.getId(), position))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("approved");
        assertThat(profile.isOnline()).isFalse();
    }

    @Test
    void aDriverWithoutAnActiveVehicleCannotGoOnline() {
        profileExists();
        hasActiveVehicle(false);
        profile.approve(java.util.UUID.randomUUID(), Instant.now());

        assertThatThrownBy(() -> service.goOnline(user.getId(), position))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("vehicle");
    }

    @Test
    void goingOfflineStopsNewOffers() {
        profileExists();
        hasActiveVehicle(true);
        profile.approve(java.util.UUID.randomUUID(), Instant.now());
        service.goOnline(user.getId(), position);

        assertThat(service.goOffline(user.getId()).online()).isFalse();
    }

    @Test
    void locationUpdatesNeedTheDriverToBeOnline() {
        profileExists();
        when(profileRepository.updateLocation(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.updateLocation(user.getId(), position))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("online");
    }

    @Test
    void anOnlineDriversLocationIsStored() {
        profileExists();
        when(profileRepository.updateLocation(any(), any(), any(), any())).thenReturn(1);

        service.updateLocation(user.getId(), position);

        verify(profileRepository).updateLocation(any(), any(), any(), any());
    }

    @Test
    void suspensionForcesAnOnlineDriverOffline() {
        profileExists();
        hasActiveVehicle(true);
        profile.approve(java.util.UUID.randomUUID(), Instant.now());
        service.goOnline(user.getId(), position);

        service.forceOffline(user.getId());

        assertThat(profile.isOnline()).isFalse();
    }
}
