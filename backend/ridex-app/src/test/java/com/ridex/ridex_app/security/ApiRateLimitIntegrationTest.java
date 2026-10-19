package com.ridex.ridex_app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;

/** The general per-address limit on /api/**. */
@TestPropertySource(properties = {
        "ridex.rate-limit.enabled=true",
        "ridex.rate-limit.api-limit=5",
        "ridex.rate-limit.api-window-seconds=2",
        "ridex.rate-limit.auth-limit=1000",
        "ridex.rate-limit.sensitive-limit=1000"
})
class ApiRateLimitIntegrationTest extends AbstractApiTest {

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void forgetEarlierCounters() {
        Set<String> keys = redis.keys("rl:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void callsBeyondTheLimitGet429AndTheWindowThenResets() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(api().get("/api/health", null).status()).isEqualTo(200);
        }

        Response blocked = api().get("/api/health", null);
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.header("Retry-After"))).isBetween(1L, 2L);

        Thread.sleep(2200);   // the 2-second window ends and Redis forgets the counter

        assertThat(api().get("/api/health", null).status()).isEqualTo(200);
    }
}
