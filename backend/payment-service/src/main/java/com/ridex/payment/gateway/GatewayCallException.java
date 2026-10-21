package com.ridex.payment.gateway;

/** A single failed call to the gateway: what Resilience4j counts as a failure and retries. */
class GatewayCallException extends RuntimeException {

    GatewayCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
