package com.ridex.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Tiny buckets and no refill worth speaking of, so the limit is easy to hit and to see. */
@TestPropertySource(properties = {"GATEWAY_RATE=1", "GATEWAY_BURST=5", "GATEWAY_AUTH_RATE=1", "GATEWAY_AUTH_BURST=3"})
class GatewayRateLimitTest extends GatewayTestBase {

    private final String token = TestJwts.token(UUID.randomUUID(), "RIDER");

    @Test
    void beyondTheBurstTheGatewayAnswers429AndTheServiceIsNotTroubled() {
        int allowed = 0;
        int limited = 0;
        int hitsBefore = FakeServices.hits("core");
        for (int i = 0; i < 12; i++) {
            Reply reply = get("/api/rides", token);
            if (reply.status() == 200) {
                allowed++;
            } else if (reply.status() == 429) {
                limited++;
            }
        }

        assertThat(allowed).as("about the burst of 5 (plus at most a refilled token or two)").isBetween(5, 7);
        assertThat(limited).isEqualTo(12 - allowed);
        assertThat(FakeServices.hits("core") - hitsBefore).as("limited requests never reached the service").isEqualTo(allowed);
    }

    @Test
    void theRemainingBudgetIsShownToTheCaller() {
        Reply reply = get("/api/rides", token);

        assertThat(reply.header("X-RateLimit-Remaining")).isNotNull();
        assertThat(reply.header("X-RateLimit-Burst-Capacity")).isEqualTo("5");
    }

    @Test
    void signingInHasAStricterLimitThanTheRestOfTheApi() {
        int loginAllowed = 0;
        for (int i = 0; i < 8; i++) {
            if (call("POST", "/api/auth/login", null).status() == 200) {
                loginAllowed++;
            }
        }

        assertThat(loginAllowed).as("burst of 3").isBetween(3, 5);
    }

    @Test
    void useUpTheLoginBudgetAndTheRestOfTheApiStillWorks() {
        for (int i = 0; i < 8; i++) {
            call("POST", "/api/auth/login", null);
        }

        assertThat(get("/api/rides", token).status()).as("separate bucket per route").isEqualTo(200);
    }

    @Test
    void theLimitIsPerRoute() {
        for (int i = 0; i < 8; i++) {
            get("/api/notifications", token);
        }

        assertThat(get("/api/notifications", token).status()).isEqualTo(429);
        assertThat(get("/api/fares/estimate", token).status()).as("pricing has its own bucket").isEqualTo(200);
    }

    @Test
    void theRateLimitedAnswerIsNotAlsoCountedAsAnAuthenticationFailure() {
        for (int i = 0; i < 10; i++) {
            get("/api/rides", token);
        }

        Reply limited = get("/api/rides", token);

        assertThat(limited.status()).isEqualTo(429);
    }
}
