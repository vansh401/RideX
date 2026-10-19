package com.ridex.ridex_app.driver;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.LatLng;
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
import jakarta.persistence.Version;

/**
 * A driver's licence details and where they are in the approval process.
 *
 * The approval status is a small STATE MACHINE. The only legal moves are:
 *   PENDING_APPROVAL -> APPROVED          (an admin approves)
 *   PENDING_APPROVAL -> REJECTED          (an admin rejects, with a reason)
 *   REJECTED         -> PENDING_APPROVAL  (the driver corrects the details and resubmits)
 * Every other move is refused with a 409 by the methods below, so no caller can skip a step.
 */
@Entity
@Table(name = "driver_profiles")
public class DriverProfile {

    @Id
    private UUID id;

    // One user <-> one profile; this side owns the foreign key. LAZY, so reading a profile
    // does not drag the user in (the admin list fetches users on purpose, in the same query).
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

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    // Availability. Whether this driver can currently be offered rides, and where they last were.
    @Column(nullable = false)
    private boolean online;

    @Column(name = "last_latitude", precision = 9, scale = 6)
    private BigDecimal lastLatitude;

    @Column(name = "last_longitude", precision = 9, scale = 6)
    private BigDecimal lastLongitude;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    // Running totals of the stars riders gave this driver (updated by atomic SQL, see the repository)
    @Column(name = "rating_sum", nullable = false, insertable = false, updatable = false)
    private int ratingSum;

    @Column(name = "rating_count", nullable = false, insertable = false, updatable = false)
    private int ratingCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Optimistic locking. Hibernate adds "WHERE version = ?" to every UPDATE and bumps it. If two
    // admins load the same profile and both save, the second UPDATE matches zero rows and fails
    // (answered as HTTP 409) instead of silently overwriting the first decision.
    // The wrapper type (null = brand new) lets Spring Data insert rather than merge.
    @Version
    private Long version;

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

    public void approve(UUID adminId, Instant now) {
        require(ApprovalStatus.PENDING_APPROVAL, "approved");
        this.approvalStatus = ApprovalStatus.APPROVED;
        this.rejectionReason = null;
        this.reviewedBy = adminId;
        this.reviewedAt = now;
    }

    public void reject(UUID adminId, String reason, Instant now) {
        require(ApprovalStatus.PENDING_APPROVAL, "rejected");
        this.approvalStatus = ApprovalStatus.REJECTED;
        this.rejectionReason = reason;
        this.reviewedBy = adminId;
        this.reviewedAt = now;
    }

    /**
     * Correct the licence details. While PENDING this just edits them; after a REJECTED decision
     * it also resubmits the profile for a new review. An APPROVED profile is locked.
     */
    public void changeLicence(String licenseNumber, LocalDate licenseExpiry) {
        if (approvalStatus == ApprovalStatus.APPROVED) {
            throw new ConflictException("Licence details cannot be changed once the profile is APPROVED");
        }
        this.licenseNumber = licenseNumber;
        this.licenseExpiry = licenseExpiry;
        if (approvalStatus == ApprovalStatus.REJECTED) {
            this.approvalStatus = ApprovalStatus.PENDING_APPROVAL;
            this.rejectionReason = null;
            this.reviewedBy = null;
            this.reviewedAt = null;
        }
    }

    /** Only an approved driver may take rides. The caller checks they also have an active vehicle. */
    public void goOnline(LatLng position, Instant now) {
        if (!isApproved()) {
            throw new ConflictException("Only approved drivers can go online");
        }
        this.online = true;
        this.lastLatitude = position.latitude();
        this.lastLongitude = position.longitude();
        this.lastSeenAt = now;
    }

    public void goOffline() {
        this.online = false;
    }

    public boolean isApproved() {
        return approvalStatus == ApprovalStatus.APPROVED;
    }

    private void require(ApprovalStatus required, String action) {
        if (approvalStatus != required) {
            throw new ConflictException(
                    "A driver profile that is " + approvalStatus + " cannot be " + action);
        }
    }

    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public String getLicenseNumber() { return licenseNumber; }
    public LocalDate getLicenseExpiry() { return licenseExpiry; }
    public ApprovalStatus getApprovalStatus() { return approvalStatus; }
    public String getRejectionReason() { return rejectionReason; }
    public UUID getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public int getRatingCount() { return ratingCount; }

    /** Average stars, or null before the first rating. */
    public BigDecimal getRatingAverage() {
        return ratingCount == 0 ? null
                : BigDecimal.valueOf(ratingSum).divide(BigDecimal.valueOf(ratingCount), 2, java.math.RoundingMode.HALF_UP);
    }

    public boolean isOnline() { return online; }
    public BigDecimal getLastLatitude() { return lastLatitude; }
    public BigDecimal getLastLongitude() { return lastLongitude; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
