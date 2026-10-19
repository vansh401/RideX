package com.ridex.ridex_app.driver;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DriverProfileResponse(
        UUID id,
        String licenseNumber,
        LocalDate licenseExpiry,
        ApprovalStatus approvalStatus,
        String rejectionReason,
        Instant createdAt
) {
    public static DriverProfileResponse from(DriverProfile profile) {
        return new DriverProfileResponse(
                profile.getId(), profile.getLicenseNumber(), profile.getLicenseExpiry(),
                profile.getApprovalStatus(), profile.getRejectionReason(), profile.getCreatedAt());
    }
}
