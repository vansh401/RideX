package com.ridex.ridex_app.ride;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A ride announces every status change as an event when it is saved. These tests call the same method Spring
 * Data calls (domainEvents) and check what other modules would receive.
 */
class RideEventsTest {

    private final RideTestData data = new RideTestData();

    private static List<RideStatusChanged> take(Ride ride) {
        List<RideStatusChanged> events = List.copyOf(ride.domainEvents());
        ride.clearDomainEvents();   // what Spring Data does after publishing
        return events;
    }

    @Test
    void aNewRideAnnouncesThatTheSearchBegan() {
        List<RideStatusChanged> events = take(data.searching());

        // The ride is created already in REQUESTED (that first audit row is written by the constructor), so the
        // first change anyone can observe is REQUESTED to SEARCHING_DRIVER
        assertThat(events).hasSize(1);
        assertThat(events.get(0).from()).isEqualTo(RideStatus.REQUESTED);
        assertThat(events.get(0).to()).isEqualTo(RideStatus.SEARCHING_DRIVER);
        assertThat(events.get(0).rideId()).isNotNull();
        assertThat(events.get(0).riderUserId()).isEqualTo(data.rider.getId());
        assertThat(events.get(0).driverUserId()).isNull();
    }

    @Test
    void anOfferCarriesTheOfferedDriverAndWhatTheyNeedToDecide() {
        Ride ride = data.searching();
        take(ride);

        ride.offerTo(data.driver, data.vehicle, RideTestData.NOW.plusSeconds(30), RideTestData.NOW);
        List<RideStatusChanged> events = take(ride);

        assertThat(events).hasSize(1);
        RideStatusChanged offer = events.get(0);
        assertThat(offer.to()).isEqualTo(RideStatus.DRIVER_ASSIGNED);
        assertThat(offer.driverUserId()).isEqualTo(data.driverUser.getId());
        assertThat(offer.offer()).isNotNull();
        assertThat(offer.offer().pickupAddress()).isEqualTo("22 Windsor Lane");
        assertThat(offer.offer().dropoffAddress()).isEqualTo("88 Madison Avenue");
        assertThat(offer.offer().totalFare()).isEqualByComparingTo("252.00");
        assertThat(offer.offer().expiresAt()).isEqualTo(RideTestData.NOW.plusSeconds(30));
    }

    @Test
    void eachStepOfTheTripIsOneEventWithTheRightActor() {
        Ride ride = data.assigned();
        take(ride);

        ride.accept(data.driverUser.getId(), RideTestData.NOW.plusSeconds(5));
        ride.arrive(data.driverUser.getId(), RideTestData.NOW.plusSeconds(60));
        ride.start(data.driverUser.getId(), RideTestData.NOW.plusSeconds(90));
        ride.complete(data.driverUser.getId(), RideTestData.NOW.plusSeconds(900));
        List<RideStatusChanged> events = take(ride);

        assertThat(events).extracting(RideStatusChanged::to).containsExactly(
                RideStatus.DRIVER_ACCEPTED, RideStatus.DRIVER_ARRIVED, RideStatus.RIDE_STARTED, RideStatus.RIDE_COMPLETED);
        assertThat(events).extracting(RideStatusChanged::actor).containsOnly(ActorRole.DRIVER);
        assertThat(events).extracting(RideStatusChanged::driverUserId).containsOnly(data.driverUser.getId());
    }

    @Test
    void aDeclinedOfferNamesTheDriverItWasTakenFrom() {
        Ride ride = data.assigned();
        take(ride);

        ride.declineOffer(data.driverUser.getId(), ActorRole.DRIVER, "Driver declined", RideTestData.NOW.plusSeconds(3));
        List<RideStatusChanged> events = take(ride);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).from()).isEqualTo(RideStatus.DRIVER_ASSIGNED);
        assertThat(events.get(0).to()).isEqualTo(RideStatus.SEARCHING_DRIVER);
        // the driver is cleared on the ride afterwards, but the event remembers who lost the offer
        assertThat(events.get(0).driverUserId()).isEqualTo(data.driverUser.getId());
        assertThat(ride.getDriver()).isNull();
    }

    @Test
    void aCancellationRecordsWhoCancelled() {
        Ride ride = data.accepted();
        take(ride);

        ride.cancel(data.rider.getId(), ActorRole.RIDER, "Plans changed", RideTestData.NOW.plusSeconds(20));
        RideStatusChanged event = take(ride).get(0);

        assertThat(event.to()).isEqualTo(RideStatus.CANCELLED);
        assertThat(event.actor()).isEqualTo(ActorRole.RIDER);
        assertThat(event.note()).isEqualTo("Plans changed");
        assertThat(event.driverUserId()).isEqualTo(data.driverUser.getId());
    }

    @Test
    void eventsAreAnnouncedOnceNotRepeatedOnTheNextSave() {
        Ride ride = data.searching();

        assertThat(take(ride)).hasSize(1);
        assertThat(take(ride)).as("already published, so nothing left").isEmpty();
    }

    @Test
    void aRefusedTransitionAnnouncesNothing() {
        Ride ride = data.searching();
        take(ride);

        try {
            ride.complete(data.driverUser.getId(), Instant.now());   // illegal: never accepted
        } catch (RuntimeException expected) {
            // the state machine refused
        }

        assertThat(take(ride)).as("an event must never describe a change that did not happen").isEmpty();
    }
}
