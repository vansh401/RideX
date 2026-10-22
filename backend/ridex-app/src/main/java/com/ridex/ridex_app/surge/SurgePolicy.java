package com.ridex.ridex_app.surge;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The rules of surge pricing, as pure arithmetic: no database, no Redis, no clock. That is what makes them easy to test, and easy to argue about.
 *
 * MEASURING. In each zone: DEMAND = rides requested there in the last few minutes; SUPPLY = free drivers near the zone. The pressure is their
 * ratio. Two drivers and ten requests is a ratio of 5: people will wait a long time, so the price goes up to bring more drivers out and to make
 * the least urgent riders wait.
 *
 * NOISE GUARD. A ratio of "1 request, 0 drivers" is infinite and means nothing. Below a minimum number of requests the zone is calm, whatever the
 * ratio says.
 *
 * SAFE UPDATES. A multiplier that jumps from 1.0 to 2.5 and back every half minute makes prices feel random and invites gaming (riders waiting for
 * the price to drop). So the multiplier does not jump to the target; it MOVES toward it by a limited step each cycle: up quickly (at most +0.3, so a
 * real shortage is answered within minutes), down slowly (at most -0.2, so a brief dip does not end the surge at once). It is rounded to 0.1, it is
 * never below 1.0 (no discounts here) and never above a cap.
 */
public final class SurgePolicy {

    public static final BigDecimal NONE = new BigDecimal("1.0");
    private static final BigDecimal STEP_UP = new BigDecimal("0.3");
    private static final BigDecimal STEP_DOWN = new BigDecimal("0.2");

    private final int minDemand;
    private final BigDecimal cap;

    public SurgePolicy(int minDemand, BigDecimal cap) {
        this.minDemand = minDemand;
        this.cap = cap.setScale(1, RoundingMode.HALF_UP);
    }

    /** Where the multiplier should be, given the measurements (not where it is allowed to go in one step). */
    public BigDecimal target(int demand, int supply) {
        if (demand < minDemand) {
            return NONE;
        }
        double pressure = demand / (double) Math.max(1, supply);
        BigDecimal target;
        if (pressure < 1.5) {
            target = NONE;
        } else if (pressure < 2) {
            target = new BigDecimal("1.2");
        } else if (pressure < 3) {
            target = new BigDecimal("1.5");
        } else if (pressure < 4) {
            target = new BigDecimal("1.8");
        } else if (pressure < 6) {
            target = new BigDecimal("2.2");
        } else {
            target = new BigDecimal("2.5");
        }
        return target.min(cap);
    }

    /** One step from the current multiplier toward the target. */
    public BigDecimal next(BigDecimal current, BigDecimal target) {
        BigDecimal from = current.max(NONE);
        BigDecimal result;
        if (target.compareTo(from) > 0) {
            result = from.add(STEP_UP).min(target);
        } else {
            result = from.subtract(STEP_DOWN).max(target);
        }
        return result.max(NONE).min(cap).setScale(1, RoundingMode.HALF_UP);
    }
}
