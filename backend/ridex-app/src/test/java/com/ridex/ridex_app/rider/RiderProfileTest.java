package com.ridex.ridex_app.rider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

class RiderProfileTest {

    private final User user = new User("Asha Rao", "asha@example.com", "+919876543210", "hash", Role.RIDER);
    private final RiderProfile profile = new RiderProfile(user);

    private SavedPlace addPlace(String label) {
        return profile.addPlace(label, "22 Windsor Lane", new BigDecimal("12.971599"), new BigDecimal("77.594566"));
    }

    @Test
    void newProfileIsEmpty() {
        assertThat(profile.getEmergencyContactName()).isNull();
        assertThat(profile.getSavedPlaces()).isEmpty();
    }

    @Test
    void emergencyContactNeedsBothNameAndPhone() {
        profile.changeEmergencyContact(" Meera Nair ", "+919811122233");

        assertThat(profile.getEmergencyContactName()).isEqualTo("Meera Nair");
        assertThat(profile.getEmergencyContactPhone()).isEqualTo("+919811122233");

        assertThatThrownBy(() -> profile.changeEmergencyContact("Only a name", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile.changeEmergencyContact(null, "+919811122233"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emergencyContactCanBeCleared() {
        profile.changeEmergencyContact("Meera Nair", "+919811122233");

        profile.changeEmergencyContact(null, null);

        assertThat(profile.getEmergencyContactName()).isNull();
        assertThat(profile.getEmergencyContactPhone()).isNull();
    }

    @Test
    void addedPlaceIsTrimmedAndKept() {
        SavedPlace place = profile.addPlace("  Home ", " 22 Windsor Lane ", new BigDecimal("12.97"), new BigDecimal("77.59"));

        assertThat(place.getLabel()).isEqualTo("Home");
        assertThat(place.getAddress()).isEqualTo("22 Windsor Lane");
        assertThat(profile.getSavedPlaces()).containsExactly(place);
    }

    @Test
    void labelsAreUniquePerRiderIgnoringCase() {
        addPlace("Home");

        assertThatThrownBy(() -> addPlace("home")).isInstanceOf(ConflictException.class);
        assertThat(profile.getSavedPlaces()).hasSize(1);
    }

    @Test
    void thereIsALimitOnSavedPlaces() {
        for (int i = 1; i <= RiderProfile.MAX_SAVED_PLACES; i++) {
            addPlace("Place " + i);
        }

        assertThatThrownBy(() -> addPlace("One too many"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void removingAPlaceDeletesIt() {
        SavedPlace home = addPlace("Home");
        SavedPlace work = addPlace("Work");

        profile.removePlace(home.getId());

        assertThat(profile.getSavedPlaces()).containsExactly(work);
    }

    @Test
    void aPlaceThatIsNotInThisProfileCannotBeRemoved() {
        addPlace("Home");

        // The same lookup rejects "someone else's id" and "no such id": a caller learns nothing
        assertThatThrownBy(() -> profile.removePlace(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(profile.getSavedPlaces()).hasSize(1);
    }
}
