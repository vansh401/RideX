package com.ridex.ridex_app.auth;

/** The password reset link is unknown, already used or expired. Mapped to HTTP 400. */
public class InvalidResetTokenException extends RuntimeException {

    public InvalidResetTokenException(String message) {
        super(message);
    }
}
