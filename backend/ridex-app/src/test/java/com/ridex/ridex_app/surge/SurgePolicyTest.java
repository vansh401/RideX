package com.ridex.ridex_app.surge;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** The surge rules, with no infrastructure: what the multiplier should be, and how fast it may move. */
class SurgePolicyTest {

    private final SurgePolicy policy = new SurgePolicy(5, new BigDecimal("3.0"));

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    // ------------------------------------------------------------------ the target

    @Test
    void aCalmZoneHasNoSurgeHoweverFewDriversThereAre() {
        assertThat(policy.target(4, 0)).as("below the minimum demand the ratio means nothing").isEqualByComparingTo("1.0");
        assertThat(policy.target(0, 0)).isEqualByComparingTo("1.0");
        assertThat(policy.target(10, 20)).as("plenty of drivers").isEqualByComparingTo("1.0");
    }

    @Test
    void theTargetRisesWithTheRatioOfRequestsToFreeDrivers() {
        assertThat(policy.target(5, 4)).as("1.25 requests per driver").isEqualByComparingTo("1.0");
        assertThat(policy.target(6, 4)).as("1.5: the first step").isEqualByComparingTo("1.2");
        assertThat(policy.target(8, 4)).as("2").isEqualByComparingTo("1.5");
        assertThat(policy.target(10, 4)).isEqualByComparingTo("1.5");
        assertThat(policy.target(12, 4)).as("3").isEqualByComparingTo("1.8");
        assertThat(policy.target(20, 4)).as("5").isEqualByComparingTo("2.2");
        assertThat(policy.target(30, 4)).isEqualByComparingTo("2.5");
        assertThat(policy.target(30, 0)).as("no driver at all counts as one: no division by zero").isEqualByComparingTo("2.5");
    }

    @Test
    void theTargetIsNeverAboveTheCap() {
        SurgePolicy lowCap = new SurgePolicy(5, new BigDecimal("1.5"));

        assertThat(lowCap.target(100, 1)).isEqualByComparingTo("1.5");
    }

    // ------------------------------------------------------------------ moving toward it

    @Test
    void theMultiplierRisesInStepsNotInJumps() {
        assertThat(policy.next(d("1.0"), d("2.5"))).isEqualByComparingTo("1.3");
        assertThat(policy.next(d("1.3"), d("2.5"))).isEqualByComparingTo("1.6");
        assertThat(policy.next(d("2.2"), d("2.5"))).as("the last step is shorter than a full one").isEqualByComparingTo("2.5");
    }

    @Test
    void itFallsSlowerThanItRises() {
        assertThat(policy.next(d("2.2"), d("1.0"))).isEqualByComparingTo("2.0");
        assertThat(policy.next(d("1.1"), d("1.0"))).as("never below 1.0").isEqualByComparingTo("1.0");
    }

    @Test
    void aBriefDipInDemandDoesNotEndTheSurgeAtOnce() {
        BigDecimal multiplier = d("2.2");
        multiplier = policy.next(multiplier, policy.target(0, 0));   // one calm cycle

        assertThat(multiplier).isEqualByComparingTo("2.0");
    }

    @Test
    void followingAConstantShortageTheMultiplierSettlesOnTheTargetAndStaysThere() {
        BigDecimal multiplier = d("1.0");
        for (int cycle = 0; cycle < 10; cycle++) {
            multiplier = policy.next(multiplier, policy.target(20, 4));
        }

        assertThat(multiplier).isEqualByComparingTo("2.2");
        assertThat(policy.next(multiplier, policy.target(20, 4))).as("no oscillation around the target").isEqualByComparingTo("2.2");
    }

    @Test
    void itAlwaysComesBackToOneWhenTheShortageEnds() {
        BigDecimal multiplier = d("2.5");
        int cycles = 0;
        while (multiplier.compareTo(d("1.0")) > 0 && cycles < 20) {
            multiplier = policy.next(multiplier, policy.target(0, 10));
            cycles++;
        }

        assertThat(multiplier).isEqualByComparingTo("1.0");
        assertThat(cycles).as("(2.5 - 1.0) in steps of 0.2").isEqualTo(8);
    }

    @Test
    void aCurrentValueOutsideTheRangeIsBroughtBackIntoIt() {
        assertThat(policy.next(d("9.0"), d("1.0"))).as("a corrupt value cannot persist: it is pulled down and capped").isLessThanOrEqualTo(d("3.0"));
        assertThat(policy.next(d("0.2"), d("1.0"))).isEqualByComparingTo("1.0");
    }
}
