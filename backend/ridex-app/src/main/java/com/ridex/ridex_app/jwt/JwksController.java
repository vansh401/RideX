package com.ridex.ridex_app.jwt;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.JWKSet;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Hidden;

/**
 * The PUBLIC half of the signing key, in the standard JWKS (JSON Web Key Set) format. Every other service reads this to
 * verify the access tokens this application issues. It is public on purpose: a public key cannot sign anything.
 */
@RestController
public class JwksController {

    private final JwtKeys keys;

    public JwksController(JwtKeys keys) {
        this.keys = keys;
    }

    @Hidden
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Public keys that verify our access tokens")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                // Services cache it; a rotated key gets a new key id, so a short cache is enough
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(new JWKSet(keys.publicJwk()).toJSONObject(true));
    }
}
