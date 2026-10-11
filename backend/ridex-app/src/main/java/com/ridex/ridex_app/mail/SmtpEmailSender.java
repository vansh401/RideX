package com.ridex.ridex_app.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends plain-text email through whatever SMTP server spring.mail.* points at: Mailpit locally
 * (read the inbox at http://localhost:8025), a real provider in production.
 */
@Component
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, @Value("${ridex.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendVerificationEmail(String toEmail, String fullName, String verifyUrl) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject("Verify your RideX account");
        message.setText("Hi " + fullName + ",\n\n"
                + "Welcome to RideX! Please confirm your email address by opening this link:\n\n"
                + verifyUrl + "\n\n"
                + "The link works once and expires soon. If you did not create an account, "
                + "you can ignore this email.\n");
        mailSender.send(message);
    }
}
