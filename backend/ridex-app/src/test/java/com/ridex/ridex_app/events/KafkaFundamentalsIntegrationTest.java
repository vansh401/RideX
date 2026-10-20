package com.ridex.ridex_app.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.support.AbstractEventTest;

/**
 * The ideas of Kafka, shown on a real broker with throwaway topics: partition, key, ordering, consumer group,
 * offset. These are the behaviours the rest of Phase 6 relies on.
 */
class KafkaFundamentalsIntegrationTest extends AbstractEventTest {

    private String newTopic(int partitions) throws Exception {
        String name = "test-" + UUID.randomUUID();
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        try (AdminClient admin = AdminClient.create(props)) {
            admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all().get();
        }
        return name;
    }

    @Test
    void messagesWithTheSameKeyStayInOneTopicPartitionInOrder() throws Exception {
        String topic = newTopic(3);
        for (int i = 0; i < 30; i++) {
            publish(topic, "ride-A", "step-" + i);
        }

        try (KafkaConsumer<String, String> consumer = consumer(uniqueGroup("order"), topic)) {
            List<ConsumerRecord<String, String>> records = readUntil(consumer, all -> all.size() >= 30, 20);

            assertThat(records).hasSize(30);
            assertThat(records.stream().map(ConsumerRecord::partition).distinct())
                    .as("one key, one partition: that is what keeps a ride's events together").hasSize(1);
            assertThat(records.stream().map(ConsumerRecord::value).toList())
                    .as("and inside a partition the order is exactly the order of sending")
                    .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 30).mapToObj(i -> "step-" + i).toList());
            assertThat(records.stream().map(ConsumerRecord::offset).toList()).isSorted();
        }
    }

    @Test
    void differentKeysSpreadAcrossPartitionsSoWorkCanRunInParallel() throws Exception {
        String topic = newTopic(3);
        for (int i = 0; i < 30; i++) {
            publish(topic, "ride-" + i, "x");
        }

        try (KafkaConsumer<String, String> consumer = consumer(uniqueGroup("spread"), topic)) {
            List<ConsumerRecord<String, String>> records = readUntil(consumer, all -> all.size() >= 30, 20);

            assertThat(records.stream().map(ConsumerRecord::partition).distinct().count()).isGreaterThan(1);
        }
    }

    @Test
    void everyConsumerGroupReceivesEveryMessageIndependently() throws Exception {
        String topic = newTopic(3);
        for (int i = 0; i < 10; i++) {
            publish(topic, "k" + i, "m" + i);
        }

        try (KafkaConsumer<String, String> notifications = consumer(uniqueGroup("notifications"), topic);
             KafkaConsumer<String, String> analytics = consumer(uniqueGroup("analytics"), topic)) {
            List<ConsumerRecord<String, String>> a = readUntil(notifications, all -> all.size() >= 10, 20);
            List<ConsumerRecord<String, String>> b = readUntil(analytics, all -> all.size() >= 10, 20);

            // Not a queue where one reader steals the message from the other: both groups see all of it
            assertThat(a).hasSize(10);
            assertThat(b).hasSize(10);
        }
    }

    @Test
    void consumersInTheSameGroupShareThePartitionsAndNeverSeeTheSameMessageTwice() throws Exception {
        String topic = newTopic(3);
        String group = uniqueGroup("shared");

        try (KafkaConsumer<String, String> first = consumer(group, topic);
             KafkaConsumer<String, String> second = consumer(group, topic)) {
            // Let both members join first. Joining or leaving makes Kafka re-divide ("rebalance") the partitions, and a
            // message that was read but not yet confirmed can then be delivered again. That is normal, and exactly why
            // consumers must be idempotent. Here we only want to see the steady state, so no data moves during the join.
            assertThat(awaitTrue(() -> {
                first.poll(Duration.ofMillis(200));
                second.poll(Duration.ofMillis(200));
                return !first.assignment().isEmpty() && !second.assignment().isEmpty()
                        && first.assignment().size() + second.assignment().size() == 3;
            }, 40)).isTrue();

            for (int i = 0; i < 60; i++) {
                publish(topic, "ride-" + i, "m" + i);
            }
            List<String> fromFirst = new ArrayList<>();
            List<String> fromSecond = new ArrayList<>();
            awaitTrue(() -> {
                first.poll(Duration.ofMillis(200)).forEach(r -> fromFirst.add(r.value()));
                second.poll(Duration.ofMillis(200)).forEach(r -> fromSecond.add(r.value()));
                return fromFirst.size() + fromSecond.size() >= 60;
            }, 40);

            Set<String> all = new HashSet<>(fromFirst);
            all.addAll(fromSecond);
            assertThat(all).as("every message was handled").hasSize(60);
            assertThat(fromFirst.size() + fromSecond.size()).as("and none was handled twice").isEqualTo(60);
            Set<?> a = first.assignment();
            Set<?> b = second.assignment();
            assertThat(a).as("each member owns some partitions").isNotEmpty();
            assertThat(b).isNotEmpty();
            assertThat(a.stream().filter(b::contains)).as("and no partition is owned by both").isEmpty();
        }
    }

    @Test
    void aGroupRemembersItsOffsetSoARestartedConsumerContinuesWhereItStopped() throws Exception {
        String topic = newTopic(1);
        String group = uniqueGroup("restart");
        for (int i = 0; i < 10; i++) {
            publish(topic, "k", "m" + i);
        }

        try (KafkaConsumer<String, String> before = consumer(group, topic)) {
            List<ConsumerRecord<String, String>> firstFive = readUntil(before, all -> all.size() >= 5, 20);
            assertThat(firstFive.size()).isGreaterThanOrEqualTo(5);
            // Confirm ("commit") only what we really handled: the first five
            before.commitSync(Map.of(
                    new org.apache.kafka.common.TopicPartition(topic, 0),
                    new org.apache.kafka.clients.consumer.OffsetAndMetadata(5)));
        }

        try (KafkaConsumer<String, String> after = consumer(group, topic)) {
            List<ConsumerRecord<String, String>> rest = readUntil(after, all -> all.size() >= 5, 20);

            assertThat(rest.stream().map(ConsumerRecord::value).collect(Collectors.toList()))
                    .as("the new consumer picks up at offset 5: m5 ... m9")
                    .containsExactly("m5", "m6", "m7", "m8", "m9");
        }
    }
}
