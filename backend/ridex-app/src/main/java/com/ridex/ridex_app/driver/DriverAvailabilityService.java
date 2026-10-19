package com.ridex.ridex_app.driver;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.common.AfterCommit;
import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.redis.DriverLocationIndex;
import com.ridex.ridex_app.user.UserService;

/**
 * Going online and offline, and the location "heartbeat". Going OFFLINE never interrupts a ride
 * in progress: it only stops NEW offers (a driver already on a ride is never offered another).
 *
 * Where things live (Phase 4): PostgreSQL keeps the durable facts (is this driver online, approved,
 * which vehicle). Redis keeps the fast-changing one: the current position. A heartbeat every few
 * seconds from every driver would otherwise be a stream of database writes that nobody needs to keep.
 */
@Service
public class DriverAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(DriverAvailabilityService.class);

    private final DriverProfileRepository profileRepository;
    private final VehicleRepository vehicleRepository;
    private final UserService userService;
    private final DriverLocationIndex locations;

    public DriverAvailabilityService(DriverProfileRepository profileRepository,
                                     VehicleRepository vehicleRepository,
                                     UserService userService,
                                     DriverLocationIndex locations) {
        this.profileRepository = profileRepository;
        this.vehicleRepository = vehicleRepository;
        this.userService = userService;
        this.locations = locations;
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse status(UUID userId) {
        DriverProfile profile = profileOf(userId);
        AvailabilityResponse response = AvailabilityResponse.of(profile, hasActiveVehicle(profile));
        try {
            // The freshest "last seen" is the heartbeat in Redis, not the moment they went online
            return locations.lastSeen(profile.getId()).map(response::withLastSeenAt).orElse(response);
        } catch (RuntimeException e) {
            return response;
        }
    }

    @Transactional
    public AvailabilityResponse goOnline(UUID userId, LatLng position) {
        userService.getActiveUser(userId);
        DriverProfile profile = profileOf(userId);
        if (!hasActiveVehicle(profile)) {
            throw new ConflictException("Add a vehicle and make it active before going online");
        }
        profile.goOnline(position, Instant.now());   // refuses drivers that are not approved
        UUID id = profile.getId();
        // Only after the database commit, so Redis never lists a driver the database says is offline
        AfterCommit.run(() -> publish(id, position));
        return AvailabilityResponse.of(profile, true);
    }

    @Transactional
    public AvailabilityResponse goOffline(UUID userId) {
        DriverProfile profile = profileOf(userId);
        profile.goOffline();
        UUID id = profile.getId();
        AfterCommit.run(() -> unpublish(id));
        return AvailabilityResponse.of(profile, hasActiveVehicle(profile));
    }

    /** Called every few seconds by the driver's app while online. Writes to Redis only, not to PostgreSQL. */
    @Transactional(readOnly = true)
    public void updateLocation(UUID userId, LatLng position) {
        DriverProfile profile = profileOf(userId);
        if (!profile.isOnline()) {
            throw new ConflictException("Go online before sharing your location");
        }
        locations.update(profile.getId(), position.latitude().doubleValue(), position.longitude().doubleValue());
    }

    /** Used when an admin suspends the account: stop sending this driver offers. */
    @Transactional
    public void forceOffline(UUID userId) {
        profileRepository.findByUserId(userId).ifPresent(profile -> {
            profile.goOffline();
            UUID id = profile.getId();
            AfterCommit.run(() -> unpublish(id));
        });
    }

    private void publish(UUID driverProfileId, LatLng position) {
        try {
            locations.update(driverProfileId, position.latitude().doubleValue(), position.longitude().doubleValue());
        } catch (RuntimeException e) {
            // The driver is online in the database. Their next heartbeat adds them to the index.
            log.warn("Could not index driver {} in Redis: {}", driverProfileId, e.getClass().getSimpleName());
        }
    }

    private void unpublish(UUID driverProfileId) {
        try {
            locations.remove(driverProfileId);
        } catch (RuntimeException e) {
            // Harmless: matching also checks the database, and the heartbeat expires on its own
            log.warn("Could not remove driver {} from Redis: {}", driverProfileId, e.getClass().getSimpleName());
        }
    }

    private boolean hasActiveVehicle(DriverProfile profile) {
        return vehicleRepository.findByDriverProfileIdAndActiveTrue(profile.getId()).isPresent();
    }

    private DriverProfile profileOf(UUID userId) {
        return profileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Create your driver profile first"));
    }
}
