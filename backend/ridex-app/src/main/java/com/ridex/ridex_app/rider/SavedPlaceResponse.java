package com.ridex.ridex_app.rider;

import java.math.BigDecimal;
import java.util.UUID;

public record SavedPlaceResponse(
        UUID id,
        String label,
        String address,
        BigDecimal latitude,
        BigDecimal longitude
) {
    public static SavedPlaceResponse from(SavedPlace place) {
        return new SavedPlaceResponse(
                place.getId(), place.getLabel(), place.getAddress(), place.getLatitude(), place.getLongitude());
    }
}
