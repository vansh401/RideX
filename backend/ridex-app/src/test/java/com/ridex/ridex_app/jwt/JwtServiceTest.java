package com.ridex.ridex_app.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

class JwtServiceTest {

    private static SecretKey keyFrom(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private final SecretKey key = keyFrom("unit-test-secret-that-is-at-least-32-bytes-long!");
    private final JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    private final JwtDecoder decoder =
            NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();

    private User driver() {
        return new User("Ravi Kumar", "ravi@example.com", "+919812345678", "hash", Role.DRIVER);
    }

    @Test
    void tokenContainsTheExpectedClaims() {
        JwtService service = new JwtService(encoder, "ridex", 15);
        User user = driver();

        JwtService.AccessToken token = service.createAccessToken(user);
        Jwt jwt = decoder.decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("DRIVER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("ridex");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(14)));
        assertThat(token.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void tokenDoesNotContainPersonalData() {
        JwtService service = new JwtService(encoder, "ridex", 15);

        Jwt jwt = decoder.decode(service.createAccessToken(driver()).value());

        assertThat(jwt.getClaims()).doesNotContainKeys("email", "phone", "password", "fullName");
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService service = new JwtService(encoder, "ridex", 15);
        String token = service.createAccessToken(driver()).value();
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        SecretKey attackerKey = keyFrom("a-completely-different-attacker-secret-key!!");
        JwtEncoder attackerEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(attackerKey));
        JwtService forged = new JwtService(attackerEncoder, "ridex", 15);

        String forgedToken = forged.createAccessToken(driver()).value();

        assertThatThrownBy(() -> decoder.decode(forgedToken)).isInstanceOf(JwtException.class);
    }

        @Test
    void expiredTokenIsRejected() {
        
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ridex")
                .subject("some-user-id")
                .issuedAt(tenMinutesAgo)
                .expiresAt(tenMinutesAgo.plus(Duration.ofMinutes(5)))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String expiredToken = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        assertThatThrownBy(() -> decoder.decode(expiredToken)).isInstanceOf(JwtException.class);
    }
}