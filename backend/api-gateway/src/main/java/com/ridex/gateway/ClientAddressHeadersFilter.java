package com.ridex.gateway;

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
 * Behind a proxy of our own (the nginx of the Docker setup) the real address is read from the header that proxy sets, but only for
 * connections that come from it (see ClientAddress and ridex.gateway.trusted-proxies).
 */
@Component
public class ClientAddressHeadersFilter implements HttpHeadersFilter, Ordered {

    private final ClientAddress clientAddress;

    public ClientAddressHeadersFilter(ClientAddress clientAddress) {
        this.clientAddress = clientAddress;
    }

    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(input);
        // The ORIGINAL request is asked, not the headers being filtered: the standard clean-up has already removed X-Forwarded-For
        headers.set("X-Forwarded-For", clientAddress.of(exchange.getRequest()));
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
