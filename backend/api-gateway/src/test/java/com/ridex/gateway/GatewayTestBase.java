package com.ridex.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import com.jayway.jsonpath.JsonPath;

/**
 * The gateway started for real, in front of five fake services, with a real Redis (rate limiter and revocation list).
 * Requests are real HTTP requests to the gateway's port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ridex.gateway.jwks-uri=",
        // generous by default; the rate limit tests choose their own
        "GATEWAY_RATE=1000", "GATEWAY_BURST=1000", "GATEWAY_AUTH_RATE=1000", "GATEWAY_AUTH_BURST=1000",
        "GATEWAY_RESPONSE_TIMEOUT=1s"
})
abstract class GatewayTestBase {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ridex.gateway.public-key-pem", TestJwts::publicKeyPem);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("CORE_URL", () -> FakeServices.url("core"));
        registry.add("CORE_WS_URL", () -> FakeServices.url("core").replace("http://", "ws://"));
        registry.add("NOTIFICATION_URL", () -> FakeServices.url("notification"));
        registry.add("PAYMENT_URL", () -> FakeServices.url("payment"));
        registry.add("PRICING_URL", () -> FakeServices.url("pricing"));
        registry.add("ANALYTICS_URL", () -> FakeServices.url("analytics"));
    }

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected ReactiveStringRedisTemplate redis;

    private final HttpClient client = HttpClient.newHttpClient();

    /** A clean slate: no rate-limit buckets and no revocations left by another test. */
    @BeforeEach
    void flushRedis() {
        redis.getConnectionFactory().getReactiveConnection().serverCommands().flushAll().block();
    }

    protected record Reply(int status, String body, Map<String, java.util.List<String>> headers) {
        <T> T json(String path) {
            return JsonPath.read(body, path);
        }

        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    protected Reply call(String method, String path, String token, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body(), response.headers().map());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Reply get(String path, String token, String... headers) {
        return call("GET", path, token, headers);
    }
}
