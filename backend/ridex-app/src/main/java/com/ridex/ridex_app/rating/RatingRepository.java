package com.ridex.ridex_app.rating;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RatingRepository extends JpaRepository<Rating, UUID> {

    boolean existsByRideIdAndRaterId(UUID rideId, UUID raterId);

    /** One query for a whole page of rides (instead of one query per ride). */
    List<Rating> findByRaterIdAndRideIdIn(UUID raterId, Collection<UUID> rideIds);

    List<Rating> findByRideId(UUID rideId);
}
