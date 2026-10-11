package com.ridex.ridex_app.mail;

public interface EmailSender {

    void sendVerificationEmail(String toEmail, String fullName, String verifyUrl);
}
