package com.ridex.pricing;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ridex.platform.web.BadRequestException;
import com.ridex.pricing.LatLng;
import com.ridex.pricing.VehicleCategory;

/** The only entry point other modules use to get a price. */
@Service
public class PricingService {

    static final BigDecimal MIN_TRIP_KM = new BigDecimal("0.20");
    static final BigDecimal MAX_TRIP_KM = new BigDecimal("100.00");

    static final int MAX_STOPS = 3;
    /** Each stop on the way adds waiting time (people get in and out): it is paid through the time fare, so the breakdown stays the same. */
    static final int MINUTES_PER_STOP = 3;

    private final DistanceEstimator distanceEstimator;
    private final FareCalculator fareCalculator;
    private final SurgeLookup surge;

    @Autowired
    public PricingService(DistanceEstimator distanceEstimator, FareCalculator fareCalculator, SurgeLookup surge) {
        this.distanceEstimator = distanceEstimator;
        this.fareCalculator = fareCalculator;
        this.surge = surge;
    }

    /** No surge: the fare is the plain formula. */
    public PricingService(DistanceEstimator distanceEstimator, FareCalculator fareCalculator) {
        this(distanceEstimator, fareCalculator, new SurgeLookup());
    }

    public FareEstimate estimate(LatLng pickup, LatLng dropoff, VehicleCategory category) {
        return estimate(pickup, dropoff, category, List.of(), false);
    }

    /**
     * @param stops        places to stop on the way, in order (at most 3). The route is pickup, stop 1, stop 2 ..., destination: the
     *                     distances of the legs add up, and every stop adds waiting time.
     * @param ignoreSurge  for rides booked ahead of time: the price is fixed at booking, so it must not depend on the demand of that moment.
     */
    public FareEstimate estimate(LatLng pickup, LatLng dropoff, VehicleCategory category, List<LatLng> stops, boolean ignoreSurge) {
        List<LatLng> between = stops == null ? List.of() : stops;
        if (between.size() > MAX_STOPS) {
            throw new BadRequestException("A ride can have at most " + MAX_STOPS + " stops");
        }
        List<LatLng> route = new java.util.ArrayList<>();
        route.add(pickup);
        route.addAll(between);
        route.add(dropoff);
        BigDecimal distanceKm = BigDecimal.ZERO;
        int minutes = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            DistanceEstimator.TripEstimate leg = distanceEstimator.estimate(route.get(i), route.get(i + 1), category);
            distanceKm = distanceKm.add(leg.distanceKm());
            minutes += leg.durationMinutes();
        }
        minutes += MINUTES_PER_STOP * between.size();
        if (distanceKm.compareTo(MIN_TRIP_KM) < 0) {
            throw new BadRequestException("Pickup and destination are too close together");
        }
        if (distanceKm.compareTo(MAX_TRIP_KM) > 0) {
            throw new BadRequestException("Trips longer than 100 km are not supported");
        }
        BigDecimal multiplier = ignoreSurge ? SurgeLookup.NONE : surge.multiplierFor(pickup);
        return fareCalculator.calculate(category, distanceKm, minutes, multiplier);
    }

    /** One quote per vehicle category, so the rider can compare and choose. */
    public List<FareEstimate> estimateAll(LatLng pickup, LatLng dropoff, List<LatLng> stops) {
        return Arrays.stream(VehicleCategory.values()).map(category -> estimate(pickup, dropoff, category, stops, false)).toList();
    }

    public List<FareEstimate> estimateAll(LatLng pickup, LatLng dropoff) {
        return estimateAll(pickup, dropoff, List.of());
    }
}
