package com.ridex.ridex_app.ride;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.common.ConflictException;

class RideTest {

    private final RideTestData data = new RideTestData();

    @SuppressWarnings("unchecked")
    private static List<RideStatusChange> historyOf(Ride ride) throws Exception {
        Field field = Ride.class.getDeclaredField("history");
        field.setAccessible(true);
        return (List<RideStatusChange>) field.get(ride);
    }

    @Test
    void aNewRideStartsSearchingAndRemembersTheQuote() throws Exception {
        Ride ride = data.searching();

        assertThat(ride.getStatus()).isEqualTo(RideStatus.SEARCHING_DRIVER);
        assertThat(ride.getTotalFare()).isEqualByComparingTo("252.00");
        assertThat(ride.getCurrency()).isEqualTo("INR");
        assertThat(ride.getDriver()).isNull();
        assertThat(historyOf(ride)).extracting(RideStatusChange::getToStatus)
                .containsExactly(RideStatus.REQUESTED, RideStatus.SEARCHING_DRIVER);
    }

    @Test
    void theFullHappyPathRecordsEveryStepWithWhoDidIt() throws Exception {
        Ride ride = data.completed();

        assertThat(ride.getStatus()).isEqualTo(RideStatus.RIDE_COMPLETED);
        assertThat(ride.getAcceptedAt()).isEqualTo(RideTestData.NOW.plusSeconds(5));
        assertThat(ride.getArrivedAt()).isEqualTo(RideTestData.NOW.plusSeconds(60));
        assertThat(ride.getStartedAt()).isEqualTo(RideTestData.NOW.plusSeconds(90));
        assertThat(ride.getCompletedAt()).isEqualTo(RideTestData.NOW.plusSeconds(900));

        List<RideStatusChange> history = historyOf(ride);
        assertThat(history).extracting(RideStatusChange::getToStatus).containsExactly(
                RideStatus.REQUESTED, RideStatus.SEARCHING_DRIVER, RideStatus.DRIVER_ASSIGNED,
                RideStatus.DRIVER_ACCEPTED, RideStatus.DRIVER_ARRIVED, RideStatus.RIDE_STARTED, RideStatus.RIDE_COMPLETED);
        assertThat(history).extracting(RideStatusChange::getActorRole).containsExactly(
                ActorRole.RIDER, ActorRole.SYSTEM, ActorRole.SYSTEM,
                ActorRole.DRIVER, ActorRole.DRIVER, ActorRole.DRIVER, ActorRole.DRIVER);
        // every entry after the first remembers where it came from
        assertThat(history.get(0).getFromStatus()).isNull();
        assertThat(history.get(4).getFromStatus()).isEqualTo(RideStatus.DRIVER_ACCEPTED);
    }

    @Test
    void stepsCannotBeSkipped() {
        // "complete" a ride that never started
        assertThatThrownBy(() -> data.arrived().complete(data.driverUser.getId(), Instant.now()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("DRIVER_ARRIVED")
                .hasMessageContaining("RIDE_COMPLETED");
        // start a ride the driver has not even arrived for
        assertThatThrownBy(() -> data.accepted().start(data.driverUser.getId(), Instant.now()))
                .isInstanceOf(ConflictException.class);
        // accept a ride that has not been offered
        assertThatThrownBy(() -> data.searching().accept(data.driverUser.getId(), Instant.now()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void anOfferHasADeadline() {
        Ride ride = data.assigned();   // expires at NOW + 30s

        assertThatThrownBy(() -> ride.accept(data.driverUser.getId(), RideTestData.NOW.plusSeconds(31)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("expired");
        assertThat(ride.getStatus()).isEqualTo(RideStatus.DRIVER_ASSIGNED);   // nothing changed
    }

    @Test
    void acceptingClearsTheDeadline() {
        assertThat(data.accepted().getOfferExpiresAt()).isNull();
    }

    @Test
    void decliningSendsTheRideBackToSearchingWithNoDriver() throws Exception {
        Ride ride = data.assigned();
        assertThat(ride.getDriver()).isNotNull();

        ride.declineOffer(data.driverUser.getId(), ActorRole.DRIVER, "Driver declined", RideTestData.NOW.plusSeconds(3));

        assertThat(ride.getStatus()).isEqualTo(RideStatus.SEARCHING_DRIVER);
        assertThat(ride.getDriver()).isNull();
        assertThat(ride.getVehicle()).isNull();
        assertThat(ride.getOfferExpiresAt()).isNull();
        assertThat(historyOf(ride).get(historyOf(ride).size() - 1).getNote()).isEqualTo("Driver declined");
    }

    @Test
    void aRideAlreadyAcceptedCannotBeDeclined() {
        assertThatThrownBy(() -> data.accepted().declineOffer(null, ActorRole.SYSTEM, "late", Instant.now()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void cancellingRecordsWhoAndWhy() {
        Ride ride = data.accepted();

        ride.cancel(data.rider.getId(), ActorRole.RIDER, "  Plans changed ", RideTestData.NOW.plusSeconds(20));

        assertThat(ride.getStatus()).isEqualTo(RideStatus.CANCELLED);
        assertThat(ride.getCancelledBy()).isEqualTo(ActorRole.RIDER);
        assertThat(ride.getCancelReason()).isEqualTo("Plans changed");
        assertThat(ride.getCancelledAt()).isEqualTo(RideTestData.NOW.plusSeconds(20));
    }

    @Test
    void cancellingWithoutAReasonIsAllowed() {
        Ride ride = data.searching();

        ride.cancel(data.rider.getId(), ActorRole.RIDER, null, RideTestData.NOW);

        assertThat(ride.getCancelReason()).isNull();
        assertThat(ride.getStatus()).isEqualTo(RideStatus.CANCELLED);
    }

    @Test
    void aTripInProgressCannotBeCancelled() {
        assertThatThrownBy(() -> data.started().cancel(data.rider.getId(), ActorRole.RIDER, "x", Instant.now()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("RIDE_STARTED");
    }

    @Test
    void finishedRidesAreFrozen() {
        Ride completed = data.completed();
        Ride cancelled = data.searching();
        cancelled.cancel(data.rider.getId(), ActorRole.RIDER, null, Instant.now());

        assertThatThrownBy(() -> completed.cancel(data.rider.getId(), ActorRole.RIDER, null, Instant.now()))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> cancelled.accept(data.driverUser.getId(), Instant.now()))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> cancelled.cancel(data.rider.getId(), ActorRole.RIDER, null, Instant.now()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void onlyTheRiderAndTheAssignedDriverAreParticipants() {
        Ride ride = data.assigned();

        assertThat(ride.isParticipant(data.rider.getId())).isTrue();
        assertThat(ride.isParticipant(data.driverUser.getId())).isTrue();
        assertThat(ride.isParticipant(java.util.UUID.randomUUID())).isFalse();
        // before a driver is attached, a stranger is still not a participant
        assertThat(data.searching().isParticipant(data.driverUser.getId())).isFalse();
    }
}
