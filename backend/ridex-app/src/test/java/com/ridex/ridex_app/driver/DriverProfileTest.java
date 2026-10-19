package com.ridex.ridex_app.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

class DriverProfileTest {

    private final User user = new User("Ravi Kumar", "ravi@example.com", "+919812345678", "hash", Role.DRIVER);
    private final UUID adminId = UUID.randomUUID();

    private DriverProfile pending() {
        return new DriverProfile(user, "DL-0420110012345", LocalDate.now().plusYears(2));
    }

    @Test
    void newProfileWaitsForReview() {
        assertThat(pending().getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
    }

    @Test
    void approvalRecordsWhoDecidedAndWhen() {
        DriverProfile profile = pending();
        Instant now = Instant.now();

        profile.approve(adminId, now);

        assertThat(profile.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(profile.isApproved()).isTrue();
        assertThat(profile.getReviewedBy()).isEqualTo(adminId);
        assertThat(profile.getReviewedAt()).isEqualTo(now);
    }

    @Test
    void rejectionKeepsTheReason() {
        DriverProfile profile = pending();

        profile.reject(adminId, "Licence photo unreadable", Instant.now());

        assertThat(profile.getApprovalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(profile.getRejectionReason()).isEqualTo("Licence photo unreadable");
    }

    @Test
    void licenceCanBeEditedWhilePending() {
        DriverProfile profile = pending();
        LocalDate newExpiry = LocalDate.now().plusYears(3);

        profile.changeLicence("DL-9999999999999", newExpiry);

        assertThat(profile.getLicenseNumber()).isEqualTo("DL-9999999999999");
        assertThat(profile.getLicenseExpiry()).isEqualTo(newExpiry);
        assertThat(profile.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
    }

    @Test
    void correctingARejectedProfileResubmitsItAndClearsTheOldDecision() {
        DriverProfile profile = pending();
        profile.reject(adminId, "Wrong number", Instant.now());

        profile.changeLicence("DL-9999999999999", LocalDate.now().plusYears(3));

        assertThat(profile.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
        assertThat(profile.getRejectionReason()).isNull();
        assertThat(profile.getReviewedBy()).isNull();
        assertThat(profile.getReviewedAt()).isNull();
    }

    @Test
    void anApprovedProfileIsLocked() {
        DriverProfile profile = pending();
        profile.approve(adminId, Instant.now());

        assertThatThrownBy(() -> profile.approve(adminId, Instant.now())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> profile.reject(adminId, "x", Instant.now())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> profile.changeLicence("DL-1", LocalDate.now().plusYears(1)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aRejectedProfileCannotBeApprovedWithoutResubmitting() {
        DriverProfile profile = pending();
        profile.reject(adminId, "No", Instant.now());

        assertThatThrownBy(() -> profile.approve(adminId, Instant.now())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> profile.reject(adminId, "again", Instant.now())).isInstanceOf(ConflictException.class);
    }
}
