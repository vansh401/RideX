package com.ridex.ridex_app.security;

import static org.assertj.core.api.Assertions.assertThat;



import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import com.ridex.ridex_app.config.JwtConfig;
import com.ridex.ridex_app.jwt.JwtKeys;
import com.ridex.ridex_app.jwt.JwtService;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

class JwtAuthenticationFilterTest {

    private final JwtKeys keys = JwtKeys.generate();
    private final JwtEncoder encoder = new JwtConfig().jwtEncoder(keys);
    private final JwtDecoder decoder = new JwtConfig().jwtDecoder(keys, "ridex");
    private final AccessTokenRevocations revocations = org.mockito.Mockito.mock(AccessTokenRevocations.class);
    private final JwtService jwtService = new JwtService(encoder, keys, "ridex", 15);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(decoder, revocations);

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
        JwtKeys attackerKeys = JwtKeys.generate();   // a different RSA key pair: not the one the core publishes
        JwtService forger = new JwtService(new JwtConfig().jwtEncoder(attackerKeys), attackerKeys, "ridex", 15);
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
