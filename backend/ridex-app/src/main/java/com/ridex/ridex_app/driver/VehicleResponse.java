package com.ridex.ridex_app.driver;

import java.util.UUID;

public record VehicleResponse(
        UUID id,
        VehicleCategory category,
        String make,
        String model,
        String color,
        String registrationNumber,
        int manufactureYear,
        int seats,
        boolean active
) {
    public static VehicleResponse from(Vehicle vehicle) {
        return new VehicleResponse(
                vehicle.getId(), vehicle.getCategory(), vehicle.getMake(), vehicle.getModel(),
                vehicle.getColor(), vehicle.getRegistrationNumber(), vehicle.getManufactureYear(),
                vehicle.getSeats(), vehicle.isActive());
    }
}
