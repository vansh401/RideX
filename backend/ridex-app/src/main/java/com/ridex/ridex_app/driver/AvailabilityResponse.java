package com.ridex.ridex_app.driver;

import java.math.BigDecimal;
import java.time.Instant;

public record AvailabilityResponse(
        boolean online,
        ApprovalStatus approvalStatus,
        boolean hasActiveVehicle,
        BigDecimal lastLatitude,
        BigDecimal lastLongitude,
        Instant lastSeenAt
) {
    public AvailabilityResponse withLastSeenAt(Instant seenAt) {
        return new AvailabilityResponse(online, approvalStatus, hasActiveVehicle, lastLatitude, lastLongitude, seenAt);
    }

    public static AvailabilityResponse of(DriverProfile profile, boolean hasActiveVehicle) {
        return new AvailabilityResponse(profile.isOnline(), profile.getApprovalStatus(), hasActiveVehicle,
                profile.getLastLatitude(), profile.getLastLongitude(), profile.getLastSeenAt());
    }
}
