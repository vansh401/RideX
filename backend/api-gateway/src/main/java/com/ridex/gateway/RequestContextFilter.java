package com.ridex.gateway;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * The first thing every request meets. Four jobs, all about trusting nothing the client says about itself:
 *
 *  1. BLOCK paths that exist inside the platform (/internal, /mock-gateway, /actuator) from the outside.
 *  2. CLEAN the request: remove X-User-Id / X-User-Role (only the gateway may set them, after checking a token).
 *     X-Forwarded-For is replaced by the real network address of the caller by Spring Cloud Gateway itself, see
 *     trusted-proxies in application.yml, so services rate-limit the right person.
 *  3. GIVE it a correlation id (X-Request-Id), sent to the services and back to the caller, so one request can be followed
 *     through five logs.
 *  4. LOG it when it ends: method, path, status, time, user. Never the query string (it can carry secrets), never a header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestContextFilter implements WebFilter {

    static final String REQUEST_ID = "X-Request-Id";
    static final String USER_ID = "X-User-Id";
    static final String USER_ROLE = "X-User-Role";
    static final String ATTR_USER = "ridex.gateway.userId";

    private static final Logger access = LoggerFactory.getLogger("gateway.access");
    /** A caller may bring its own correlation id, but only a harmless-looking one (it ends up in logs). */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private final PathRules blocked;
    private final PathRules publicPaths;

    public RequestContextFilter(GatewayProperties properties) {
        this.blocked = new PathRules(properties.blockedPaths());
        this.publicPaths = new PathRules(properties.publicPaths());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long started = System.nanoTime();
        var request = exchange.getRequest();
        String path = request.getPath().value();

        String supplied = request.getHeaders().getFirst(REQUEST_ID);
        String requestId = supplied != null && SAFE_ID.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
        InetSocketAddress peer = request.getRemoteAddress();
        String client = peer == null || peer.getAddress() == null ? "unknown" : peer.getAddress().getHostAddress();

        ServerWebExchange clean = exchange.mutate().request(r -> r.headers(h -> {
            h.remove(USER_ID);
            h.remove(USER_ROLE);
            h.set(REQUEST_ID, requestId);
        })).build();
        clean.getResponse().getHeaders().set(REQUEST_ID, requestId);

        Mono<Void> work;
        if (blocked.matches(request.getMethod(), path) && !publicPaths.matches(request.getMethod(), path)) {
            work = EdgeErrors.write(clean, HttpStatus.NOT_FOUND, "Not found");
        } else {
            work = chain.filter(clean);
        }
        return work.doFinally(signal -> {
            Object user = clean.getAttribute(ATTR_USER);
            var status = clean.getResponse().getStatusCode();
            access.info("{} {} -> {} in {} ms [requestId={} client={} user={}]", request.getMethod(), path,
                    status == null ? "?" : status.value(), (System.nanoTime() - started) / 1_000_000, requestId, client,
                    user == null ? "-" : user);
        });
    }
}
