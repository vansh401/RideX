package com.ridex.ridex_app.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

class VehicleTest {

    private final DriverProfile profile = new DriverProfile(
            new User("Ravi Kumar", "ravi@example.com", "+919812345678", "hash", Role.DRIVER),
            "DL-12345", LocalDate.now().plusYears(2));

    @Test
    void seatLimitsPerCategory() {
        assertThat(VehicleCategory.BIKE.maxPassengerSeats()).isEqualTo(1);
        assertThat(VehicleCategory.AUTO.maxPassengerSeats()).isEqualTo(3);
        assertThat(VehicleCategory.CAB.maxPassengerSeats()).isEqualTo(6);
    }

    @Test
    void aNewVehicleIsInactiveAndTrimsItsText() {
        Vehicle vehicle = new Vehicle(profile, VehicleCategory.AUTO, " Bajaj ", " RE ", " Yellow ", "KA01AB1234", 2020, 3);

        assertThat(vehicle.isActive()).isFalse();
        assertThat(vehicle.getMake()).isEqualTo("Bajaj");
        assertThat(vehicle.getColor()).isEqualTo("Yellow");
    }

    @Test
    void tooManySeatsForTheCategoryIsRefused() {
        assertThatThrownBy(() -> new Vehicle(profile, VehicleCategory.BIKE, "Honda", "Activa", "Red", "KA01AB1234", 2020, 2))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void activationCanBeToggled() {
        Vehicle vehicle = new Vehicle(profile, VehicleCategory.CAB, "Maruti", "Dzire", "White", "KA01AB1234", 2021, 4);

        vehicle.activate();
        assertThat(vehicle.isActive()).isTrue();
        vehicle.deactivate();
        assertThat(vehicle.isActive()).isFalse();
    }
}
