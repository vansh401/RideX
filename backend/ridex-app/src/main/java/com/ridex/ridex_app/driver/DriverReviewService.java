package com.ridex.ridex_app.driver;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.common.Paging;
import com.ridex.ridex_app.common.ResourceNotFoundException;

/** What an admin does with driver applications: browse the queue, inspect one, decide. */
@Service
public class DriverReviewService {

    private final DriverProfileRepository profileRepository;
    private final DriverDocumentRepository documentRepository;
    private final DriverDocumentService documentService;
    private final VehicleService vehicleService;

    public DriverReviewService(DriverProfileRepository profileRepository,
                               DriverDocumentRepository documentRepository,
                               DriverDocumentService documentService,
                               VehicleService vehicleService) {
        this.profileRepository = profileRepository;
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.vehicleService = vehicleService;
    }

    /** Oldest application first, so nobody waits forever. */
    @Transactional(readOnly = true)
    public PageResponse<AdminDriverSummary> list(ApprovalStatus status, int page, int size) {
        Pageable pageable = Paging.of(page, size, Sort.by("createdAt").ascending());
        Page<DriverProfile> result = status == null
                ? profileRepository.findAll(pageable)
                : profileRepository.findByApprovalStatus(status, pageable);
        return PageResponse.from(result, AdminDriverSummary::from);
    }

    @Transactional(readOnly = true)
    public AdminDriverDetail detail(UUID profileId) {
        DriverProfile profile = load(profileId);
        List<DocumentResponse> documents = documentRepository
                .findByDriverProfileIdOrderByUploadedAtAsc(profileId).stream().map(DocumentResponse::from).toList();
        return AdminDriverDetail.from(profile, documents, vehicleService.listOfProfile(profileId));
    }

    @Transactional(readOnly = true)
    public DocumentFile document(UUID profileId, UUID documentId) {
        return documentService.fileOf(profileId, documentId);
    }

    /**
     * Approval has business preconditions on top of the state machine: the driver must have
     * uploaded a driving licence, and the licence must not already have expired.
     */
    @Transactional
    public AdminDriverDetail approve(UUID adminId, UUID profileId) {
        DriverProfile profile = load(profileId);
        if (!documentRepository.existsByDriverProfileIdAndType(profileId, DocumentType.DRIVING_LICENSE)) {
            throw new ConflictException("Cannot approve: no driving licence document has been uploaded");
        }
        if (!profile.getLicenseExpiry().isAfter(LocalDate.now())) {
            throw new ConflictException("Cannot approve: the licence has expired");
        }
        profile.approve(adminId, Instant.now());
        return detail(profileId);
    }

    @Transactional
    public AdminDriverDetail reject(UUID adminId, UUID profileId, String reason) {
        DriverProfile profile = load(profileId);
        profile.reject(adminId, reason.trim(), Instant.now());
        return detail(profileId);
    }

    private DriverProfile load(UUID profileId) {
        return profileRepository.findWithUserById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));
    }
}
