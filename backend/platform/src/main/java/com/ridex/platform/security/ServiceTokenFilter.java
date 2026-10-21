package com.ridex.platform.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * SERVICE-TO-SERVICE authentication for internal endpoints. When one service calls another on its own behalf (no user is
 * involved), it sends the shared secret in the X-Service-Token header and is given the role SERVICE.
 *
 * The secret is compared in constant time, so response timing reveals nothing about it. A blank configured secret disables
 * the mechanism completely (nobody can authenticate this way), which is the safe default. In production this would be
 * replaced by mutual TLS or a service-mesh identity; the idea (a service proves who it is) stays the same.
 */
public class ServiceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Service-Token";
    public static final String ROLE = "SERVICE";
    private static final UUID SERVICE_ID = new UUID(0, 0);

    private final byte[] expected;

    public ServiceTokenFilter(String token) {
        this.expected = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (expected != null && presented != null
                && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    new ServicePrincipal(SERVICE_ID, ROLE), null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
