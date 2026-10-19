package com.ridex.ridex_app.driver;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.user.UserService;

/**
 * Going online and offline, and the location "heartbeat". Going OFFLINE never interrupts a ride
 * in progress: it only stops NEW offers (a driver already on a ride is never offered another).
 */
@Service
public class DriverAvailabilityService {

    private final DriverProfileRepository profileRepository;
    private final VehicleRepository vehicleRepository;
    private final UserService userService;

    public DriverAvailabilityService(DriverProfileRepository profileRepository,
                                     VehicleRepository vehicleRepository,
                                     UserService userService) {
        this.profileRepository = profileRepository;
        this.vehicleRepository = vehicleRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse status(UUID userId) {
        DriverProfile profile = profileOf(userId);
        return AvailabilityResponse.of(profile, hasActiveVehicle(profile));
    }

    @Transactional
    public AvailabilityResponse goOnline(UUID userId, LatLng position) {
        userService.getActiveUser(userId);
        DriverProfile profile = profileOf(userId);
        if (!hasActiveVehicle(profile)) {
            throw new ConflictException("Add a vehicle and make it active before going online");
        }
        profile.goOnline(position, Instant.now());   // refuses drivers that are not approved
        return AvailabilityResponse.of(profile, true);
    }

    @Transactional
    public AvailabilityResponse goOffline(UUID userId) {
        DriverProfile profile = profileOf(userId);
        profile.goOffline();
        return AvailabilityResponse.of(profile, hasActiveVehicle(profile));
    }

    /** Called every few seconds by the driver's app while online. */
    @Transactional
    public void updateLocation(UUID userId, LatLng position) {
        DriverProfile profile = profileOf(userId);
        int updated = profileRepository.updateLocation(
                profile.getId(), position.latitude(), position.longitude(), Instant.now());
        if (updated == 0) {
            throw new ConflictException("Go online before sharing your location");
        }
    }

    /** Used when an admin suspends the account: stop sending this driver offers. */
    @Transactional
    public void forceOffline(UUID userId) {
        profileRepository.findByUserId(userId).ifPresent(DriverProfile::goOffline);
    }

    private boolean hasActiveVehicle(DriverProfile profile) {
        return vehicleRepository.findByDriverProfileIdAndActiveTrue(profile.getId()).isPresent();
    }

    private DriverProfile profileOf(UUID userId) {
        return profileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Create your driver profile first"));
    }
}
