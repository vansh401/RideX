package com.ridex.ridex_app.rider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.user.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Extra information about a rider. The profile is the aggregate root for saved places: places
 * are only ever added or removed through it, so the rules (limit, unique label) live here.
 */
@Entity
@Table(name = "rider_profiles")
public class RiderProfile {

    static final int MAX_SAVED_PLACES = 10;

    @Id
    private UUID id;

    // One user <-> one profile. LAZY: loading a profile does not load the user.
    // This side owns the foreign key (user_id).
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false, unique = true)
    private User user;

    @Column(name = "emergency_contact_name")
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone")
    private String emergencyContactPhone;

    // One profile -> many places. Not owning (mappedBy): the foreign key lives on SavedPlace.
    // LAZY is the default for collections and is what we want; cascade + orphanRemoval make
    // "add to / remove from the list" save and delete rows automatically.
    @OneToMany(mappedBy = "riderProfile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<SavedPlace> savedPlaces = new ArrayList<>();

    // Running totals of the stars drivers gave this rider
    @Column(name = "rating_sum", nullable = false, insertable = false, updatable = false)
    private int ratingSum;

    @Column(name = "rating_count", nullable = false, insertable = false, updatable = false)
    private int ratingCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Required by JPA; not for application code
    protected RiderProfile() {
    }

    public RiderProfile(User user) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Both values, or both null to clear the contact. */
    public void changeEmergencyContact(String name, String phone) {
        boolean hasName = name != null && !name.isBlank();
        boolean hasPhone = phone != null && !phone.isBlank();
        if (hasName != hasPhone) {
            throw new IllegalArgumentException("Emergency contact needs both a name and a phone number");
        }
        this.emergencyContactName = hasName ? name.trim() : null;
        this.emergencyContactPhone = hasPhone ? phone.trim() : null;
    }

    public SavedPlace addPlace(String label, String address, BigDecimal latitude, BigDecimal longitude) {
        if (savedPlaces.size() >= MAX_SAVED_PLACES) {
            throw new ConflictException("You can save at most " + MAX_SAVED_PLACES + " places");
        }
        String cleanLabel = label.trim();
        boolean taken = savedPlaces.stream()
                .anyMatch(place -> place.getLabel().toLowerCase(Locale.ROOT).equals(cleanLabel.toLowerCase(Locale.ROOT)));
        if (taken) {
            throw new ConflictException("You already have a place called \"" + cleanLabel + "\"");
        }
        SavedPlace place = new SavedPlace(this, cleanLabel, address.trim(), latitude, longitude);
        savedPlaces.add(place);
        return place;
    }

    /** Looks only inside THIS profile, so one rider can never delete another rider's place. */
    public void removePlace(UUID placeId) {
        boolean removed = savedPlaces.removeIf(place -> place.getId().equals(placeId));
        if (!removed) {
            throw new ResourceNotFoundException("Saved place not found");
        }
    }

    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmergencyContactName() { return emergencyContactName; }
    public String getEmergencyContactPhone() { return emergencyContactPhone; }
    public List<SavedPlace> getSavedPlaces() { return List.copyOf(savedPlaces); }
    public int getRatingCount() { return ratingCount; }

    /** Average stars, or null before the first rating. */
    public BigDecimal getRatingAverage() {
        return ratingCount == 0 ? null
                : BigDecimal.valueOf(ratingSum).divide(BigDecimal.valueOf(ratingCount), 2, java.math.RoundingMode.HALF_UP);
    }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
