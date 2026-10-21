package com.ridex.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ridex.platform.web.BadRequestException;
import com.ridex.pricing.LatLng;
import com.ridex.pricing.VehicleCategory;

class PricingServiceTest {

    private final PricingProperties properties = PricingTestSupport.realRates();
    private final PricingService service =
            new PricingService(new HaversineDistanceEstimator(properties), new FareCalculator(properties));

    private static LatLng point(String lat, String lng) {
        return new LatLng(new BigDecimal(lat), new BigDecimal(lng));
    }

    // Two real places in Bengaluru, roughly 12.4 km apart in a straight line
    private final LatLng indiranagar = point("12.971599", "77.594566");
    private final LatLng hebbal = point("13.082700", "77.587700");

    @Test
    void distanceIsStraightLineTimesTheRoadFactor() {
        double straight = indiranagar.distanceKmTo(hebbal);
        assertThat(straight).isBetween(12.2, 12.6);

        DistanceEstimator.TripEstimate trip =
                new HaversineDistanceEstimator(properties).estimate(indiranagar, hebbal, VehicleCategory.CAB);

        assertThat(trip.distanceKm().doubleValue()).isBetween(15.9, 16.3);   // x 1.30
        assertThat(trip.durationMinutes()).isBetween(38, 43);                  // at 24 km/h, rounded up
    }

    @Test
    void theDistanceBetweenTwoIdenticalPointsIsZero() {
        assertThat(indiranagar.distanceKmTo(indiranagar)).isZero();
    }

    @Test
    void estimateAllQuotesEveryCategoryAndBikesAreCheapest() {
        List<FareEstimate> options = service.estimateAll(indiranagar, hebbal);

        assertThat(options).extracting(FareEstimate::category)
                .containsExactly(VehicleCategory.BIKE, VehicleCategory.AUTO, VehicleCategory.CAB);
        assertThat(options.get(0).total()).isLessThan(options.get(1).total());
        assertThat(options.get(1).total()).isLessThan(options.get(2).total());
    }

    @Test
    void pickupAndDestinationTooCloseIsRejected() {
        assertThatThrownBy(() -> service.estimate(indiranagar, indiranagar, VehicleCategory.CAB))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("too close");
    }

    @Test
    void tripsOverOneHundredKilometresAreRejected() {
        LatLng delhi = point("28.613900", "77.209000");

        assertThatThrownBy(() -> service.estimate(indiranagar, delhi, VehicleCategory.CAB))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("100 km");
    }
}
