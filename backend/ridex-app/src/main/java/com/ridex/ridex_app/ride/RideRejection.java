package com.ridex.ridex_app.ride;

import java.time.Instant;
import java.util.UUID;

import com.ridex.ridex_app.driver.DriverProfile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A driver who declined (or ignored) a ride: the dispatcher never offers them that ride again. */
@Entity
@Table(name = "ride_rejections")
public class RideRejection {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ride_id", nullable = false, updatable = false)
    private Ride ride;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_profile_id", nullable = false, updatable = false)
    private DriverProfile driver;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private RejectionReason reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Required by JPA; not for application code
    protected RideRejection() {
    }

    public RideRejection(Ride ride, DriverProfile driver, RejectionReason reason, Instant now) {
        this.id = UUID.randomUUID();
        this.ride = ride;
        this.driver = driver;
        this.reason = reason;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public RejectionReason getReason() { return reason; }
}
