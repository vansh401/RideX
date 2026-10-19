package com.ridex.ridex_app.common;

/** The request is well-formed but asks for something impossible (e.g. a 2,000 km ride). Mapped to HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
