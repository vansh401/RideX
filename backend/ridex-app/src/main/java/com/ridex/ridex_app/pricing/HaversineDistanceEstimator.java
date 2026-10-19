package com.ridex.ridex_app.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.driver.VehicleCategory;

/**
 * Straight-line distance x a "road factor" (roads are never straight), and a duration from the
 * category's average speed. An approximation, honest about being one.
 */
@Component
public class HaversineDistanceEstimator implements DistanceEstimator {

    private final PricingProperties properties;

    public HaversineDistanceEstimator(PricingProperties properties) {
        this.properties = properties;
    }

    @Override
    public TripEstimate estimate(LatLng from, LatLng to, VehicleCategory category) {
        double straightKm = from.distanceKmTo(to);
        BigDecimal distanceKm = BigDecimal.valueOf(straightKm)
                .multiply(properties.roadFactor())
                .setScale(2, RoundingMode.HALF_UP);

        int speed = properties.ratesFor(category).averageSpeedKmh();
        // Round UP: a 7.2 minute trip is quoted as 8 minutes, never less than it takes
        int minutes = (int) Math.ceil(distanceKm.doubleValue() / speed * 60.0);
        return new TripEstimate(distanceKm, Math.max(minutes, 1));
    }
}
