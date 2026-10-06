package com.ridex.ridex_app.common;

/** Thrown when a request clashes with existing data. Mapped to HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}