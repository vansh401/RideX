package com.ridex.platform.web;

/** Maps to HTTP 503 with a Retry-After header: a service we depend on is not answering. */
public class ServiceUnavailableException extends RuntimeException {

    private final long retryAfterSeconds;

    public ServiceUnavailableException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
