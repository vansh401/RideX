package com.ridex.ridex_app.auth;

/** The verification link is unknown, already used or expired. Mapped to HTTP 400. */
public class InvalidVerificationTokenException extends RuntimeException {

    public InvalidVerificationTokenException(String message) {
        super(message);
    }
}
