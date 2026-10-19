package com.ridex.ridex_app.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

class DriverReviewServiceTest {

    private final DriverProfileRepository profileRepository = mock(DriverProfileRepository.class);
    private final DriverDocumentRepository documentRepository = mock(DriverDocumentRepository.class);
    private final DriverDocumentService documentService = mock(DriverDocumentService.class);
    private final VehicleService vehicleService = mock(VehicleService.class);
    private final DriverReviewService service =
            new DriverReviewService(profileRepository, documentRepository, documentService, vehicleService);

    private final UUID adminId = UUID.randomUUID();
    private final User user = new User("Ravi Kumar", "ravi@example.com", "+919812345678", "hash", Role.DRIVER);

    private DriverProfile profile(LocalDate expiry) {
        DriverProfile profile = new DriverProfile(user, "DL-12345", expiry);
        when(profileRepository.findWithUserById(profile.getId())).thenReturn(Optional.of(profile));
        return profile;
    }

    private void hasLicenceDocument(DriverProfile profile, boolean has) {
        when(documentRepository.existsByDriverProfileIdAndType(profile.getId(), DocumentType.DRIVING_LICENSE)).thenReturn(has);
    }

    @Test
    void approvalNeedsAnUploadedLicence() {
        DriverProfile profile = profile(LocalDate.now().plusYears(1));
        hasLicenceDocument(profile, false);

        assertThatThrownBy(() -> service.approve(adminId, profile.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("driving licence");
        assertThat(profile.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
    }

    @Test
    void approvalRefusesAnExpiredLicence() {
        DriverProfile profile = profile(LocalDate.now().minusDays(1));
        hasLicenceDocument(profile, true);

        assertThatThrownBy(() -> service.approve(adminId, profile.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void approvalRecordsTheAdmin() {
        DriverProfile profile = profile(LocalDate.now().plusYears(1));
        hasLicenceDocument(profile, true);

        AdminDriverDetail detail = service.approve(adminId, profile.getId());

        assertThat(detail.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(detail.reviewedBy()).isEqualTo(adminId);
        assertThat(detail.email()).isEqualTo("ravi@example.com");
    }

    @Test
    void rejectionStoresATrimmedReason() {
        DriverProfile profile = profile(LocalDate.now().plusYears(1));

        AdminDriverDetail detail = service.reject(adminId, profile.getId(), "  Photo is blurry ");

        assertThat(detail.approvalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(detail.rejectionReason()).isEqualTo("Photo is blurry");
    }

    @Test
    void unknownProfilesAreNotFound() {
        UUID id = UUID.randomUUID();
        when(profileRepository.findWithUserById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(adminId, id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.detail(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theQueueCanBeFilteredByStatusAndSizeIsClamped() {
        DriverProfile profile = new DriverProfile(user, "DL-12345", LocalDate.now().plusYears(1));
        when(profileRepository.findByApprovalStatus(eq(ApprovalStatus.PENDING_APPROVAL), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(profile)));

        PageResponse<AdminDriverSummary> page = service.list(ApprovalStatus.PENDING_APPROVAL, -3, 100000);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).fullName()).isEqualTo("Ravi Kumar");
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(profileRepository).findByApprovalStatus(eq(ApprovalStatus.PENDING_APPROVAL), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }
}
