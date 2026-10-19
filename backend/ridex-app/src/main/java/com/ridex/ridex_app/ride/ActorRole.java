package com.ridex.ridex_app.ride;

/** Who made a change to a ride. SYSTEM means the dispatch job (timeouts), not a person. */
public enum ActorRole {
    RIDER,
    DRIVER,
    ADMIN,
    SYSTEM
}
