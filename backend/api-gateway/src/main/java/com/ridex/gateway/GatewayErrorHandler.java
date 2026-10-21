package com.ridex.gateway;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * When the gateway itself fails (a service is down, too slow, or something unexpected), the caller still gets the JSON shape every
 * RideX service uses, with an honest status:
 *   service did not answer in time  -> 504,  service refused or dropped the connection -> 503 with Retry-After,
 *   anything else we did not expect  -> 500 (details only in the log, never to the caller).
 */
@Component
@Order(-2)   // before Spring Boot's default error handler
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (error instanceof ResponseStatusException status) {
            HttpStatus resolved = HttpStatus.resolve(status.getStatusCode().value());
            if (resolved != null) {
                return EdgeErrors.write(exchange, resolved, status.getReason() == null ? resolved.getReasonPhrase() : status.getReason());
            }
        }
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof TimeoutException || cause.getClass().getSimpleName().contains("Timeout")) {
                log.warn("A service did not answer in time for {}", exchange.getRequest().getPath().value());
                return EdgeErrors.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "The service took too long to answer. Please try again");
            }
            if (cause instanceof ConnectException || cause instanceof ClosedChannelException
                    || cause.getClass().getSimpleName().contains("ConnectException")) {
                log.warn("A service could not be reached for {}", exchange.getRequest().getPath().value());
                exchange.getResponse().getHeaders().set("Retry-After", "5");
                return EdgeErrors.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "This part of RideX is temporarily unavailable. Please try again in a moment");
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        log.error("Unexpected gateway error for {}", exchange.getRequest().getPath().value(), error);
        return EdgeErrors.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");
    }
}
