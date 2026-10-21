package com.ridex.gateway;

import java.net.InetSocketAddress;
import java.util.regex.Pattern;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * Who is the caller, really?
 *
 * Normally the gateway IS the edge, so the answer is the address of the TCP connection and the X-Forwarded-For header is ignored
 * (any client could write anything in it). When the gateway sits BEHIND a proxy we run ourselves (the nginx that serves the
 * frontend in Docker), every connection comes from that proxy, so the real client can only be learned from the header the proxy
 * sets. That is safe only for connections that really come from the proxy, so the header is honoured ONLY when the connection's
 * address matches {@code ridex.gateway.trusted-proxies}. Empty (the default) means nobody is trusted.
 *
 * With several hops the header is read from the right, skipping trusted proxies: the right-most entries were written by our own
 * infrastructure; anything further left was written by the client and cannot be believed.
 */
@Component
public class ClientAddress {

    private static final Pattern LOOKS_LIKE_AN_ADDRESS = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    private final Pattern trusted;

    public ClientAddress(GatewayProperties properties) {
        String regex = properties.trustedProxies();
        this.trusted = regex == null || regex.isBlank() ? null : Pattern.compile(regex);
    }

    public String of(ServerHttpRequest request) {
        String peer = peer(request);
        if (trusted == null || !trusted.matcher(peer).matches()) {
            return peer;
        }
        String header = request.getHeaders().getFirst("X-Forwarded-For");
        if (header == null) {
            return peer;
        }
        String[] hops = header.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!LOOKS_LIKE_AN_ADDRESS.matcher(hop).matches()) {
                return peer;   // garbage: do not guess
            }
            if (!trusted.matcher(hop).matches()) {
                return hop;
            }
        }
        return peer;
    }

    private static String peer(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
