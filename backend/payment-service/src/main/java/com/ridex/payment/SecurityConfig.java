package com.ridex.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import com.ridex.platform.security.RevocationChecker;
import com.ridex.platform.security.ServiceSecurity;

import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder, RevocationChecker revocations, JsonMapper mapper) throws Exception {
        ServiceSecurity.baseline(http, decoder, revocations, mapper);
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                // The pretend gateway stands for somebody else's server: it has its own rules (an Idempotency-Key), not our tokens
                .requestMatchers("/mock-gateway/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/payments/rides/*/retry").hasRole("RIDER")
                .requestMatchers(HttpMethod.GET, "/api/payments/rides/*").hasAnyRole("RIDER", "DRIVER", "ADMIN")
                .anyRequest().denyAll());
        return http.build();
    }
}
