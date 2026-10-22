package com.ridex.ridex_app.rider;

import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.config.CacheConfig;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserService;

@Service
public class RiderProfileService {

    private final RiderProfileRepository repository;
    private final UserService userService;

    public RiderProfileService(RiderProfileRepository repository, UserService userService) {
        this.repository = repository;
        this.userService = userService;
    }

    /** Called when a rider registers, inside the registration transaction. */
    @Transactional
    public void createFor(User user) {
        repository.save(new RiderProfile(user));
    }

    // Everything below returns DTOs built INSIDE the transaction. The places are lazy, so reading
    // them after the transaction ends (open-in-view is off) would throw LazyInitializationException.

    /** Cached per rider; every method below that changes the profile evicts it (invalidation). */
    @Cacheable(cacheNames = CacheConfig.RIDER_PROFILE, key = "#userId")
    @Transactional(readOnly = true)
    public RiderProfileResponse get(UUID userId) {
        return RiderProfileResponse.from(load(userId));
    }

    @CacheEvict(cacheNames = CacheConfig.RIDER_PROFILE, key = "#userId")
    @Transactional
    public RiderProfileResponse updateEmergencyContact(UUID userId, UpdateRiderProfileRequest request) {
        userService.getActiveUser(userId);   // the token may be stale; the database decides
        RiderProfile profile = load(userId);
        profile.changeEmergencyContact(request.emergencyContactName(), request.emergencyContactPhone());
        profile.changeEmergencyContactEmail(request.emergencyContactName() == null || request.emergencyContactName().isBlank() ? null : request.emergencyContactEmail());
        return RiderProfileResponse.from(profile);
    }

    @CacheEvict(cacheNames = CacheConfig.RIDER_PROFILE, key = "#userId")
    @Transactional
    public SavedPlaceResponse addPlace(UUID userId, SavedPlaceRequest request) {
        userService.getActiveUser(userId);
        RiderProfile profile = load(userId);
        SavedPlace place = profile.addPlace(
                request.label(), request.address(), request.latitude(), request.longitude());
        repository.flush();   // so the response reflects what was actually stored
        return SavedPlaceResponse.from(place);
    }

    @CacheEvict(cacheNames = CacheConfig.RIDER_PROFILE, key = "#userId")
    @Transactional
    public void removePlace(UUID userId, UUID placeId) {
        userService.getActiveUser(userId);
        load(userId).removePlace(placeId);
    }

    /** Records one more star rating given to this rider (called by the rating module). */
    @Transactional
    public void recordRating(UUID riderUserId, int stars) {
        repository.addRating(riderUserId, stars);
    }

    private RiderProfile load(UUID userId) {
        return repository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Rider profile not found"));
    }
}
