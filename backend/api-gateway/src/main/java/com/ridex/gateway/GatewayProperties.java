package com.ridex.gateway;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under "ridex.gateway" in application.yml. */
@ConfigurationProperties(prefix = "ridex.gateway")
public record GatewayProperties(
        /** Where the core publishes the public key that verifies tokens. */
        String jwksUri,
        /** Alternative to jwksUri: a fixed public key in PEM form (used by tests). */
        String publicKeyPem,
        String issuer,
        /** Entries like "GET:/api/health" or "*:/api/auth/**". Requests matching one need no token. */
        List<String> publicPaths,
        /** Paths that are never reachable from outside, even with a token. */
        List<String> blockedPaths,
        /** Regular expression of the addresses of proxies we run in front of the gateway (see ClientAddress). Empty = none. */
        String trustedProxies,
        List<Service> services
) {
    public record Service(String name, String healthUrl) {
    }

    public GatewayProperties {
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
        blockedPaths = blockedPaths == null ? List.of() : List.copyOf(blockedPaths);
        services = services == null ? List.of() : List.copyOf(services);
    }
}
