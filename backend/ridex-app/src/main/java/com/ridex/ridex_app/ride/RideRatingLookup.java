package com.ridex.ridex_app.ride;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * A "port": what the ride module needs to know about ratings, stated as an interface that the
 * ride module OWNS. The rating module implements it. This way ride -> rating never becomes a
 * dependency (rating already depends on ride), so the two modules stay one-directional and can
 * later be split into separate services.
 */
public interface RideRatingLookup {

    /** For each given ride, the stars THIS user gave (rides they have not rated are absent). */
    Map<UUID, Integer> starsGivenBy(UUID raterUserId, Collection<UUID> rideIds);
}
