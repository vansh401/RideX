package com.ridex.ridex_app.ride;

import java.time.Instant;

/** One step of a ride's audit trail. */
public record TimelineEntry(
        RideStatus from,
        RideStatus to,
        ActorRole actorRole,
        String note,
        Instant at
) {
    public static TimelineEntry from(RideStatusChange change) {
        return new TimelineEntry(change.getFromStatus(), change.getToStatus(), change.getActorRole(),
                change.getNote(), change.getChangedAt());
    }
}
