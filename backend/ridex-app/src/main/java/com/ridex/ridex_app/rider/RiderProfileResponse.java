package com.ridex.ridex_app.rider;

import java.util.List;
import java.util.UUID;

/** What the rider sees. Built inside the service transaction, because places are lazy. */
public record RiderProfileResponse(
        UUID id,
        EmergencyContact emergencyContact,
        List<SavedPlaceResponse> savedPlaces
) {
    /** null in the parent record when no contact is set */
    public record EmergencyContact(String name, String phone, String email) {
        public EmergencyContact(String name, String phone) {
            this(name, phone, null);
        }
    }

    public static RiderProfileResponse from(RiderProfile profile) {
        EmergencyContact contact = profile.getEmergencyContactName() == null
                ? null
                : new EmergencyContact(profile.getEmergencyContactName(), profile.getEmergencyContactPhone(), profile.getEmergencyContactEmail());
        return new RiderProfileResponse(
                profile.getId(), contact,
                profile.getSavedPlaces().stream().map(SavedPlaceResponse::from).toList());
    }
}
