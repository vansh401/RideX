package com.ridex.ridex_app.ride;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.ridex.ridex_app.driver.DriverProfile;
import com.ridex.ridex_app.driver.Vehicle;
import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.user.User;

/**
 * What a client sees of a ride. Built from the entity INSIDE the service transaction (the
 * associations are lazy). Phone numbers are shared only between the two people on an accepted
 * ride, never earlier and never with anyone else.
 */
public record RideResponse(
        UUID id,
        RideStatus status,
        VehicleCategory category,
        PlaceView pickup,
        PlaceView dropoff,
        BigDecimal distanceKm,
        int durationMinutes,
        FareView fare,
        PersonView rider,
        DriverView driver,
        VehicleView vehicle,
        Instant requestedAt,
        Instant offerExpiresAt,
        Instant acceptedAt,
        Instant arrivedAt,
        Instant startedAt,
        Instant completedAt,
        Instant cancelledAt,
        ActorRole cancelledBy,
        String cancelReason,
        /** The stars the viewer gave for this ride, or null if they have not rated it. */
        Integer myRating
) {
    public record PlaceView(String address, BigDecimal latitude, BigDecimal longitude) {
    }

    public record FareView(BigDecimal baseFare, BigDecimal distanceFare, BigDecimal timeFare,
                           BigDecimal surgeMultiplier, BigDecimal subtotal, BigDecimal tax,
                           BigDecimal total, String currency) {
    }

    public record PersonView(UUID userId, String fullName, String phone) {
    }

    public record DriverView(UUID userId, String fullName, String phone,
                             BigDecimal ratingAverage, int ratingCount) {
    }

    public record VehicleView(VehicleCategory category, String make, String model, String color,
                              String registrationNumber, int seats) {
    }

    public static RideResponse from(Ride ride, ActorRole viewer, Integer myRating) {
        boolean showPhones = viewer == ActorRole.ADMIN || switch (ride.getStatus()) {
            case DRIVER_ACCEPTED, DRIVER_ARRIVED, RIDE_STARTED -> true;
            default -> false;
        };

        User rider = ride.getRider();
        PersonView riderView = new PersonView(rider.getId(), rider.getFullName(), showPhones ? rider.getPhone() : null);

        DriverView driverView = null;
        VehicleView vehicleView = null;
        DriverProfile driver = ride.getDriver();
        if (driver != null) {
            User driverUser = driver.getUser();
            driverView = new DriverView(driverUser.getId(), driverUser.getFullName(),
                    showPhones ? driverUser.getPhone() : null, driver.getRatingAverage(), driver.getRatingCount());
        }
        Vehicle vehicle = ride.getVehicle();
        if (vehicle != null) {
            vehicleView = new VehicleView(vehicle.getCategory(), vehicle.getMake(), vehicle.getModel(),
                    vehicle.getColor(), vehicle.getRegistrationNumber(), vehicle.getSeats());
        }

        return new RideResponse(
                ride.getId(), ride.getStatus(), ride.getCategory(),
                new PlaceView(ride.getPickupAddress(), ride.getPickupLatitude(), ride.getPickupLongitude()),
                new PlaceView(ride.getDropoffAddress(), ride.getDropoffLatitude(), ride.getDropoffLongitude()),
                ride.getDistanceKm(), ride.getDurationMinutes(),
                new FareView(ride.getBaseFare(), ride.getDistanceFare(), ride.getTimeFare(), ride.getSurgeMultiplier(),
                        ride.getSubtotal(), ride.getTax(), ride.getTotalFare(), ride.getCurrency()),
                riderView, driverView, vehicleView,
                ride.getRequestedAt(), ride.getOfferExpiresAt(), ride.getAcceptedAt(), ride.getArrivedAt(),
                ride.getStartedAt(), ride.getCompletedAt(), ride.getCancelledAt(),
                ride.getCancelledBy(), ride.getCancelReason(), myRating);
    }
}
