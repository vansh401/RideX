package com.ridex.gateway;

import java.io.IOException;
import java.net.InetAddress;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Says WHICH gateway copy handled the request (X-Gateway-Instance), next to the X-Instance header the service behind it adds. Together they
 * show both levels of load balancing: the ingress choosing a gateway pod, and the gateway's connection choosing a service pod.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class GatewayInstanceFilter implements WebFilter {

    static final String HEADER = "X-Gateway-Instance";

    private final String instance = instanceName();

    private static String instanceName() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().getHeaders().set(HEADER, instance);
        return chain.filter(exchange);
    }
}
