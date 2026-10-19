package com.ridex.ridex_app.rider;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiderProfileRepository extends JpaRepository<RiderProfile, UUID> {

    /**
     * The places are LAZY, so without help, reading them would trigger a SECOND query (and in a
     * list of N profiles, N more: the "N+1 problem"). The entity graph tells Hibernate to fetch
     * the profile and its places together in ONE query (a join).
     * "ByUserId" walks the association: it filters on user.id without loading the User.
     */
    @EntityGraph(attributePaths = "savedPlaces")
    Optional<RiderProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    /** Atomic increment: two ratings arriving together both count (no lost update). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RiderProfile p set p.ratingSum = p.ratingSum + :stars, p.ratingCount = p.ratingCount + 1 where p.user.id = :userId")
    int addRating(@Param("userId") UUID userId, @Param("stars") int stars);
}
