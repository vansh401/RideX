package com.ridex.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class GatewayRoutingAndSecurityTest extends GatewayTestBase {

    private final UUID user = UUID.randomUUID();

    private String rider() {
        return TestJwts.token(user, "RIDER");
    }

    // ------------------------------------------------------------------ 8.1 routing

    @Test
    void eachPathPrefixIsSentToTheServiceThatOwnsIt() {
        String token = rider();

        assertThat(get("/api/notifications/unread-count", token).<String>json("$.service")).isEqualTo("notification");
        assertThat(get("/api/payments/rides/abc", token).<String>json("$.service")).isEqualTo("payment");
        assertThat(get("/api/admin/payments", token).<String>json("$.service")).isEqualTo("payment");
        assertThat(call("POST", "/api/fares/estimate", token).<String>json("$.service")).isEqualTo("pricing");
        assertThat(get("/api/admin/events/lag", token).<String>json("$.service")).isEqualTo("analytics");
        assertThat(get("/api/admin/analytics/ride-status-per-day", token).<String>json("$.service")).isEqualTo("analytics");
    }

    // Phase 14: the new paths go to the service that owns the data (everything not listed goes to the core)
    @Test
    void thePhase14PathsGoToTheServicesThatOwnThem() {
        String token = rider();

        for (String path : new String[] {"/api/wallet", "/api/wallet/transactions", "/api/coupons/WELCOME50/preview", "/api/driver/earnings", "/api/admin/coupons",
                "/api/admin/refunds", "/api/admin/ledger/summary", "/api/admin/incentives/run"}) {
            assertThat(get(path, token).<String>json("$.service")).as(path).isEqualTo("payment");
        }
        assertThat(get("/api/admin/fraud/signals", token).<String>json("$.service")).isEqualTo("analytics");
        assertThat(get("/api/admin/analytics/overview", token).<String>json("$.service")).isEqualTo("analytics");
        assertThat(get("/api/complaints", token).<String>json("$.service")).as("complaints belong to the core").isEqualTo("core");
        assertThat(get("/api/admin/complaints", token).<String>json("$.service")).isEqualTo("core");
    }

    @Test
    void everythingElseGoesToTheCore() {
        String token = rider();

        for (String path : List.of("/api/rides", "/api/rides/current", "/api/riders/me", "/api/admin/users", "/api/admin/outbox",
                "/api/drivers/me", "/api/users/me")) {
            assertThat(get(path, token).<String>json("$.service")).as(path).isEqualTo("core");
        }
    }

    @Test
    void thePathMethodAndQueryReachTheServiceUnchanged() {
        Reply reply = call("POST", "/api/rides/123/accept?x=1&y=two%20words", rider());

        assertThat(reply.<String>json("$.path")).isEqualTo("/api/rides/123/accept");
        assertThat(reply.<String>json("$.method")).isEqualTo("POST");
        assertThat(reply.<String>json("$.query")).isEqualTo("x=1&y=two%20words");
    }

    @Test
    void thePublicKeyEndpointIsReachableWithoutAToken() {
        assertThat(get("/.well-known/jwks.json", null).<String>json("$.service")).isEqualTo("core");
    }

    // ------------------------------------------------------------------ platform-internal paths stay inside

    @Test
    void internalPathsAreNeverReachableFromOutsideNotEvenWithATokenOrAServiceName() {
        int before = FakeServices.totalHits();
        String admin = TestJwts.token(user, "ADMIN");

        assertThat(call("POST", "/internal/quotes", admin).status()).isEqualTo(404);
        assertThat(get("/mock-gateway/charges", admin).status()).isEqualTo(404);
        assertThat(get("/actuator/env", admin).status()).isEqualTo(404);
        assertThat(get("/internal/quotes", null).status()).isEqualTo(404);

        assertThat(FakeServices.totalHits()).as("nothing reached any service").isEqualTo(before);
    }

    @Test
    void theGatewaysOwnHealthIsPublic() {
        assertThat(get("/actuator/health", null).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ 8.2 JWT at the edge

    @Test
    void aRequestWithoutATokenIsTurnedAwayAtTheEdgeAndNeverReachesAService() {
        int before = FakeServices.totalHits();

        Reply reply = get("/api/rides", null);

        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.<String>json("$.error")).isEqualTo("UNAUTHORIZED");
        assertThat(reply.<Object>json("$.fieldErrors")).isNotNull();   // same JSON shape as every RideX service
        assertThat(FakeServices.totalHits()).isEqualTo(before);
    }

    @Test
    void badTokensAreAllRefused() {
        int before = FakeServices.totalHits();

        assertThat(get("/api/rides", "not-a-token").status()).as("garbage").isEqualTo(401);
        assertThat(get("/api/rides", TestJwts.expired(user, "RIDER")).status()).as("expired").isEqualTo(401);
        assertThat(get("/api/rides", TestJwts.forged(user, "RIDER")).status()).as("signed with someone else's key").isEqualTo(401);
        assertThat(get("/api/rides", TestJwts.foreignIssuer(user, "RIDER")).status()).as("issued for another system").isEqualTo(401);
        assertThat(get("/api/rides", TestJwts.withSubject("not-a-uuid", "RIDER")).status()).as("subject is not a user id").isEqualTo(401);
        assertThat(get("/api/rides", TestJwts.withSubject(user.toString(), null)).status()).as("no role").isEqualTo(401);
        assertThat(call("GET", "/api/rides", null, "Authorization", "Basic dXNlcjpwYXNz").status()).as("wrong scheme").isEqualTo(401);

        assertThat(FakeServices.totalHits()).isEqualTo(before);
    }

    @Test
    void aValidTokenPassesAndTheServiceLearnsWhoIsCalling() {
        String token = rider();

        Reply reply = get("/api/rides", token);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.<String>json("$.userId")).isEqualTo(user.toString());
        assertThat(reply.<String>json("$.role")).isEqualTo("RIDER");
    }

    @Test
    void theTokenItselfIsPassedOnUnchangedSoEveryServiceCanCheckItAgain() {
        String token = rider();

        assertThat(get("/api/payments/rides/x", token).<String>json("$.authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    void aClientCannotPretendToBeSomeoneElseWithItsOwnIdentityHeaders() {
        Reply onProtectedPath = get("/api/rides", rider(), "X-User-Id", UUID.randomUUID().toString(), "X-User-Role", "ADMIN");
        Reply onPublicPath = call("POST", "/api/auth/login", null, "X-User-Id", UUID.randomUUID().toString(), "X-User-Role", "ADMIN");

        assertThat(onProtectedPath.<String>json("$.userId")).as("the verified identity replaces the claimed one").isEqualTo(user.toString());
        assertThat(onProtectedPath.<String>json("$.role")).isEqualTo("RIDER");
        assertThat(onPublicPath.<Object>json("$.userId")).as("no token, so no identity at all").isNull();
        assertThat(onPublicPath.<Object>json("$.role")).isNull();
    }

    @Test
    void signingInAndOtherPublicPathsNeedNoToken() {
        assertThat(call("POST", "/api/auth/login", null).status()).isEqualTo(200);
        assertThat(call("POST", "/api/auth/register", null).status()).isEqualTo(200);
        assertThat(get("/api/health", null).status()).isEqualTo(200);
        assertThat(get("/v3/api-docs", null).status()).isEqualTo(200);
    }

    @Test
    void publicDoesNotMeanEveryMethod() {
        // /api/health is public for GET only
        assertThat(call("POST", "/api/health", null).status()).isEqualTo(401);
    }

    @Test
    void webSocketHandshakesPassBecauseTheTokenTravelsInsideTheStompConnectFrame() {
        // The fake service is plain HTTP and answers 200: what matters is that the edge did not demand a header the browser cannot send
        assertThat(get("/ws/info", null).status()).isNotEqualTo(401);
    }

    // ------------------------------------------------------------------ revocation

    @Test
    void aTokenCancelledByIdIsRefusedAtTheEdge() {
        TestJwts.Issued token = TestJwts.valid(user, "RIDER");
        assertThat(get("/api/rides", token.value()).status()).isEqualTo(200);

        redis.opsForValue().set("revoked:jti:" + token.jti(), "1").block();

        assertThat(get("/api/rides", token.value()).status()).isEqualTo(401);
    }

    @Test
    void everyTokenIssuedBeforeAUsersCutOffIsRefusedButNewerOnesWork() {
        TestJwts.Issued old = TestJwts.valid(user, "RIDER");
        redis.opsForValue().set("revoked:user:" + user, String.valueOf(old.issuedAtMillis() + 1)).block();
        TestJwts.Issued newer = TestJwts.issue(user.toString(), "RIDER", "ridex", Instant.now().plusSeconds(5), Instant.now().plusSeconds(905), false);

        assertThat(get("/api/rides", old.value()).status()).as("issued before the cut-off (user was suspended or logged out)").isEqualTo(401);
        assertThat(get("/api/rides", newer.value()).status()).as("issued after it").isEqualTo(200);
    }

    // ------------------------------------------------------------------ 8.3 request id and client address

    @Test
    void everyRequestGetsACorrelationIdSentToTheServiceAndBackToTheCaller() {
        Reply reply = get("/api/rides", rider());

        String id = reply.header("X-Request-Id");
        assertThat(id).isNotBlank();
        assertThat(reply.<String>json("$.requestId")).isEqualTo(id);
    }

    @Test
    void aSensibleIdFromTheCallerIsKeptAndAnUnsafeOneIsReplaced() {
        Reply kept = get("/api/rides", rider(), "X-Request-Id", "my-trace-12345");
        Reply replaced = get("/api/rides", rider(), "X-Request-Id", "bad id with spaces & <script>");

        assertThat(kept.header("X-Request-Id")).isEqualTo("my-trace-12345");
        assertThat(replaced.header("X-Request-Id")).doesNotContain("script").hasSize(36);
    }

    @Test
    void theClientAddressSeenByServicesIsTheRealOneNotWhatTheClientClaims() {
        Reply reply = get("/api/rides", rider(), "X-Forwarded-For", "6.6.6.6");

        assertThat(reply.<String>json("$.forwardedFor")).doesNotContain("6.6.6.6").isNotBlank();
    }

    // ------------------------------------------------------------------ 8.3 CORS

    @Test
    void thePreflightOfAnAllowedOriginIsAnsweredWithoutAToken() {
        Reply reply = call("OPTIONS", "/api/rides", null, "Origin", "http://localhost:5173",
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "authorization,content-type");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.header("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(reply.header("Access-Control-Allow-Headers")).containsIgnoringCase("authorization");
    }

    @Test
    void aPreflightFromAnotherWebsiteIsRefused() {
        Reply reply = call("OPTIONS", "/api/rides", null, "Origin", "http://evil.example",
                "Access-Control-Request-Method", "POST");

        assertThat(reply.status()).isEqualTo(403);
        assertThat(reply.header("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void responsesToAllowedOriginsCarryTheCorsHeaderAndExposeTheRequestId() {
        Reply reply = get("/api/rides", rider(), "Origin", "http://localhost:5173");

        assertThat(reply.header("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(reply.header("Access-Control-Expose-Headers")).containsIgnoringCase("X-Request-Id");
    }
}
