package com.ridex.ridex_app.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.ridex.ridex_app.security.AccessTokenRevocations;
import com.ridex.ridex_app.security.JwtAuthenticationFilter;
import com.ridex.ridex_app.security.RateLimitFilter;
import com.ridex.ridex_app.security.RateLimitProperties;
import com.ridex.ridex_app.security.RateLimiter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            AccessTokenRevocations revocations,
            RateLimiter rateLimiter,
            RateLimitProperties rateLimitProperties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
            @org.springframework.beans.factory.annotation.Value("${ridex.internal.service-token:}") String internalServiceToken)
            throws Exception {

        // Created with "new" on purpose: if it were a @Component, Spring Boot would also
        // register it as a plain servlet filter and it would run twice.
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtDecoder, revocations);
        RateLimitFilter rateLimitFilter = new RateLimitFilter(rateLimiter, rateLimitProperties, exceptionResolver);

        http
                // No cookies/sessions: tokens in the Authorization header, so CSRF protection is not needed
                .csrf(csrf -> csrf.disable())
                // No cross-origin access is configured on purpose: the browser talks to ONE origin
                // (the Vite dev proxy today, the gateway/ingress later), so CORS is not needed.
                // With no CorsConfigurationSource bean this leaves cross-origin requests blocked.
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // We build our own JSON login; turn off the built-in browser login mechanisms
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/health").permitAll()
                        // Kubernetes probes (state of the application only; no details are shown)
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Metrics for Prometheus. Open INSIDE the network only: the gateway blocks /actuator/** and the ingress never routes it
                        .requestMatchers("/actuator/prometheus").permitAll()
                        // The public half of the token signing key: other services read it to verify our tokens
                        .requestMatchers("/.well-known/jwks.json").permitAll()
                        // The WebSocket handshake itself is open; the STOMP CONNECT frame is authenticated with the
                        // JWT (see StompSecurityInterceptor), because browsers cannot set headers on a handshake
                        .requestMatchers("/ws/**").permitAll()
                        // API docs. Disable them in production (springdoc.*.enabled=false)
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Phase 15: a shared trip is read by anyone holding the (unguessable, expiring) link, so no login
                        .requestMatchers(HttpMethod.GET, "/api/public/trips/*").permitAll()
                        // Another service asks for a person's name and email (service token, not a user token)
                        .requestMatchers("/internal/**").hasRole("SERVICE")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/riders/**").hasRole("RIDER")
                        // Ride endpoints: roles are enforced HERE as well as with @PreAuthorize. Method
                        // security runs only after the request body has been validated, so without these
                        // URL rules a caller with the wrong role would see validation errors instead of 403.
                        .requestMatchers(HttpMethod.POST, "/api/rides").hasRole("RIDER")
                        .requestMatchers(HttpMethod.POST, "/api/rides/*/accept", "/api/rides/*/reject",
                                "/api/rides/*/arrived", "/api/rides/*/start", "/api/rides/*/complete", "/api/rides/*/stops/next").hasRole("DRIVER")
                        .requestMatchers("/api/rides/*/cancel", "/api/rides/*/ratings").hasAnyRole("RIDER", "DRIVER")
                        // Phase 15: safety and chat are for the two people on the ride
                        .requestMatchers("/api/rides/*/share", "/api/rides/*/sos", "/api/rides/*/messages").hasAnyRole("RIDER", "DRIVER")
                        // Phase 14: complaints are filed by the two people on a ride; administrators handle them (/api/admin/** above)
                        .requestMatchers("/api/complaints", "/api/complaints/**").hasAnyRole("RIDER", "DRIVER")
                        .requestMatchers(HttpMethod.GET, "/api/rides", "/api/rides/current").hasAnyRole("RIDER", "DRIVER")
                        .requestMatchers(HttpMethod.GET, "/api/rides/*", "/api/rides/*/timeline", "/api/rides/*/driver-location").hasAnyRole("RIDER", "DRIVER", "ADMIN")
                        .requestMatchers("/api/drivers/**").hasRole("DRIVER")
                        .anyRequest().authenticated())
                // Run our JWT filter before Spring's username/password filter position
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // First of all: turn away abusive traffic before any real work happens
                .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
                // Other services present the shared X-Service-Token (role SERVICE); an empty token switches this off
                .addFilterBefore(new com.ridex.platform.security.ServiceTokenFilter(internalServiceToken), JwtAuthenticationFilter.class)
                // Send 401/403 through our GlobalExceptionHandler so the JSON format is consistent
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex))
                        .accessDeniedHandler((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex)));

        return http.build();
    }
}
