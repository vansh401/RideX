package com.ridex.ridex_app.security;

import java.io.IOException;
import java.time.Duration;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.ridex.ridex_app.common.TooManyRequestsException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limits how often one client address may call the API. It runs first, before any password hashing
 * or database work, so abusive traffic is cheap to turn away.
 *
 * The client address is the TCP peer address. X-Forwarded-For is deliberately NOT trusted: any
 * client can send that header with any value and would pick its own bucket. Behind a real reverse
 * proxy, configure the proxy to set the address properly (server.forward-headers-strategy) instead.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final RateLimitProperties properties;
    private final HandlerExceptionResolver exceptionResolver;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties, HandlerExceptionResolver exceptionResolver) {
        this.limiter = limiter;
        this.properties = properties;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String client = request.getRemoteAddr();

        RateLimiter.Decision decision;
        if (isSensitive(path)) {
            decision = limiter.hit("sensitive", client, properties.sensitiveLimit(),
                    Duration.ofSeconds(properties.sensitiveWindowSeconds()));
        } else if (path.startsWith("/api/auth/")) {
            decision = limiter.hit("auth", client, properties.authLimit(),
                    Duration.ofSeconds(properties.authWindowSeconds()));
        } else {
            decision = limiter.hit("api", client, properties.apiLimit(),
                    Duration.ofSeconds(properties.apiWindowSeconds()));
        }

        if (!decision.allowed()) {
            exceptionResolver.resolveException(request, response, null,
                    new TooManyRequestsException("Too many requests. Please slow down and try again shortly.",
                            decision.retryAfterSeconds()));
            return;
        }
        chain.doFilter(request, response);
    }

    /** Endpoints that send an email or check a guessable code. */
    private static boolean isSensitive(String path) {
        return path.equals("/api/auth/register")
                || path.equals("/api/auth/forgot-password")
                || path.equals("/api/auth/resend-verification")
                || path.equals("/api/auth/verify-otp");
    }
}
