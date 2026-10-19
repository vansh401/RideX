package com.ridex.ridex_app.ride;

import static org.assertj.core.api.Assertions.assertThat;
import static com.ridex.ridex_app.ride.RideStatus.CANCELLED;
import static com.ridex.ridex_app.ride.RideStatus.DRIVER_ACCEPTED;
import static com.ridex.ridex_app.ride.RideStatus.DRIVER_ARRIVED;
import static com.ridex.ridex_app.ride.RideStatus.DRIVER_ASSIGNED;
import static com.ridex.ridex_app.ride.RideStatus.REQUESTED;
import static com.ridex.ridex_app.ride.RideStatus.RIDE_COMPLETED;
import static com.ridex.ridex_app.ride.RideStatus.RIDE_STARTED;
import static com.ridex.ridex_app.ride.RideStatus.SEARCHING_DRIVER;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The state machine, checked EXHAUSTIVELY: all 8 x 8 = 64 possible moves are listed in one table
 * below, and every single one is verified. If someone edits the rules, this test shows exactly
 * which move changed.
 */
class RideStatusTest {

    private static final Map<RideStatus, Set<RideStatus>> EXPECTED = Map.of(
            REQUESTED, EnumSet.of(SEARCHING_DRIVER, CANCELLED),
            SEARCHING_DRIVER, EnumSet.of(DRIVER_ASSIGNED, CANCELLED),
            DRIVER_ASSIGNED, EnumSet.of(DRIVER_ACCEPTED, SEARCHING_DRIVER, CANCELLED),
            DRIVER_ACCEPTED, EnumSet.of(DRIVER_ARRIVED, CANCELLED),
            DRIVER_ARRIVED, EnumSet.of(RIDE_STARTED, CANCELLED),
            RIDE_STARTED, EnumSet.of(RIDE_COMPLETED),
            RIDE_COMPLETED, EnumSet.noneOf(RideStatus.class),
            CANCELLED, EnumSet.noneOf(RideStatus.class));

    @Test
    void allSixtyFourMovesMatchTheTable() {
        for (RideStatus from : RideStatus.values()) {
            for (RideStatus to : RideStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(EXPECTED.get(from).contains(to));
            }
        }
    }

    @Test
    void nothingCanLeaveAFinishedRide() {
        for (RideStatus target : RideStatus.values()) {
            assertThat(RIDE_COMPLETED.canTransitionTo(target)).isFalse();
            assertThat(CANCELLED.canTransitionTo(target)).isFalse();
        }
        assertThat(RIDE_COMPLETED.allowedNext()).isEmpty();
    }

    @Test
    void aRideCanBeCancelledUntilTheTripStartsAndNeverAfter() {
        for (RideStatus before : EnumSet.of(REQUESTED, SEARCHING_DRIVER, DRIVER_ASSIGNED, DRIVER_ACCEPTED, DRIVER_ARRIVED)) {
            assertThat(before.canTransitionTo(CANCELLED)).as("cancel from %s", before).isTrue();
        }
        assertThat(RIDE_STARTED.canTransitionTo(CANCELLED)).isFalse();
    }

    @Test
    void thereIsExactlyOneWayThroughTheHappyPath() {
        RideStatus[] path = {REQUESTED, SEARCHING_DRIVER, DRIVER_ASSIGNED, DRIVER_ACCEPTED, DRIVER_ARRIVED, RIDE_STARTED, RIDE_COMPLETED};
        for (int i = 0; i < path.length - 1; i++) {
            assertThat(path[i].canTransitionTo(path[i + 1])).isTrue();
        }
        // and no step can be skipped
        assertThat(REQUESTED.canTransitionTo(DRIVER_ACCEPTED)).isFalse();
        assertThat(DRIVER_ACCEPTED.canTransitionTo(RIDE_STARTED)).isFalse();
        assertThat(DRIVER_ARRIVED.canTransitionTo(RIDE_COMPLETED)).isFalse();
    }

    @Test
    void anOfferThatIsDeclinedGoesBackToSearching() {
        assertThat(DRIVER_ASSIGNED.canTransitionTo(SEARCHING_DRIVER)).isTrue();
        assertThat(DRIVER_ACCEPTED.canTransitionTo(SEARCHING_DRIVER)).isFalse();
    }

    @Test
    void activeAndDriverOccupyingStatuses() {
        assertThat(RideStatus.activeStatuses())
                .containsExactlyInAnyOrder(REQUESTED, SEARCHING_DRIVER, DRIVER_ASSIGNED, DRIVER_ACCEPTED, DRIVER_ARRIVED, RIDE_STARTED);
        assertThat(RIDE_COMPLETED.isActive()).isFalse();
        assertThat(CANCELLED.isTerminal()).isTrue();

        assertThat(SEARCHING_DRIVER.occupiesDriver()).isFalse();
        assertThat(DRIVER_ASSIGNED.occupiesDriver()).isTrue();
        assertThat(RIDE_STARTED.occupiesDriver()).isTrue();
        assertThat(RIDE_COMPLETED.occupiesDriver()).isFalse();
    }
}
