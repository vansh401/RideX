package com.ridex.ridex_app.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import com.ridex.ridex_app.admin.AdminStats;
import com.ridex.ridex_app.rider.RiderProfileResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spring's cache abstraction on top of Redis. A method marked {@code @Cacheable("name")} runs only
 * on a cache miss; the result is stored in Redis under that name until its TTL passes or a
 * {@code @CacheEvict} removes it.
 *
 * Every cache has a TTL. That is the safety net for the hardest problem in caching, invalidation:
 * if some code path forgets to evict, the stale value still disappears on its own.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String ADMIN_STATS = "adminStats";
    public static final String RIDER_PROFILE = "riderProfile";

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                     JsonMapper jsonMapper,
                                     @Value("${ridex.cache.enabled:true}") boolean enabled,
                                     @Value("${ridex.cache.admin-stats-ttl-seconds}") long adminStatsTtlSeconds,
                                     @Value("${ridex.cache.rider-profile-ttl-minutes}") long riderProfileTtlMinutes) {
        if (!enabled) {
            return new NoOpCacheManager();   // every call runs the real method
        }
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base(Duration.ofMinutes(5)))
                .withInitialCacheConfigurations(Map.of(
                        ADMIN_STATS, typed(jsonMapper, AdminStats.class, Duration.ofSeconds(adminStatsTtlSeconds)),
                        RIDER_PROFILE, typed(jsonMapper, RiderProfileResponse.class, Duration.ofMinutes(riderProfileTtlMinutes))))
                // Writes and evictions made inside a database transaction are applied only AFTER it
                // commits. Otherwise a reader could re-cache the OLD data between our evict and our commit.
                .transactionAware()
                .build();
    }

    private static RedisCacheConfiguration base(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .prefixCacheNameWith("ridex:cache:")
                .disableCachingNullValues();
    }

    /**
     * Values are stored as plain JSON of one known class (no class names inside the JSON), which is
     * readable in redis-cli and cannot be tricked into instantiating other classes.
     */
    private static <T> RedisCacheConfiguration typed(JsonMapper mapper, Class<T> type, Duration ttl) {
        RedisSerializer<T> serializer = new JacksonJsonRedisSerializer<>(mapper, type);
        return base(ttl).serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
    }
}
