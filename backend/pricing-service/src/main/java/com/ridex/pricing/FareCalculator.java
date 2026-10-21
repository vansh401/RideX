package com.ridex.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.ridex.pricing.VehicleCategory;

/**
 * The fare formula. It is pure arithmetic: no database, no HTTP, no rides. That is what makes it
 * easy to test exhaustively and to change without touching anything else.
 *
 *   raw      = base + (perKm x km) + (perMinute x minutes)
 *   subtotal = max(raw, minimumFare) x surge
 *   tax      = subtotal x taxRate
 *   total    = subtotal + tax
 *
 * MONEY IS NEVER A double. 0.1 + 0.2 is 0.30000000000000004 in floating point; with BigDecimal
 * it is exactly 0.3. Every intermediate amount is rounded HALF_UP to 2 decimals, so the printed
 * breakdown always adds up to the total.
 */
@Component
public class FareCalculator {

    static final String CURRENCY = "INR";
    static final BigDecimal NO_SURGE = BigDecimal.ONE.setScale(2);

    private final PricingProperties properties;

    public FareCalculator(PricingProperties properties) {
        this.properties = properties;
    }

    public FareEstimate calculate(VehicleCategory category, BigDecimal distanceKm, int durationMinutes) {
        return calculate(category, distanceKm, durationMinutes, NO_SURGE);
    }

    /** The surge multiplier is a hook for step 14.1; today it is always 1.00. */
    public FareEstimate calculate(VehicleCategory category, BigDecimal distanceKm, int durationMinutes,
                                  BigDecimal surgeMultiplier) {
        PricingProperties.CategoryRates rates = properties.ratesFor(category);

        BigDecimal baseFare = money(rates.baseFare());
        BigDecimal distanceFare = money(rates.perKm().multiply(distanceKm));
        BigDecimal timeFare = money(rates.perMinute().multiply(BigDecimal.valueOf(durationMinutes)));

        BigDecimal raw = baseFare.add(distanceFare).add(timeFare);
        BigDecimal minimum = money(rates.minimumFare());
        boolean minimumApplied = raw.compareTo(minimum) < 0;

        BigDecimal subtotal = money(raw.max(minimum).multiply(surgeMultiplier));
        BigDecimal tax = money(subtotal.multiply(properties.taxRate()));
        BigDecimal total = subtotal.add(tax);

        return new FareEstimate(category, distanceKm.setScale(2, RoundingMode.HALF_UP), durationMinutes,
                baseFare, distanceFare, timeFare, minimumApplied, surgeMultiplier.setScale(2, RoundingMode.HALF_UP),
                subtotal, tax, total, CURRENCY);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
