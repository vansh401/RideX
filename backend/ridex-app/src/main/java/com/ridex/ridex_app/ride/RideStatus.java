package com.ridex.ridex_app.ride;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The life of a ride, as a STATE MACHINE.
 *
 * <pre>
 *  REQUESTED -> SEARCHING_DRIVER -> DRIVER_ASSIGNED -> DRIVER_ACCEPTED -> DRIVER_ARRIVED -> RIDE_STARTED -> RIDE_COMPLETED
 *                    ^                   |
 *                    +-------------------+   (the driver declined or did not answer in time: offer it to the next one)
 *
 *  CANCELLED is reachable from every state before the trip starts, and from nowhere after it.
 * </pre>
 *
 * Each state lists the states it may move to. Ride.moveTo() asks this table and refuses (HTTP 409)
 * anything else, so there is exactly ONE place that decides what is legal, and no endpoint can
 * skip a step (for example "complete" a ride that never started).
 *
 * Payment is deliberately NOT a ride status: a payment can fail and be retried without the ride
 * changing state. It gets its own state machine in Phase 6.
 */
public enum RideStatus {

    REQUESTED,
    SEARCHING_DRIVER,
    DRIVER_ASSIGNED,
    DRIVER_ACCEPTED,
    DRIVER_ARRIVED,
    RIDE_STARTED,
    RIDE_COMPLETED,
    CANCELLED;

    private static final Map<RideStatus, Set<RideStatus>> ALLOWED = new EnumMap<>(RideStatus.class);

    static {
        ALLOWED.put(REQUESTED, EnumSet.of(SEARCHING_DRIVER, CANCELLED));
        ALLOWED.put(SEARCHING_DRIVER, EnumSet.of(DRIVER_ASSIGNED, CANCELLED));
        ALLOWED.put(DRIVER_ASSIGNED, EnumSet.of(DRIVER_ACCEPTED, SEARCHING_DRIVER, CANCELLED));
        ALLOWED.put(DRIVER_ACCEPTED, EnumSet.of(DRIVER_ARRIVED, CANCELLED));
        ALLOWED.put(DRIVER_ARRIVED, EnumSet.of(RIDE_STARTED, CANCELLED));
        ALLOWED.put(RIDE_STARTED, EnumSet.of(RIDE_COMPLETED));
        ALLOWED.put(RIDE_COMPLETED, EnumSet.noneOf(RideStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(RideStatus.class));
    }

    public boolean canTransitionTo(RideStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public Set<RideStatus> allowedNext() {
        return Set.copyOf(ALLOWED.get(this));
    }

    public boolean isTerminal() {
        return this == RIDE_COMPLETED || this == CANCELLED;
    }

    /** A ride that still occupies its rider (and possibly a driver). */
    public boolean isActive() {
        return !isTerminal();
    }

    /** Statuses in which a driver is attached to the ride and cannot take another. */
    public boolean occupiesDriver() {
        return this == DRIVER_ASSIGNED || this == DRIVER_ACCEPTED || this == DRIVER_ARRIVED || this == RIDE_STARTED;
    }

    public static Set<RideStatus> activeStatuses() {
        EnumSet<RideStatus> active = EnumSet.allOf(RideStatus.class);
        active.removeIf(RideStatus::isTerminal);
        return active;
    }
}
