package com.ridex.ridex_app.driver;

/** Where a driver is in the review process. Admin approve/reject arrives in step 3.3. */
public enum ApprovalStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED
}
