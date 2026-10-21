package com.ridex.platform.web;

/** Maps to HTTP 400: the request is understood but its content is not acceptable. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
