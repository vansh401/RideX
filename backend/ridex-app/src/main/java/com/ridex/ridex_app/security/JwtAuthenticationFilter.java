package com.ridex.ridex_app.security;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ridex.ridex_app.user.Role;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads "Authorization: Bearer <token>", verifies it, and records who the caller is.
 * It never rejects a request itself: unauthenticated requests simply stay anonymous and the
 * authorization rules decide whether that is acceptable for the URL.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    /** Request attributes that let /logout cancel the very token that was used to call it. */
    public static final String ATTR_JTI = "ridex.jwt.jti";
    public static final String ATTR_EXPIRES_AT = "ridex.jwt.expiresAt";

    private final JwtDecoder jwtDecoder;
    private final AccessTokenRevocations revocations;

    public JwtAuthenticationFilter(JwtDecoder jwtDecoder, AccessTokenRevocations revocations) {
        this.jwtDecoder = jwtDecoder;
        this.revocations = revocations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                Jwt jwt = jwtDecoder.decode(token);   // checks signature and expiry

                String subject = jwt.getSubject();
                String roleClaim = jwt.getClaimAsString("role");
                if (subject == null || roleClaim == null) {
                    throw new IllegalArgumentException("Token is missing required claims");
                }

                AuthenticatedUser principal =
                        new AuthenticatedUser(UUID.fromString(subject), Role.valueOf(roleClaim));

                // A valid signature is not enough: the user may have been suspended, logged out or had
                // their password reset since this token was issued (see AccessTokenRevocations).
                if (revocations.isRevoked(principal.id(), jwt.getId(), issuedAtMillis(jwt))) {
                    throw new IllegalArgumentException("Token has been revoked");
                }
                request.setAttribute(ATTR_JTI, jwt.getId());
                request.setAttribute(ATTR_EXPIRES_AT, jwt.getExpiresAt());
                List<GrantedAuthority> authorities =
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));

                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, authorities));
                SecurityContextHolder.setContext(context);

            } catch (JwtException | IllegalArgumentException ex) {
                // Never log the token itself
                log.debug("Ignoring invalid bearer token: {}", ex.getClass().getSimpleName());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private static long issuedAtMillis(Jwt jwt) {
        Long precise = jwt.getClaim("iat_ms");
        if (precise != null) {
            return precise;
        }
        return jwt.getIssuedAt() == null ? 0 : jwt.getIssuedAt().toEpochMilli();
    }
}
