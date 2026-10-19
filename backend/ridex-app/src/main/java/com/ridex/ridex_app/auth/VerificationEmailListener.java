package com.ridex.ridex_app.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ridex.ridex_app.mail.EmailSender;

/** Sends the verification email only after the database transaction has committed. */
@Component
public class VerificationEmailListener {

    private static final Logger log = LoggerFactory.getLogger(VerificationEmailListener.class);

    private final EmailSender emailSender;
    private final String frontendBaseUrl;

    public VerificationEmailListener(EmailSender emailSender,
                                     @Value("${ridex.frontend.base-url}") String frontendBaseUrl) {
        this.emailSender = emailSender;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVerificationEmailRequested(VerificationEmailRequested event) {
        String url = frontendBaseUrl + "/verify-email?token=" + event.rawToken();
        try {
            emailSender.sendVerificationEmail(event.toEmail(), event.fullName(), url, event.otp());
        } catch (RuntimeException ex) {
            // The account exists; the user can ask for another link via /resend-verification
            log.error("Could not send verification email to {}", event.toEmail(), ex);
        }
    }
}
