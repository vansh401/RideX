package com.ridex.ridex_app.ride.sharing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.ride.sharing.SharedRideMatcher.DropOrder;
import com.ridex.ridex_app.ride.sharing.SharedRideMatcher.Match;
import com.ridex.ridex_app.ride.sharing.SharedRideMatcher.Point;
import com.ridex.ridex_app.ride.sharing.SharedRideMatcher.Trip;

/** Route compatibility for ride sharing: on a straight road north, so the expected geometry is easy to reason about. */
class SharedRideMatcherTest {

    /** A point this many km north of 12.90, 77.60 (one degree of latitude is about 111.2 km). */
    private static Point north(double km) {
        return new Point(12.90 + km / 111.2, 77.60);
    }

    private static Point northEast(double northKm, double eastKm) {
        return new Point(12.90 + northKm / 111.2, 77.60 + eastKm / 108.4);
    }

    private static Trip trip(Point from, Point to) {
        return new Trip(from, to);
    }

    @Test
    void aSecondRiderOnTheSameRoadMatchesAndTheCarDropsThemInRoadOrder() {
        Trip first = trip(north(0), north(10));
        Trip second = trip(north(1), north(6));   // joins 1 km up the road and leaves before the first rider

        Match match = SharedRideMatcher.evaluate(first, second, 2);

        assertThat(match.compatible()).isTrue();
        assertThat(match.order()).as("B's stop is on the way: B is dropped first").isEqualTo(DropOrder.SECOND_RIDER_DROPPED_FIRST);
        assertThat(match.firstRiderDetour()).as("the first rider loses nothing on a straight road").isBetween(0.99, 1.01);
        assertThat(match.secondRiderDetour()).isBetween(0.99, 1.01);
    }

    @Test
    void aRiderWhoWantsToGoFurtherThanTheFirstIsDroppedSecond() {
        Match match = SharedRideMatcher.evaluate(trip(north(0), north(8)), trip(north(1), north(12)), 1);

        assertThat(match.compatible()).isTrue();
        assertThat(match.order()).isEqualTo(DropOrder.FIRST_RIDER_DROPPED_FIRST);
    }

    @Test
    void aPickupTooFarFromTheFirstIsRefused() {
        Match match = SharedRideMatcher.evaluate(trip(north(0), north(10)), trip(north(3), north(9)), 2);

        assertThat(match.compatible()).isFalse();
        assertThat(match.reason()).contains("too far");
    }

    @Test
    void aRiderGoingTheOppositeWayWouldDelayTheFirstTooMuch() {
        Match match = SharedRideMatcher.evaluate(trip(north(0), north(10)), trip(north(1), north(-8)), 2);

        assertThat(match.compatible()).isFalse();
        assertThat(match.reason()).as("one of the two riders would be delayed far too much, whichever order is used").contains("too much");
    }

    @Test
    void aSidewaysDetourForTheFirstRiderIsLimited() {
        // B is dropped 8 km to the side and close by: the shortest route takes the car there first, and A is delayed to twice the direct trip
        Match match = SharedRideMatcher.evaluate(trip(north(0), north(10)), trip(north(1), northEast(2, 8)), 2);

        assertThat(match.compatible()).isFalse();
        assertThat(match.firstRiderDetour()).isGreaterThan(SharedRideMatcher.MAX_DETOUR_FIRST);
    }

    @Test
    void aFullCarAndTinyTripsAreRefusedBeforeAnyGeometry() {
        assertThat(SharedRideMatcher.evaluate(trip(north(0), north(10)), trip(north(1), north(6)), 0).reason()).contains("full");
        assertThat(SharedRideMatcher.evaluate(trip(north(0), north(0.1)), trip(north(0), north(6)), 2).reason()).contains("short");
    }

    @Test
    void theSharedFareIsTheSoloFareMinusTheDiscountRoundedToTheCent() {
        assertThat(SharedRideMatcher.sharedFare(new BigDecimal("252.00"), 25)).isEqualByComparingTo("189.00");
        assertThat(SharedRideMatcher.sharedFare(new BigDecimal("99.99"), 25)).isEqualByComparingTo("74.99");
        assertThat(SharedRideMatcher.sharedFare(new BigDecimal("100.00"), 0)).isEqualByComparingTo("100.00");
    }

    @Test
    void forTheDriverSharingEarnsMoreThanASoloTripOnTheSameRoad() {
        BigDecimal solo = new BigDecimal("250.00");
        BigDecimal sharedTotal = SharedRideMatcher.sharedFare(solo, 25).add(SharedRideMatcher.sharedFare(new BigDecimal("150.00"), 25));

        assertThat(sharedTotal).as("two riders at 25 % off still bring in more than the long rider alone").isGreaterThan(solo);
    }
}
