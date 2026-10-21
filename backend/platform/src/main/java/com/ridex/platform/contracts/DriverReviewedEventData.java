package com.ridex.platform.contracts;

import java.util.UUID;

/** The facts of a "driver.application-reviewed" event (version 1). */
public record DriverReviewedEventData(UUID driverUserId, String decision, String reason) {

    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
}
