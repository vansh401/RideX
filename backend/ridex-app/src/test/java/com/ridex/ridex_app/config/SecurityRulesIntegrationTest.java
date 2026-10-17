package com.ridex.ridex_app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import com.ridex.ridex_app.jwt.JwtService;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityRulesIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JwtService jwtService;

    private final HttpClient client = HttpClient.newHttpClient();

    private String tokenFor(Role role) {
        // The user only exists in memory; the token is real and correctly signed
        User user = new User("Test Person", "test@example.com", "+910000000000", "hash", role);
        return jwtService.createAccessToken(user).value();
    }

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
        assertThat(get("/api/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    void anyOtherEndpointRequiresAuthentication() throws Exception {
        HttpResponse<String> response = get("/api/rides", null);

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

    @Test
    void emailVerificationEndpointsArePublic() throws Exception {
        // Invalid bodies: validation (400) proves security let the request through
        assertThat(postJson("/api/auth/verify-email", "{}").statusCode()).isEqualTo(400);
        assertThat(postJson("/api/auth/resend-verification", "{}").statusCode()).isEqualTo(400);
    }

    @Test
    void passwordResetEndpointsArePublic() throws Exception {
        assertThat(postJson("/api/auth/forgot-password", "{}").statusCode()).isEqualTo(400);
        assertThat(postJson("/api/auth/reset-password", "{}").statusCode()).isEqualTo(400);
    }

    @Test
    void apiDocumentationIsPublicAndDescribesTheApi() throws Exception {
        HttpResponse<String> docs = get("/v3/api-docs", null);

        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.body())
                .contains("RideX API")
                .contains("/api/auth/login")
                .contains("/api/users/me")
                .contains("bearerAuth")
                .contains("ApiError");
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        assertThat(get("/swagger-ui/index.html", null).statusCode()).isEqualTo(200);
    }

    @Test
    void riderAreaIsOnlyForRiders() throws Exception {
        assertThat(get("/api/riders/me", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/riders/me", tokenFor(Role.DRIVER)).statusCode()).isEqualTo(403);
        assertThat(get("/api/riders/me", tokenFor(Role.ADMIN)).statusCode()).isEqualTo(403);
        // This rider exists only in the token: "profile not found" proves the request reached the controller
        assertThat(get("/api/riders/me", tokenFor(Role.RIDER)).statusCode()).isEqualTo(404);
    }

    @Test
    void driverProfileAreaIsOnlyForDrivers() throws Exception {
        assertThat(get("/api/drivers/me", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/drivers/me", tokenFor(Role.RIDER)).statusCode()).isEqualTo(403);
        assertThat(get("/api/drivers/me", tokenFor(Role.ADMIN)).statusCode()).isEqualTo(403);
        assertThat(get("/api/drivers/me", tokenFor(Role.DRIVER)).statusCode()).isEqualTo(404);
    }

    @Test
    void garbageTokenIsTreatedAsNotLoggedIn() throws Exception {
        assertThat(get("/api/users/me", "not-a-real-token").statusCode()).isEqualTo(401);
    }

    @Test
    void validTokenForAUserThatDoesNotExistGets404() throws Exception {
        HttpResponse<String> response = get("/api/users/me", tokenFor(Role.RIDER));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void riderCannotEnterTheAdminArea() throws Exception {
        HttpResponse<String> response = get("/api/admin/ping", tokenFor(Role.RIDER));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"status\":403");
    }

    @Test
    void adminCanEnterTheAdminArea() throws Exception {
        assertThat(get("/api/admin/ping", tokenFor(Role.ADMIN)).statusCode()).isEqualTo(200);
    }

    @Test
    void riderCannotCallDriverEndpointProtectedByMethodSecurity() throws Exception {
        assertThat(get("/api/driver/ping", tokenFor(Role.RIDER)).statusCode()).isEqualTo(403);
    }

    @Test
    void driverCanCallDriverEndpoint() throws Exception {
        assertThat(get("/api/driver/ping", tokenFor(Role.DRIVER)).statusCode()).isEqualTo(200);
    }
}
