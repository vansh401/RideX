package com.ridex.ridex_app.mail;

public interface EmailSender {

    void sendVerificationEmail(String toEmail, String fullName, String verifyUrl, String otp);

    void sendPasswordResetEmail(String toEmail, String fullName, String resetUrl);

    /** A plain notice (a safety alert, for example). */
    void sendNotice(String toEmail, String subject, String text);
}
