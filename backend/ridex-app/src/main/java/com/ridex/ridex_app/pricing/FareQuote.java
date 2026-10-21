package com.ridex.ridex_app.pricing;

import java.math.BigDecimal;

/**
 * A price quote as the core uses it: the amounts to freeze onto a ride. It lists only the fields the core NEEDS from the
 * pricing service's answer (a "tolerant reader"): if the pricing service adds a field tomorrow, nothing here breaks.
 */
public record FareQuote(
        BigDecimal distanceKm,
        int durationMinutes,
        BigDecimal baseFare,
        BigDecimal distanceFare,
        BigDecimal timeFare,
        BigDecimal surgeMultiplier,
        BigDecimal subtotal,
        BigDecimal tax,
        BigDecimal total,
        String currency
) {
}
