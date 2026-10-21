package com.ridex.gateway;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import reactor.core.publisher.Mono;

@Configuration
public class GatewayConfig {

    /**
     * Verifies tokens with the core's PUBLIC key only: the gateway can check a token but never make one. Signature, expiry and
     * issuer are all checked, so a token signed by someone else, or made for another system, is refused.
     */
    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(GatewayProperties properties) throws Exception {
        NimbusReactiveJwtDecoder decoder;
        if (properties.publicKeyPem() != null && !properties.publicKeyPem().isBlank()) {
            String base64 = properties.publicKeyPem().replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.getBytes(StandardCharsets.UTF_8))));
            decoder = NimbusReactiveJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        } else {
            decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwksUri()).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        }
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /**
     * Who is "one client" for rate limiting? The network address of the TCP connection. The X-Forwarded-For header is NOT used:
     * the gateway is the edge, so a client could write anything in it and choose its own bucket.
     * (Behind a load balancer, you would configure the balancer's address as trusted and read the header it sets.)
     */
    @Bean
    KeyResolver clientAddressKeyResolver(ClientAddress clientAddress) {
        return exchange -> {
            String client = clientAddress.of(exchange.getRequest());
            // One bucket per client PER ROUTE: hammering the login route must not use up the budget for the rest of the API
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            return Mono.just(client + "|" + (route == null ? "none" : route.getId()));
        };
    }
}
