package com.ridex.ridex_app.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

class DriverProfileTest {

    private final User user = new User("Ravi Kumar", "ravi@example.com", "+919812345678", "hash", Role.DRIVER);

    private DriverProfile pending() {
        return new DriverProfile(user, "DL-0420110012345", LocalDate.now().plusYears(2));
    }

    @Test
    void newProfileWaitsForReview() {
        assertThat(pending().getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
    }

    @Test
    void licenceCanBeCorrectedWhileThePendingReviewIsOpen() {
        DriverProfile profile = pending();
        LocalDate newExpiry = LocalDate.now().plusYears(3);

        profile.changeLicence("DL-9999999999999", newExpiry);

        assertThat(profile.getLicenseNumber()).isEqualTo("DL-9999999999999");
        assertThat(profile.getLicenseExpiry()).isEqualTo(newExpiry);
    }

    @Test
    void licenceCannotBeChangedAfterTheReviewHasDecided() throws Exception {
        for (ApprovalStatus decided : new ApprovalStatus[] {ApprovalStatus.APPROVED, ApprovalStatus.REJECTED}) {
            DriverProfile profile = pending();
            // Approve/reject arrive in step 3.3; set the state directly for now
            Field status = DriverProfile.class.getDeclaredField("approvalStatus");
            status.setAccessible(true);
            status.set(profile, decided);

            assertThatThrownBy(() -> profile.changeLicence("DL-1234567", LocalDate.now().plusYears(1)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining(decided.name());
        }
    }
}
