package com.ridex.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ridex.platform.testing.TestTokens;

/** The REST side: tokens issued by ANOTHER service are verified here with only the public key. */
class NotificationApiIntegrationTest extends NotificationTestBase {

    @Autowired
    private StringRedisTemplate redis;

    private Http http;

    @BeforeEach
    void client() {
        http = new Http(port);
    }

    private UUID userWithNotifications(int how) {
        UUID user = id();
        for (int i = 0; i < how; i++) {
            jdbc.update("insert into notifications (id, user_id, type, title, body, created_at) values (?, ?, 'RIDE_ACCEPTED', ?, 'body', ?)",
                    id(), user, "Title " + i, java.sql.Timestamp.from(Instant.now().plusSeconds(i)));
        }
        return user;
    }

    // ------------------------------------------------------------------ authentication with a foreign token

    @Test
    void noTokenIsRefused() {
        assertThat(http.get("/api/notifications", null).status()).isEqualTo(401);
        assertThat(http.get("/api/notifications/unread-count", null).status()).isEqualTo(401);
    }

    @Test
    void aTokenThatIsGarbageExpiredForgedOrFromAnotherIssuerIsRefused() {
        UUID user = id();
        assertThat(http.get("/api/notifications", "not-a-token").status()).isEqualTo(401);
        assertThat(http.get("/api/notifications", TestTokens.expiredToken(user, "RIDER")).status()).isEqualTo(401);
        assertThat(http.get("/api/notifications", TestTokens.forgedToken(user, "RIDER")).status())
                .as("signed with a key that is not the issuer's: only the real private key can make a valid token").isEqualTo(401);
        assertThat(http.get("/api/notifications", TestTokens.tokenFromAnotherIssuer(user, "RIDER")).status()).isEqualTo(401);
    }

    @Test
    void aValidTokenFromTheCoreIsAccepted() {
        UUID user = userWithNotifications(1);

        assertThat(http.get("/api/notifications", TestTokens.token(user, "RIDER")).status()).isEqualTo(200);
        assertThat(http.get("/api/notifications", TestTokens.token(user, "DRIVER")).status()).isEqualTo(200);
    }

    @Test
    void aRevokedTokenIsRefusedBecauseTheRevocationListIsSharedThroughRedis() {
        UUID user = userWithNotifications(1);
        String tokenId = UUID.randomUUID().toString();
        String token = TestTokens.token(user, "RIDER", tokenId, Instant.now(), Instant.now().plusSeconds(900), "ridex");
        assertThat(http.get("/api/notifications", token).status()).isEqualTo(200);

        // What the core writes when a user logs out (one token)...
        redis.opsForValue().set("revoked:jti:" + tokenId, "1", java.time.Duration.ofMinutes(15));

        assertThat(http.get("/api/notifications", token).status()).isEqualTo(401);
    }

    @Test
    void everyTokenOfASuspendedUserIsRefusedButTokensIssuedLaterWork() throws Exception {
        UUID user = userWithNotifications(1);
        String before = TestTokens.token(user, "RIDER");
        Thread.sleep(5);

        // What the core writes when it suspends a user: "tokens issued before now are void"
        redis.opsForValue().set("revoked:user:" + user, String.valueOf(System.currentTimeMillis()), java.time.Duration.ofMinutes(16));
        Thread.sleep(5);
        String after = TestTokens.token(user, "RIDER");

        assertThat(http.get("/api/notifications", before).status()).isEqualTo(401);
        assertThat(http.get("/api/notifications", after).status()).isEqualTo(200);
    }

    @Test
    void errorsHaveTheSameJsonShapeAsTheCoreApplication() {
        Http.Response response = http.get("/api/notifications", null);

        assertThat(response.<Integer>json("$.status")).isEqualTo(401);
        assertThat(response.<String>json("$.error")).isEqualTo("UNAUTHORIZED");
        assertThat(response.<String>json("$.path")).isEqualTo("/api/notifications");
    }

    @Test
    void theHealthEndpointIsPublicSoOrchestratorsCanProbeIt() {
        assertThat(http.get("/actuator/health", null).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ the API itself

    @Test
    void aUserSeesOnlyTheirOwnNotificationsNewestFirstAndPaged() {
        UUID user = userWithNotifications(5);
        userWithNotifications(3);   // somebody else
        String token = TestTokens.token(user, "RIDER");

        Http.Response page = http.get("/api/notifications?size=2", token);

        assertThat(page.<Integer>json("$.totalElements")).isEqualTo(5);
        assertThat(page.<Integer>json("$.totalPages")).isEqualTo(3);
        assertThat(page.<List<String>>json("$.content[*].title")).containsExactly("Title 4", "Title 3");
        assertThat(http.get("/api/notifications?size=1000", token).<Integer>json("$.size")).as("page size is clamped").isEqualTo(100);
    }

    @Test
    void unreadCountsAndMarkingAsReadWork() {
        UUID user = userWithNotifications(3);
        String token = TestTokens.token(user, "RIDER");
        String first = http.get("/api/notifications", token).json("$.content[0].id");

        assertThat(http.get("/api/notifications/unread-count", token).<Integer>json("$.count")).isEqualTo(3);
        assertThat(http.post("/api/notifications/" + first + "/read", token, null).status()).isEqualTo(204);
        assertThat(http.get("/api/notifications/unread-count", token).<Integer>json("$.count")).isEqualTo(2);
        assertThat(http.post("/api/notifications/read-all", token, null).<Integer>json("$.updated")).isEqualTo(2);
        assertThat(http.get("/api/notifications/unread-count", token).<Integer>json("$.count")).isZero();
    }

    @Test
    void nobodyCanMarkSomeoneElsesNotificationAsRead() {
        UUID owner = userWithNotifications(1);
        UUID intruder = id();
        String id = http.get("/api/notifications", TestTokens.token(owner, "RIDER")).json("$.content[0].id");

        assertThat(http.post("/api/notifications/" + id + "/read", TestTokens.token(intruder, "RIDER"), null).status())
                .as("looks like it does not exist").isEqualTo(404);
        assertThat(http.get("/api/notifications/unread-count", TestTokens.token(owner, "RIDER")).<Integer>json("$.count")).isEqualTo(1);
    }

    @Test
    void theServiceHasItsOwnDatabaseWithNoTablesOfOtherServices() {
        assertThat(count("select count(*) from flyway_schema_history where script like 'V1__create_notifications%'")).isEqualTo(1);
        assertThat(count("select count(*) from information_schema.tables where table_name in ('users', 'rides', 'driver_profiles')"))
                .as("no tables of other services exist in this database").isZero();
    }
}
