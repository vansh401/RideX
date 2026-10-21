package com.ridex.pricing;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/** A point on Earth. Validated at the API edge, used everywhere a location travels. */
public record LatLng(

        @NotNull(message = "is required")
        @DecimalMin(value = "-90.0", message = "must be between -90 and 90")
        @DecimalMax(value = "90.0", message = "must be between -90 and 90")
        @Digits(integer = 2, fraction = 6, message = "use at most 6 decimal places")
        BigDecimal latitude,

        @NotNull(message = "is required")
        @DecimalMin(value = "-180.0", message = "must be between -180 and 180")
        @DecimalMax(value = "180.0", message = "must be between -180 and 180")
        @Digits(integer = 3, fraction = 6, message = "use at most 6 decimal places")
        BigDecimal longitude
) {
    private static final double EARTH_RADIUS_KM = 6371.0088;

    /** Straight-line ("as the crow flies") distance using the haversine formula. */
    public double distanceKmTo(LatLng other) {
        double lat1 = Math.toRadians(latitude.doubleValue());
        double lat2 = Math.toRadians(other.latitude.doubleValue());
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(other.longitude.doubleValue() - longitude.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
