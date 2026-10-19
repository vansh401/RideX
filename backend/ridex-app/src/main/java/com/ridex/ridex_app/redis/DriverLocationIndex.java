package com.ridex.ridex_app.redis;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * "Where are the drivers right now?" Positions change every few seconds, are only useful while
 * fresh, and are read on every ride request, so they live in memory (Redis), not in PostgreSQL.
 */
public interface DriverLocationIndex {

    /** A driver and how far they are from the point searched around. */
    record Nearby(UUID driverProfileId, double distanceKm) {
    }

    /** Records a position and refreshes the driver's heartbeat. */
    void update(UUID driverProfileId, double latitude, double longitude);

    /** The driver is no longer available (went offline). */
    void remove(UUID driverProfileId);

    /** Drivers within the radius, nearest first, at most {@code limit}, all with a fresh heartbeat. */
    List<Nearby> findNearby(double latitude, double longitude, double radiusKm, int limit);

    /** When this driver's location was last received, if they are still being tracked. */
    Optional<Instant> lastSeen(UUID driverProfileId);

    /** Removes drivers whose heartbeat has expired. Returns how many were removed. */
    int purgeStale();
}
