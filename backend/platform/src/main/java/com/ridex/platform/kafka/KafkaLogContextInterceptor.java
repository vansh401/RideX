package com.ridex.platform.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import com.ridex.platform.contracts.Topics;
import com.ridex.platform.web.LogContext;

/**
 * While an event is handled, every log line says which event and which ride it is about (the HTTP side does the same through
 * RequestIdFilter and LogContext). The trace id is added by the tracing library from the "traceparent" header of the message.
 */
public class KafkaLogContextInterceptor implements RecordInterceptor<Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(KafkaLogContextInterceptor.class);

    static final String EVENT_ID = "eventId";
    static final String EVENT_TYPE = "eventType";

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        put(EVENT_ID, header(record, "event-id"));
        put(EVENT_TYPE, header(record, "event-type"));
        // ride and payment events are keyed by ride id; driver events are keyed by the driver's user id
        if (!Topics.DRIVER_EVENTS.equals(record.topic()) && record.key() != null) {
            put(LogContext.RIDE_ID, record.key().toString());
        }
        log.info("Event received from {}", record.topic());   // the id, type, ride and trace are in the context, not in the message
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        clear();
    }

    @Override
    public void clearThreadState(Consumer<?, ?> consumer) {
        clear();
    }

    private static void clear() {
        MDC.remove(EVENT_ID);
        MDC.remove(EVENT_TYPE);
        MDC.remove(LogContext.RIDE_ID);
    }

    private static void put(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
