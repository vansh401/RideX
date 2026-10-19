package com.ridex.ridex_app.ride;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The matching query. Native SQL, because it needs PostgreSQL's row locking and trigonometry. */
public interface DispatchRepository extends Repository<Ride, UUID> {

    interface Candidate {
        UUID getDriverProfileId();

        UUID getVehicleId();
    }

    /**
     * The nearest driver who can take this ride RIGHT NOW, locked for this transaction.
     *
     * Who counts as available: approved, account active, online, location fresh, an active vehicle
     * of the requested category, not already on a ride, not one who already declined THIS ride,
     * and within the search radius. They are ordered by straight-line (haversine) distance.
     *
     * FOR UPDATE ... SKIP LOCKED is the important part. If two riders request at the same moment,
     * the first transaction locks the nearest driver's row; the second does not wait and does not
     * get the same driver: it silently skips locked rows and takes the next nearest. So one driver
     * can never be offered to two rides. (The partial unique index on rides is the second wall.)
     * Must run inside a transaction: the lock lives until it ends.
     */
    @Query(nativeQuery = true, value = """
            SELECT dp.id AS driverProfileId, v.id AS vehicleId
            FROM driver_profiles dp
            JOIN users u ON u.id = dp.user_id
            JOIN vehicles v ON v.driver_profile_id = dp.id AND v.active = TRUE
            WHERE dp.approval_status = 'APPROVED'
              AND dp.online = TRUE
              AND u.status = 'ACTIVE'
              AND v.category = :category
              AND dp.last_seen_at >= :freshSince
              AND dp.last_latitude IS NOT NULL AND dp.last_longitude IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM ride_rejections rr
                              WHERE rr.ride_id = :rideId AND rr.driver_profile_id = dp.id)
              AND NOT EXISTS (SELECT 1 FROM rides r
                              WHERE r.driver_profile_id = dp.id
                                AND r.status IN ('DRIVER_ASSIGNED', 'DRIVER_ACCEPTED', 'DRIVER_ARRIVED', 'RIDE_STARTED'))
              AND 6371.0088 * acos(LEAST(1.0,
                    cos(radians(:lat)) * cos(radians(dp.last_latitude)) * cos(radians(dp.last_longitude) - radians(:lng))
                  + sin(radians(:lat)) * sin(radians(dp.last_latitude)))) <= :radiusKm
            ORDER BY 6371.0088 * acos(LEAST(1.0,
                    cos(radians(:lat)) * cos(radians(dp.last_latitude)) * cos(radians(dp.last_longitude) - radians(:lng))
                  + sin(radians(:lat)) * sin(radians(dp.last_latitude)))) ASC
            LIMIT 1
            FOR UPDATE OF dp SKIP LOCKED
            """)
    List<Candidate> lockNearestAvailableDriver(@Param("rideId") UUID rideId,
                                               @Param("category") String category,
                                               @Param("lat") BigDecimal pickupLatitude,
                                               @Param("lng") BigDecimal pickupLongitude,
                                               @Param("radiusKm") double radiusKm,
                                               @Param("freshSince") Instant freshSince);
}
