package com.ridex.ridex_app.ride;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every query that returns rides to be shown uses the same entity graph: rider, driver, the
 * driver's user and the vehicle are joined into the SAME query. The associations are lazy, so
 * without it, showing a page of 20 rides would run 1 + 20 x (up to 4) extra queries (the N+1
 * problem). All four are to-one joins, so pagination stays correct.
 */
public interface RideRepository extends JpaRepository<Ride, UUID> {

    String PEOPLE = "rider";   // documentation only; the graphs below spell the paths out

    @EntityGraph(attributePaths = {"rider", "driver", "driver.user", "vehicle"})
    Optional<Ride> findDetailedById(UUID id);

    @EntityGraph(attributePaths = {"rider", "driver", "driver.user", "vehicle"})
    Optional<Ride> findFirstByRiderIdAndStatusIn(UUID riderId, Collection<RideStatus> statuses);

    @EntityGraph(attributePaths = {"rider", "driver", "driver.user", "vehicle"})
    Optional<Ride> findFirstByDriverIdAndStatusIn(UUID driverProfileId, Collection<RideStatus> statuses);

    /** Is this user the rider or the driver of this ride? (Used to authorise WebSocket subscriptions.) */
    @Query("""
            select count(r) > 0 from Ride r
            left join r.driver d
            left join d.user du
            where r.id = :rideId and (r.rider.id = :userId or du.id = :userId)
            """)
    boolean isParticipant(@Param("rideId") UUID rideId, @Param("userId") UUID userId);

    /** The ride this driver (by user id) is currently on, if any. One cheap lookup through an index. */
    @Query("""
            select r.id from Ride r join r.driver d join d.user du
            where du.id = :userId and r.status in :statuses
            """)
    Optional<UUID> findActiveRideIdOfDriverUser(@Param("userId") UUID userId,
                                               @Param("statuses") Collection<RideStatus> statuses);

    // ----- history (step 3.9) -----

    @EntityGraph(attributePaths = {"rider", "driver", "driver.user", "vehicle"})
    Page<Ride> findByRiderId(UUID riderId, Pageable pageable);

    @EntityGraph(attributePaths = {"rider", "driver", "driver.user", "vehicle"})
    Page<Ride> findByRiderIdAndStatus(UUID riderId, RideStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"rider", "driver", "driver.user", "vehicle"})
    Page<Ride> findByDriverId(UUID driverProfileId, Pageable pageable);

    @EntityGraph(attributePaths = {"rider", "driver", "driver.user", "vehicle"})
    Page<Ride> findByDriverIdAndStatus(UUID driverProfileId, RideStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"rider", "driver", "driver.user", "vehicle"})
    Page<Ride> findByStatus(RideStatus status, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"rider", "driver", "driver.user", "vehicle"})
    Page<Ride> findAll(Pageable pageable);

    // ----- dispatch job: two small groups of rides -----

    @Query("select r.id from Ride r where r.status = :status and r.offerExpiresAt < :now")
    List<UUID> findIdsWithExpiredOffer(@Param("status") RideStatus status, @Param("now") Instant now);

    @Query("select r.id from Ride r where r.status = :status order by r.requestedAt")
    List<UUID> findIdsByStatusOldestFirst(@Param("status") RideStatus status);

    // ----- admin overview -----

    @Query("select r.status, count(r) from Ride r group by r.status")
    List<Object[]> countGroupedByStatus();

    @Query("select coalesce(sum(r.totalFare), 0) from Ride r where r.status = :status")
    BigDecimal sumTotalFareByStatus(@Param("status") RideStatus status);

    long countByRequestedAtAfter(Instant since);
}
