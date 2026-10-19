package com.ridex.ridex_app.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.driver.DriverProfileService;
import com.ridex.ridex_app.rider.RiderProfileService;
import com.ridex.ridex_app.ride.ActorRole;
import com.ridex.ridex_app.ride.Ride;
import com.ridex.ridex_app.ride.RideRepository;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

class RatingServiceTest {

    private final RatingRepository ratingRepository = mock(RatingRepository.class);
    private final RideRepository rideRepository = mock(RideRepository.class);
    private final DriverProfileService driverProfileService = mock(DriverProfileService.class);
    private final RiderProfileService riderProfileService = mock(RiderProfileService.class);
    private final RatingService service =
            new RatingService(ratingRepository, rideRepository, driverProfileService, riderProfileService);

    private final com.ridex.ridex_app.ride.RideTestDataAccess data = new com.ridex.ridex_app.ride.RideTestDataAccess();

    private Ride rideAt(Ride ride) {
        when(rideRepository.findDetailedById(ride.getId())).thenReturn(Optional.of(ride));
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(i -> i.getArgument(0));
        return ride;
    }

    @Test
    void aRiderRatesTheDriverAndTheDriversAverageIsUpdated() {
        Ride ride = rideAt(data.completed());

        RatingResponse response = service.rate(data.riderId(), ride.getId(), 5, "  Great ride ");

        assertThat(response.stars()).isEqualTo(5);
        assertThat(response.comment()).isEqualTo("Great ride");
        assertThat(response.raterRole()).isEqualTo(ActorRole.RIDER);
        verify(driverProfileService).recordRating(ride.getDriver().getId(), 5);
        verify(riderProfileService, never()).recordRating(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aDriverRatesTheRiderAndTheRidersAverageIsUpdated() {
        Ride ride = rideAt(data.completed());

        RatingResponse response = service.rate(data.driverUserId(), ride.getId(), 4, null);

        assertThat(response.raterRole()).isEqualTo(ActorRole.DRIVER);
        assertThat(response.comment()).isNull();
        verify(riderProfileService).recordRating(data.riderId(), 4);
        verify(driverProfileService, never()).recordRating(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void onlyACompletedRideCanBeRated() {
        for (Ride unfinished : List.of(data.searching(), data.accepted(), data.started())) {
            rideAt(unfinished);
            assertThatThrownBy(() -> service.rate(data.riderId(), unfinished.getId(), 5, null))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("completed");
        }
        verify(ratingRepository, never()).saveAndFlush(any());
    }

    @Test
    void aPersonCanRateARideOnlyOnce() {
        Ride ride = rideAt(data.completed());
        when(ratingRepository.existsByRideIdAndRaterId(ride.getId(), data.riderId())).thenReturn(true);

        assertThatThrownBy(() -> service.rate(data.riderId(), ride.getId(), 3, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already rated");
        verify(driverProfileService, never()).recordRating(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aStrangerCannotRateAndSeesNotFound() {
        Ride ride = rideAt(data.completed());

        assertThatThrownBy(() -> service.rate(UUID.randomUUID(), ride.getId(), 5, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void starsGivenByIsOneQueryForAWholePage() {
        UUID rideId = UUID.randomUUID();
        UUID raterId = UUID.randomUUID();
        Rating rating = new Rating(rideId, raterId, UUID.randomUUID(), ActorRole.RIDER, 4, null, java.time.Instant.now());
        when(ratingRepository.findByRaterIdAndRideIdIn(raterId, List.of(rideId))).thenReturn(List.of(rating));

        assertThat(service.starsGivenBy(raterId, List.of(rideId))).isEqualTo(Map.of(rideId, 4));
        assertThat(service.starsGivenBy(raterId, List.of())).isEmpty();
    }

    @SuppressWarnings("unused")
    private static User unused() {
        return new User("x", "x@example.com", "+910000000000", "h", Role.RIDER);
    }
}
