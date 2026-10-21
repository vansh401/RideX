package com.ridex.platform.web;

/** Maps to HTTP 409: the request is fine, but not allowed in the current state. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
