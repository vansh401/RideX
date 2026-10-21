package com.ridex.notification;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ridex.platform.testing.EventTestSupport;
import com.ridex.platform.testing.ServiceContainers;
import com.ridex.platform.testing.TestTokens;

/** The notification service started for real, against real PostgreSQL, Kafka and Redis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class NotificationTestBase {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ServiceContainers.postgres()::getJdbcUrl);
        registry.add("spring.datasource.username", ServiceContainers.postgres()::getUsername);
        registry.add("spring.datasource.password", ServiceContainers.postgres()::getPassword);
        registry.add("spring.kafka.bootstrap-servers", ServiceContainers.kafka()::getBootstrapServers);
        registry.add("spring.data.redis.host", ServiceContainers.redis()::getHost);
        registry.add("spring.data.redis.port", () -> ServiceContainers.redis().getMappedPort(6379));
        registry.add("ridex.security.public-key-pem", TestTokens::publicKeyPem);
    }

    @Value("${local.server.port}")
    protected int port;

    @Value("${spring.kafka.bootstrap-servers}")
    protected String bootstrap;

    @Autowired
    protected JdbcTemplate jdbc;

    protected void publish(String topic, String key, String json) {
        EventTestSupport.publish(bootstrap, topic, key, json);
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    protected static boolean await(java.util.function.BooleanSupplier condition, int seconds) {
        return EventTestSupport.awaitTrue(condition, seconds);
    }

    protected static UUID id() {
        return UUID.randomUUID();
    }
}
