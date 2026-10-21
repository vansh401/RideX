package com.ridex.platform.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Token validation for services. A service needs ONE of:
 *  - ridex.security.jwks-uri        the core's public-key endpoint (normal operation; keys can be rotated)
 *  - ridex.security.public-key-file or ridex.security.public-key-pem   a fixed public key (tests, simple setups)
 */
@AutoConfiguration
public class PlatformSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    @ConditionalOnExpression("'${ridex.security.jwks-uri:}' != '' or '${ridex.security.public-key-file:}' != '' or '${ridex.security.public-key-pem:}' != ''")
    JwtDecoder platformJwtDecoder(@Value("${ridex.security.jwks-uri:}") String jwksUri,
                                  @Value("${ridex.security.public-key-file:}") String publicKeyFile,
                                  @Value("${ridex.security.public-key-pem:}") String publicKeyPem,
                                  @Value("${ridex.jwt.issuer:ridex}") String issuer) throws Exception {
        NimbusJwtDecoder decoder;
        if (!jwksUri.isBlank()) {
            decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        } else {
            String pem = !publicKeyPem.isBlank() ? publicKeyPem : Files.readString(Path.of(publicKeyFile), StandardCharsets.UTF_8);
            decoder = NimbusJwtDecoder.withPublicKey(parsePublicKey(pem)).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        }
        // Signature, expiry AND issuer: a token signed by someone else's key, or minted for another system, is refused
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    @ConditionalOnMissingBean
    RevocationChecker revocationChecker(ObjectProvider<StringRedisTemplate> redis) {
        return new RevocationChecker(redis.getIfAvailable());
    }

    /** Reads a "-----BEGIN PUBLIC KEY-----" PEM (X.509 SubjectPublicKeyInfo) RSA key. */
    public static RSAPublicKey parsePublicKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }
}
