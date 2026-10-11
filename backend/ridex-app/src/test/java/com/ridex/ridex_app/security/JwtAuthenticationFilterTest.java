package com.ridex.ridex_app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.ridex.ridex_app.jwt.JwtService;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

class JwtAuthenticationFilterTest {

    private static SecretKey keyFrom(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private final SecretKey key = keyFrom("filter-test-secret-that-is-at-least-32-bytes!!");
    private final JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    private final JwtDecoder decoder =
            NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    private final JwtService jwtService = new JwtService(encoder, "ridex", 15);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(decoder);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private MockFilterChain run(String authorizationHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @Test
    void validTokenAuthenticatesTheCallerWithTheirRole() throws Exception {
        User driver = new User("Ravi Kumar", "ravi@example.com", "+919812345678", "hash", Role.DRIVER);
        String token = jwtService.createAccessToken(driver).value();

        MockFilterChain chain = run("Bearer " + token);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal())
                .isEqualTo(new AuthenticatedUser(driver.getId(), Role.DRIVER));
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_DRIVER");
        assertThat(chain.getRequest()).isNotNull();   // the request continued down the chain
    }

    @Test
    void requestWithoutHeaderStaysAnonymousButContinues() throws Exception {
        MockFilterChain chain = run(null);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void garbageTokenStaysAnonymousButContinues() throws Exception {
        MockFilterChain chain = run("Bearer this-is-not-a-jwt");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void tokenSignedWithAnotherKeyIsNotAccepted() throws Exception {
        JwtEncoder attackerEncoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(keyFrom("a-completely-different-attacker-secret-key!!")));
        JwtService forger = new JwtService(attackerEncoder, "ridex", 15);
        User admin = new User("Evil", "evil@example.com", "+919800000099", "hash", Role.ADMIN);

        run("Bearer " + forger.createAccessToken(admin).value());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nonBearerSchemeIsIgnored() throws Exception {
        run("Basic dXNlcjpwYXNz");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
