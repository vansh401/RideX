package com.ridex.ridex_app.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ridex.platform.web.LogContext;
import com.ridex.ridex_app.mail.EmailSender;

/** Sends the password reset email only after the database transaction has committed. */
@Component
public class PasswordResetEmailListener {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailListener.class);

    private final EmailSender emailSender;
    private final String frontendBaseUrl;

    public PasswordResetEmailListener(EmailSender emailSender,
                                      @Value("${ridex.frontend.base-url}") String frontendBaseUrl) {
        this.emailSender = emailSender;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPasswordResetEmailRequested(PasswordResetEmailRequested event) {
        String url = frontendBaseUrl + "/reset-password?token=" + event.rawToken();
        try {
            emailSender.sendPasswordResetEmail(event.toEmail(), event.fullName(), url);
        } catch (RuntimeException ex) {
            // The user can simply ask for another link
            log.error("Could not send password reset email to {}", LogContext.maskEmail(event.toEmail()), ex);
        }
    }
}
