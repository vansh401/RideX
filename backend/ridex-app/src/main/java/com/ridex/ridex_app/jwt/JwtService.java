package com.ridex.ridex_app.jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.ridex.ridex_app.user.User;

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final String issuer;
    private final long accessTokenTtlMinutes;

    public JwtService(JwtEncoder jwtEncoder,
                      @Value("${ridex.jwt.issuer}") String issuer,
                      @Value("${ridex.jwt.access-token-ttl-minutes}") long accessTokenTtlMinutes) {
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    }

    public AccessToken createAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(accessTokenTtlMinutes));

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                // The standard iat claim only has whole-second precision. Revocation compares against a
                // millisecond cutoff, so a token created in the same second as a suspension is still judged correctly.
                .claim("iat_ms", now.toEpochMilli())
                .claim("role", user.getRole().name())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new AccessToken(token, expiresAt, Duration.between(now, expiresAt).toSeconds());
    }

    public record AccessToken(String value, Instant expiresAt, long expiresInSeconds) {
    }
}