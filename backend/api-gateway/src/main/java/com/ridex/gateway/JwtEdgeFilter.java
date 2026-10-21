package com.ridex.gateway;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * JWT validation AT THE EDGE.
 *
 * Why here: a request without a valid token is turned away once, cheaply, before it touches any service. The services see only
 * traffic that has already proved who it is, and the gateway adds two headers saying who: X-User-Id and X-User-Role.
 *
 * What it deliberately does NOT do:
 *  - It does not decide what a role may do. "May this rider see this ride?" needs business knowledge, so authorization stays in
 *    the services.
 *  - It does not replace the services' own token checks. The Authorization header is passed through unchanged and every service
 *    verifies it again (DEFENCE IN DEPTH): if the gateway is bypassed, misconfigured or breached, the services are still safe.
 *    The X-User-* headers are an identity hint for logging and tracing; no service makes a security decision from them.
 *
 * Revocation (logout, suspension) is checked here too, against the same Redis entries the services read.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class JwtEdgeFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtEdgeFilter.class);
    private static final String BEARER = "Bearer ";

    private final ReactiveJwtDecoder decoder;
    private final ReactiveStringRedisTemplate redis;
    private final PathRules publicPaths;

    public JwtEdgeFilter(ReactiveJwtDecoder decoder, ReactiveStringRedisTemplate redis, GatewayProperties properties) {
        this.decoder = decoder;
        this.redis = redis;
        this.publicPaths = new PathRules(properties.publicPaths());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        // A browser's CORS preflight carries no token by design; the CORS handler answers it
        if (HttpMethod.OPTIONS.equals(request.getMethod()) || publicPaths.matches(request.getMethod(), request.getPath().value())) {
            return chain.filter(exchange);
        }
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return EdgeErrors.write(exchange, HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        return decoder.decode(header.substring(BEARER.length()).trim())
                .flatMap(jwt -> checkNotRevoked(jwt).filter(ok -> ok).map(ok -> jwt))
                .map(jwt -> identify(exchange, jwt))
                .onErrorResume(e -> {
                    log.debug("Token refused at the edge: {}", e.getClass().getSimpleName());
                    return Mono.empty();
                })
                // Wrapped so that "refused" (empty) is told apart from "the request was served" (which also ends empty)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(identified -> identified.isPresent()
                        ? chain.filter(identified.get())
                        : EdgeErrors.write(exchange, HttpStatus.UNAUTHORIZED, "Authentication is required or the credentials are invalid"));
    }

    /** Adds who the caller is, as headers the services can log. Returns a new exchange to pass down the chain. */
    private ServerWebExchange identify(ServerWebExchange exchange, Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());   // throws (and so refuses the token) if it is not a UUID
        String role = jwt.getClaimAsString("role");
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("token has no role");
        }
        exchange.getAttributes().put(RequestContextFilter.ATTR_USER, userId.toString());
        exchange.getAttributes().put("ridex.gateway.role", role);
        return exchange.mutate().request(r -> r.headers(h -> {
            h.set(RequestContextFilter.USER_ID, userId.toString());
            h.set(RequestContextFilter.USER_ROLE, role);
        })).build();
    }

    /** Same keys the core writes and the services read: the user's cut-off time, and individual token ids. */
    private Mono<Boolean> checkNotRevoked(Jwt jwt) {
        Long precise = jwt.getClaim("iat_ms");
        long issuedAt = precise != null ? precise : jwt.getIssuedAt().toEpochMilli();
        return redis.opsForValue().multiGet(List.of("revoked:user:" + jwt.getSubject(), "revoked:jti:" + jwt.getId()))
                .map(values -> {
                    if (values.get(1) != null) {
                        return false;
                    }
                    String cutoff = values.get(0);
                    return cutoff == null || issuedAt > Long.parseLong(cutoff);
                })
                // Same rule as the services: if Redis is unreachable, do not take every user offline for it
                .onErrorResume(e -> {
                    log.warn("Could not check token revocation (Redis unavailable?): {}", e.getClass().getSimpleName());
                    return Mono.just(true);
                });
    }
}
