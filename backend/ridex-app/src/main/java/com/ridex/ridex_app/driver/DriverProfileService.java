package com.ridex.ridex_app.driver;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserService;

@Service
public class DriverProfileService {

    private final DriverProfileRepository repository;
    private final UserService userService;

    public DriverProfileService(DriverProfileRepository repository, UserService userService) {
        this.repository = repository;
        this.userService = userService;
    }

    @Transactional
    public DriverProfileResponse create(UUID userId, DriverProfileRequest request) {
        User user = userService.getActiveUser(userId);   // the token may be stale; the database decides

        if (repository.existsByUserId(userId)) {
            throw new ConflictException("You already have a driver profile");
        }
        String licenseNumber = normalise(request.licenseNumber());
        requireLicenceFree(licenseNumber);

        return DriverProfileResponse.from(
                repository.save(new DriverProfile(user, licenseNumber, request.licenseExpiry())));
    }

    @Transactional(readOnly = true)
    public DriverProfileResponse get(UUID userId) {
        return DriverProfileResponse.from(load(userId));
    }

    @Transactional
    public DriverProfileResponse update(UUID userId, DriverProfileRequest request) {
        userService.getActiveUser(userId);
        DriverProfile profile = load(userId);

        String licenseNumber = normalise(request.licenseNumber());
        if (!licenseNumber.equals(profile.getLicenseNumber())) {
            requireLicenceFree(licenseNumber);
        }
        profile.changeLicence(licenseNumber, request.licenseExpiry());
        return DriverProfileResponse.from(profile);
    }

    private DriverProfile load(UUID userId) {
        return repository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));
    }

    private void requireLicenceFree(String licenseNumber) {
        if (repository.existsByLicenseNumber(licenseNumber)) {
            throw new ConflictException("This licence number is already registered");
        }
    }

    private static String normalise(String licenseNumber) {
        return licenseNumber.trim().toUpperCase(Locale.ROOT);
    }
}
