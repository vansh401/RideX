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

import com.ridex.ridex_app.security.JwtAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
            throws Exception {

        // Created with "new" on purpose: if it were a @Component, Spring Boot would also
        // register it as a plain servlet filter and it would run twice.
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtDecoder);

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
                        // API docs. Disable them in production (springdoc.*.enabled=false)
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/riders/**").hasRole("RIDER")
                        .requestMatchers("/api/fares/**").hasRole("RIDER")
                        // Ride endpoints: roles are enforced HERE as well as with @PreAuthorize. Method
                        // security runs only after the request body has been validated, so without these
                        // URL rules a caller with the wrong role would see validation errors instead of 403.
                        .requestMatchers(HttpMethod.POST, "/api/rides").hasRole("RIDER")
                        .requestMatchers(HttpMethod.POST, "/api/rides/*/accept", "/api/rides/*/reject",
                                "/api/rides/*/arrived", "/api/rides/*/start", "/api/rides/*/complete").hasRole("DRIVER")
                        .requestMatchers("/api/rides/*/cancel", "/api/rides/*/ratings").hasAnyRole("RIDER", "DRIVER")
                        .requestMatchers(HttpMethod.GET, "/api/rides", "/api/rides/current").hasAnyRole("RIDER", "DRIVER")
                        .requestMatchers(HttpMethod.GET, "/api/rides/*", "/api/rides/*/timeline").hasAnyRole("RIDER", "DRIVER", "ADMIN")
                        .requestMatchers("/api/drivers/**").hasRole("DRIVER")
                        .anyRequest().authenticated())
                // Run our JWT filter before Spring's username/password filter position
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // Send 401/403 through our GlobalExceptionHandler so the JSON format is consistent
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex))
                        .accessDeniedHandler((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex)));

        return http.build();
    }
}
