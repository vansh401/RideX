package com.ridex.ridex_app.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.jayway.jsonpath.JsonPath;
import com.ridex.ridex_app.support.AbstractApiTest;

/**
 * How the OTHER services trust the core, tested the way they really do it: fetch the public key over HTTP from the running
 * core, then verify a token the core issued at login. The services' own tests use a fixed public key instead, so this is the
 * test that proves the real URL, the key id and the token signature fit together.
 */
class JwksIntegrationTest extends AbstractApiTest {

    @Value("${local.server.port}")
    private int port;

    private String jwksUrl() {
        return "http://localhost:" + port + "/.well-known/jwks.json";
    }

    private HttpResponse<String> get(String url) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theJwksIsPublicAndContainsOnlyPublicKeyMaterial() throws Exception {
        HttpResponse<String> response = get(jwksUrl());

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> kinds = JsonPath.read(response.body(), "$.keys[*].kty");
        assertThat(kinds).containsExactly("RSA");
        assertThat(response.body()).as("the private parts of an RSA key").doesNotContain("\"d\"").doesNotContain("\"p\"").doesNotContain("\"q\"")
                .doesNotContain("\"dp\"").doesNotContain("\"dq\"").doesNotContain("\"qi\"");
        assertThat(JsonPath.<String>read(response.body(), "$.keys[0].kid")).isNotBlank();
    }

    @Test
    void aTokenIssuedAtLoginVerifiesWithOnlyThePublicKeyFetchedFromTheCore() throws Exception {
        Person rider = newRider();

        // This is exactly what the notification, payment, pricing and analytics services configure
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUrl()).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        Jwt jwt = decoder.decode(rider.token());

        assertThat(jwt.getSubject()).isEqualTo(rider.user().getId().toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("RIDER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("ridex");
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
        assertThat(jwt.getHeaders().get("kid")).as("the key id lets the key be rotated").isNotNull();
    }

    @Test
    void theKeyIdInTheTokenMatchesAKeyInTheJwks() throws Exception {
        Person rider = newRider();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUrl()).jwsAlgorithm(SignatureAlgorithm.RS256).build();

        String kid = (String) decoder.decode(rider.token()).getHeaders().get("kid");

        List<String> published = JsonPath.read(get(jwksUrl()).body(), "$.keys[*].kid");
        assertThat(published).contains(kid);
    }
}
