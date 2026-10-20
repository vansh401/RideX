package com.ridex.ridex_app.realtime;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Connects every application server to every browser, however many servers there are.
 *
 * The problem: Spring's built-in STOMP broker lives in the memory of ONE server. A browser is connected
 * to just one of them, so a message produced on server A (say a driver accepted a ride) would never reach
 * a rider connected to server B.
 *
 * The fix is Redis PUBLISH/SUBSCRIBE. Whoever wants to send a message publishes it to ONE Redis channel;
 * EVERY server is subscribed to that channel, receives it, and hands it to the browsers connected to it.
 * Servers that have no such browser simply ignore it. So producers never need to know which server a
 * user is on. Same code path with one server or fifty.
 *
 * Pub/sub is fire and forget: Redis does not store the message, and a server that is down at that moment
 * misses it. That fits real-time UI hints (REST and stored notifications are the safety net).
 */
@Component
public class RedisRealtimeBridge implements RealtimePublisher, MessageListener {

    public static final String CHANNEL = "realtime:events";

    private static final Logger log = LoggerFactory.getLogger(RedisRealtimeBridge.class);

    /** What travels through Redis. The payload is JSON so it survives the trip unchanged. */
    record Envelope(String kind, String target, String destination, JsonNode payload) {
    }

    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messaging;
    private final JsonMapper mapper;

    public RedisRealtimeBridge(StringRedisTemplate redis, @Lazy SimpMessagingTemplate messaging, JsonMapper mapper) {
        this.redis = redis;
        this.messaging = messaging;
        this.mapper = mapper;
    }

    @Override
    public void toUser(UUID userId, String destination, Object payload) {
        publish(new Envelope("USER", userId.toString(), destination, mapper.valueToTree(payload)));
    }

    @Override
    public void toTopic(String destination, Object payload) {
        publish(new Envelope("TOPIC", null, destination, mapper.valueToTree(payload)));
    }

    private void publish(Envelope envelope) {
        try {
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(envelope));
        } catch (RuntimeException e) {
            // Redis is unreachable. Deliver to the browsers on THIS server at least; others miss it.
            log.warn("Realtime fan-out through Redis failed ({}), delivering locally only", e.getClass().getSimpleName());
            deliverLocally(envelope);
        }
    }

    /** Called by Redis for every message on the channel, on every server. */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            deliverLocally(mapper.readValue(message.getBody(), Envelope.class));
        } catch (RuntimeException e) {
            log.warn("Ignoring a malformed realtime message: {}", e.getClass().getSimpleName());
        }
    }

    private void deliverLocally(Envelope envelope) {
        if ("USER".equals(envelope.kind())) {
            messaging.convertAndSendToUser(envelope.target(), envelope.destination(), envelope.payload());
        } else {
            messaging.convertAndSend(envelope.destination(), envelope.payload());
        }
    }
}
