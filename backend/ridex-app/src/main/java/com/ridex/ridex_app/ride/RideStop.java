package com.ridex.ridex_app.ride;

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

/** A place to stop on the way (position 1, 2 or 3). The driver marks each one as reached, in order. */
@Entity
@Table(name = "ride_stops")
public class RideStop {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ride_id", nullable = false, updatable = false)
    private Ride ride;

    @Column(nullable = false, updatable = false)
    private int position;
    @Column(nullable = false, updatable = false)
    private String address;
    @Column(nullable = false, updatable = false, precision = 9, scale = 6)
    private BigDecimal latitude;
    @Column(nullable = false, updatable = false, precision = 9, scale = 6)
    private BigDecimal longitude;
    @Column(name = "arrived_at")
    private Instant arrivedAt;

    protected RideStop() {
    }

    RideStop(Ride ride, int position, String address, BigDecimal latitude, BigDecimal longitude) {
        this.id = UUID.randomUUID();
        this.ride = ride;
        this.position = position;
        this.address = address.trim();
        this.latitude = latitude;
        this.longitude = longitude;
    }

    void reached(Instant now) {
        this.arrivedAt = now;
    }

    public int getPosition() { return position; }
    public String getAddress() { return address; }
    public BigDecimal getLatitude() { return latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public Instant getArrivedAt() { return arrivedAt; }
}
