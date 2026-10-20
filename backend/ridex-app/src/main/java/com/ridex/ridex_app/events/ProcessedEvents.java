package com.ridex.ridex_app.events;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes a consumer IDEMPOTENT: handling the same event twice has the same effect as handling it once.
 *
 * Why it is needed: Kafka promises "at least once". If a consumer finishes its work and crashes before
 * confirming (committing the offset), Kafka delivers the message again. Without protection a driver would be
 * notified twice, a ride counted twice, a customer charged twice.
 *
 * How: in ONE database transaction we (1) write "this group handled this event id" into processed_events and
 * (2) do the work. The primary key (group, event id) makes the second delivery's insert fail to insert, so we
 * skip the work. Because both happen in the same transaction, a crash in the middle rolls both back, and the retry
 * does everything again, exactly once in effect.
 *
 * (Limit: this covers effects inside our own database. For effects outside, such as charging a card, the other
 * system must be idempotent too. That is what the payment idempotency key is for.)
 */
@Component
public class ProcessedEvents {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ProcessedEvents(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Runs {@code work} unless this group already handled this event. Returns true if the work ran. */
    public boolean runOnce(String consumerGroup, UUID eventId, Runnable work) {
        Boolean ran = transaction.execute(status -> {
            int inserted = jdbc.update(
                    "insert into processed_events (consumer_group, event_id) values (?, ?) on conflict do nothing",
                    consumerGroup, eventId);
            if (inserted == 0) {
                return false;   // a duplicate delivery: already done
            }
            work.run();
            return true;
        });
        return Boolean.TRUE.equals(ran);
    }
}
