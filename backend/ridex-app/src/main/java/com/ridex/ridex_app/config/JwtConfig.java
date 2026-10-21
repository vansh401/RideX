package com.ridex.ridex_app.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.ridex.ridex_app.jwt.JwtKeys;

/**
 * Signing and verifying access tokens with an RSA key pair (RS256). This application is the only place that holds the
 * private key. Other services fetch the public key from /.well-known/jwks.json and can verify tokens, never create them.
 */
@Configuration
public class JwtConfig {

    @Bean
    public JwtKeys jwtKeys(@Value("${ridex.jwt.key-file:}") String keyFile) {
        return keyFile.isBlank() ? JwtKeys.generate() : JwtKeys.loadOrCreate(Path.of(keyFile));
    }

    @Bean
    public JwtEncoder jwtEncoder(JwtKeys keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingJwk())));
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtKeys keys, @Value("${ridex.jwt.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)   // pin the algorithm: no "alg" confusion
                .build();
        // Signature + expiry (the defaults) AND the issuer must match ours. Without the issuer
        // check, any token signed with this key by another service would be accepted.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
