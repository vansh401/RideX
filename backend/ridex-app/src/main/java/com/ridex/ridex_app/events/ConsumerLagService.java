package com.ridex.ridex_app.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

/**
 * Consumer LAG: how far behind a consumer group is. For every partition:
 *
 *     lag = (offset of the newest message) - (offset the group has confirmed)
 *
 * An OFFSET is a message's position number inside a partition (0, 1, 2 ...). A group confirms ("commits") the offset
 * it has handled. Lag 0 means the group is fully caught up. A lag that keeps growing means the consumers cannot keep
 * up, or are down. It is the single most useful number for operating a Kafka system.
 */
@Service
public class ConsumerLagService {

    public record Lag(String group, String topic, long lag) {
    }

    private static final long TIMEOUT_SECONDS = 10;

    private final KafkaAdmin kafkaAdmin;

    public ConsumerLagService(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    public List<Lag> lag() {
        List<Lag> result = new ArrayList<>();
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            for (Map.Entry<String, List<String>> entry : Groups.TOPICS_BY_GROUP.entrySet()) {
                Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(entry.getKey())
                        .partitionsToOffsetAndMetadata().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                for (String topic : entry.getValue()) {
                    result.add(new Lag(entry.getKey(), topic, lagOf(admin, topic, committed)));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading consumer lag", e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read consumer lag from Kafka", e);
        }
        result.sort(java.util.Comparator.comparing(Lag::group).thenComparing(Lag::topic));
        return result;
    }

    private long lagOf(AdminClient admin, String topic, Map<TopicPartition, OffsetAndMetadata> committed) throws Exception {
        TopicDescription description = admin.describeTopics(List.of(topic)).allTopicNames().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(topic);
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
        description.partitions().forEach(p -> {
            TopicPartition tp = new TopicPartition(topic, p.partition());
            latest.put(tp, OffsetSpec.latest());
            earliest.put(tp, OffsetSpec.earliest());
        });
        Map<TopicPartition, ListOffsetsResultInfo> ends = admin.listOffsets(latest).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Map<TopicPartition, ListOffsetsResultInfo> starts = admin.listOffsets(earliest).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long lag = 0;
        for (TopicPartition tp : latest.keySet()) {
            OffsetAndMetadata done = committed.get(tp);
            // A group that has not confirmed anything yet is behind by everything still in the partition
            long position = done != null ? done.offset() : starts.get(tp).offset();
            lag += Math.max(0, ends.get(tp).offset() - position);
        }
        return lag;
    }
}
