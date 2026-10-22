package com.ridex.ridex_app.complaint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.ridex.ridex_app.common.ConflictException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A complaint about a ride, and its workflow:
 *
 *   OPEN ──assign──▶ IN_REVIEW ──┬─ resolve ─▶ RESOLVED   (possibly with a refund)
 *     │                          └─ reject  ─▶ REJECTED
 *     └────────── resolve / reject directly ─▶ (same)
 *
 * RESOLVED and REJECTED are final and always carry the reason (the database insists too). Every change goes through the methods below, which are
 * the only place that decides what is allowed, as with the ride's status machine.
 */
@Entity
@Table(name = "complaints")
public class Complaint {

    public enum Category { OVERCHARGED, RUDE_BEHAVIOUR, UNSAFE_DRIVING, ROUTE_ISSUE, VEHICLE_ISSUE, PAYMENT_ISSUE, LOST_ITEM, OTHER }

    public enum Status { OPEN, IN_REVIEW, RESOLVED, REJECTED;
        public boolean isFinal() {
            return this == RESOLVED || this == REJECTED;
        }
    }

    @Id
    private UUID id;
    @Column(name = "ride_id", nullable = false, updatable = false)
    private UUID rideId;
    @Column(name = "filed_by", nullable = false, updatable = false)
    private UUID filedBy;
    @Column(name = "filed_by_role", nullable = false, updatable = false)
    private String filedByRole;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Category category;
    @Column(nullable = false, updatable = false)
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "assigned_to")
    private UUID assignedTo;
    @Column(name = "resolution_note")
    private String resolutionNote;
    @Column(name = "refund_amount")
    private BigDecimal refundAmount;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Version
    private long version;

    protected Complaint() {
    }

    public static Complaint file(UUID rideId, UUID filedBy, String role, Category category, String description, Instant now) {
        Complaint c = new Complaint();
        c.id = UUID.randomUUID();
        c.rideId = rideId;
        c.filedBy = filedBy;
        c.filedByRole = role;
        c.category = category;
        c.description = description.trim();
        c.status = Status.OPEN;
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    /** An administrator takes the complaint: it is now being looked at. */
    public void assign(UUID adminId, Instant now) {
        requireNotFinal();
        this.status = Status.IN_REVIEW;
        this.assignedTo = adminId;
        this.updatedAt = now;
    }

    /** @param refund money to give back to the person who complained, or null for none */
    public void resolve(UUID adminId, String note, BigDecimal refund, Instant now) {
        requireNotFinal();
        if (refund != null && (refund.signum() <= 0 || !"RIDER".equals(filedByRole))) {
            throw new ConflictException("Only a rider who complained can be refunded, with an amount above zero");
        }
        close(Status.RESOLVED, adminId, note, refund, now);
    }

    public void reject(UUID adminId, String note, Instant now) {
        requireNotFinal();
        close(Status.REJECTED, adminId, note, null, now);
    }

    private void close(Status result, UUID adminId, String note, BigDecimal refund, Instant now) {
        if (note == null || note.isBlank()) {
            throw new ConflictException("A complaint is closed with a reason the person can read");
        }
        this.status = result;
        this.assignedTo = this.assignedTo == null ? adminId : this.assignedTo;
        this.resolutionNote = note.trim();
        this.refundAmount = refund;
        this.resolvedAt = now;
        this.updatedAt = now;
    }

    private void requireNotFinal() {
        if (status.isFinal()) {
            throw new ConflictException("This complaint is already " + status);
        }
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getRideId() { return rideId; }
    public UUID getFiledBy() { return filedBy; }
    public String getFiledByRole() { return filedByRole; }
    public Category getCategory() { return category; }
    public String getDescription() { return description; }
    public Status getStatus() { return status; }
    public UUID getAssignedTo() { return assignedTo; }
    public String getResolutionNote() { return resolutionNote; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
