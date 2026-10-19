package com.ridex.ridex_app.auth;

/**
 * Published inside the registration transaction; the email is only sent if that transaction
 * commits (see VerificationEmailListener). Carries the raw token and the one-time code because neither is stored in readable form.
 */
public record VerificationEmailRequested(String toEmail, String fullName, String rawToken, String otp) {
}
