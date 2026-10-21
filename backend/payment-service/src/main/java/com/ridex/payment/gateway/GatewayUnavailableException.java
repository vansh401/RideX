package com.ridex.payment.gateway;

/** The gateway gave no usable answer (timeout, server error, circuit open, too many calls at once). Outcome UNKNOWN. */
public class GatewayUnavailableException extends RuntimeException {

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
