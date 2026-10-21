package com.ridex.platform.realtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * How a service that has NO WebSocket server of its own talks to a user's open browser.
 *
 * The core application owns the WebSocket connections. Every core server listens on the Redis channel
 * "realtime:events" and delivers what it receives to the browsers connected to it (see the core's RedisRealtimeBridge).
 * A service just PUBLISHES to that channel. The format is part of the contract:
 * {"kind":"USER","target":"&lt;user id&gt;","destination":"/queue/notifications","payload":{...}}
 *
 * Fire and forget: if nobody is connected, or Redis is down, the push is lost. That is fine for live hints because
 * anything important is also stored (notifications) or can be fetched again over REST.
 */
public class RealtimePush {

    public static final String CHANNEL = "realtime:events";

    private static final Logger log = LoggerFactory.getLogger(RealtimePush.class);

    private final StringRedisTemplate redis;
    private final JsonMapper mapper;

    public RealtimePush(StringRedisTemplate redis, JsonMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void toUser(UUID userId, String destination, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("kind", "USER");
        envelope.put("target", userId.toString());
        envelope.put("destination", destination);
        envelope.put("payload", payload);
        try {
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(envelope));
        } catch (RuntimeException e) {
            log.warn("Could not push to user {}: {}", userId, e.getClass().getSimpleName());
        }
    }
}
