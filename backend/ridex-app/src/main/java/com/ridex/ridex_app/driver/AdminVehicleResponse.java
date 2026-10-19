package com.ridex.ridex_app.driver;

import java.util.UUID;

/** A vehicle in the admin list, with its owner. */
public record AdminVehicleResponse(
        UUID id,
        UUID driverProfileId,
        String driverName,
        String driverEmail,
        ApprovalStatus driverStatus,
        VehicleCategory category,
        String make,
        String model,
        String color,
        String registrationNumber,
        boolean active
) {
    public static AdminVehicleResponse from(Vehicle vehicle) {
        DriverProfile profile = vehicle.getDriverProfile();   // loaded by the entity graph
        return new AdminVehicleResponse(
                vehicle.getId(), profile.getId(), profile.getUser().getFullName(), profile.getUser().getEmail(),
                profile.getApprovalStatus(), vehicle.getCategory(), vehicle.getMake(), vehicle.getModel(),
                vehicle.getColor(), vehicle.getRegistrationNumber(), vehicle.isActive());
    }
}
