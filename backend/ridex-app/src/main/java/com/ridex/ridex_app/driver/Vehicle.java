package com.ridex.ridex_app.driver;

import java.time.Instant;
import java.util.UUID;

import com.ridex.ridex_app.common.ConflictException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "vehicles")
public class Vehicle {

    @Id
    private UUID id;

    // Many vehicles -> one driver profile. This side owns the foreign key.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_profile_id", nullable = false, updatable = false)
    private DriverProfile driverProfile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleCategory category;

    @Column(nullable = false)
    private String make;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private String color;

    @Column(name = "registration_number", nullable = false, updatable = false)
    private String registrationNumber;

    @Column(name = "manufacture_year", nullable = false)
    private int manufactureYear;

    @Column(nullable = false)
    private int seats;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Required by JPA; not for application code
    protected Vehicle() {
    }

    public Vehicle(DriverProfile driverProfile, VehicleCategory category, String make, String model,
                   String color, String registrationNumber, int manufactureYear, int seats) {
        this.id = UUID.randomUUID();
        this.driverProfile = driverProfile;
        this.registrationNumber = registrationNumber;
        this.active = false;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        describe(category, make, model, color, manufactureYear, seats);
    }

    /** Everything about a vehicle can be corrected except its registration number (its identity). */
    public void describe(VehicleCategory category, String make, String model, String color,
                         int manufactureYear, int seats) {
        if (seats > category.maxPassengerSeats()) {
            throw new ConflictException(
                    "A " + category + " carries at most " + category.maxPassengerSeats() + " passenger(s)");
        }
        this.category = category;
        this.make = make.trim();
        this.model = model.trim();
        this.color = color.trim();
        this.manufactureYear = manufactureYear;
        this.seats = seats;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public DriverProfile getDriverProfile() { return driverProfile; }
    public VehicleCategory getCategory() { return category; }
    public String getMake() { return make; }
    public String getModel() { return model; }
    public String getColor() { return color; }
    public String getRegistrationNumber() { return registrationNumber; }
    public int getManufactureYear() { return manufactureYear; }
    public int getSeats() { return seats; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
