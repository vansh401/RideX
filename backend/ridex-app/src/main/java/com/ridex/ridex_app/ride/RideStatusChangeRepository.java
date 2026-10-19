package com.ridex.ridex_app.ride;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RideStatusChangeRepository extends JpaRepository<RideStatusChange, UUID> {

    /** The audit trail in the order things happened. */
    List<RideStatusChange> findByRideIdOrderBySequenceAsc(UUID rideId);
}
