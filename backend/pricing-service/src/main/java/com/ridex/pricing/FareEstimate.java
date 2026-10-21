package com.ridex.pricing;

import java.math.BigDecimal;

import com.ridex.pricing.VehicleCategory;

/**
 * A price quote with its full breakdown, so a rider (and a support agent) can see exactly how
 * the total was reached. Every amount is a BigDecimal with 2 decimal places, in INR.
 */
public record FareEstimate(
        VehicleCategory category,
        BigDecimal distanceKm,
        int durationMinutes,
        BigDecimal baseFare,
        BigDecimal distanceFare,
        BigDecimal timeFare,
        /** True when the minimum fare was higher than base + distance + time. */
        boolean minimumFareApplied,
        BigDecimal surgeMultiplier,
        BigDecimal subtotal,
        BigDecimal tax,
        BigDecimal total,
        String currency
) {
}
