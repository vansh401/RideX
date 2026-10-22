package com.ridex.ridex_app.safety;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.common.Paging;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.mail.EmailSender;
import com.ridex.ridex_app.redis.DriverLocationIndex;
import com.ridex.ridex_app.ride.ActorRole;
import com.ridex.ridex_app.ride.Ride;
import com.ridex.ridex_app.ride.RideRepository;
import com.ridex.ridex_app.ride.RideStatus;
import com.ridex.ridex_app.rider.RiderProfile;
import com.ridex.ridex_app.rider.RiderProfileRepository;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;

/**
 * Safety: a live trip link anyone can follow, and an SOS button.
 *
 * TRIP LINK. A random 192-bit token in a URL. Whoever holds it can see the trip's progress (read only, no login), so the page shows only
 * what a worried friend needs: where the car is, the driver's FIRST name, the vehicle and plate, the pickup and drop-off. No phone
 * numbers, no rider details, no payment. It expires after 24 hours, and ending the ride keeps it readable only until it expires.
 *
 * SOS. Raised by the rider or the driver of an ACTIVE ride. It is stored (administrators see it at once) and emailed to every
 * administrator and, when the rider saved one, to the emergency contact together with the live trip link. Email is sent after the
 * database commit and its failure never undoes or hides the alert. One open alert per person per ride: pressing twice is one emergency.
 */
@Service
public class SafetyService {

    private static final Logger log = LoggerFactory.getLogger(SafetyService.class);
    private static final Duration LINK_LIFETIME = Duration.ofHours(24);
    private static final Set<RideStatus> FOLLOWABLE = Set.of(RideStatus.DRIVER_ACCEPTED, RideStatus.DRIVER_ARRIVED, RideStatus.RIDE_STARTED);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record ShareLink(String token, String url, Instant expiresAt) {
    }

    public record Vehicle(String description, String registrationNumber) {
    }

    public record Position(double latitude, double longitude, Instant updatedAt) {
    }

    public record TripView(String status, String driverFirstName, Vehicle vehicle, String pickupAddress, String dropoffAddress,
                           BigDecimal pickupLatitude, BigDecimal pickupLongitude, BigDecimal dropoffLatitude, BigDecimal dropoffLongitude,
                           Position position, Instant expiresAt) {
    }

    public record SosView(UUID id, UUID rideId, String raisedByName, String raisedByRole, String raisedByPhone, String pickupAddress, String dropoffAddress,
                          BigDecimal latitude, BigDecimal longitude, String status, String note, Instant createdAt, Instant handledAt) {
    }

    private static final RowMapper<SosView> SOS_MAPPER = (rs, n) -> new SosView(rs.getObject("id", UUID.class), rs.getObject("ride_id", UUID.class),
            rs.getString("full_name"), rs.getString("raised_by_role"), rs.getString("phone"), rs.getString("pickup_address"), rs.getString("dropoff_address"),
            rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("status"), rs.getString("note"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("handled_at") == null ? null : rs.getTimestamp("handled_at").toInstant());

    private static final String SOS_SELECT = """
            select s.id, s.ride_id, u.full_name, u.phone, s.raised_by_role, r.pickup_address, r.dropoff_address, s.latitude, s.longitude, s.status,
                   s.note, s.created_at, s.handled_at
            from sos_alerts s join rides r on r.id = s.ride_id join users u on u.id = s.raised_by
            """;

    private final JdbcTemplate jdbc;
    private final RideRepository rides;
    private final UserRepository users;
    private final RiderProfileRepository riderProfiles;
    private final DriverLocationIndex locations;
    private final EmailSender email;
    private final String frontendBaseUrl;

    public SafetyService(JdbcTemplate jdbc, RideRepository rides, UserRepository users, RiderProfileRepository riderProfiles, DriverLocationIndex locations,
                         EmailSender email, @Value("${ridex.frontend.base-url}") String frontendBaseUrl) {
        this.jdbc = jdbc;
        this.rides = rides;
        this.users = users;
        this.riderProfiles = riderProfiles;
        this.locations = locations;
        this.email = email;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /** The ride, only for one of its two people (anybody else gets "not found"). */
    Ride participantRide(UUID userId, ActorRole role, UUID rideId) {
        return rides.findDetailedById(rideId)
                .filter(ride -> ride.isParticipant(userId))
                .filter(ride -> role != ActorRole.RIDER || ride.getRider().getId().equals(userId))
                .filter(ride -> role != ActorRole.DRIVER || (ride.getDriver() != null && ride.getDriver().getUser().getId().equals(userId)))
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found"));
    }

    // ------------------------------------------------------------------ the trip link

    @Transactional
    public ShareLink shareLink(UUID userId, ActorRole role, UUID rideId) {
        Ride ride = participantRide(userId, role, rideId);
        if (!ride.getStatus().isActive() && ride.getStatus() != RideStatus.SCHEDULED) {
            throw new ConflictException("This ride is over, so it cannot be shared any more");
        }
        return linkFor(rideId, userId);
    }

    private ShareLink linkFor(UUID rideId, UUID createdBy) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plus(LINK_LIFETIME);
        // One link per ride: asking again returns the same link and extends its life
        jdbc.update("""
                insert into ride_shares (ride_id, token, created_by, expires_at) values (?, ?, ?, ?)
                on conflict (ride_id) do update set expires_at = excluded.expires_at
                """, rideId, candidate, createdBy, Timestamp.from(expires));
        String token = jdbc.queryForObject("select token from ride_shares where ride_id = ?", String.class, rideId);
        return new ShareLink(token, frontendBaseUrl + "/trip/" + token, expires);
    }

    /** What a person holding the link sees. 404 for an unknown or expired link: the two look the same on purpose. */
    @Transactional(readOnly = true)
    public TripView publicTrip(String token) {
        if (token == null || token.length() < 20 || token.length() > 64) {
            throw new ResourceNotFoundException("This link is not valid");
        }
        List<UUID> found = jdbc.queryForList("select ride_id from ride_shares where token = ? and expires_at > now()", UUID.class, token);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("This link is not valid or has expired");
        }
        Ride ride = rides.findDetailedById(found.get(0)).orElseThrow(() -> new ResourceNotFoundException("This link is not valid or has expired"));
        Instant expires = jdbc.queryForObject("select expires_at from ride_shares where token = ?", Timestamp.class, token).toInstant();
        String firstName = null;
        Vehicle vehicle = null;
        Position position = null;
        if (ride.getDriver() != null) {
            String full = ride.getDriver().getUser().getFullName();
            firstName = full == null ? null : full.trim().split("\\s+")[0];
            if (ride.getVehicle() != null) {
                vehicle = new Vehicle(ride.getVehicle().getColor() + " " + ride.getVehicle().getMake() + " " + ride.getVehicle().getModel(),
                        ride.getVehicle().getRegistrationNumber());
            }
            if (FOLLOWABLE.contains(ride.getStatus())) {
                position = locations.position(ride.getDriver().getId())
                        .map(p -> new Position(p[0], p[1], locations.lastSeen(ride.getDriver().getId()).orElse(null))).orElse(null);
            }
        }
        return new TripView(ride.getStatus().name(), firstName, vehicle, ride.getPickupAddress(), ride.getDropoffAddress(), ride.getPickupLatitude(),
                ride.getPickupLongitude(), ride.getDropoffLatitude(), ride.getDropoffLongitude(), position, expires);
    }

    // ------------------------------------------------------------------ SOS

    @Transactional
    public SosView raiseSos(UUID userId, ActorRole role, UUID rideId, BigDecimal latitude, BigDecimal longitude) {
        Ride ride = participantRide(userId, role, rideId);
        if (!ride.getStatus().isActive()) {
            throw new ConflictException("SOS is for a ride in progress. In an emergency call your local emergency number");
        }
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into sos_alerts (id, ride_id, raised_by, raised_by_role, latitude, longitude) values (?, ?, ?, ?, ?, ?)
                on conflict (ride_id, raised_by) where status = 'OPEN' do nothing
                """, id, rideId, userId, role.name(), latitude, longitude);
        if (inserted == 0) {
            return jdbc.queryForObject(SOS_SELECT + " where s.ride_id = ? and s.raised_by = ? and s.status = 'OPEN'", SOS_MAPPER, rideId, userId);   // pressed twice
        }
        SosView alert = jdbc.queryForObject(SOS_SELECT + " where s.id = ?", SOS_MAPPER, id);
        ShareLink link = linkFor(rideId, userId);
        afterCommit(() -> notifyAboutSos(alert, ride, link));
        return alert;
    }

    private void notifyAboutSos(SosView alert, Ride ride, ShareLink link) {
        String where = alert.latitude() == null ? "(location not shared)" : "https://maps.google.com/?q=" + alert.latitude() + "," + alert.longitude();
        String text = "SOS on a RideX trip.\n\n"
                + alert.raisedByName() + " (" + alert.raisedByRole().toLowerCase() + ") pressed the SOS button.\n"
                + "From: " + alert.pickupAddress() + "\nTo: " + alert.dropoffAddress() + "\n"
                + "Location: " + where + "\n"
                + "Follow the trip live: " + link.url() + "\n\n"
                + "If you think someone is in danger, call your local emergency number now.";
        for (User admin : users.findByRole(Role.ADMIN)) {
            send(admin.getEmail(), "SOS: " + alert.raisedByName() + " needs help", text + "\n\nOpen the alert in the admin console: " + frontendBaseUrl + "/admin/safety");
        }
        riderProfiles.findByUserId(ride.getRider().getId()).map(RiderProfile::getEmergencyContactEmail).filter(e -> e != null && !e.isBlank())
                .ifPresent(contact -> send(contact, alert.raisedByName() + " pressed SOS on a RideX trip", text));
    }

    private void send(String to, String subject, String text) {
        try {
            email.sendNotice(to, subject, text);
        } catch (RuntimeException e) {
            log.warn("A safety email could not be sent: {}", e.getClass().getSimpleName());   // the alert itself is stored; email is best effort
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // ------------------------------------------------------------------ administrators

    @Transactional(readOnly = true)
    public PageResponse<SosView> list(String status, int page, int size) {
        Pageable pageable = Paging.of(page, size, Sort.unsorted());
        Long total = jdbc.queryForObject("select count(*) from sos_alerts where (?::text is null or status = ?)", Long.class, status, status);
        List<SosView> rows = jdbc.query(SOS_SELECT + " where (?::text is null or s.status = ?) order by (s.status = 'OPEN') desc, s.created_at desc limit ? offset ?",
                SOS_MAPPER, status, status, pageable.getPageSize(), pageable.getOffset());
        return PageResponse.from(new PageImpl<>(rows, pageable, total == null ? 0 : total), a -> a);
    }

    @Transactional
    public SosView decide(UUID adminId, UUID id, String newStatus, String note) {
        List<String> current = jdbc.queryForList("select status from sos_alerts where id = ? for update", String.class, id);
        if (current.isEmpty()) {
            throw new ResourceNotFoundException("Alert not found");
        }
        if ("RESOLVED".equals(current.get(0))) {
            throw new ConflictException("This alert is already resolved");
        }
        jdbc.update("update sos_alerts set status = ?, handled_by = ?, handled_at = now(), note = coalesce(?, note) where id = ?", newStatus, adminId,
                note == null || note.isBlank() ? null : (note.length() > 300 ? note.substring(0, 300) : note), id);
        return jdbc.queryForObject(SOS_SELECT + " where s.id = ?", SOS_MAPPER, id);
    }
}
