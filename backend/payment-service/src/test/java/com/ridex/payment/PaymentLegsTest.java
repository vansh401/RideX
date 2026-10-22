package com.ridex.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.payment.ledger.AccountType;
import com.ridex.payment.ledger.LedgerService.Leg;

/** The ledger legs of a ride payment: pure arithmetic, so it can be checked over many amounts. */
class PaymentLegsTest {

    private static final UUID PAYER = UUID.randomUUID();
    private static final UUID DRIVER = UUID.randomUUID();
    private static final BigDecimal RATE = new BigDecimal("0.20");

    private static BigDecimal sum(List<Leg> legs) {
        return legs.stream().map(Leg::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal of(List<Leg> legs, UUID account) {
        return legs.stream().filter(l -> l.accountId().equals(account)).map(Leg::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void theDriverGetsTheFareMinusCommissionAndTheCompanyKeepsTheCommission() {
        List<Leg> legs = PaymentService.ridePaymentLegs(PAYER, new BigDecimal("252.00"), BigDecimal.ZERO, new BigDecimal("252.00"), RATE, DRIVER);

        assertThat(of(legs, PAYER)).isEqualByComparingTo("-252.00");
        assertThat(of(legs, DRIVER)).isEqualByComparingTo("201.60");
        assertThat(of(legs, AccountType.REVENUE_ID)).isEqualByComparingTo("50.40");
        assertThat(sum(legs)).isEqualByComparingTo("0");
    }

    @Test
    void aCouponIsPaidByThePromoAccountNotByTheRiderOrTheDriver() {
        List<Leg> legs = PaymentService.ridePaymentLegs(PAYER, new BigDecimal("202.00"), new BigDecimal("50.00"), new BigDecimal("252.00"), RATE, DRIVER);

        assertThat(of(legs, PAYER)).isEqualByComparingTo("-202.00");
        assertThat(of(legs, AccountType.PROMO_ID)).isEqualByComparingTo("-50.00");
        assertThat(of(legs, DRIVER)).as("the driver's share does not depend on the coupon").isEqualByComparingTo("201.60");
        assertThat(sum(legs)).isEqualByComparingTo("0");
    }

    @Test
    void aFullyDiscountedRideHasNoPayerLeg() {
        List<Leg> legs = PaymentService.ridePaymentLegs(PAYER, BigDecimal.ZERO.setScale(2), new BigDecimal("252.00"), new BigDecimal("252.00"), RATE, DRIVER);

        assertThat(legs).noneMatch(l -> l.accountId().equals(PAYER));
        assertThat(sum(legs)).isEqualByComparingTo("0");
    }

    @Test
    void whateverTheAmountTheLegsAddUpToExactlyZeroAndNoCentIsLost() {
        for (int cents = 1; cents <= 20_000; cents += 7) {
            BigDecimal fare = BigDecimal.valueOf(cents, 2);
            for (BigDecimal discount : List.of(BigDecimal.ZERO.setScale(2), fare.divide(BigDecimal.valueOf(3), 2, java.math.RoundingMode.DOWN), fare)) {
                List<Leg> legs = PaymentService.ridePaymentLegs(PAYER, fare.subtract(discount), discount, fare, RATE, DRIVER);
                assertThat(sum(legs)).as("fare " + fare + " discount " + discount).isEqualByComparingTo("0");
                assertThat(legs).allMatch(l -> l.amount().signum() != 0 && l.amount().scale() <= 2);
            }
        }
    }
}
