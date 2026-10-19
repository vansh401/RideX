package com.ridex.ridex_app.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that start the whole application.
 *
 * They run against a REAL PostgreSQL 17 and a REAL Redis 7 started in Docker (Testcontainers), not against your
 * development database and not against an in-memory fake. Why: our code relies on PostgreSQL
 * features (partial unique indexes, FOR UPDATE SKIP LOCKED, NUMERIC arithmetic) that a fake
 * database would not behave like, and tests must never touch development data.
 *
 * One container is started once and shared by every test class, which keeps the suite fast.
 * It is removed automatically when the test JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    // A real Redis too (not a fake): GETDEL, GEOSEARCH, SET NX PX and Lua scripts must behave like production
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
