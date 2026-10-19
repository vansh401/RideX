package com.ridex.ridex_app.driver;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverDocumentRepository extends JpaRepository<DriverDocument, UUID> {

    List<DriverDocument> findByDriverProfileIdOrderByUploadedAtAsc(UUID driverProfileId);

    Optional<DriverDocument> findByDriverProfileIdAndType(UUID driverProfileId, DocumentType type);

    /** Scoped by profile: a document id alone never reaches another driver's file. */
    Optional<DriverDocument> findByIdAndDriverProfileId(UUID id, UUID driverProfileId);

    boolean existsByDriverProfileIdAndType(UUID driverProfileId, DocumentType type);
}
