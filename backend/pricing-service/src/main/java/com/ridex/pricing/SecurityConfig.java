package com.ridex.pricing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import com.ridex.platform.security.RevocationChecker;
import com.ridex.platform.security.ServiceSecurity;

import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder, RevocationChecker revocations, JsonMapper mapper,
                                            @Value("${ridex.internal.service-token:}") String serviceToken) throws Exception {
        ServiceSecurity.baseline(http, decoder, revocations, mapper);
        ServiceSecurity.acceptServiceToken(http, serviceToken);
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                // Two kinds of caller: a rider's browser (user token) and the core (service token)
                .requestMatchers("/api/fares/estimate").hasRole("RIDER")
                .requestMatchers("/internal/**").hasRole("SERVICE")
                .anyRequest().denyAll());
        return http.build();
    }
}
