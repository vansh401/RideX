package com.ridex.ridex_app.support;

import java.time.Duration;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.events.ConsumerLagService;

/**
 * Helpers for tests about events: raw Kafka clients (so tests see exactly what is on the topic), waiting for
 * asynchronous consumers, and a way to run a whole ride through the API.
 */
public abstract class AbstractEventTest extends AbstractApiTest {

    @Value("${spring.kafka.bootstrap-servers}")
    protected String bootstrap;

    @Autowired
    protected ConsumerLagService lagService;

    protected record Journey(Person rider, DriverFixture driver, String rideId) {
    }

    protected static Journey journey(Person rider, DriverFixture driver, String rideId) {
        return new Journey(rider, driver, rideId);
    }

    // ------------------------------------------------------------------ raw Kafka

    protected KafkaConsumer<String, String> consumer(String group, String... topics) {
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

    protected static String uniqueGroup(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** Sends one message and waits until the broker has it. */
    protected void publish(String topic, String key, String value) {
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

    /** Reads from the consumer until {@code done} says enough, or the time runs out. */
    protected List<ConsumerRecord<String, String>> readUntil(KafkaConsumer<String, String> consumer,
                                                            java.util.function.Predicate<List<ConsumerRecord<String, String>>> done,
                                                            int seconds) {
        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline && !done.test(all)) {
            consumer.poll(Duration.ofMillis(300)).forEach(all::add);
        }
        return all;
    }

    // ------------------------------------------------------------------ waiting

    protected static boolean awaitTrue(BooleanSupplier condition, int seconds) {
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

    /** Waits until every consumer group has handled everything published so far (lag 0, twice in a row). */
    protected boolean awaitCaughtUp(int seconds) {
        int[] quiet = {0};
        return awaitTrue(() -> {
            boolean zero = lagService.lag().stream().allMatch(l -> l.lag() == 0)
                    && count("select count(*) from outbox_events where published_at is null") == 0;
            quiet[0] = zero ? quiet[0] + 1 : 0;
            return quiet[0] >= 2;
        }, seconds);
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ a whole ride through the API

    /** A rider requests, a driver accepts, arrives, starts and completes. Cleans the driver up afterwards. */
    protected Journey completeRide(double lat, double lng) {
        Person admin = newAdmin();
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        Person rider = newRider();
        String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng))).id();
        expect(200, api().post("/api/rides/" + rideId + "/accept", driver.token(), null));
        expect(200, api().post("/api/rides/" + rideId + "/arrived", driver.token(), null));
        expect(200, api().post("/api/rides/" + rideId + "/start", driver.token(), null));
        expect(200, api().post("/api/rides/" + rideId + "/complete", driver.token(), null));
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
        return new Journey(rider, driver, rideId);
    }

    /** The JSON of the event that moved this ride to the given status, as stored by the audit consumer. */
    protected String auditedEvent(String rideId, String toStatus) {
        awaitTrue(() -> count("select count(*) from event_audit_log where event_key = ? and payload like ?",
                rideId, "%\"to\":\"" + toStatus + "\"%") > 0, 20);
        return jdbc.queryForObject("select payload from event_audit_log where event_key = ? and payload like ? order by id limit 1",
                String.class, rideId, "%\"to\":\"" + toStatus + "\"%");
    }
}
