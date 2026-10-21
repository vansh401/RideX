package com.ridex.ridex_app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.ridex.ridex_app.jwt.JwtKeys;
import com.ridex.ridex_app.jwt.JwtService;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

/** The decoder bean we actually use in production, not a hand-built one. */
class JwtConfigTest {

    private final JwtConfig config = new JwtConfig();
    private final JwtKeys keys = JwtKeys.generate();
    private final JwtEncoder encoder = config.jwtEncoder(keys);
    private final JwtDecoder decoder = config.jwtDecoder(keys, "ridex");

    private final User user = new User("Asha Rao", "asha@example.com", "+919876543210", "hash", Role.RIDER);

    @Test
    void acceptsATokenWithOurIssuer() {
        String token = new JwtService(encoder, keys, "ridex", 15).createAccessToken(user).value();

        assertThat(decoder.decode(token).getSubject()).isEqualTo(user.getId().toString());
    }

    @Test
    void rejectsATokenSignedWithOurKeyButAnotherIssuer() {
        String token = new JwtService(encoder, keys, "some-other-service", 15).createAccessToken(user).value();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATokenSignedWithAnotherKeyPair() {
        JwtKeys attacker = JwtKeys.generate();
        String forged = new JwtService(config.jwtEncoder(attacker), attacker, "ridex", 15).createAccessToken(user).value();

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }
}
