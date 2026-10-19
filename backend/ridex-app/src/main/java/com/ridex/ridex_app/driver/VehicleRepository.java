package com.ridex.ridex_app.driver;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    List<Vehicle> findByDriverProfileIdOrderByCreatedAtAsc(UUID driverProfileId);

    /** Scoped by profile: a vehicle id alone never reaches another driver's vehicle. */
    Optional<Vehicle> findByIdAndDriverProfileId(UUID id, UUID driverProfileId);

    Optional<Vehicle> findByDriverProfileIdAndActiveTrue(UUID driverProfileId);

    long countByDriverProfileId(UUID driverProfileId);

    boolean existsByRegistrationNumber(String registrationNumber);

    // Admin list: show the owner's name without one extra query per vehicle (N+1)
    @EntityGraph(attributePaths = {"driverProfile", "driverProfile.user"})
    Page<Vehicle> findByCategory(VehicleCategory category, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"driverProfile", "driverProfile.user"})
    Page<Vehicle> findAll(Pageable pageable);
}
