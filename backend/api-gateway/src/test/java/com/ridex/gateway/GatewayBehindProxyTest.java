package com.ridex.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The gateway behind a proxy of our own (the nginx of the Docker setup). The tests connect from 127.0.0.1 / ::1, so those are
 * declared the "trusted proxy" here; in Docker it is the private network range.
 */
@TestPropertySource(properties = {"GATEWAY_TRUSTED_PROXIES=127[.].*|0:0:0:0:0:0:0:1|::1", "GATEWAY_RATE=1", "GATEWAY_BURST=3"})
class GatewayBehindProxyTest extends GatewayTestBase {

    private final String token = TestJwts.token(UUID.randomUUID(), "RIDER");

    @Test
    void theClientAddressTheProxyReportsIsPassedOnToTheServices() {
        Reply reply = get("/api/rides", token, "X-Forwarded-For", "203.0.113.7");

        assertThat(reply.<String>json("$.forwardedFor")).isEqualTo("203.0.113.7");
    }

    @Test
    void whatTheClientWroteFurtherLeftInTheChainIsNotBelieved() {
        // "6.6.6.6" was written by the client; "203.0.113.7" was appended by the trusted proxy
        Reply reply = get("/api/rides", token, "X-Forwarded-For", "6.6.6.6, 203.0.113.7");

        assertThat(reply.<String>json("$.forwardedFor")).isEqualTo("203.0.113.7");
    }

    @Test
    void garbageInTheHeaderFallsBackToTheConnectionAddress() {
        Reply reply = get("/api/rides", token, "X-Forwarded-For", "not an address; DROP TABLE");

        assertThat(reply.<String>json("$.forwardedFor")).doesNotContain("DROP").isNotBlank();
    }

    @Test
    void eachRealClientBehindTheProxyGetsItsOwnRateLimitBucket() {
        for (int i = 0; i < 6; i++) {
            get("/api/rides", token, "X-Forwarded-For", "198.51.100.1");
        }

        assertThat(get("/api/rides", token, "X-Forwarded-For", "198.51.100.1").status()).as("client A used its budget").isEqualTo(429);
        assertThat(get("/api/rides", token, "X-Forwarded-For", "198.51.100.2").status()).as("client B is a different person").isEqualTo(200);
    }
}
