package com.ridex.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.ridex.pricing.VehicleCategory;

class FareCalculatorTest {

    private final FareCalculator calculator = new FareCalculator(PricingTestSupport.realRates());

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void aNormalCabTripAddsBaseDistanceTimeAndTax() {
        // 10 km, 25 min:  50.00 + 14 x 10 + 2 x 25 = 240.00;  tax 5% = 12.00
        FareEstimate fare = calculator.calculate(VehicleCategory.CAB, bd("10.00"), 25);

        assertThat(fare.baseFare()).isEqualByComparingTo("50.00");
        assertThat(fare.distanceFare()).isEqualByComparingTo("140.00");
        assertThat(fare.timeFare()).isEqualByComparingTo("50.00");
        assertThat(fare.subtotal()).isEqualByComparingTo("240.00");
        assertThat(fare.tax()).isEqualByComparingTo("12.00");
        assertThat(fare.total()).isEqualByComparingTo("252.00");
        assertThat(fare.minimumFareApplied()).isFalse();
        assertThat(fare.currency()).isEqualTo("INR");
    }

    @Test
    void aShortTripPaysTheMinimumFare() {
        // 1 km, 3 min: 50 + 14 + 6 = 70.00, below the CAB minimum of 90.00
        FareEstimate fare = calculator.calculate(VehicleCategory.CAB, bd("1.00"), 3);

        assertThat(fare.minimumFareApplied()).isTrue();
        assertThat(fare.subtotal()).isEqualByComparingTo("90.00");
        assertThat(fare.tax()).isEqualByComparingTo("4.50");
        assertThat(fare.total()).isEqualByComparingTo("94.50");
    }

    @Test
    void everyCategoryUsesItsOwnRates() {
        // 5 km, 15 min
        assertThat(calculator.calculate(VehicleCategory.BIKE, bd("5.00"), 15).subtotal()).isEqualByComparingTo("70.00");   // 20+35+15
        assertThat(calculator.calculate(VehicleCategory.AUTO, bd("5.00"), 15).subtotal()).isEqualByComparingTo("107.50"); // 30+55+22.5
        assertThat(calculator.calculate(VehicleCategory.CAB, bd("5.00"), 15).subtotal()).isEqualByComparingTo("150.00");  // 50+70+30
    }

    @Test
    void surgeMultipliesTheSubtotalBeforeTax() {
        FareEstimate fare = calculator.calculate(VehicleCategory.CAB, bd("10.00"), 25, bd("1.50"));

        assertThat(fare.subtotal()).isEqualByComparingTo("360.00");   // 240 x 1.5
        assertThat(fare.tax()).isEqualByComparingTo("18.00");
        assertThat(fare.total()).isEqualByComparingTo("378.00");
        assertThat(fare.surgeMultiplier()).isEqualByComparingTo("1.50");
    }

    @Test
    void halvesRoundUpNotToEven() {
        FareCalculator probe = new FareCalculator(PricingTestSupport.roundingProbe());

        // 1.445 would round to 1.44 with "banker's rounding"; money uses HALF_UP => 1.45
        assertThat(probe.calculate(VehicleCategory.CAB, bd("1.445"), 1).distanceFare()).isEqualByComparingTo("1.45");
        assertThat(probe.calculate(VehicleCategory.CAB, bd("1.444"), 1).distanceFare()).isEqualByComparingTo("1.44");
    }

    @Test
    void theBreakdownAlwaysAddsUpToTheTotal() {
        for (VehicleCategory category : VehicleCategory.values()) {
            for (String km : new String[] {"0.37", "2.00", "7.13", "19.99", "55.55"}) {
                for (int minutes : new int[] {1, 9, 31, 77}) {
                    FareEstimate fare = calculator.calculate(category, bd(km), minutes);

                    assertThat(fare.subtotal().add(fare.tax())).isEqualByComparingTo(fare.total());
                    assertThat(fare.total().scale()).isEqualTo(2);
                    assertThat(fare.subtotal()).isGreaterThanOrEqualTo(
                            fare.baseFare().add(fare.distanceFare()).add(fare.timeFare()));
                }
            }
        }
    }

    @Test
    void floatingPointWouldHaveGotThisWrong() {
        // 0.1 + 0.2 in double is 0.30000000000000004; BigDecimal keeps it exact
        assertThat(bd("0.1").add(bd("0.2"))).isEqualByComparingTo("0.3");
        assertThat(0.1 + 0.2).isNotEqualTo(0.3);
    }
}
