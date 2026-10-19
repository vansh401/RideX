package com.ridex.ridex_app.admin;

import java.math.BigDecimal;
import java.util.Map;

import com.ridex.ridex_app.ride.RideStatus;
import com.ridex.ridex_app.user.Role;

/** The headline numbers for the admin overview. */
public record AdminStats(
        Map<Role, Long> usersByRole,
        long suspendedUsers,
        long pendingDriverApplications,
        long onlineDrivers,
        Map<RideStatus, Long> ridesByStatus,
        long ridesInLast24Hours,
        /** Sum of the fares of completed rides (tax included). */
        BigDecimal completedRevenue,
        String currency
) {
}
