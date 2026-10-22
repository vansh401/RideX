package com.ridex.platform.testing;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Raw Kafka clients and sample events, so a service can be tested exactly as the other services would talk to it. */
public final class EventTestSupport {

    private EventTestSupport() {
    }

    public static void publish(String bootstrap, String topic, String key, String value) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish to " + topic, e);
        }
    }

    public static KafkaConsumer<String, String> consumer(String bootstrap, String group, String... topics) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topics));
        return consumer;
    }

    public static List<ConsumerRecord<String, String>> readUntil(KafkaConsumer<String, String> consumer,
                                                                java.util.function.Predicate<List<ConsumerRecord<String, String>>> done,
                                                                int seconds) {
        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline && !done.test(all)) {
            consumer.poll(Duration.ofMillis(300)).forEach(all::add);
        }
        return all;
    }

    public static boolean awaitTrue(BooleanSupplier condition, int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    // ------------------------------------------------------------------ sample events (the JSON other services publish)

    public static String rideEvent(UUID eventId, UUID rideId, UUID riderId, UUID driverId, String from, String to, String actor, BigDecimal fare) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"ride.status-changed\",\"version\":1,\"occurredAt\":\"" + Instant.now()
                + "\",\"key\":\"" + rideId + "\",\"data\":{\"rideId\":\"" + rideId + "\",\"riderUserId\":\"" + riderId
                + "\",\"driverUserId\":" + (driverId == null ? "null" : "\"" + driverId + "\"") + ",\"from\":\"" + from + "\",\"to\":\"" + to
                + "\",\"actor\":\"" + actor + "\",\"note\":null,\"at\":\"" + Instant.now() + "\",\"pickupAddress\":\"12 Lake Road\","
                + "\"dropoffAddress\":\"90 Hill Street\",\"fareTotal\":" + fare.toPlainString() + ",\"currency\":\"INR\"}}";
    }

    public static String paymentEvent(UUID eventId, String type, UUID rideId, UUID riderId, UUID driverId, BigDecimal amount, String failure) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"version\":1,\"occurredAt\":\"" + Instant.now() + "\",\"key\":\""
                + rideId + "\",\"data\":{\"paymentId\":\"" + UUID.randomUUID() + "\",\"rideId\":\"" + rideId + "\",\"riderUserId\":\"" + riderId
                + "\",\"driverUserId\":\"" + driverId + "\",\"amount\":" + amount.toPlainString() + ",\"currency\":\"INR\",\"status\":\""
                + (type.endsWith("succeeded") ? "SUCCEEDED" : "FAILED") + "\",\"failureReason\":" + (failure == null ? "null" : "\"" + failure + "\"")
                + ",\"attempts\":1,\"gatewayReference\":null}}";
    }

    public static String driverReviewedEvent(UUID eventId, UUID driverUserId, String decision, String reason) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"driver.application-reviewed\",\"version\":1,\"occurredAt\":\"" + Instant.now()
                + "\",\"key\":\"" + driverUserId + "\",\"data\":{\"driverUserId\":\"" + driverUserId + "\",\"decision\":\"" + decision
                + "\",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}}";
    }

    /** A completed ride whose rider asked for a coupon (Phase 14: an optional field of the same event). */
    public static String rideEventWithCoupon(UUID eventId, UUID rideId, UUID riderId, UUID driverId, String from, String to, String actor, BigDecimal fare,
                                             String couponCode) {
        String json = rideEvent(eventId, rideId, riderId, driverId, from, to, actor, fare);
        return json.substring(0, json.length() - 2) + ",\"couponCode\":\"" + couponCode + "\"}}";
    }

    /** complaint.filed / complaint.resolved */
    public static String complaintEvent(UUID eventId, String type, UUID complaintId, UUID rideId, UUID userId, String category, String status,
                                        BigDecimal refundAmount) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"version\":1,\"occurredAt\":\"" + Instant.now()
                + "\",\"key\":\"" + rideId + "\",\"data\":{\"complaintId\":\"" + complaintId + "\",\"rideId\":\"" + rideId + "\",\"userId\":\"" + userId
                + "\",\"category\":\"" + category + "\",\"status\":\"" + status + "\",\"refundAmount\":" + (refundAmount == null ? "null" : refundAmount.toPlainString())
                + ",\"resolutionNote\":\"checked\"}}";
    }

    /** A ride event with every Phase 14 field and a chosen time (both the event time and the time of the change). */
    public static String rideEventAt(UUID eventId, UUID rideId, UUID riderId, UUID driverId, String from, String to, String actor, BigDecimal fare,
                                     String category, String cell, java.time.Instant at) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"ride.status-changed\",\"version\":1,\"occurredAt\":\"" + at + "\",\"key\":\"" + rideId
                + "\",\"data\":{\"rideId\":\"" + rideId + "\",\"riderUserId\":\"" + riderId + "\",\"driverUserId\":" + (driverId == null ? "null" : "\"" + driverId + "\"")
                + ",\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"actor\":\"" + actor + "\",\"note\":null,\"at\":\"" + at + "\",\"pickupAddress\":\"A\",\"dropoffAddress\":\"B\","
                + "\"fareTotal\":" + (fare == null ? "null" : fare.toPlainString()) + ",\"currency\":\"INR\",\"category\":\"" + category + "\",\"pickupCell\":\"" + cell
                + "\",\"surgeMultiplier\":1.50,\"couponCode\":null}}";
    }

    /** payment.succeeded, payment.failed or payment.refunded with the Phase 14 fields. */
    public static String paymentEventFull(UUID eventId, String type, UUID rideId, UUID riderId, UUID driverId, BigDecimal amount, BigDecimal discount, String method,
                                          BigDecimal refund, java.time.Instant at) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"version\":1,\"occurredAt\":\"" + at + "\",\"key\":\"" + rideId + "\",\"data\":{\"paymentId\":\""
                + UUID.randomUUID() + "\",\"rideId\":\"" + rideId + "\",\"riderUserId\":\"" + riderId + "\",\"driverUserId\":\"" + driverId + "\",\"amount\":" + amount.toPlainString()
                + ",\"currency\":\"INR\",\"status\":\"" + ("payment.failed".equals(type) ? "FAILED" : "SUCCEEDED") + "\",\"failureReason\":null,\"attempts\":1,\"gatewayReference\":\"ref\","
                + "\"discount\":" + discount.toPlainString() + ",\"method\":\"" + method + "\",\"refundAmount\":" + (refund == null ? "null" : refund.toPlainString()) + "}}";
    }
}
