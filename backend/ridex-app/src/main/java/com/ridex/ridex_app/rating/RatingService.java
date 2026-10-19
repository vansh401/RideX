package com.ridex.ridex_app.rating;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.driver.DriverProfileService;
import com.ridex.ridex_app.rider.RiderProfileService;
import com.ridex.ridex_app.ride.ActorRole;
import com.ridex.ridex_app.ride.Ride;
import com.ridex.ridex_app.ride.RideRatingLookup;
import com.ridex.ridex_app.ride.RideRepository;
import com.ridex.ridex_app.ride.RideStatus;

@Service
public class RatingService implements RideRatingLookup {

    private final RatingRepository ratingRepository;
    private final RideRepository rideRepository;
    private final DriverProfileService driverProfileService;
    private final RiderProfileService riderProfileService;

    public RatingService(RatingRepository ratingRepository,
                         RideRepository rideRepository,
                         DriverProfileService driverProfileService,
                         RiderProfileService riderProfileService) {
        this.ratingRepository = ratingRepository;
        this.rideRepository = rideRepository;
        this.driverProfileService = driverProfileService;
        this.riderProfileService = riderProfileService;
    }

    /**
     * The rider rates the driver, or the driver rates the rider. Rules:
     *  - you must have taken part in the ride (anyone else gets 404, as if it did not exist);
     *  - the ride must be completed;
     *  - once per person per ride (a friendly check here, and a UNIQUE constraint in the database
     *    as the real guarantee when two requests race).
     */
    @Transactional
    public RatingResponse rate(UUID userId, UUID rideId, int stars, String comment) {
        Ride ride = rideRepository.findDetailedById(rideId)
                .filter(found -> found.isParticipant(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found"));
        if (ride.getStatus() != RideStatus.RIDE_COMPLETED) {
            throw new ConflictException("You can rate a ride only after it is completed");
        }
        if (ratingRepository.existsByRideIdAndRaterId(rideId, userId)) {
            throw new ConflictException("You have already rated this ride");
        }

        boolean riderIsRating = ride.getRider().getId().equals(userId);
        UUID rateeId = riderIsRating ? ride.getDriver().getUser().getId() : ride.getRider().getId();
        ActorRole role = riderIsRating ? ActorRole.RIDER : ActorRole.DRIVER;

        Rating saved = ratingRepository.saveAndFlush(new Rating(rideId, userId, rateeId, role, stars, comment, Instant.now()));

        // Keep the running average up to date (atomic increments)
        if (riderIsRating) {
            driverProfileService.recordRating(ride.getDriver().getId(), stars);
        } else {
            riderProfileService.recordRating(ride.getRider().getId(), stars);
        }
        return RatingResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> starsGivenBy(UUID raterUserId, Collection<UUID> rideIds) {
        if (rideIds.isEmpty()) {
            return Map.of();
        }
        return ratingRepository.findByRaterIdAndRideIdIn(raterUserId, rideIds).stream()
                .collect(Collectors.toMap(Rating::getRideId, Rating::getStars));
    }

    /** Both ratings of a ride, for the people who took part in it. */
    @Transactional(readOnly = true)
    public List<RatingResponse> ofRide(UUID userId, UUID rideId) {
        rideRepository.findById(rideId)
                .filter(ride -> ride.isParticipant(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found"));
        return ratingRepository.findByRideId(rideId).stream().map(RatingResponse::from).toList();
    }
}
