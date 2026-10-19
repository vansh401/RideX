package com.ridex.ridex_app.ride;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRejectionRepository extends JpaRepository<RideRejection, UUID> {

    long countByRideId(UUID rideId);
}
