package com.ridex.ridex_app.driver;

import java.time.Year;
import java.util.List;
import java.util.Locale;
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
import com.ridex.ridex_app.user.UserService;

@Service
public class VehicleService {

    static final int MAX_VEHICLES_PER_DRIVER = 3;

    private final VehicleRepository vehicleRepository;
    private final DriverProfileRepository profileRepository;
    private final UserService userService;

    public VehicleService(VehicleRepository vehicleRepository,
                          DriverProfileRepository profileRepository,
                          UserService userService) {
        this.vehicleRepository = vehicleRepository;
        this.profileRepository = profileRepository;
        this.userService = userService;
    }

    /** The first vehicle a driver adds becomes the active one automatically. */
    @Transactional
    public VehicleResponse add(UUID userId, VehicleRequest request) {
        userService.getActiveUser(userId);
        DriverProfile profile = profileOf(userId);
        requireYearNotInTheFuture(request.manufactureYear());

        if (vehicleRepository.countByDriverProfileId(profile.getId()) >= MAX_VEHICLES_PER_DRIVER) {
            throw new ConflictException("You can register at most " + MAX_VEHICLES_PER_DRIVER + " vehicles");
        }
        String registration = normalise(request.registrationNumber());
        if (vehicleRepository.existsByRegistrationNumber(registration)) {
            throw new ConflictException("This registration number is already registered");
        }

        Vehicle vehicle = new Vehicle(profile, request.category(), request.make(), request.model(),
                request.color(), registration, request.manufactureYear(), request.seats());
        if (vehicleRepository.countByDriverProfileId(profile.getId()) == 0) {
            vehicle.activate();
        }
        return VehicleResponse.from(vehicleRepository.save(vehicle));
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> list(UUID userId) {
        return vehicleRepository.findByDriverProfileIdOrderByCreatedAtAsc(profileOf(userId).getId())
                .stream().map(VehicleResponse::from).toList();
    }

    @Transactional
    public VehicleResponse update(UUID userId, UUID vehicleId, VehicleRequest request) {
        userService.getActiveUser(userId);
        Vehicle vehicle = ownVehicle(userId, vehicleId);
        requireYearNotInTheFuture(request.manufactureYear());
        // The registration number is the vehicle's identity and cannot be changed
        if (!normalise(request.registrationNumber()).equals(vehicle.getRegistrationNumber())) {
            throw new ConflictException("The registration number cannot be changed; add a new vehicle instead");
        }
        vehicle.describe(request.category(), request.make(), request.model(), request.color(),
                request.manufactureYear(), request.seats());
        return VehicleResponse.from(vehicle);
    }

    /** Makes this the vehicle used for rides. The previously active one is switched off first. */
    @Transactional
    public VehicleResponse activate(UUID userId, UUID vehicleId) {
        userService.getActiveUser(userId);
        DriverProfile profile = profileOf(userId);
        Vehicle vehicle = ownVehicle(userId, vehicleId);

        vehicleRepository.findByDriverProfileIdAndActiveTrue(profile.getId())
                .filter(current -> !current.getId().equals(vehicleId))
                .ifPresent(current -> {
                    current.deactivate();
                    // Flush NOW: the partial unique index is checked per statement, so the old
                    // row must be switched off in the database before the new one is switched on.
                    vehicleRepository.flush();
                });
        vehicle.activate();
        return VehicleResponse.from(vehicle);
    }

    @Transactional
    public void delete(UUID userId, UUID vehicleId) {
        userService.getActiveUser(userId);
        DriverProfile profile = profileOf(userId);
        Vehicle vehicle = ownVehicle(userId, vehicleId);
        if (vehicle.isActive() && profile.isOnline()) {
            throw new ConflictException("Go offline before removing the vehicle you are driving");
        }
        vehicleRepository.delete(vehicle);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminVehicleResponse> listForAdmin(VehicleCategory category, int page, int size) {
        Pageable pageable = Paging.of(page, size, Sort.by("createdAt").descending());
        Page<Vehicle> result = category == null
                ? vehicleRepository.findAll(pageable)
                : vehicleRepository.findByCategory(category, pageable);
        return PageResponse.from(result, AdminVehicleResponse::from);
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> listOfProfile(UUID profileId) {
        return vehicleRepository.findByDriverProfileIdOrderByCreatedAtAsc(profileId)
                .stream().map(VehicleResponse::from).toList();
    }

    /** Normalised form stored in the database: upper case, no spaces or hyphens. */
    static String normalise(String registrationNumber) {
        return registrationNumber.replaceAll("[ -]", "").toUpperCase(Locale.ROOT);
    }

    private void requireYearNotInTheFuture(int year) {
        if (year > Year.now().getValue() + 1) {
            throw new ConflictException("The manufacture year cannot be in the future");
        }
    }

    private DriverProfile profileOf(UUID userId) {
        return profileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Create your driver profile first"));
    }

    private Vehicle ownVehicle(UUID userId, UUID vehicleId) {
        return vehicleRepository.findByIdAndDriverProfileId(vehicleId, profileOf(userId).getId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found"));
    }
}
