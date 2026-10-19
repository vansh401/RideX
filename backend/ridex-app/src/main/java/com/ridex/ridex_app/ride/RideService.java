package com.ridex.ridex_app.ride;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.common.Paging;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.driver.DriverProfileService;
import com.ridex.ridex_app.pricing.FareEstimate;
import com.ridex.ridex_app.pricing.PricingService;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserService;

/**
 * What riders and drivers do with rides: request, accept, decline, arrive, start, complete,
 * cancel, and read. The rules of WHAT is allowed live in the Ride entity (state machine); this
 * class decides WHO may do WHAT, loads the right ride, and saves the outcome.
 *
 * "Who" is always taken from the verified token, never from the request: a driver can only act on
 * a ride that is assigned to them, a rider only on their own. Anyone else gets 404, exactly as if
 * the ride did not exist, so ride ids cannot be probed.
 */
@Service
public class RideService {

    private static final Set<RideStatus> ACTIVE = RideStatus.activeStatuses();

    private final RideRepository rideRepository;
    private final RideStatusChangeRepository historyRepository;
    private final RideDispatcher dispatcher;
    private final PricingService pricingService;
    private final UserService userService;
    private final DriverProfileService driverProfileService;
    private final RideRatingLookup ratingLookup;

    public RideService(RideRepository rideRepository,
                       RideStatusChangeRepository historyRepository,
                       RideDispatcher dispatcher,
                       PricingService pricingService,
                       UserService userService,
                       DriverProfileService driverProfileService,
                       RideRatingLookup ratingLookup) {
        this.rideRepository = rideRepository;
        this.historyRepository = historyRepository;
        this.dispatcher = dispatcher;
        this.pricingService = pricingService;
        this.userService = userService;
        this.driverProfileService = driverProfileService;
        this.ratingLookup = ratingLookup;
    }

    // ------------------------------------------------------------------ rider: request

    /**
     * Quote, create the ride, and immediately look for a driver, all in ONE transaction. If anything
     * fails, nothing is saved: there is never a ride without its audit trail, or a half-made one.
     */
    @Transactional
    public RideResponse request(UUID riderId, RideRequest request) {
        User rider = userService.getActiveUser(riderId);
        if (rideRepository.findFirstByRiderIdAndStatusIn(riderId, ACTIVE).isPresent()) {
            throw new ConflictException("You already have a ride in progress");
        }

        FareEstimate fare = pricingService.estimate(
                request.pickup().location(), request.dropoff().location(), request.category());
        Instant now = Instant.now();
        Ride ride = Ride.request(rider, request.category(),
                request.pickup().address(), request.pickup().location(),
                request.dropoff().address(), request.dropoff().location(), fare, now);

        // Flush now: the "one active ride per rider" unique index is checked here, inside this method,
        // so two requests racing each other cannot both get through
        rideRepository.saveAndFlush(ride);
        dispatcher.tryAssign(ride, now);
        return RideResponse.from(ride, ActorRole.RIDER, null);
    }

    // ------------------------------------------------------------------ reading

    /** The ride the caller is currently involved in, if any. */
    @Transactional(readOnly = true)
    public Optional<RideResponse> current(UUID userId, ActorRole role) {
        Optional<Ride> ride = role == ActorRole.DRIVER
                ? rideRepository.findFirstByDriverIdAndStatusIn(driverProfileService.requireProfileId(userId), RideStatus.activeStatuses())
                : rideRepository.findFirstByRiderIdAndStatusIn(userId, ACTIVE);
        return ride.map(found -> toResponse(found, role, userId));
    }

    @Transactional(readOnly = true)
    public RideResponse get(UUID userId, ActorRole role, UUID rideId) {
        return toResponse(accessibleRide(userId, role, rideId), role, userId);
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(UUID userId, ActorRole role, UUID rideId) {
        accessibleRide(userId, role, rideId);
        return historyRepository.findByRideIdOrderBySequenceAsc(rideId).stream().map(TimelineEntry::from).toList();
    }

    /** "My rides", newest first, one page at a time. */
    @Transactional(readOnly = true)
    public PageResponse<RideResponse> history(UUID userId, ActorRole role, RideStatus status, int page, int size) {
        Pageable pageable = Paging.of(page, size, Sort.by(Sort.Direction.DESC, "requestedAt"));
        Page<Ride> rides;
        if (role == ActorRole.DRIVER) {
            UUID profileId = driverProfileService.requireProfileId(userId);
            rides = status == null
                    ? rideRepository.findByDriverId(profileId, pageable)
                    : rideRepository.findByDriverIdAndStatus(profileId, status, pageable);
        } else {
            rides = status == null
                    ? rideRepository.findByRiderId(userId, pageable)
                    : rideRepository.findByRiderIdAndStatus(userId, status, pageable);
        }
        return toPage(rides, role, userId);
    }

    /** Every ride on the platform (admin). */
    @Transactional(readOnly = true)
    public PageResponse<RideResponse> historyForAdmin(RideStatus status, int page, int size) {
        Pageable pageable = Paging.of(page, size, Sort.by(Sort.Direction.DESC, "requestedAt"));
        Page<Ride> rides = status == null ? rideRepository.findAll(pageable) : rideRepository.findByStatus(status, pageable);
        return PageResponse.from(rides, ride -> RideResponse.from(ride, ActorRole.ADMIN, null));
    }

    // ------------------------------------------------------------------ driver actions

    @Transactional
    public RideResponse accept(UUID driverUserId, UUID rideId) {
        userService.getActiveUser(driverUserId);
        Ride ride = assignedRide(driverUserId, rideId);
        ride.accept(driverUserId, Instant.now());
        return saved(ride, driverUserId);
    }

    /** Declining hands the ride to the next driver; the decliner gets nothing back. */
    @Transactional
    public void reject(UUID driverUserId, UUID rideId) {
        Ride ride = assignedRide(driverUserId, rideId);
        dispatcher.declineAndReassign(ride, RejectionReason.REJECTED, driverUserId, ActorRole.DRIVER,
                "Driver declined", Instant.now());
        rideRepository.flush();
    }

    @Transactional
    public RideResponse arrived(UUID driverUserId, UUID rideId) {
        Ride ride = assignedRide(driverUserId, rideId);
        ride.arrive(driverUserId, Instant.now());
        return saved(ride, driverUserId);
    }

    @Transactional
    public RideResponse start(UUID driverUserId, UUID rideId) {
        Ride ride = assignedRide(driverUserId, rideId);
        ride.start(driverUserId, Instant.now());
        return saved(ride, driverUserId);
    }

    @Transactional
    public RideResponse complete(UUID driverUserId, UUID rideId) {
        Ride ride = assignedRide(driverUserId, rideId);
        ride.complete(driverUserId, Instant.now());
        return saved(ride, driverUserId);
    }

    // ------------------------------------------------------------------ cancel (rider or driver)

    @Transactional
    public RideResponse cancel(UUID userId, ActorRole role, UUID rideId, String reason) {
        Ride ride = accessibleRide(userId, role, rideId);
        if (role == ActorRole.DRIVER
                && ride.getStatus() != RideStatus.DRIVER_ACCEPTED && ride.getStatus() != RideStatus.DRIVER_ARRIVED) {
            throw new ConflictException(
                    "A driver can cancel only after accepting and before the trip starts. To turn an offer down, decline it");
        }
        // The state machine decides whether this ride can still be cancelled at all
        // (it cannot once the trip has started or finished)
        ride.cancel(userId, role, reason, Instant.now());
        return saved(ride, userId);
    }

    // ------------------------------------------------------------------ helpers

    /** The ride, if the caller is its rider, its driver, or an admin; otherwise "not found". */
    private Ride accessibleRide(UUID userId, ActorRole role, UUID rideId) {
        return rideRepository.findDetailedById(rideId)
                .filter(ride -> role == ActorRole.ADMIN || ride.isParticipant(userId))
                .filter(ride -> role != ActorRole.RIDER || ride.getRider().getId().equals(userId))
                .filter(ride -> role != ActorRole.DRIVER || (ride.getDriver() != null && ride.getDriver().getUser().getId().equals(userId)))
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found"));
    }

    /** The ride, only if it is assigned to this driver. */
    private Ride assignedRide(UUID driverUserId, UUID rideId) {
        return accessibleRide(driverUserId, ActorRole.DRIVER, rideId);
    }

    private RideResponse saved(Ride ride, UUID viewerUserId) {
        rideRepository.flush();   // surface a concurrent-change conflict here, not at commit
        return toResponse(ride, roleOf(ride, viewerUserId), viewerUserId);
    }

    private static ActorRole roleOf(Ride ride, UUID userId) {
        return ride.getRider().getId().equals(userId) ? ActorRole.RIDER : ActorRole.DRIVER;
    }

    private RideResponse toResponse(Ride ride, ActorRole viewer, UUID viewerUserId) {
        Integer mine = viewer == ActorRole.ADMIN ? null
                : ratingLookup.starsGivenBy(viewerUserId, List.of(ride.getId())).get(ride.getId());
        return RideResponse.from(ride, viewer, mine);
    }

    /** One extra query for the whole page (not one per ride) to find which rides I already rated. */
    private PageResponse<RideResponse> toPage(Page<Ride> rides, ActorRole viewer, UUID viewerUserId) {
        Map<UUID, Integer> mine = ratingLookup.starsGivenBy(viewerUserId, rides.getContent().stream().map(Ride::getId).toList());
        return PageResponse.from(rides, ride -> RideResponse.from(ride, viewer, mine.get(ride.getId())));
    }
}
