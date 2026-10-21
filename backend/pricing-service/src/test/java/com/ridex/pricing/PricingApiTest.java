package com.ridex.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.jayway.jsonpath.JsonPath;
import com.ridex.platform.testing.ServiceContainers;
import com.ridex.platform.testing.TestTokens;

/**
 * The pricing service started for real. It has NO database and NO Kafka (only Redis, to honour logouts): it is a pure function behind HTTP, which
 * is exactly what makes it the easiest service to extract and to scale.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ridex.security.jwks-uri=",
        "ridex.internal.service-token=test-service-token-not-a-real-secret"
})
class PricingApiTest {

    private static final String SERVICE_TOKEN = "test-service-token-not-a-real-secret";
    private static final String BODY = "{\"pickup\":{\"latitude\":12.971599,\"longitude\":77.594566},"
            + "\"dropoff\":{\"latitude\":13.0827,\"longitude\":77.5877}%s}";

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("ridex.security.public-key-pem", TestTokens::publicKeyPem);
        registry.add("spring.data.redis.host", ServiceContainers.redis()::getHost);
        registry.add("spring.data.redis.port", () -> ServiceContainers.redis().getMappedPort(6379));
    }

    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String bearer, String serviceToken, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (serviceToken != null) {
            request.header("X-Service-Token", serviceToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aRiderGetsAQuoteForEveryCategory() throws Exception {
        var response = post("/api/fares/estimate", TestTokens.token(UUID.randomUUID(), "RIDER"), null, String.format(BODY, ""));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<java.util.List<String>>read(response.body(), "$[*].category")).containsExactly("BIKE", "AUTO", "CAB");
    }

    @Test
    void driversAndAnonymousCallersCannotAskRiderQuotes() throws Exception {
        assertThat(post("/api/fares/estimate", null, null, String.format(BODY, "")).statusCode()).isEqualTo(401);
        assertThat(post("/api/fares/estimate", TestTokens.token(UUID.randomUUID(), "DRIVER"), null, String.format(BODY, "")).statusCode()).isEqualTo(403);
        assertThat(post("/api/fares/estimate", TestTokens.forgedToken(UUID.randomUUID(), "RIDER"), null, String.format(BODY, "")).statusCode()).isEqualTo(401);
    }

    @Test
    void anotherServiceGetsOneExactQuoteWithTheServiceToken() throws Exception {
        var response = post("/internal/quotes", null, SERVICE_TOKEN, String.format(BODY, ",\"category\":\"CAB\""));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.category")).isEqualTo("CAB");
        assertThat(JsonPath.<Object>read(response.body(), "$.total")).isNotNull();
        assertThat(JsonPath.<String>read(response.body(), "$.currency")).isEqualTo("INR");
    }

    /** The core reads these fields (its FareQuote record). If pricing drops or renames one, this fails here, before it fails in production. */
    @Test
    void theInternalQuoteContainsEveryFieldTheCoreReads() throws Exception {
        var response = post("/internal/quotes", null, SERVICE_TOKEN, String.format(BODY, ",\"category\":\"CAB\""));

        for (String field : java.util.List.of("distanceKm", "durationMinutes", "baseFare", "distanceFare", "timeFare", "surgeMultiplier", "subtotal", "tax", "total", "currency")) {
            assertThat((Object) JsonPath.read(response.body(), "$." + field)).as(field).isNotNull();
        }
    }

    @Test
    void theInternalEndpointRefusesEveryoneElse() throws Exception {
        String body = String.format(BODY, ",\"category\":\"CAB\"");

        assertThat(post("/internal/quotes", null, null, body).statusCode()).as("no credentials").isEqualTo(401);
        assertThat(post("/internal/quotes", null, "wrong-token", body).statusCode()).as("wrong service token").isEqualTo(401);
        assertThat(post("/internal/quotes", TestTokens.token(UUID.randomUUID(), "ADMIN"), null, body).statusCode())
                .as("even an admin's user token is not a service identity").isEqualTo(403);
        assertThat(post("/internal/quotes", TestTokens.token(UUID.randomUUID(), "RIDER"), null, body).statusCode()).isEqualTo(403);
    }

    @Test
    void impossibleTripsAreRejectedWithAClearMessage() throws Exception {
        String sameSpot = "{\"pickup\":{\"latitude\":12.97,\"longitude\":77.59},\"dropoff\":{\"latitude\":12.97,\"longitude\":77.59},\"category\":\"CAB\"}";
        var tooClose = post("/internal/quotes", null, SERVICE_TOKEN, sameSpot);
        assertThat(tooClose.statusCode()).isEqualTo(400);
        assertThat(tooClose.body()).contains("too close");

        String farAway = "{\"pickup\":{\"latitude\":12.97,\"longitude\":77.59},\"dropoff\":{\"latitude\":28.61,\"longitude\":77.20},\"category\":\"CAB\"}";
        assertThat(post("/internal/quotes", null, SERVICE_TOKEN, farAway).body()).contains("100 km");
    }

    @Test
    void anUnknownCategoryIsABadRequest() throws Exception {
        var response = post("/internal/quotes", null, SERVICE_TOKEN, String.format(BODY, ",\"category\":\"HELICOPTER\""));

        assertThat(response.statusCode()).isEqualTo(400);
    }
}
