package com.ridex.ridex_app.auth;

/**
 * Published inside the transaction that created the reset token; the email is only sent if
 * that transaction commits. Carries the raw token because it is not stored.
 */
public record PasswordResetEmailRequested(String toEmail, String fullName, String rawToken) {
}
