package com.ridex.ridex_app.ride;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The database half of matching. WHO IS NEARBY is answered by Redis GEO (see DriverLocationIndex);
 * this asks PostgreSQL the question only it can answer reliably: is this particular driver really
 * free right now, and if so, lock them.
 */
public interface DispatchRepository extends Repository<Ride, UUID> {

    interface Candidate {
        UUID getDriverProfileId();

        UUID getVehicleId();
    }

    /**
     * Returns the driver (and their active vehicle) if they can take this ride RIGHT NOW, locked for
     * this transaction; otherwise returns nothing.
     *
     * Available means: approved, account active, online, an active vehicle of the requested
     * category, not already on a ride, and not one who already declined THIS ride.
     *
     * FOR UPDATE ... SKIP LOCKED: if another transaction is assigning this same driver at this very
     * moment, the row is locked, so this query silently returns nothing instead of waiting or
     * double-booking. This is the database-level wall; the Redis lock in front of it stops most
     * collisions earlier and cheaper, and the partial unique index on rides is the last wall.
     * Must run inside a transaction: the lock lives until it ends.
     */
    @Query(nativeQuery = true, value = """
            SELECT dp.id AS driverProfileId, v.id AS vehicleId
            FROM driver_profiles dp
            JOIN users u ON u.id = dp.user_id
            JOIN vehicles v ON v.driver_profile_id = dp.id AND v.active = TRUE
            WHERE dp.id = :driverId
              AND dp.approval_status = 'APPROVED'
              AND dp.online = TRUE
              AND u.status = 'ACTIVE'
              AND v.category = :category
              AND NOT EXISTS (SELECT 1 FROM ride_rejections rr
                              WHERE rr.ride_id = :rideId AND rr.driver_profile_id = dp.id)
              AND NOT EXISTS (SELECT 1 FROM rides r
                              WHERE r.driver_profile_id = dp.id
                                AND r.status IN ('DRIVER_ASSIGNED', 'DRIVER_ACCEPTED', 'DRIVER_ARRIVED', 'RIDE_STARTED'))
            FOR UPDATE OF dp SKIP LOCKED
            """)
    List<Candidate> lockIfAvailable(@Param("rideId") UUID rideId,
                                    @Param("driverId") UUID driverId,
                                    @Param("category") String category);
}
