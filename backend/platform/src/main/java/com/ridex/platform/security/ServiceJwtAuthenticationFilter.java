package com.ridex.platform.security;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Lets a service trust a token issued by ANOTHER service (the core).
 *
 * The core signs access tokens with a PRIVATE key. Services only hold the matching PUBLIC key (fetched from the core's
 * JWKS endpoint), so they can verify a token but could never forge one. A shared secret would let every service mint
 * tokens for anyone, which is why we moved to RS256.
 *
 * It never rejects a request itself: an invalid or revoked token simply leaves the caller anonymous and the
 * authorization rules decide.
 */
public class ServiceJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ServiceJwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder decoder;
    private final RevocationChecker revocations;

    public ServiceJwtAuthenticationFilter(JwtDecoder decoder, RevocationChecker revocations) {
        this.decoder = decoder;
        this.revocations = revocations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                Jwt jwt = decoder.decode(header.substring(BEARER_PREFIX.length()).trim());
                UUID userId = UUID.fromString(jwt.getSubject());
                String role = jwt.getClaimAsString("role");
                if (role == null) {
                    throw new IllegalArgumentException("missing role");
                }
                Long precise = jwt.getClaim("iat_ms");
                long issuedAt = precise != null ? precise : jwt.getIssuedAt().toEpochMilli();
                if (revocations.isRevoked(userId, jwt.getId(), issuedAt)) {
                    throw new IllegalArgumentException("revoked");
                }
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(
                        new ServicePrincipal(userId, role), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
                SecurityContextHolder.setContext(context);
            } catch (JwtException | IllegalArgumentException | NullPointerException e) {
                log.debug("Ignoring invalid bearer token: {}", e.getClass().getSimpleName());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
