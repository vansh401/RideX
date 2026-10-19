package com.ridex.ridex_app.driver;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One row of the admin review queue. */
public record AdminDriverSummary(
        UUID id,
        UUID userId,
        String fullName,
        String email,
        String phone,
        String licenseNumber,
        LocalDate licenseExpiry,
        ApprovalStatus approvalStatus,
        Instant createdAt
) {
    public static AdminDriverSummary from(DriverProfile profile) {
        // getUser() is already loaded by the entity graph: no extra query per row
        return new AdminDriverSummary(
                profile.getId(), profile.getUser().getId(),
                profile.getUser().getFullName(), profile.getUser().getEmail(), profile.getUser().getPhone(),
                profile.getLicenseNumber(), profile.getLicenseExpiry(),
                profile.getApprovalStatus(), profile.getCreatedAt());
    }
}
