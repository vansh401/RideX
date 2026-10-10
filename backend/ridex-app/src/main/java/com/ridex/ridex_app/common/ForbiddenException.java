package com.ridex.ridex_app.common;

/** Thrown when the caller is known but not allowed to proceed. Mapped to HTTP 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}