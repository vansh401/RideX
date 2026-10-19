package com.ridex.ridex_app.driver;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Metadata about an uploaded file. The bytes themselves are in FileStorage under storageKey. */
@Entity
@Table(name = "driver_documents")
public class DriverDocument {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_profile_id", nullable = false, updatable = false)
    private DriverProfile driverProfile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType type;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    // Required by JPA; not for application code
    protected DriverDocument() {
    }

    public DriverDocument(DriverProfile driverProfile, DocumentType type, String originalFilename,
                          String contentType, long sizeBytes, String storageKey) {
        this.id = UUID.randomUUID();
        this.driverProfile = driverProfile;
        this.type = type;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.uploadedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public DriverProfile getDriverProfile() { return driverProfile; }
    public DocumentType getType() { return type; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getStorageKey() { return storageKey; }
    public Instant getUploadedAt() { return uploadedAt; }
}
