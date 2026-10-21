package com.ridex.pricing;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.ridex.pricing.VehicleCategory;

/**
 * Prices live in configuration (application.properties), not in code, so changing a rate is
 * a configuration change. Amounts are in INR.
 */
@ConfigurationProperties(prefix = "ridex.pricing")
public record PricingProperties(
        /** Tax added on top of the fare, e.g. 0.05 for 5%. */
        BigDecimal taxRate,
        /** Roads are longer than straight lines: multiplies the straight-line distance. */
        BigDecimal roadFactor,
        Map<VehicleCategory, CategoryRates> categories
) {
    public record CategoryRates(
            BigDecimal baseFare,
            BigDecimal perKm,
            BigDecimal perMinute,
            BigDecimal minimumFare,
            int averageSpeedKmh
    ) {
    }

    public CategoryRates ratesFor(VehicleCategory category) {
        CategoryRates rates = categories.get(category);
        if (rates == null) {
            throw new IllegalStateException("No pricing configured for " + category);
        }
        return rates;
    }
}
