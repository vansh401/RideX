package com.ridex.ridex_app.pricing;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ridex.ridex_app.common.BadRequestException;
import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.driver.VehicleCategory;

/** The only entry point other modules use to get a price. */
@Service
public class PricingService {

    static final BigDecimal MIN_TRIP_KM = new BigDecimal("0.20");
    static final BigDecimal MAX_TRIP_KM = new BigDecimal("100.00");

    private final DistanceEstimator distanceEstimator;
    private final FareCalculator fareCalculator;

    public PricingService(DistanceEstimator distanceEstimator, FareCalculator fareCalculator) {
        this.distanceEstimator = distanceEstimator;
        this.fareCalculator = fareCalculator;
    }

    public FareEstimate estimate(LatLng pickup, LatLng dropoff, VehicleCategory category) {
        DistanceEstimator.TripEstimate trip = distanceEstimator.estimate(pickup, dropoff, category);
        if (trip.distanceKm().compareTo(MIN_TRIP_KM) < 0) {
            throw new BadRequestException("Pickup and destination are too close together");
        }
        if (trip.distanceKm().compareTo(MAX_TRIP_KM) > 0) {
            throw new BadRequestException("Trips longer than 100 km are not supported");
        }
        return fareCalculator.calculate(category, trip.distanceKm(), trip.durationMinutes());
    }

    /** One quote per vehicle category, so the rider can compare and choose. */
    public List<FareEstimate> estimateAll(LatLng pickup, LatLng dropoff) {
        return Arrays.stream(VehicleCategory.values()).map(category -> estimate(pickup, dropoff, category)).toList();
    }
}
