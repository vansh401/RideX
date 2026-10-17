package com.ridex.ridex_app.rider;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Send both fields to set the emergency contact, or neither (null) to clear it. */
public record UpdateRiderProfileRequest(

        @Size(max = 100, message = "must be at most 100 characters")
        String emergencyContactName,

        @Pattern(regexp = "^\\+?[0-9]{10,15}$",
                 message = "must be 10-15 digits with an optional leading +")
        String emergencyContactPhone
) {
    @AssertTrue(message = "name and phone must be provided together")
    public boolean isEmergencyContactComplete() {
        boolean hasName = emergencyContactName != null && !emergencyContactName.isBlank();
        boolean hasPhone = emergencyContactPhone != null && !emergencyContactPhone.isBlank();
        return hasName == hasPhone;
    }
}
