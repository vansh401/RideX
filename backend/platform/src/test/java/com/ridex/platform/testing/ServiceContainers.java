package com.ridex.platform.testing;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The real infrastructure a service is tested against: PostgreSQL, Kafka and Redis in Docker, started once per test JVM
 * and shared by every test class. Not fakes: partial indexes, consumer groups and GETDEL must behave like production.
 */
public final class ServiceContainers {

    private static PostgreSQLContainer postgres;
    private static KafkaContainer kafka;
    private static GenericContainer<?> redis;

    private ServiceContainers() {
    }

    public static synchronized PostgreSQLContainer postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer("postgres:17");
            postgres.start();
        }
        return postgres;
    }

    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            kafka = new KafkaContainer("apache/kafka:3.9.1").withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx384m");
            kafka.start();
        }
        return kafka;
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
            redis.start();
        }
        return redis;
    }
}
