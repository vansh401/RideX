package com.ridex.platform.testing;

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

/**
 * Plays the part of the core application's token issuer in a service's tests: it owns an RSA key pair, signs tokens with
 * the private key, and hands the PUBLIC key to the service under test, exactly as in production (where the service
 * fetches it from the core's JWKS endpoint).
 */
public final class TestTokens {

    private static final KeyPair KEYS = generate();
    private static final NimbusJwtEncoder ENCODER = encoder();

    private TestTokens() {
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

    private static NimbusJwtEncoder encoder() {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).privateKey((RSAPrivateKey) KEYS.getPrivate())
                .keyID("test-key").build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    /** The public key in PEM form: what a service is configured with (ridex.security.public-key-pem). */
    public static String publicKeyPem() {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEYS.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
    }

    public static String token(UUID userId, String role) {
        return token(userId, role, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(900), "ridex");
    }

    public static String expiredToken(UUID userId, String role) {
        Instant past = Instant.now().minusSeconds(3600);
        return token(userId, role, UUID.randomUUID().toString(), past, past.plusSeconds(60), "ridex");
    }

    public static String tokenFromAnotherIssuer(UUID userId, String role) {
        return token(userId, role, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(900), "someone-else");
    }

    public static String token(UUID userId, String role, String tokenId, Instant issuedAt, Instant expiresAt, String issuer) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(userId.toString())
                .issuedAt(issuedAt).expiresAt(expiresAt).id(tokenId)
                .claim("iat_ms", issuedAt.toEpochMilli()).claim("role", role).build();
        return ENCODER.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build(), claims)).getTokenValue();
    }

    /** A token signed with a DIFFERENT key (a forger who does not have the real private key). */
    public static String forgedToken(UUID userId, String role) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair other = generator.generateKeyPair();
            RSAKey jwk = new RSAKey.Builder((RSAPublicKey) other.getPublic()).privateKey((RSAPrivateKey) other.getPrivate()).keyID("test-key").build();
            JwtClaimsSet claims = JwtClaimsSet.builder().issuer("ridex").subject(userId.toString()).issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(900)).id(UUID.randomUUID().toString()).claim("role", role).build();
            return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)))
                    .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build(), claims)).getTokenValue();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
