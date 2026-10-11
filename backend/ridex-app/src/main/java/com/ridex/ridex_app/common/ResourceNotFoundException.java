package com.ridex.ridex_app.common;

/** Thrown when something the caller asked for does not exist. Mapped to HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
