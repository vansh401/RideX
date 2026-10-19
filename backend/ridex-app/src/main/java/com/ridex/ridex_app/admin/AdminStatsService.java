package com.ridex.ridex_app.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.driver.ApprovalStatus;
import com.ridex.ridex_app.driver.DriverProfileRepository;
import com.ridex.ridex_app.ride.RideRepository;
import com.ridex.ridex_app.ride.RideStatus;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.UserRepository;
import com.ridex.ridex_app.user.UserStatus;

/**
 * Reporting is the one place where reading across modules is natural: it only COUNTS, through a
 * handful of grouped queries, and never changes anything. (Later it becomes an Analytics service
 * fed by events instead of reading the tables directly.)
 */
@Service
public class AdminStatsService {

    private final UserRepository userRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final RideRepository rideRepository;

    public AdminStatsService(UserRepository userRepository,
                             DriverProfileRepository driverProfileRepository,
                             RideRepository rideRepository) {
        this.userRepository = userRepository;
        this.driverProfileRepository = driverProfileRepository;
        this.rideRepository = rideRepository;
    }

    @Transactional(readOnly = true)
    public AdminStats stats() {
        Map<Role, Long> usersByRole = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            usersByRole.put(role, 0L);
        }
        userRepository.countGroupedByRole().forEach(row -> usersByRole.put((Role) row[0], (Long) row[1]));

        Map<RideStatus, Long> ridesByStatus = new EnumMap<>(RideStatus.class);
        for (RideStatus status : RideStatus.values()) {
            ridesByStatus.put(status, 0L);
        }
        rideRepository.countGroupedByStatus().forEach(row -> ridesByStatus.put((RideStatus) row[0], (Long) row[1]));

        return new AdminStats(
                usersByRole,
                userRepository.countByStatus(UserStatus.SUSPENDED),
                driverProfileRepository.countByApprovalStatus(ApprovalStatus.PENDING_APPROVAL),
                driverProfileRepository.countByOnlineTrue(),
                ridesByStatus,
                rideRepository.countByRequestedAtAfter(Instant.now().minus(Duration.ofHours(24))),
                rideRepository.sumTotalFareByStatus(RideStatus.RIDE_COMPLETED),
                "INR");
    }
}
