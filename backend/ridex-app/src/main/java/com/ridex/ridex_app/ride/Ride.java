package com.ridex.ridex_app.ride;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.driver.DriverProfile;
import com.ridex.ridex_app.driver.Vehicle;
import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.pricing.FareEstimate;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

import com.ridex.ridex_app.user.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

/**
 * A ride. All changes go through the intention-named methods below (offerTo, accept, start, ...),
 * which all funnel into moveTo(): the single place that checks the state machine, stamps the time
 * and appends the audit trail. Nothing outside this class can set the status directly.
 */
@Entity
@Table(name = "rides")
public class Ride {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rider_id", nullable = false, updatable = false)
    private User rider;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_profile_id")
    private DriverProfile driver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private VehicleCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RideStatus status;

    @Column(name = "pickup_address", nullable = false, updatable = false)
    private String pickupAddress;
    @Column(name = "pickup_latitude", nullable = false, updatable = false, precision = 9, scale = 6)
    private BigDecimal pickupLatitude;
    @Column(name = "pickup_longitude", nullable = false, updatable = false, precision = 9, scale = 6)
    private BigDecimal pickupLongitude;

    @Column(name = "dropoff_address", nullable = false, updatable = false)
    private String dropoffAddress;
    @Column(name = "dropoff_latitude", nullable = false, updatable = false, precision = 9, scale = 6)
    private BigDecimal dropoffLatitude;
    @Column(name = "dropoff_longitude", nullable = false, updatable = false, precision = 9, scale = 6)
    private BigDecimal dropoffLongitude;

    // The quote, frozen at request time
    @Column(name = "distance_km", nullable = false, updatable = false, precision = 7, scale = 2)
    private BigDecimal distanceKm;
    @Column(name = "duration_minutes", nullable = false, updatable = false)
    private int durationMinutes;
    @Column(name = "base_fare", nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal baseFare;
    @Column(name = "distance_fare", nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal distanceFare;
    @Column(name = "time_fare", nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal timeFare;
    @Column(name = "surge_multiplier", nullable = false, updatable = false, precision = 4, scale = 2)
    private BigDecimal surgeMultiplier;
    @Column(nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;
    @Column(nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal tax;
    @Column(name = "total_fare", nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal totalFare;
    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "offer_expires_at")
    private Instant offerExpiresAt;
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;
    @Column(name = "accepted_at")
    private Instant acceptedAt;
    @Column(name = "arrived_at")
    private Instant arrivedAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by")
    private ActorRole cancelledBy;
    @Column(name = "cancel_reason")
    private String cancelReason;

    // Optimistic locking: Hibernate adds "WHERE version = ?" to every UPDATE. If a driver accepts
    // at the very moment the timeout job reassigns the ride (or a rider cancels), the second
    // UPDATE matches no row and fails with HTTP 409, instead of silently overwriting the first.
    @Version
    private Long version;

    // Inverse side of RideStatusChange.ride. Adding to it does not load the existing entries.
    @OneToMany(mappedBy = "ride", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RideStatusChange> history = new ArrayList<>();

    // Status changes made since this ride was last saved. Spring Data publishes them as events when the
    // ride is saved (see domainEvents), so the rest of the application can react (live pushes, notifications)
    // without this class knowing about any of it. Not stored in the database.
    @Transient
    private final List<RideStatusChanged> pendingEvents = new ArrayList<>();

    // Required by JPA; not for application code
    protected Ride() {
    }

    /** Creates the ride and immediately starts searching for a driver. */
    public static Ride request(User rider, VehicleCategory category,
                               String pickupAddress, LatLng pickup,
                               String dropoffAddress, LatLng dropoff,
                               FareEstimate fare, Instant now) {
        Ride ride = new Ride();
        ride.id = UUID.randomUUID();
        ride.rider = rider;
        ride.category = category;
        ride.pickupAddress = pickupAddress.trim();
        ride.pickupLatitude = pickup.latitude();
        ride.pickupLongitude = pickup.longitude();
        ride.dropoffAddress = dropoffAddress.trim();
        ride.dropoffLatitude = dropoff.latitude();
        ride.dropoffLongitude = dropoff.longitude();
        ride.distanceKm = fare.distanceKm();
        ride.durationMinutes = fare.durationMinutes();
        ride.baseFare = fare.baseFare();
        ride.distanceFare = fare.distanceFare();
        ride.timeFare = fare.timeFare();
        ride.surgeMultiplier = fare.surgeMultiplier();
        ride.subtotal = fare.subtotal();
        ride.tax = fare.tax();
        ride.totalFare = fare.total();
        ride.currency = fare.currency();
        ride.requestedAt = now;

        // The very first history line has no "from" status
        ride.status = RideStatus.REQUESTED;
        ride.history.add(new RideStatusChange(ride, null, RideStatus.REQUESTED, rider.getId(), ActorRole.RIDER,
                "Ride requested", now));
        ride.moveTo(RideStatus.SEARCHING_DRIVER, null, ActorRole.SYSTEM, "Looking for a driver", now);
        return ride;
    }

    // ----- driver offer -----

    /** The dispatcher found a nearby driver: offer the ride, with a deadline to answer. */
    public void offerTo(DriverProfile driver, Vehicle vehicle, Instant expiresAt, Instant now) {
        moveTo(RideStatus.DRIVER_ASSIGNED, null, ActorRole.SYSTEM, "Offered to a nearby driver", now);
        this.driver = driver;
        this.vehicle = vehicle;
        this.offerExpiresAt = expiresAt;
        // The offered driver is only known now, so complete the event that moveTo just recorded
        RideStatusChanged recorded = pendingEvents.remove(pendingEvents.size() - 1);
        pendingEvents.add(recorded.withOffer(driver.getUser().getId(), new RideStatusChanged.OfferSummary(
                category.name(), pickupAddress, dropoffAddress, distanceKm, totalFare, expiresAt)));
    }

    public void accept(UUID driverUserId, Instant now) {
        if (status == RideStatus.DRIVER_ASSIGNED && offerExpiresAt != null && !offerExpiresAt.isAfter(now)) {
            throw new ConflictException("This ride offer has expired");
        }
        moveTo(RideStatus.DRIVER_ACCEPTED, driverUserId, ActorRole.DRIVER, "Driver accepted", now);
        this.acceptedAt = now;
        this.offerExpiresAt = null;
    }

    /** The driver declined, or the offer timed out: back to searching, with no driver attached. */
    public void declineOffer(UUID actorId, ActorRole role, String note, Instant now) {
        moveTo(RideStatus.SEARCHING_DRIVER, actorId, role, note, now);
        this.driver = null;
        this.vehicle = null;
        this.offerExpiresAt = null;
    }

    // ----- the trip -----

    public void arrive(UUID driverUserId, Instant now) {
        moveTo(RideStatus.DRIVER_ARRIVED, driverUserId, ActorRole.DRIVER, "Driver arrived at pickup", now);
        this.arrivedAt = now;
    }

    public void start(UUID driverUserId, Instant now) {
        moveTo(RideStatus.RIDE_STARTED, driverUserId, ActorRole.DRIVER, "Trip started", now);
        this.startedAt = now;
    }

    public void complete(UUID driverUserId, Instant now) {
        moveTo(RideStatus.RIDE_COMPLETED, driverUserId, ActorRole.DRIVER, "Trip completed", now);
        this.completedAt = now;
    }

    public void cancel(UUID actorId, ActorRole role, String reason, Instant now) {
        moveTo(RideStatus.CANCELLED, actorId, role, reason == null || reason.isBlank() ? "Cancelled" : reason.trim(), now);
        this.cancelledAt = now;
        this.cancelledBy = role;
        this.cancelReason = reason == null || reason.isBlank() ? null : reason.trim();
        this.offerExpiresAt = null;
    }

    /** The only place the status changes. */
    private void moveTo(RideStatus target, UUID actorId, ActorRole role, String note, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new ConflictException("A ride that is " + status + " cannot become " + target);
        }
        RideStatus from = status;
        this.status = target;
        this.history.add(new RideStatusChange(this, from, target, actorId, role, note, now));
        UUID driverUserId = driver == null ? null : driver.getUser().getId();
        pendingEvents.add(new RideStatusChanged(id, rider.getId(), driverUserId, from, target, role, note, now, null));
    }

    /** Read by Spring Data when this ride is saved: every status change since the last save. */
    @DomainEvents
    Collection<RideStatusChanged> domainEvents() {
        return List.copyOf(pendingEvents);
    }

    @AfterDomainEventPublication
    void clearDomainEvents() {
        pendingEvents.clear();
    }

    public boolean isParticipant(UUID userId) {
        return rider.getId().equals(userId) || (driver != null && driver.getUser().getId().equals(userId));
    }

    public UUID getId() { return id; }
    public User getRider() { return rider; }
    public DriverProfile getDriver() { return driver; }
    public Vehicle getVehicle() { return vehicle; }
    public VehicleCategory getCategory() { return category; }
    public RideStatus getStatus() { return status; }
    public String getPickupAddress() { return pickupAddress; }
    public BigDecimal getPickupLatitude() { return pickupLatitude; }
    public BigDecimal getPickupLongitude() { return pickupLongitude; }
    public String getDropoffAddress() { return dropoffAddress; }
    public BigDecimal getDropoffLatitude() { return dropoffLatitude; }
    public BigDecimal getDropoffLongitude() { return dropoffLongitude; }
    public BigDecimal getDistanceKm() { return distanceKm; }
    public int getDurationMinutes() { return durationMinutes; }
    public BigDecimal getBaseFare() { return baseFare; }
    public BigDecimal getDistanceFare() { return distanceFare; }
    public BigDecimal getTimeFare() { return timeFare; }
    public BigDecimal getSurgeMultiplier() { return surgeMultiplier; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getTax() { return tax; }
    public BigDecimal getTotalFare() { return totalFare; }
    public String getCurrency() { return currency; }
    public Instant getOfferExpiresAt() { return offerExpiresAt; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getArrivedAt() { return arrivedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public ActorRole getCancelledBy() { return cancelledBy; }
    public String getCancelReason() { return cancelReason; }
}
