package com.ridex.ridex_app.ride;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.driver.DriverProfile;
import com.ridex.ridex_app.driver.Vehicle;
import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.pricing.FareCalculator;
import com.ridex.ridex_app.pricing.FareEstimate;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

/** Ready-made objects for ride tests. Each call builds fresh ones. */
final class RideTestData {

    // The real current time: services read the system clock, so a fixed date would make every offer look expired
    static final Instant NOW = Instant.now();

    final User rider = new User("Asha Rao", "asha@example.com", "+919876543210", "hash", Role.RIDER);
    final User driverUser = new User("Ravi Kumar", "ravi@example.com", "+919812345678", "hash", Role.DRIVER);
    final DriverProfile driver = new DriverProfile(driverUser, "DL-12345", LocalDate.now().plusYears(2));
    final Vehicle vehicle = new Vehicle(driver, VehicleCategory.CAB, "Maruti", "Dzire", "White", "KA01AB1234", 2021, 4);

    static LatLng point(String lat, String lng) {
        return new LatLng(new BigDecimal(lat), new BigDecimal(lng));
    }

    static FareEstimate fare() {
        return new FareEstimate(VehicleCategory.CAB, new BigDecimal("10.00"), 25,
                new BigDecimal("50.00"), new BigDecimal("140.00"), new BigDecimal("50.00"), false,
                new BigDecimal("1.00"), new BigDecimal("240.00"), new BigDecimal("12.00"), new BigDecimal("252.00"), "INR");
    }

    /** A ride in SEARCHING_DRIVER, as created by a rider. */
    Ride searching() {
        return Ride.request(rider, VehicleCategory.CAB, "22 Windsor Lane", point("12.971599", "77.594566"),
                "88 Madison Avenue", point("12.935200", "77.624500"), fare(), NOW);
    }

    Ride assigned() {
        Ride ride = searching();
        ride.offerTo(driver, vehicle, NOW.plus(30, ChronoUnit.SECONDS), NOW);
        return ride;
    }

    Ride accepted() {
        Ride ride = assigned();
        ride.accept(driverUser.getId(), NOW.plusSeconds(5));
        return ride;
    }

    Ride arrived() {
        Ride ride = accepted();
        ride.arrive(driverUser.getId(), NOW.plusSeconds(60));
        return ride;
    }

    Ride started() {
        Ride ride = arrived();
        ride.start(driverUser.getId(), NOW.plusSeconds(90));
        return ride;
    }

    Ride completed() {
        Ride ride = started();
        ride.complete(driverUser.getId(), NOW.plusSeconds(900));
        return ride;
    }
}
