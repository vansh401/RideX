package com.ridex.payment.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.payment.coupon.CouponService.Coupon;
import com.ridex.payment.coupon.CouponService.Evaluation;
import com.ridex.payment.coupon.CouponService.Reason;

/** The coupon rules on their own, with no database: one rule per test, in the order they are checked. */
class CouponRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    private static Coupon coupon(String type, String value, String maxDiscount, String minFare, Integer maxTotal, int perUser, int used, boolean active) {
        return new Coupon(UUID.randomUUID(), "TEST", "test", type, new BigDecimal(value), maxDiscount == null ? null : new BigDecimal(maxDiscount),
                new BigDecimal(minFare), NOW.minus(1, ChronoUnit.DAYS), NOW.plus(1, ChronoUnit.DAYS), maxTotal, perUser, used, active);
    }

    private static Evaluation check(Coupon coupon, int usesByUser, String fare) {
        return CouponService.evaluate(coupon, usesByUser, new BigDecimal(fare), NOW);
    }

    @Test
    void aPercentCouponTakesAShareOfTheFare() {
        Evaluation result = check(coupon("PERCENT", "20", null, "0", null, 1, 0, true), 0, "252.00");

        assertThat(result.eligible()).isTrue();
        assertThat(result.discount()).isEqualByComparingTo("50.40");
    }

    @Test
    void aPercentCouponIsCappedByItsMaximum() {
        assertThat(check(coupon("PERCENT", "50", "75", "0", null, 1, 0, true), 0, "500.00").discount()).isEqualByComparingTo("75.00");
    }

    @Test
    void aFlatCouponTakesAFixedAmountButNeverMoreThanTheFare() {
        assertThat(check(coupon("FLAT", "50", null, "0", null, 1, 0, true), 0, "252.00").discount()).isEqualByComparingTo("50.00");
        assertThat(check(coupon("FLAT", "50", null, "0", null, 1, 0, true), 0, "35.00").discount()).as("a ride cannot cost less than nothing").isEqualByComparingTo("35.00");
    }

    @Test
    void percentagesRoundHalfUpToTwoDecimals() {
        assertThat(check(coupon("PERCENT", "15", null, "0", null, 1, 0, true), 0, "99.99").discount()).isEqualByComparingTo("15.00");   // 14.9985
    }

    @Test
    void eachRuleRefusesWithItsOwnReason() {
        assertThat(check(coupon("FLAT", "50", null, "0", null, 1, 0, false), 0, "252").reason()).isEqualTo(Reason.INACTIVE);
        assertThat(check(coupon("FLAT", "50", null, "300", null, 1, 0, true), 0, "252").reason()).isEqualTo(Reason.FARE_TOO_LOW);
        assertThat(check(coupon("FLAT", "50", null, "0", 100, 1, 100, true), 0, "252").reason()).isEqualTo(Reason.SOLD_OUT);
        assertThat(check(coupon("FLAT", "50", null, "0", null, 2, 0, true), 2, "252").reason()).isEqualTo(Reason.ALREADY_USED);
    }

    @Test
    void theDatesAreHonouredAtBothEnds() {
        Coupon future = new Coupon(UUID.randomUUID(), "T", "t", "FLAT", new BigDecimal("10"), null, BigDecimal.ZERO, NOW.plusSeconds(1), NOW.plus(2, ChronoUnit.DAYS), null, 1, 0, true);
        Coupon past = new Coupon(UUID.randomUUID(), "T", "t", "FLAT", new BigDecimal("10"), null, BigDecimal.ZERO, NOW.minus(2, ChronoUnit.DAYS), NOW, null, 1, 0, true);

        assertThat(CouponService.evaluate(future, 0, new BigDecimal("100"), NOW).reason()).isEqualTo(Reason.NOT_STARTED);
        assertThat(CouponService.evaluate(past, 0, new BigDecimal("100"), NOW).reason()).as("the end moment itself is already too late").isEqualTo(Reason.EXPIRED);
    }

    @Test
    void aLimitOfUsesLeftOverIsStillUsable() {
        assertThat(check(coupon("FLAT", "50", null, "0", 100, 1, 99, true), 0, "252").eligible()).isTrue();
    }

    @Test
    void codesAreNotCaseOrSpaceSensitive() {
        assertThat(CouponService.normalise("  welcome50 ")).isEqualTo("WELCOME50");
        assertThat(CouponService.normalise(null)).isEmpty();
    }
}
