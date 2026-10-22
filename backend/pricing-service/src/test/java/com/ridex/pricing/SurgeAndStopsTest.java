package com.ridex.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.ridex.platform.geo.SurgeZones;
import com.ridex.platform.web.BadRequestException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Surge in the quote (14.1) and quotes with several stops (14.8). */
class SurgeAndStopsTest {

    private final PricingProperties properties = PricingTestSupport.realRates();
    private final HaversineDistanceEstimator estimator = new HaversineDistanceEstimator(properties);
    private final FareCalculator calculator = new FareCalculator(properties);

    private static LatLng point(String lat, String lng) {
        return new LatLng(new BigDecimal(lat), new BigDecimal(lng));
    }

    private final LatLng indiranagar = point("12.971599", "77.594566");
    private final LatLng hebbal = point("13.082700", "77.587700");
    private final LatLng midway = point("13.020000", "77.600000");

    // ------------------------------------------------------------------ what is stored is never trusted

    @Test
    void whateverIsStoredTheMultiplierIsBetweenOneAndTheCapWithTwoDecimals() {
        BigDecimal cap = new BigDecimal("3.0");
        assertThat(SurgeLookup.sanitize(null, cap)).isEqualByComparingTo("1.00");
        assertThat(SurgeLookup.sanitize("", cap)).isEqualByComparingTo("1.00");
        assertThat(SurgeLookup.sanitize("garbage", cap)).isEqualByComparingTo("1.00");
        assertThat(SurgeLookup.sanitize("NaN", cap)).isEqualByComparingTo("1.00");
        assertThat(SurgeLookup.sanitize("0.5", cap)).as("never a discount").isEqualByComparingTo("1.00");
        assertThat(SurgeLookup.sanitize("-2", cap)).isEqualByComparingTo("1.00");
        assertThat(SurgeLookup.sanitize("1.5", cap)).isEqualByComparingTo("1.50");
        assertThat(SurgeLookup.sanitize("1.234", cap)).isEqualByComparingTo("1.23");
        assertThat(SurgeLookup.sanitize("9", cap)).as("never above the cap").isEqualByComparingTo("3.00");
    }

    @Test
    void theMultiplierOfTheCellOfThePickupPointIsRead() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        String key = SurgeZones.keyOf(SurgeZones.cellOf(12.971599, 77.594566));
        when(ops.get(key)).thenReturn("1.80");
        SurgeLookup lookup = new SurgeLookup(redis, new BigDecimal("3.0"), new SimpleMeterRegistry());

        assertThat(lookup.multiplierFor(indiranagar)).isEqualByComparingTo("1.80");
        assertThat(lookup.multiplierFor(hebbal)).as("another zone has no surge").isEqualByComparingTo("1.00");
    }

    @Test
    void whenRedisFailsThePriceHasNoSurgeAndTheFailureIsCounted() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        SurgeLookup lookup = new SurgeLookup(redis, new BigDecimal("3.0"), metrics);

        assertThat(lookup.multiplierFor(indiranagar)).isEqualByComparingTo("1.00");
        assertThat(metrics.get("ridex.surge.lookup.failures").counter().count()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------ surge in the fare

    private PricingService withSurge(String multiplier) {
        SurgeLookup lookup = mock(SurgeLookup.class);
        when(lookup.multiplierFor(indiranagar)).thenReturn(new BigDecimal(multiplier));
        return new PricingService(estimator, calculator, lookup);
    }

    @Test
    void surgeMultipliesTheSubtotalAndTheTaxFollows() {
        FareEstimate normal = new PricingService(estimator, calculator).estimate(indiranagar, hebbal, VehicleCategory.CAB);
        FareEstimate surged = withSurge("1.50").estimate(indiranagar, hebbal, VehicleCategory.CAB);

        assertThat(surged.surgeMultiplier()).isEqualByComparingTo("1.50");
        assertThat(surged.subtotal()).isEqualByComparingTo(normal.subtotal().multiply(new BigDecimal("1.5")).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(surged.total()).isEqualByComparingTo(surged.subtotal().add(surged.tax()));
        assertThat(surged.baseFare()).as("the breakdown parts are the same: only the multiplier changes").isEqualByComparingTo(normal.baseFare());
    }

    @Test
    void aRideBookedAheadIgnoresSurgeSoItsPriceIsFixedAtBooking() {
        FareEstimate scheduled = withSurge("2.50").estimate(indiranagar, hebbal, VehicleCategory.CAB, List.of(), true);

        assertThat(scheduled.surgeMultiplier()).isEqualByComparingTo("1.00");
    }

    // ------------------------------------------------------------------ stops

    @Test
    void theLegsOfARouteWithStopsAddUpAndEachStopAddsWaitingTime() {
        PricingService service = new PricingService(estimator, calculator);
        FareEstimate direct = service.estimate(indiranagar, hebbal, VehicleCategory.CAB);
        FareEstimate withStop = service.estimate(indiranagar, hebbal, VehicleCategory.CAB, List.of(midway), false);

        DistanceEstimator.TripEstimate leg1 = estimator.estimate(indiranagar, midway, VehicleCategory.CAB);
        DistanceEstimator.TripEstimate leg2 = estimator.estimate(midway, hebbal, VehicleCategory.CAB);
        assertThat(withStop.distanceKm()).isEqualByComparingTo(leg1.distanceKm().add(leg2.distanceKm()));
        assertThat(withStop.durationMinutes()).isEqualTo(leg1.durationMinutes() + leg2.durationMinutes() + PricingService.MINUTES_PER_STOP);
        assertThat(withStop.total()).isGreaterThanOrEqualTo(direct.total());
    }

    @Test
    void atMostThreeStopsAreAllowed() {
        PricingService service = new PricingService(estimator, calculator);

        assertThat(service.estimate(indiranagar, hebbal, VehicleCategory.AUTO, List.of(midway, midway, midway), false)).isNotNull();
        assertThatThrownBy(() -> service.estimate(indiranagar, hebbal, VehicleCategory.AUTO, List.of(midway, midway, midway, midway), false))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("at most 3 stops");
    }

    @Test
    void theZonesOfTwoPointsInTheSameBlockAreTheSame() {
        assertThat(SurgeZones.cellOf(12.9716, 77.5946)).isEqualTo(SurgeZones.cellOf(12.9790, 77.5990));
        assertThat(SurgeZones.cellOf(12.9716, 77.5946)).isNotEqualTo(SurgeZones.cellOf(13.0827, 77.5877));
        double[] center = SurgeZones.centerOf(SurgeZones.cellOf(12.9716, 77.5946));
        assertThat(SurgeZones.cellOf(center[0], center[1])).isEqualTo(SurgeZones.cellOf(12.9716, 77.5946));
    }
}
