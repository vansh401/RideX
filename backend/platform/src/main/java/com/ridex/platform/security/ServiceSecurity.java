package com.ridex.platform.security;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** The security settings every service shares: stateless, token in the Authorization header, JSON 401 and 403. */
public final class ServiceSecurity {

    private ServiceSecurity() {
    }

    public static void baseline(HttpSecurity http, JwtDecoder decoder, RevocationChecker revocations, JsonMapper mapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())      // no cookies: tokens travel in a header, so CSRF does not apply
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .addFilterBefore(new ServiceJwtAuthenticationFilter(decoder, revocations), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> write(res, req, mapper, 401, "UNAUTHORIZED",
                                "Authentication is required or the credentials are invalid"))
                        .accessDeniedHandler((req, res, ex) -> write(res, req, mapper, 403, "FORBIDDEN",
                                "You do not have access to this resource")));
    }

    /** Accept calls from other services that present the shared X-Service-Token (role SERVICE). */
    public static void acceptServiceToken(HttpSecurity http, String token) {
        http.addFilterBefore(new ServiceTokenFilter(token), UsernamePasswordAuthenticationFilter.class);
    }

    /** Same JSON shape as the core application's errors, so the frontend handles every service the same way. */
    public static void write(HttpServletResponse response, HttpServletRequest request, JsonMapper mapper,
                             int status, String error, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status);
        body.put("error", error);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("fieldErrors", Map.of());
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
