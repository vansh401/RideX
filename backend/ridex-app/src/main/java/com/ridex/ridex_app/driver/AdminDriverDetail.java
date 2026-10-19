package com.ridex.ridex_app.driver;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Everything an admin needs to decide on one driver. */
public record AdminDriverDetail(
        UUID id,
        UUID userId,
        String fullName,
        String email,
        String phone,
        String licenseNumber,
        LocalDate licenseExpiry,
        ApprovalStatus approvalStatus,
        String rejectionReason,
        UUID reviewedBy,
        Instant reviewedAt,
        Instant createdAt,
        List<DocumentResponse> documents,
        List<VehicleResponse> vehicles
) {
    public static AdminDriverDetail from(DriverProfile profile, List<DocumentResponse> documents,
                                         List<VehicleResponse> vehicles) {
        return new AdminDriverDetail(
                profile.getId(), profile.getUser().getId(),
                profile.getUser().getFullName(), profile.getUser().getEmail(), profile.getUser().getPhone(),
                profile.getLicenseNumber(), profile.getLicenseExpiry(), profile.getApprovalStatus(),
                profile.getRejectionReason(), profile.getReviewedBy(), profile.getReviewedAt(),
                profile.getCreatedAt(), documents, vehicles);
    }
}
