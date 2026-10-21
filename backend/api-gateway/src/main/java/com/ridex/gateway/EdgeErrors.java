package com.ridex.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/** Errors produced by the gateway itself, in the same JSON shape every RideX service uses, so the frontend handles them alike. */
final class EdgeErrors {

    private EdgeErrors() {
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String message) {
        var response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"timestamp\":\"" + Instant.now() + "\",\"status\":" + status.value() + ",\"error\":\"" + status.name()
                + "\",\"message\":\"" + message.replace("\"", "'") + "\",\"path\":\""
                + exchange.getRequest().getPath().value().replace("\"", "") + "\",\"fieldErrors\":{}}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
