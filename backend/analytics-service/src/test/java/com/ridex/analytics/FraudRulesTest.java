package com.ridex.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/** What a count means: the thresholds of each fraud rule, exactly at their edges. */
class FraudRulesTest {

    @Test
    void cancellationsAreOnlySuspiciousInABurst() {
        assertThat(FraudRules.riderCancelBurst(3)).isFalse();
        assertThat(FraudRules.riderCancelBurst(4)).isTrue();
        assertThat(FraudRules.driverCancelPattern(2)).isFalse();
        assertThat(FraudRules.driverCancelPattern(3)).isTrue();
    }

    @Test
    void aTripCompletedFasterThanACarCanMoveIsSuspiciousOnlyWhenTheFareIsReal() {
        assertThat(FraudRules.instantCompletion(Duration.ofSeconds(60), new BigDecimal("250"))).isTrue();
        assertThat(FraudRules.instantCompletion(Duration.ofSeconds(89), new BigDecimal("100"))).as("the edges: under 90 s, from 100").isTrue();
        assertThat(FraudRules.instantCompletion(Duration.ofSeconds(90), new BigDecimal("250"))).isFalse();
        assertThat(FraudRules.instantCompletion(Duration.ofSeconds(30), new BigDecimal("40"))).as("a tiny fare is not worth faking").isFalse();
        assertThat(FraudRules.instantCompletion(null, new BigDecimal("250"))).isFalse();
        assertThat(FraudRules.instantCompletion(Duration.ofSeconds(-5), new BigDecimal("250"))).as("a clock going backwards is bad data, not fraud").isFalse();
    }

    @Test
    void theSameRiderAndDriverThreeTimesInADayIsAPattern() {
        assertThat(FraudRules.repeatPair(2)).isFalse();
        assertThat(FraudRules.repeatPair(3)).isTrue();
    }

    @Test
    void manyCouponsInADayAddUpToAbuse() {
        assertThat(FraudRules.couponAbuse(2)).isFalse();
        assertThat(FraudRules.couponAbuse(3)).isTrue();
    }

    @Test
    void refundsAreSuspiciousWhenThereAreSeveralAndTheyReturnHalfOfWhatWasPaid() {
        assertThat(FraudRules.refundHeavy(2, new BigDecimal("900"), new BigDecimal("1000"))).as("too few").isFalse();
        assertThat(FraudRules.refundHeavy(3, new BigDecimal("499.99"), new BigDecimal("1000"))).as("just under half").isFalse();
        assertThat(FraudRules.refundHeavy(3, new BigDecimal("500"), new BigDecimal("1000"))).isTrue();
        assertThat(FraudRules.refundHeavy(5, new BigDecimal("10"), BigDecimal.ZERO)).as("nothing paid: no division by zero").isFalse();
    }
}
