package com.ridex.ridex_app.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.ridex.ridex_app.config.JwtConfig;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

class JwtServiceTest {

    private final JwtKeys keys = JwtKeys.generate();
    private final JwtEncoder encoder = new JwtConfig().jwtEncoder(keys);
    private final JwtDecoder decoder = new JwtConfig().jwtDecoder(keys, "ridex");

    private JwtService service(JwtEncoder anEncoder, JwtKeys theKeys) {
        return new JwtService(anEncoder, theKeys, "ridex", 15);
    }

    private User driver() {
        return new User("Ravi Kumar", "ravi@example.com", "+919812345678", "hash", Role.DRIVER);
    }

    @Test
    void tokenContainsTheExpectedClaims() {
        JwtService service = service(encoder, keys);
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
        JwtService service = service(encoder, keys);

        Jwt jwt = decoder.decode(service.createAccessToken(driver()).value());

        assertThat(jwt.getClaims()).doesNotContainKeys("email", "phone", "password", "fullName");
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService service = service(encoder, keys);
        String token = service.createAccessToken(driver()).value();
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtKeys attacker = JwtKeys.generate();
        JwtService forged = service(new JwtConfig().jwtEncoder(attacker), attacker);

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
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.keyId()).build();
        String expiredToken = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        assertThatThrownBy(() -> decoder.decode(expiredToken)).isInstanceOf(JwtException.class);
    }
}