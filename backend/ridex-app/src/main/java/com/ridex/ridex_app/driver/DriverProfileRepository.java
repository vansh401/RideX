package com.ridex.ridex_app.driver;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;

public interface DriverProfileRepository extends JpaRepository<DriverProfile, UUID> {

    Optional<DriverProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    boolean existsByLicenseNumber(String licenseNumber);

    // The admin list shows each driver's name and email. The user association is LAZY, so
    // without the entity graph every row would trigger its own "select user" (the N+1 problem).
    // With it, Hibernate joins the users in the same single query (plus one count query).
    @EntityGraph(attributePaths = "user")
    Page<DriverProfile> findByApprovalStatus(ApprovalStatus status, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "user")
    Page<DriverProfile> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Optional<DriverProfile> findWithUserById(UUID id);

    /** Atomic increment: two ratings arriving together both count (no lost update). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DriverProfile p set p.ratingSum = p.ratingSum + :stars, p.ratingCount = p.ratingCount + 1 where p.id = :id")
    int addRating(@Param("id") UUID id, @Param("stars") int stars);

    long countByApprovalStatus(ApprovalStatus status);

    long countByOnlineTrue();
}
