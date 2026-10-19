package com.ridex.ridex_app.driver;

/** The kinds of ride RideX offers. A rider asks for a category; a driver's active vehicle has one. */
public enum VehicleCategory {

    BIKE(1),
    AUTO(3),
    CAB(6);

    private final int maxPassengerSeats;

    VehicleCategory(int maxPassengerSeats) {
        this.maxPassengerSeats = maxPassengerSeats;
    }

    public int maxPassengerSeats() {
        return maxPassengerSeats;
    }
}
