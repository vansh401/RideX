package com.ridex.pricing;

import java.math.BigDecimal;

import com.ridex.pricing.LatLng;
import com.ridex.pricing.VehicleCategory;

/**
 * How far, and how long, a trip is. Behind an interface on purpose: today we estimate from
 * straight-line distance; later a routing service (real roads and traffic) can replace the
 * implementation without changing the fare formula or the ride code.
 */
public interface DistanceEstimator {

    record TripEstimate(BigDecimal distanceKm, int durationMinutes) {
    }

    TripEstimate estimate(LatLng from, LatLng to, VehicleCategory category);
}
