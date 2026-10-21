package com.ridex.payment;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ridex.payment.gateway.MockGateway;
import com.ridex.platform.testing.EventTestSupport;
import com.ridex.platform.testing.ServiceContainers;
import com.ridex.platform.testing.TestTokens;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * The payment service started for real, against real PostgreSQL, Kafka and Redis. The "remote" payment gateway is the mock
 * inside the service, reached over real HTTP on the service's own port, so timeouts, 500s and the circuit breaker are real.
 */
// Needs Docker (Testcontainers): the CI pipeline runs these in a separate job from the fast unit tests (see docs/ci-cd-concepts.md)
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
abstract class PaymentTestBase {

    private static final int PORT = freePort();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("ridex.payments.gateway.base-url", () -> "http://localhost:" + PORT + "/mock-gateway");
        registry.add("spring.datasource.url", ServiceContainers.postgres()::getJdbcUrl);
        registry.add("spring.datasource.username", ServiceContainers.postgres()::getUsername);
        registry.add("spring.datasource.password", ServiceContainers.postgres()::getPassword);
        registry.add("spring.kafka.bootstrap-servers", ServiceContainers.kafka()::getBootstrapServers);
        registry.add("spring.data.redis.host", ServiceContainers.redis()::getHost);
        registry.add("spring.data.redis.port", () -> ServiceContainers.redis().getMappedPort(6379));
        registry.add("ridex.security.public-key-pem", TestTokens::publicKeyPem);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected final int port = PORT;

    @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}")
    protected String bootstrap;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MockGateway mock;

    @Autowired
    protected CircuitBreaker circuitBreaker;

    /** Every test starts with a healthy gateway and a closed circuit, whatever the previous test did to them. */
    @BeforeEach
    void healthyGateway() {
        mock.setChaos(new MockGateway.Chaos(MockGateway.ChaosMode.NORMAL, 0));
        circuitBreaker.reset();
    }

    protected void publish(String topic, String key, String json) {
        EventTestSupport.publish(bootstrap, topic, key, json);
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    protected String statusOf(UUID rideId) {
        var rows = jdbc.queryForList("select status from payments where ride_id = ?", String.class, rideId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    protected boolean awaitStatus(UUID rideId, String status, int seconds) {
        return EventTestSupport.awaitTrue(() -> status.equals(statusOf(rideId)), seconds);
    }

    protected static UUID id() {
        return UUID.randomUUID();
    }
}
