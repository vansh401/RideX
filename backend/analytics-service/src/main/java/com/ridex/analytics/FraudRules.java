package com.ridex.analytics;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Fraud detection RULES: the thresholds, and the questions they answer, as pure functions of counts. The detector (FraudDetector) does the
 * counting from the facts table; this class decides what a count MEANS.
 *
 * A rule is a hypothesis about how someone cheats, turned into a number and a limit:
 *
 *   RIDER_CANCEL_BURST    A rider keeps cancelling after a driver accepted (4 in an hour): wasting drivers' time, or probing for a cheaper offer.
 *   DRIVER_CANCEL_PATTERN A driver accepts and then cancels (3 in a day): picking only the rides they like.
 *   INSTANT_COMPLETION    A trip "completed" less than 90 seconds after the driver accepted, with a real fare: no car can go anywhere in that time. A
 *                         typical way to cash in a bonus, or to move money, with a fake ride between two accomplices.
 *   REPEAT_PAIR           The same rider and driver complete 3 rides in a day: friends, or two accounts of one person farming incentives.
 *   COUPON_ABUSE          Coupons used on 3 rides in a day by one rider (each coupon is limited per rider, but many different codes add up).
 *   REFUND_HEAVY          Several refunds that return half or more of what was paid in a month.
 *
 * SIGNALS, NOT VERDICTS. Every rule has innocent explanations (two friends who really do share a commute; a rider whose plans change). So a rule
 * writes a SIGNAL with a severity for a human to review, and never blocks anybody. Automatic blocking is only for patterns with essentially no
 * innocent cause, and needs an appeals process. Thresholds are guesses to be tuned against reviewed signals: each dismissal is a false positive
 * measured, each confirmation a true one.
 */
public final class FraudRules {

    public static final int RIDER_CANCELS = 4;
    public static final Duration RIDER_CANCEL_WINDOW = Duration.ofHours(1);
    public static final int DRIVER_CANCELS = 3;
    public static final Duration DRIVER_CANCEL_WINDOW = Duration.ofDays(1);
    public static final Duration INSTANT_TRIP = Duration.ofSeconds(90);
    public static final BigDecimal INSTANT_TRIP_MIN_FARE = new BigDecimal("100");
    public static final int PAIR_RIDES = 3;
    public static final Duration PAIR_WINDOW = Duration.ofDays(1);
    public static final int COUPON_RIDES = 3;
    public static final Duration COUPON_WINDOW = Duration.ofDays(1);
    public static final int REFUNDS = 3;
    public static final BigDecimal REFUND_SHARE = new BigDecimal("0.50");
    public static final Duration REFUND_WINDOW = Duration.ofDays(30);

    private FraudRules() {
    }

    public static boolean riderCancelBurst(int cancelsInWindow) {
        return cancelsInWindow >= RIDER_CANCELS;
    }

    public static boolean driverCancelPattern(int cancelsInWindow) {
        return cancelsInWindow >= DRIVER_CANCELS;
    }

    public static boolean instantCompletion(Duration acceptedToCompleted, BigDecimal fare) {
        return acceptedToCompleted != null && !acceptedToCompleted.isNegative() && acceptedToCompleted.compareTo(INSTANT_TRIP) < 0
                && fare != null && fare.compareTo(INSTANT_TRIP_MIN_FARE) >= 0;
    }

    public static boolean repeatPair(int ridesTogether) {
        return ridesTogether >= PAIR_RIDES;
    }

    public static boolean couponAbuse(int ridesWithCoupon) {
        return ridesWithCoupon >= COUPON_RIDES;
    }

    public static boolean refundHeavy(int refunds, BigDecimal refunded, BigDecimal paid) {
        return refunds >= REFUNDS && paid.signum() > 0 && refunded.compareTo(paid.multiply(REFUND_SHARE)) >= 0;
    }
}
