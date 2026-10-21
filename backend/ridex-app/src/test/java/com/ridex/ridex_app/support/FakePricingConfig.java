package com.ridex.ridex_app.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.ridex.ridex_app.common.BadRequestException;
import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.pricing.FareQuote;
import com.ridex.ridex_app.pricing.PricingClient;

/**
 * Pricing is a separate service. Tests of the CORE do not start it: they plug in this small stand-in, which follows the same
 * contract (a distance-based fare with tax, the same limits). The real formula is tested in the pricing service itself, and the
 * real HTTP call in HttpPricingClientTest, so nothing is left untested; it is just tested where it lives.
 */
@TestConfiguration
public class FakePricingConfig {

    @Bean
    @Primary
    PricingClient fakePricingClient() {
        return (pickup, dropoff, category) -> quote(pickup, dropoff, category);
    }

    static FareQuote quote(LatLng pickup, LatLng dropoff, VehicleCategory category) {
        BigDecimal km = BigDecimal.valueOf(pickup.distanceKmTo(dropoff) * 1.30).setScale(2, RoundingMode.HALF_UP);
        if (km.compareTo(new BigDecimal("0.20")) < 0) {
            throw new BadRequestException("Pickup and destination are too close together");
        }
        if (km.compareTo(new BigDecimal("100.00")) > 0) {
            throw new BadRequestException("Trips longer than 100 km are not supported");
        }
        BigDecimal perKm = switch (category) {
            case BIKE -> new BigDecimal("7.00");
            case AUTO -> new BigDecimal("11.00");
            case CAB -> new BigDecimal("14.00");
        };
        int minutes = Math.max(1, (int) Math.ceil(km.doubleValue() / 24.0 * 60.0));
        BigDecimal base = new BigDecimal("50.00");
        BigDecimal distanceFare = perKm.multiply(km).setScale(2, RoundingMode.HALF_UP);
        BigDecimal timeFare = new BigDecimal("2.00").multiply(BigDecimal.valueOf(minutes)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal subtotal = base.add(distanceFare).add(timeFare).max(new BigDecimal("90.00"));
        BigDecimal tax = subtotal.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP);
        return new FareQuote(km, minutes, base, distanceFare, timeFare, new BigDecimal("1.00"), subtotal, tax, subtotal.add(tax), "INR");
    }
}
