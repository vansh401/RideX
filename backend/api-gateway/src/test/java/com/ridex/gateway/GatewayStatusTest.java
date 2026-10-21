package com.ridex.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Services are "crashed" (their port is closed) to see how the gateway reports and behaves with a dead service. */
class GatewayStatusTest extends GatewayTestBase {

    private final UUID user = UUID.randomUUID();

    @AfterEach
    void bringEverythingBack() {
        for (String name : List.of("pricing", "analytics")) {
            try {
                FakeServices.restart(name);
            } catch (RuntimeException alreadyRunning) {
                // it was not stopped by this test
            }
        }
    }

    @Test
    void anAdminSeesWhichServicesAreAliveAndWhichAreNot() {
        FakeServices.stop("pricing");
        FakeServices.stop("analytics");

        Reply reply = get("/gateway/services", TestJwts.token(user, "ADMIN"));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.<List<String>>json("$[*].name")).containsExactly("analytics", "core", "notification", "payment", "pricing");
        assertThat(reply.<List<String>>json("$[?(@.name=='core')].status")).containsExactly("UP");
        assertThat(reply.<List<String>>json("$[?(@.name=='payment')].status")).containsExactly("UP");
        assertThat(reply.<List<String>>json("$[?(@.name=='pricing')].status")).containsExactly("DOWN");
        assertThat(reply.<List<String>>json("$[?(@.name=='analytics')].status")).containsExactly("DOWN");
    }

    @Test
    void theServiceMapIsNotShownToRidersOrAnonymousCallers() {
        assertThat(get("/gateway/services", null).status()).isEqualTo(401);
        assertThat(get("/gateway/services", TestJwts.token(user, "RIDER")).status()).isEqualTo(403);
        assertThat(get("/gateway/services", TestJwts.token(user, "DRIVER")).status()).isEqualTo(403);
    }

    @Test
    void aRequestForADeadServiceFailsFastAsAGatewayErrorWhileOtherRoutesKeepWorking() {
        FakeServices.stop("pricing");
        String token = TestJwts.token(user, "RIDER");

        int status = call("POST", "/api/fares/estimate", token).status();

        assertThat(status).as("the gateway cannot reach pricing: 503, not a bare 500").isEqualTo(503);
        assertThat(get("/api/rides", token).status()).as("one dead service does not take the front door down").isEqualTo(200);
    }

    @Test
    void aServiceThatAnswersTooSlowlyGetsA504InsteadOfHoldingTheCallerForEver() {
        long started = System.currentTimeMillis();

        Reply reply = get("/api/rides/slow", TestJwts.token(user, "RIDER"));

        assertThat(reply.status()).isEqualTo(504);
        assertThat(reply.<String>json("$.error")).isEqualTo("GATEWAY_TIMEOUT");
        assertThat(System.currentTimeMillis() - started).as("gave up after about 1 s, not the 3 s the service needed").isLessThan(2500);
    }

    @Test
    void whenTheServiceComesBackTheSameRouteWorksAgainWithoutRestartingTheGateway() {
        String token = TestJwts.token(user, "RIDER");
        FakeServices.stop("pricing");
        assertThat(call("POST", "/api/fares/estimate", token).status()).isGreaterThanOrEqualTo(500);

        FakeServices.restart("pricing");

        assertThat(call("POST", "/api/fares/estimate", token).status()).isEqualTo(200);
    }
}
