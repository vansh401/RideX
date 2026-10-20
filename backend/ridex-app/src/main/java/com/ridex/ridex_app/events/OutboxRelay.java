package com.ridex.ridex_app.events;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Copies events from the outbox table to Kafka, oldest first.
 *
 * Guarantees and their price:
 * - AT LEAST ONCE. An event is marked published only after Kafka confirmed it (acks=all). If we crash
 *   after Kafka confirmed but before marking, the event is sent again next time. So consumers must cope
 *   with duplicates (see ProcessedEvents).
 * - ORDER per ride. Events are sent one by one in seq order, and the message key is the ride id, so Kafka
 *   puts all events of a ride in the same partition, in that order. If one send fails we STOP the batch:
 *   skipping ahead would deliver a ride's later events before its earlier one.
 * - ONE relay at a time. A PostgreSQL advisory lock lets only one application server publish at any moment,
 *   otherwise two servers could interleave and break the order.
 */
@Component
@ConditionalOnProperty(name = "ridex.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long ADVISORY_LOCK_ID = 6_001L;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final int batchSize;

    public OutboxRelay(OutboxEventRepository repository, KafkaTemplate<String, String> kafka, JdbcTemplate jdbc,
                       PlatformTransactionManager transactionManager,
                       @Value("${ridex.outbox.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.kafka = kafka;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ridex.outbox.poll-interval-ms:500}")
    public void tick() {
        try {
            relayOnce();
        } catch (RuntimeException e) {
            log.warn("Outbox relay pass failed, will retry: {}", e.getClass().getSimpleName());
        }
    }

    /** One pass. Returns how many events were published. Public so tests can drive it directly. */
    public int relayOnce() {
        Integer published = transaction.execute(status -> {
            Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_ID);
            if (!Boolean.TRUE.equals(locked)) {
                return 0;   // another server is relaying right now
            }
            List<OutboxEvent> batch = repository.findUnpublished(PageRequest.of(0, batchSize));
            int count = 0;
            for (OutboxEvent event : batch) {
                try {
                    ProducerRecord<String, String> record = new ProducerRecord<>(event.getTopic(), event.getEventKey(), event.getPayload());
                    record.headers().add("event-id", event.getEventId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    record.headers().add("event-type", event.getEventType().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    kafka.send(record).get(10, TimeUnit.SECONDS);   // wait for the broker to confirm
                    event.markPublished(Instant.now());
                    count++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Could not publish outbox event {} ({}): {}. Stopping this pass to keep the order.",
                            event.getSeq(), event.getEventType(), e.getClass().getSimpleName());
                    break;
                }
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    /** Published rows are only needed for a short while; delete them so the table stays small. */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L)
    public void purgePublished() {
        try {
            Integer removed = transaction.execute(status ->
                    repository.deletePublishedBefore(Instant.now().minus(Duration.ofDays(1))));
            if (removed != null && removed > 0) {
                log.info("Removed {} published outbox rows older than a day", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Outbox purge failed: {}", e.getClass().getSimpleName());
        }
    }
}
