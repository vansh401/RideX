package com.ridex.pricing;

/**
 * The kinds of ride RideX offers. This service keeps its OWN copy of the list instead of importing the core's enum: services
 * share contracts (the JSON on the wire), never classes. A category the core adds later is simply not priced here until this
 * service learns about it.
 */
public enum VehicleCategory {
    BIKE,
    AUTO,
    CAB
}
