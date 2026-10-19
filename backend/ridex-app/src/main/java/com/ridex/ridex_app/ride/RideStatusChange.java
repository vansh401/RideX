package com.ridex.ridex_app.ride;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One line of a ride's audit trail: it only ever grows, nothing is edited or deleted. */
@Entity
@Table(name = "ride_status_history")
public class RideStatusChange {

    @Id
    private UUID id;

    // Assigned by the database (identity column); used only to read events back in order
    @Column(name = "seq", insertable = false, updatable = false)
    private Long sequence;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ride_id", nullable = false, updatable = false)
    private Ride ride;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private RideStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private RideStatus toStatus;

    @Column(name = "actor_id")
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false)
    private ActorRole actorRole;

    @Column
    private String note;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    // Required by JPA; not for application code
    protected RideStatusChange() {
    }

    RideStatusChange(Ride ride, RideStatus from, RideStatus to, UUID actorId, ActorRole actorRole,
                     String note, Instant changedAt) {
        this.id = UUID.randomUUID();
        this.ride = ride;
        this.fromStatus = from;
        this.toStatus = to;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.note = note;
        this.changedAt = changedAt;
    }

    public UUID getId() { return id; }
    public RideStatus getFromStatus() { return fromStatus; }
    public RideStatus getToStatus() { return toStatus; }
    public UUID getActorId() { return actorId; }
    public ActorRole getActorRole() { return actorRole; }
    public String getNote() { return note; }
    public Instant getChangedAt() { return changedAt; }
}
