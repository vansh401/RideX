package com.ridex.ridex_app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.ridex.ridex_app.jwt.JwtService;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

/** The decoder bean we actually use in production, not a hand-built one. */
class JwtConfigTest {

    private final SecretKey key =
            new SecretKeySpec("config-test-secret-that-is-at-least-32-bytes!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private final JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    private final JwtDecoder decoder = new JwtConfig().jwtDecoder(key, "ridex");

    private final User user = new User("Asha Rao", "asha@example.com", "+919876543210", "hash", Role.RIDER);

    @Test
    void acceptsATokenWithOurIssuer() {
        String token = new JwtService(encoder, "ridex", 15).createAccessToken(user).value();

        assertThat(decoder.decode(token).getSubject()).isEqualTo(user.getId().toString());
    }

    @Test
    void rejectsATokenSignedWithOurKeyButAnotherIssuer() {
        String token = new JwtService(encoder, "some-other-service", 15).createAccessToken(user).value();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
