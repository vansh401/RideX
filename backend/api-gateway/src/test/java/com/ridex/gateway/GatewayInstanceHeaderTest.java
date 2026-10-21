package com.ridex.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class GatewayInstanceHeaderTest extends GatewayTestBase {

    @Test
    void everyResponseNamesTheGatewayInstanceThatHandledIt() {
        Reply ok = get("/api/rides", TestJwts.token(UUID.randomUUID(), "RIDER"));

        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.header("X-Gateway-Instance")).isNotBlank();
    }

    @Test
    void refusedRequestsCarryItToo() {
        Reply refused = get("/api/rides", null);

        assertThat(refused.status()).isEqualTo(401);
        assertThat(refused.header("X-Gateway-Instance")).isNotBlank();
    }
}
