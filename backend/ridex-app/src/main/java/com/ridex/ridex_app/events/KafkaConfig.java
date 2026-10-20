package com.ridex.ridex_app.events;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.apache.kafka.clients.admin.NewTopic;

/**
 * Topics and the rule for what happens when a consumer fails.
 *
 * A topic is a named, append-only log of events. It is split into PARTITIONS (here 3). The message key decides the
 * partition (same key, same partition), and inside a partition order is guaranteed. Different partitions can be
 * read by different consumers of a group in parallel, which is how Kafka scales out. Ride events use the ride id
 * as key, so the events of one ride are always in order, while different rides proceed in parallel.
 */
@Configuration
@EnableConfigurationProperties(KafkaConfig.RetryProperties.class)
public class KafkaConfig {

    @ConfigurationProperties(prefix = "ridex.kafka")
    public record RetryProperties(int maxAttempts, long retryInitialMs, double retryMultiplier, long retryMaxMs) {
    }

    @Bean
    NewTopic rideEventsTopic() {
        return TopicBuilder.name(Topics.RIDE_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentEventsTopic() {
        return TopicBuilder.name(Topics.PAYMENT_EVENTS).partitions(3).replicas(1).build();
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

    /**
     * What to do when handling a message throws:
     *  1. Try again after a growing pause (0.5 s, 1 s, 2 s ...): most failures are temporary (a lock, a slow database).
     *  2. After the last try, publish the message to "<topic>.DLT" and MOVE ON. A message that will never work must
     *     not block everything behind it.
     *  3. A message that is broken for good (PoisonEventException) skips step 1: retrying cannot help.
     * Spring Boot picks this bean up for every @KafkaListener.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, RetryProperties retry) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(Topics.deadLetterOf(record.topic()), -1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(Math.max(0, retry.maxAttempts() - 1));
        backOff.setInitialInterval(retry.retryInitialMs());
        backOff.setMultiplier(retry.retryMultiplier());
        backOff.setMaxInterval(retry.retryMaxMs());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(PoisonEventException.class);
        return handler;
    }
}
