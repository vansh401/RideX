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
    public void sendVerificationEmail(String toEmail, String fullName, String verifyUrl, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject("Verify your RideX account");
        message.setText("Hi " + fullName + ",\n\n"
                + "Welcome to RideX! Please confirm your email address by opening this link:\n\n"
                + verifyUrl + "\n\n"
                + "Or enter this 6-digit code on the verification page: " + otp + "\n\n"
                + "The link and the code work once and expire soon. If you did not create an account, "
                + "you can ignore this email.\n");
        mailSender.send(message);
    }

    @Override
    public void sendNotice(String toEmail, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(text);
        mailSender.send(message);
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String fullName, String resetUrl) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject("Reset your RideX password");
        message.setText("Hi " + fullName + ",\n\n"
                + "We received a request to reset your RideX password. "
                + "Choose a new one by opening this link:\n\n"
                + resetUrl + "\n\n"
                + "The link works once and expires soon. If you did not ask for this, "
                + "ignore this email: your password has not changed.\n");
        mailSender.send(message);
    }
}
