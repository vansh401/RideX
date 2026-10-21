package com.ridex.ridex_app.ride;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.ForbiddenException;
import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.driver.DriverProfileService;
import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.pricing.PricingClient;
import com.ridex.ridex_app.saga.RiderPaymentBlocks;
import com.ridex.ridex_app.user.UserService;

class RideServiceTest {

    private final RideRepository rideRepository = mock(RideRepository.class);
    private final RideStatusChangeRepository historyRepository = mock(RideStatusChangeRepository.class);
    private final RideDispatcher dispatcher = mock(RideDispatcher.class);
    private final PricingClient pricingClient = mock(PricingClient.class);
    private final RiderPaymentBlocks paymentBlocks = mock(RiderPaymentBlocks.class);
    private final UserService userService = mock(UserService.class);
    private final DriverProfileService driverProfileService = mock(DriverProfileService.class);
    private final RideRatingLookup ratingLookup = mock(RideRatingLookup.class);
    private final RideService service = new RideService(rideRepository, historyRepository, dispatcher,
            pricingClient, userService, driverProfileService, ratingLookup,
            mock(com.ridex.ridex_app.redis.DriverLocationIndex.class), paymentBlocks);

    private final RideTestData data = new RideTestData();
    private final UUID riderId = data.rider.getId();
    private final UUID driverUserId = data.driverUser.getId();

    private RideRequest request() {
        return new RideRequest(VehicleCategory.CAB,
                new PlaceInput("22 Windsor Lane", RideTestData.point("12.971599", "77.594566")),
                new PlaceInput("88 Madison Avenue", RideTestData.point("12.935200", "77.624500")));
    }

    private Ride exists(Ride ride) {
        when(rideRepository.findDetailedById(ride.getId())).thenReturn(Optional.of(ride));
        return ride;
    }

    // ---------- request ----------

    @Test
    void requestingARideQuotesSavesAndLooksForADriver() {
        when(userService.getActiveUser(riderId)).thenReturn(data.rider);
        when(pricingClient.quote(any(), any(), eq(VehicleCategory.CAB))).thenReturn(RideTestData.fare());

        RideResponse response = service.request(riderId, request());

        assertThat(response.status()).isEqualTo(RideStatus.SEARCHING_DRIVER);
        assertThat(response.fare().total()).isEqualByComparingTo("252.00");
        verify(rideRepository).saveAndFlush(any(Ride.class));
        verify(dispatcher).tryAssign(any(Ride.class), any());
    }

    @Test
    void aRiderWithARideInProgressCannotRequestAnother() {
        when(userService.getActiveUser(riderId)).thenReturn(data.rider);
        when(rideRepository.findFirstByRiderIdAndStatusIn(eq(riderId), any())).thenReturn(Optional.of(data.searching()));

        assertThatThrownBy(() -> service.request(riderId, request()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already have a ride");
        verify(rideRepository, never()).saveAndFlush(any());
    }

    @Test
    void aRiderWhoOwesForAnEarlierRideCannotBookAgainUntilItIsPaid() {
        UUID unpaidRide = UUID.randomUUID();
        when(userService.getActiveUser(riderId)).thenReturn(data.rider);
        when(paymentBlocks.firstUnpaidRide(riderId)).thenReturn(Optional.of(unpaidRide));

        assertThatThrownBy(() -> service.request(riderId, request()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(unpaidRide.toString())
                .hasMessageContaining("settle");
        verify(pricingClient, never()).quote(any(), any(), any());
        verify(rideRepository, never()).saveAndFlush(any());
    }

    @Test
    void whenPricingIsDownNoRideIsCreated() {
        when(userService.getActiveUser(riderId)).thenReturn(data.rider);
        when(pricingClient.quote(any(), any(), any()))
                .thenThrow(new com.ridex.platform.web.ServiceUnavailableException("Pricing is temporarily unavailable", 10));

        assertThatThrownBy(() -> service.request(riderId, request()))
                .isInstanceOf(com.ridex.platform.web.ServiceUnavailableException.class);
        verify(rideRepository, never()).saveAndFlush(any());
        verify(dispatcher, never()).tryAssign(any(), any());
    }

    @Test
    void aSuspendedRiderCannotRequest() {
        when(userService.getActiveUser(riderId)).thenThrow(new ForbiddenException("This account is not active"));

        assertThatThrownBy(() -> service.request(riderId, request())).isInstanceOf(ForbiddenException.class);
        verify(pricingClient, never()).quote(any(), any(), any());
    }

    // ---------- who may see a ride ----------

    @Test
    void theRiderTheAssignedDriverAndAnAdminMaySeeARide() {
        Ride ride = exists(data.accepted());

        assertThat(service.get(riderId, ActorRole.RIDER, ride.getId()).id()).isEqualTo(ride.getId());
        assertThat(service.get(driverUserId, ActorRole.DRIVER, ride.getId()).id()).isEqualTo(ride.getId());
        assertThat(service.get(UUID.randomUUID(), ActorRole.ADMIN, ride.getId()).id()).isEqualTo(ride.getId());
    }

    @Test
    void anyoneElseGetsNotFoundAsIfTheRideDidNotExist() {
        Ride ride = exists(data.accepted());

        assertThatThrownBy(() -> service.get(UUID.randomUUID(), ActorRole.RIDER, ride.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), ActorRole.DRIVER, ride.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.timeline(UUID.randomUUID(), ActorRole.RIDER, ride.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aDriverNotAssignedToTheRideCannotSeeItEvenIfTheyAreADriver() {
        Ride ride = exists(data.searching());   // no driver yet

        assertThatThrownBy(() -> service.get(driverUserId, ActorRole.DRIVER, ride.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void phoneNumbersAreSharedOnlyOnceTheRideIsAccepted() {
        Ride offered = exists(data.assigned());
        assertThat(service.get(riderId, ActorRole.RIDER, offered.getId()).driver().phone()).isNull();
        assertThat(service.get(driverUserId, ActorRole.DRIVER, offered.getId()).rider().phone()).isNull();

        RideTestData other = new RideTestData();
        Ride accepted = exists(other.accepted());
        assertThat(service.get(other.rider.getId(), ActorRole.RIDER, accepted.getId()).driver().phone()).isEqualTo("+919812345678");
        assertThat(service.get(other.driverUser.getId(), ActorRole.DRIVER, accepted.getId()).rider().phone()).isEqualTo("+919876543210");
    }

    // ---------- driver actions ----------

    @Test
    void theAssignedDriverCanAcceptAndWalkTheTripToTheEnd() {
        Ride ride = exists(data.assigned());

        assertThat(service.accept(driverUserId, ride.getId()).status()).isEqualTo(RideStatus.DRIVER_ACCEPTED);
        assertThat(service.arrived(driverUserId, ride.getId()).status()).isEqualTo(RideStatus.DRIVER_ARRIVED);
        assertThat(service.start(driverUserId, ride.getId()).status()).isEqualTo(RideStatus.RIDE_STARTED);
        assertThat(service.complete(driverUserId, ride.getId()).status()).isEqualTo(RideStatus.RIDE_COMPLETED);
    }

    @Test
    void anotherDriverCannotAcceptSomeoneElsesOffer() {
        Ride ride = exists(data.assigned());

        assertThatThrownBy(() -> service.accept(UUID.randomUUID(), ride.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(ride.getStatus()).isEqualTo(RideStatus.DRIVER_ASSIGNED);
    }

    @Test
    void aSuspendedDriverCannotAccept() {
        Ride ride = exists(data.assigned());
        when(userService.getActiveUser(driverUserId)).thenThrow(new ForbiddenException("This account is not active"));

        assertThatThrownBy(() -> service.accept(driverUserId, ride.getId())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void decliningHandsTheRideToTheDispatcher() {
        Ride ride = exists(data.assigned());

        service.reject(driverUserId, ride.getId());

        verify(dispatcher).declineAndReassign(eq(ride), eq(RejectionReason.REJECTED), eq(driverUserId),
                eq(ActorRole.DRIVER), any(), any());
    }

    // ---------- cancel ----------

    @Test
    void aRiderCanCancelBeforeTheTripStarts() {
        Ride ride = exists(data.arrived());

        RideResponse response = service.cancel(riderId, ActorRole.RIDER, ride.getId(), "Changed my mind");

        assertThat(response.status()).isEqualTo(RideStatus.CANCELLED);
        assertThat(response.cancelledBy()).isEqualTo(ActorRole.RIDER);
        assertThat(response.cancelReason()).isEqualTo("Changed my mind");
    }

    @Test
    void aRiderCannotCancelATripInProgress() {
        Ride ride = exists(data.started());

        assertThatThrownBy(() -> service.cancel(riderId, ActorRole.RIDER, ride.getId(), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aDriverMustDeclineAnOfferInsteadOfCancellingIt() {
        Ride ride = exists(data.assigned());

        assertThatThrownBy(() -> service.cancel(driverUserId, ActorRole.DRIVER, ride.getId(), "no"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("decline");
    }

    @Test
    void aDriverCanCancelAfterAcceptingButBeforeStarting() {
        Ride accepted = exists(data.accepted());
        assertThat(service.cancel(driverUserId, ActorRole.DRIVER, accepted.getId(), "Car trouble").status())
                .isEqualTo(RideStatus.CANCELLED);

        RideTestData other = new RideTestData();
        Ride arrived = exists(other.arrived());
        assertThat(service.cancel(other.driverUser.getId(), ActorRole.DRIVER, arrived.getId(), null).cancelledBy())
                .isEqualTo(ActorRole.DRIVER);
    }

    @Test
    void aStrangerCannotCancel() {
        Ride ride = exists(data.accepted());

        assertThatThrownBy(() -> service.cancel(UUID.randomUUID(), ActorRole.RIDER, ride.getId(), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- history ----------

    @Test
    void aRidersHistoryIsNewestFirstAndKnowsWhichRidesTheyRated() {
        Ride ride = data.completed();
        when(rideRepository.findByRiderId(eq(riderId), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(ride)));
        when(ratingLookup.starsGivenBy(eq(riderId), any())).thenReturn(Map.of(ride.getId(), 5));

        PageResponse<RideResponse> page = service.history(riderId, ActorRole.RIDER, null, -2, 100000);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).myRating()).isEqualTo(5);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(rideRepository).findByRiderId(eq(riderId), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(pageable.getValue().getSort().getOrderFor("requestedAt").isDescending()).isTrue();
    }

    @Test
    void aStatusFilterUsesTheMatchingQuery() {
        when(rideRepository.findByRiderIdAndStatus(eq(riderId), eq(RideStatus.CANCELLED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.history(riderId, ActorRole.RIDER, RideStatus.CANCELLED, 0, 20);

        verify(rideRepository).findByRiderIdAndStatus(eq(riderId), eq(RideStatus.CANCELLED), any(Pageable.class));
    }

    @Test
    void aDriversHistoryIsLookedUpByTheirDriverProfile() {
        UUID profileId = UUID.randomUUID();
        when(driverProfileService.requireProfileId(driverUserId)).thenReturn(profileId);
        when(rideRepository.findByDriverId(eq(profileId), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.history(driverUserId, ActorRole.DRIVER, null, 0, 20);

        verify(rideRepository).findByDriverId(eq(profileId), any(Pageable.class));
        verify(rideRepository, never()).findByRiderId(any(), any());
    }

    @Test
    void currentReturnsNothingWhenThereIsNoRideInProgress() {
        when(rideRepository.findFirstByRiderIdAndStatusIn(eq(riderId), any())).thenReturn(Optional.empty());

        assertThat(service.current(riderId, ActorRole.RIDER)).isEmpty();
    }
}
