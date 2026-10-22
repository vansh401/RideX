package com.ridex.payment.earnings;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class IncentiveRulesTest {

    private final List<IncentiveService.Tier> tiers = IncentiveService.parse("20:250, 10:100");

    @Test
    void tiersAreParsedAndSortedFromTheConfiguration() {
        assertThat(tiers).extracting(IncentiveService.Tier::trips).containsExactly(10, 20);
        assertThat(tiers.get(1).bonus()).isEqualByComparingTo("250.00");
    }

    @Test
    void theHighestTierReachedPaysAndTiersDoNotAddUp() {
        assertThat(IncentiveService.bonusFor(tiers, 9)).isEqualByComparingTo("0");
        assertThat(IncentiveService.bonusFor(tiers, 10)).isEqualByComparingTo("100");
        assertThat(IncentiveService.bonusFor(tiers, 19)).isEqualByComparingTo("100");
        assertThat(IncentiveService.bonusFor(tiers, 20)).isEqualByComparingTo(new BigDecimal("250"));
        assertThat(IncentiveService.bonusFor(tiers, 45)).isEqualByComparingTo("250");
    }

    @Test
    void noTiersMeansNoBonus() {
        assertThat(IncentiveService.bonusFor(IncentiveService.parse(""), 100)).isEqualByComparingTo("0");
    }
}
