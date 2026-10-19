package com.ridex.ridex_app.ride;

import java.util.UUID;

/**
 * Lets tests in OTHER packages (such as the rating tests) reuse the ride test data, which is
 * package-private on purpose.
 */
public final class RideTestDataAccess {

    private final RideTestData data = new RideTestData();

    public Ride searching() { return data.searching(); }
    public Ride accepted() { return data.accepted(); }
    public Ride started() { return data.started(); }
    public Ride completed() { return data.completed(); }
    public UUID riderId() { return data.rider.getId(); }
    public UUID driverUserId() { return data.driverUser.getId(); }
}
