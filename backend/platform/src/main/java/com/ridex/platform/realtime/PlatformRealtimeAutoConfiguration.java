package com.ridex.platform.realtime;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.json.JsonMapper;

/** Switched on by a service with ridex.realtime.push.enabled=true (it wants to push to the core's WebSockets). */
@AutoConfiguration
public class PlatformRealtimeAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ridex.realtime.push.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    RealtimePush realtimePush(StringRedisTemplate redis, JsonMapper mapper) {
        return new RealtimePush(redis, mapper);
    }
}
