package com.ridex.ridex_app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityRulesIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        HttpResponse<String> response = get("/api/health");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void anyOtherEndpointRequiresAuthentication() throws Exception {
        HttpResponse<String> response = get("/api/rides");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"status\":401");
        assertThat(response.body()).contains("\"error\":\"UNAUTHORIZED\"");
    }

    @Test
    void registrationEndpointIsPublicAndReachesTheController() throws Exception {
        // Invalid body: validation (400) proves the request got past security to our controller
        HttpResponse<String> response = postJson("/api/auth/register", "{}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("Validation failed");
    }
}