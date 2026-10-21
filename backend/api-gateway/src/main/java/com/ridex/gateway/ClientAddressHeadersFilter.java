package com.ridex.gateway;

import java.net.InetSocketAddress;

import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * Tells the services who the caller really is.
 *
 * Services tell clients apart by the X-Forwarded-For header (the core uses it for its per-client rate limits). A client can
 * write anything in that header, so the gateway, which is the edge, REPLACES it with the address of the actual connection.
 * It runs last, after the standard header clean-up, so nothing can put the client's own value back.
 * Behind a load balancer you control you would read the address it reports instead; that is a configuration decision, not a default.
 */
@Component
public class ClientAddressHeadersFilter implements HttpHeadersFilter, Ordered {

    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(input);
        InetSocketAddress peer = exchange.getRequest().getRemoteAddress();
        if (peer != null && peer.getAddress() != null) {
            headers.set("X-Forwarded-For", peer.getAddress().getHostAddress());
        } else {
            headers.remove("X-Forwarded-For");
        }
        return headers;
    }

    @Override
    public boolean supports(Type type) {
        return type == Type.REQUEST;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
