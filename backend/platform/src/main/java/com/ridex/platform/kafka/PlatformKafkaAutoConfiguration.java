package com.ridex.platform.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.ridex.platform.contracts.Topics;

import tools.jackson.databind.json.JsonMapper;

/**
 * Everything a service needs to take part in the event system, switched on by having this library on the classpath:
 * the topics, the retry-then-dead-letter rule for consumers, the outbox, and idempotent processing.
 *
 * Topics are declared here by EVERY service: creating a topic that already exists with the same settings is a no-op,
 * so no service has to wait for another to start first.
 */
@AutoConfiguration
@EnableScheduling
@EnableTransactionManagement
@EnableConfigurationProperties(PlatformKafkaAutoConfiguration.RetryProperties.class)
public class PlatformKafkaAutoConfiguration {

    @ConfigurationProperties(prefix = "ridex.kafka")
    public record RetryProperties(Integer maxAttempts, Long retryInitialMs, Double retryMultiplier, Long retryMaxMs) {
        int attempts() { return maxAttempts == null ? 4 : maxAttempts; }
        long initial() { return retryInitialMs == null ? 500 : retryInitialMs; }
        double multiplier() { return retryMultiplier == null ? 2.0 : retryMultiplier; }
        long max() { return retryMaxMs == null ? 8000 : retryMaxMs; }
    }

    // ------------------------------------------------------------------ topics

    @Bean
    NewTopic rideEventsTopic() {
        return TopicBuilder.name(Topics.RIDE_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentEventsTopic() {
        return TopicBuilder.name(Topics.PAYMENT_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic driverEventsTopic() {
        return TopicBuilder.name(Topics.DRIVER_EVENTS).partitions(3).replicas(1).build();
    }

    // Dead-letter topics: one partition is plenty, they hold only the rare failures
    @Bean
    NewTopic rideEventsDeadLetterTopic() {
        return TopicBuilder.name(Topics.deadLetterOf(Topics.RIDE_EVENTS)).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic paymentEventsDeadLetterTopic() {
        return TopicBuilder.name(Topics.deadLetterOf(Topics.PAYMENT_EVENTS)).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic driverEventsDeadLetterTopic() {
        return TopicBuilder.name(Topics.deadLetterOf(Topics.DRIVER_EVENTS)).partitions(1).replicas(1).build();
    }

    // ------------------------------------------------------------------ consuming

    /**
     * What to do when handling a message throws:
     *  1. Try again after a growing pause (0.5 s, 1 s, 2 s ...): most failures are temporary (a lock, a slow database).
     *  2. After the last try, publish the message to "<topic>.DLT" (dead-letter topic) and MOVE ON. A message that will
     *     never work must not block everything behind it.
     *  3. A message that is broken for good (PoisonEventException) skips step 1: retrying cannot help.
     * Spring Boot picks this bean up for every @KafkaListener.
     */
    @Bean
    @ConditionalOnProperty("spring.kafka.bootstrap-servers")
    @ConditionalOnMissingBean(DefaultErrorHandler.class)
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, RetryProperties retry) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(Topics.deadLetterOf(record.topic()), -1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(Math.max(0, retry.attempts() - 1));
        backOff.setInitialInterval(retry.initial());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.max());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(PoisonEventException.class);
        return handler;
    }

    @Bean
    @ConditionalOnMissingBean
    EventCodec eventCodec(JsonMapper mapper) {
        return new EventCodec(mapper);
    }

    @Bean
    @ConditionalOnProperty("spring.datasource.url")
    @ConditionalOnMissingBean
    ProcessedEvents processedEvents(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new ProcessedEvents(jdbc, transactionManager);
    }

    // ------------------------------------------------------------------ publishing

    @Bean
    @ConditionalOnProperty("spring.datasource.url")
    @ConditionalOnMissingBean
    OutboxStore outboxStore(JdbcTemplate jdbc) {
        return new JdbcOutboxStore(jdbc);
    }

    @Bean
    @ConditionalOnProperty("spring.datasource.url")
    @ConditionalOnMissingBean
    OutboxService outboxService(JdbcTemplate jdbc, EventCodec codec) {
        return new OutboxService(jdbc, codec);
    }

    @Bean
    @ConditionalOnProperty("spring.datasource.url")
    @ConditionalOnExpression("${ridex.outbox.enabled:true}")
    @ConditionalOnMissingBean
    OutboxRelay outboxRelay(OutboxStore store, KafkaTemplate<String, String> kafka, PlatformTransactionManager transactionManager,
                            @org.springframework.beans.factory.annotation.Value("${ridex.outbox.batch-size:100}") int batchSize) {
        return new OutboxRelay(store, kafka, transactionManager, batchSize);
    }
}
