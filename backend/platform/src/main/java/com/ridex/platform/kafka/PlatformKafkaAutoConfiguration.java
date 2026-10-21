package com.ridex.platform.kafka;

import java.util.List;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.ridex.platform.contracts.Topics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

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
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, RetryProperties retry, MeterRegistry metrics) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(Topics.deadLetterOf(record.topic()), -1));
        // Every message that ends up in a dead-letter topic is counted: this number should be zero, and an alert watches it
        for (String topic : List.of(Topics.RIDE_EVENTS, Topics.PAYMENT_EVENTS, Topics.DRIVER_EVENTS)) {
            Counter.builder("ridex.dead.letters").tag("topic", topic).register(metrics);   // visible as 0 from the start
        }
        ConsumerRecordRecoverer counted = (record, exception) -> {
            Counter.builder("ridex.dead.letters").description("Messages moved to a dead-letter topic").tag("topic", record.topic())
                    .register(metrics).increment();
            recoverer.accept(record, exception);
        };
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(Math.max(0, retry.attempts() - 1));
        backOff.setInitialInterval(retry.initial());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.max());
        DefaultErrorHandler handler = new DefaultErrorHandler(counted, backOff);
        handler.addNotRetryableExceptions(PoisonEventException.class);
        return handler;
    }

    /** Puts the event id, event type and ride id in the logging context while a message is handled. */
    @Bean
    @ConditionalOnMissingBean
    KafkaLogContextInterceptor kafkaLogContextInterceptor() {
        return new KafkaLogContextInterceptor();
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

    /** Keeps the trace of a request attached to the events it causes (see TraceContextCarrier). Does nothing when tracing is off. */
    @Bean
    @ConditionalOnMissingBean
    TraceContextCarrier traceContextCarrier(ObjectProvider<Tracer> tracer,
                                            ObjectProvider<Propagator> propagator) {
        return new TraceContextCarrier(tracer.getIfAvailable(), propagator.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty("spring.datasource.url")
    @ConditionalOnMissingBean
    OutboxStore outboxStore(JdbcTemplate jdbc) {
        return new JdbcOutboxStore(jdbc);
    }

    @Bean
    @ConditionalOnProperty("spring.datasource.url")
    @ConditionalOnMissingBean
    OutboxService outboxService(JdbcTemplate jdbc, EventCodec codec, TraceContextCarrier trace) {
        return new OutboxService(jdbc, codec, trace);
    }

    @Bean
    @ConditionalOnProperty("spring.datasource.url")
    @ConditionalOnMissingBean
    OutboxMetrics outboxMetrics(OutboxStore store, MeterRegistry metrics) {
        return new OutboxMetrics(store, metrics);
    }

    @Bean
    @ConditionalOnProperty("spring.datasource.url")
    @ConditionalOnExpression("${ridex.outbox.enabled:true}")
    @ConditionalOnMissingBean
    OutboxRelay outboxRelay(OutboxStore store, KafkaTemplate<String, String> kafka, PlatformTransactionManager transactionManager,
                            @Value("${ridex.outbox.batch-size:100}") int batchSize,
                            TraceContextCarrier trace, MeterRegistry metrics) {
        return new OutboxRelay(store, kafka, transactionManager, batchSize, trace, metrics);
    }
}
