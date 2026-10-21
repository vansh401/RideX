package com.ridex.analytics;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import com.ridex.platform.contracts.Topics;

/**
 * Parks every dead-lettered message in a table an admin can read. The failed message itself, with the error, the
 * original topic, partition and offset, is also kept in the ".DLT" Kafka topic.
 *
 * This consumer must never fail: a failure here would have nowhere left to go.
 */
@Component
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);

    private final JdbcTemplate jdbc;

    public DeadLetterConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = {Topics.RIDE_EVENTS + ".DLT", Topics.PAYMENT_EVENTS + ".DLT", Topics.DRIVER_EVENTS + ".DLT"}, groupId = Groups.DEAD_LETTERS)
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        try {
            String originalTopic = text(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
            jdbc.update("""
                    insert into dead_letter_events (source_topic, partition_no, offset_no, event_key, failed_group, error_message, payload)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    originalTopic != null ? originalTopic : record.topic(),
                    integer(record, KafkaHeaders.DLT_ORIGINAL_PARTITION),
                    longValue(record, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                    record.key(),
                    text(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP),
                    text(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                    record.value());
            log.warn("A message was dead-lettered from {} (group {}): {}", originalTopic,
                    text(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP), text(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
        } catch (RuntimeException e) {
            log.error("Could not record a dead letter: {}", e.getClass().getSimpleName());
        }
    }

    private static String text(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Integer integer(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value().length != 4 ? null : ByteBuffer.wrap(header.value()).getInt();
    }

    private static Long longValue(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value().length != 8 ? null : ByteBuffer.wrap(header.value()).getLong();
    }
}
