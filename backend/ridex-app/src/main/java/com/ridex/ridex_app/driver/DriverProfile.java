package com.ridex.ridex_app.driver;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A driver's licence details and review status. One per driver user. */
@Entity
@Table(name = "driver_profiles")
public class DriverProfile {

    @Id
    private UUID id;

    // One user <-> one profile; this side owns the foreign key. LAZY, so reading a profile
    // does not drag the user in (the admin list in 3.3 will fetch users deliberately).
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false, unique = true)
    private User user;

    @Column(name = "license_number", nullable = false)
    private String licenseNumber;

    @Column(name = "license_expiry", nullable = false)
    private LocalDate licenseExpiry;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false)
    private ApprovalStatus approvalStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Required by JPA; not for application code
    protected DriverProfile() {
    }

    public DriverProfile(User user, String licenseNumber, LocalDate licenseExpiry) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.licenseNumber = licenseNumber;
        this.licenseExpiry = licenseExpiry;
        this.approvalStatus = ApprovalStatus.PENDING_APPROVAL;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Details can only be corrected while the review is still pending. */
    public void changeLicence(String licenseNumber, LocalDate licenseExpiry) {
        if (approvalStatus != ApprovalStatus.PENDING_APPROVAL) {
            throw new ConflictException(
                    "Licence details cannot be changed once the profile is " + approvalStatus);
        }
        this.licenseNumber = licenseNumber;
        this.licenseExpiry = licenseExpiry;
    }

    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getLicenseNumber() { return licenseNumber; }
    public LocalDate getLicenseExpiry() { return licenseExpiry; }
    public ApprovalStatus getApprovalStatus() { return approvalStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
