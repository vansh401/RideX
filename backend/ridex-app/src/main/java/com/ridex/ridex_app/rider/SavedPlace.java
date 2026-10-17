package com.ridex.ridex_app.rider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A named place a rider reuses when booking (Home, Work, ...). Owned by one rider profile. */
@Entity
@Table(name = "saved_places")
public class SavedPlace {

    @Id
    private UUID id;

    // Many places -> one profile. LAZY: loading a place does not load its profile.
    // This side owns the foreign key (rider_profile_id).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rider_profile_id", nullable = false, updatable = false)
    private RiderProfile riderProfile;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Required by JPA; not for application code
    protected SavedPlace() {
    }

    SavedPlace(RiderProfile riderProfile, String label, String address, BigDecimal latitude, BigDecimal longitude) {
        this.id = UUID.randomUUID();
        this.riderProfile = riderProfile;
        this.label = label;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getLabel() { return label; }
    public String getAddress() { return address; }
    public BigDecimal getLatitude() { return latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public Instant getCreatedAt() { return createdAt; }
}
