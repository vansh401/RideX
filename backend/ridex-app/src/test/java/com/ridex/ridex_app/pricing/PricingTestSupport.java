package com.ridex.ridex_app.pricing;

import java.math.BigDecimal;
import java.util.Map;

import com.ridex.ridex_app.driver.VehicleCategory;

/** The same rates as application.properties, built by hand so tests do not need Spring. */
final class PricingTestSupport {

    private PricingTestSupport() {
    }

    static PricingProperties realRates() {
        return new PricingProperties(new BigDecimal("0.05"), new BigDecimal("1.30"), Map.of(
                VehicleCategory.BIKE, new PricingProperties.CategoryRates(
                        new BigDecimal("20.00"), new BigDecimal("7.00"), new BigDecimal("1.00"), new BigDecimal("35.00"), 25),
                VehicleCategory.AUTO, new PricingProperties.CategoryRates(
                        new BigDecimal("30.00"), new BigDecimal("11.00"), new BigDecimal("1.50"), new BigDecimal("50.00"), 22),
                VehicleCategory.CAB, new PricingProperties.CategoryRates(
                        new BigDecimal("50.00"), new BigDecimal("14.00"), new BigDecimal("2.00"), new BigDecimal("90.00"), 24)));
    }

    /** Zero base, zero tax, 1 per km: isolates the rounding rule. */
    static PricingProperties roundingProbe() {
        return new PricingProperties(BigDecimal.ZERO, new BigDecimal("1.00"), Map.of(
                VehicleCategory.CAB, new PricingProperties.CategoryRates(
                        BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30)));
    }
}
