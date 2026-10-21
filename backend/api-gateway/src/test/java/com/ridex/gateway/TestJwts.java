package com.ridex.gateway;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.JWKSet;

/** Makes tokens like the core's, with a key pair that exists only for the tests. */
final class TestJwts {

    private static final KeyPair PAIR = generate();
    private static final KeyPair OTHER = generate();

    private TestJwts() {
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(PAIR.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    record Issued(String value, String jti, long issuedAtMillis) {
    }

    static Issued issue(String subject, String role, String issuer, Instant issuedAt, Instant expiresAt, boolean withOtherKey) {
        KeyPair pair = withOtherKey ? OTHER : PAIR;
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate()).keyID("test").build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        String jti = UUID.randomUUID().toString();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject(subject).issuer(issuer).id(jti)
                .issuedAt(issuedAt).expiresAt(expiresAt).claim("iat_ms", issuedAt.toEpochMilli());
        if (role != null) {
            claims.claim("role", role);
        }
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("test").build(), claims.build()))
                .getTokenValue();
        return new Issued(value, jti, issuedAt.toEpochMilli());
    }

    static Issued valid(UUID user, String role) {
        Instant now = Instant.now();
        return issue(user.toString(), role, "ridex", now, now.plusSeconds(900), false);
    }

    static String token(UUID user, String role) {
        return valid(user, role).value();
    }

    static String expired(UUID user, String role) {
        Instant past = Instant.now().minusSeconds(3600);
        return issue(user.toString(), role, "ridex", past, past.plusSeconds(900), false).value();
    }

    static String foreignIssuer(UUID user, String role) {
        Instant now = Instant.now();
        return issue(user.toString(), role, "someone-else", now, now.plusSeconds(900), false).value();
    }

    static String forged(UUID user, String role) {
        Instant now = Instant.now();
        return issue(user.toString(), role, "ridex", now, now.plusSeconds(900), true).value();
    }

    static String withSubject(String subject, String role) {
        Instant now = Instant.now();
        return issue(subject, role, "ridex", now, now.plusSeconds(900), false).value();
    }
}
