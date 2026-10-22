package com.ridex.platform.contracts;

import java.math.BigDecimal;
import java.util.UUID;

/** The facts of "complaint.filed" and "complaint.resolved" events (version 1). Schema: contracts/events/complaint.v1.json */
public record ComplaintEventData(
        UUID complaintId,
        UUID rideId,
        UUID userId,
        String category,
        String status,
        /** On "complaint.resolved": the money to give back to the person who complained (null = none). */
        BigDecimal refundAmount,
        String resolutionNote
) {
}
