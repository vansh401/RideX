package com.ridex.ridex_app.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import com.ridex.ridex_app.driver.VehicleCategory;
import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.StompTestClient;

/**
 * Who may connect, subscribe and send over the WebSocket. A WebSocket skips the normal HTTP filter chain after
 * the first request, so every one of these rules is enforced by StompSecurityInterceptor and is tested here.
 */
class WebSocketSecurityIntegrationTest extends AbstractApiTest {

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private com.ridex.ridex_app.jwt.JwtKeys keys;

    @Autowired
    private StringRedisTemplate redis;

    @Value("${ridex.jwt.issuer}")
    private String issuer;

    private final List<StompTestClient> open = new java.util.ArrayList<>();

    private StompTestClient connect(String token) {
        StompTestClient client = StompTestClient.connect(serverPort(), token);
        open.add(client);
        return client;
    }

    @AfterEach
    void closeAll() {
        open.forEach(StompTestClient::close);
    }

    // ------------------------------------------------------------------ connecting

    @Test
    void aValidTokenConnects() {
        Person rider = newRider();

        assertThat(connect(rider.token()).awaitConnected(5)).isTrue();
    }

    @Test
    void noTokenNoConnection() {
        StompTestClient anonymous = connect(null);

        assertThat(anonymous.awaitConnected(3)).isFalse();
        assertThat(anonymous.awaitRefused(5)).isTrue();
    }

    @Test
    void aGarbageTokenIsRefused() {
        StompTestClient forged = connect("not-a-real-token");

        assertThat(forged.awaitConnected(3)).isFalse();
        assertThat(forged.awaitRefused(5)).isTrue();
    }

    @Test
    void anExpiredTokenIsRefused() {
        Person rider = newRider();
        Instant past = Instant.now().minusSeconds(3600);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(rider.user().getId().toString())
                .issuedAt(past).expiresAt(past.plusSeconds(60)).id("expired-token")
                .claim("role", "RIDER").build();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.keyId()).build(), claims)).getTokenValue();

        StompTestClient client = connect(expired);

        assertThat(client.awaitConnected(3)).isFalse();
        assertThat(client.awaitRefused(5)).isTrue();
    }

    @Test
    void aSuspendedUsersTokenCannotOpenASocket() {
        Person admin = newAdmin();
        Person rider = newRider();
        expect(200, api().post("/api/admin/users/" + rider.user().getId() + "/suspend", admin.token(), "{\"reason\":\"test\"}"));

        StompTestClient client = connect(rider.token());

        assertThat(client.awaitConnected(3)).as("revoked tokens are refused over WebSocket too").isFalse();
        assertThat(client.awaitRefused(5)).isTrue();
    }

    // ------------------------------------------------------------------ subscribing

    @Test
    void aUserMayListenToTheirOwnQueues() {
        Person rider = newRider();
        StompTestClient client = connect(rider.token());
        assertThat(client.awaitConnected(5)).isTrue();

        client.subscribe("/user/queue/ride-updates");
        client.subscribe("/user/queue/notifications");

        assertThat(client.awaitRefused(1)).as("none of these subscriptions is refused").isFalse();
    }

    @Test
    void aStrangerCannotListenToSomeoneElsesRide() {
        Person admin = newAdmin();
        double lat = 30.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        Person rider = newRider();
        Person stranger = newRider();
        String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng))).id();

        StompTestClient client = connect(stranger.token());
        assertThat(client.awaitConnected(5)).isTrue();
        client.subscribe("/topic/rides/" + rideId + "/location");

        assertThat(client.awaitRefused(5)).as("subscribing to another rider's ride is refused").isTrue();

        api().post("/api/rides/" + rideId + "/cancel", rider.token(), null);
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }

    @Test
    void theRiderTheDriverAndAnAdminMayListenToARidesLocation() {
        Person admin = newAdmin();
        double lat = 31.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat + 0.009, lng);
        Person rider = newRider();
        String rideId = expect(201, api().post("/api/rides", rider.token(), rideBody("CAB", lat, lng))).id();

        for (String token : List.of(rider.token(), driver.token(), admin.token())) {
            StompTestClient client = connect(token);
            assertThat(client.awaitConnected(5)).isTrue();
            client.subscribe("/topic/rides/" + rideId + "/location");
            assertThat(client.awaitRefused(1)).isFalse();
        }

        api().post("/api/rides/" + rideId + "/cancel", rider.token(), null);
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }

    @Test
    void anyOtherDestinationIsRefused() {
        Person rider = newRider();
        StompTestClient client = connect(rider.token());
        assertThat(client.awaitConnected(5)).isTrue();

        client.subscribe("/topic/anything");

        assertThat(client.awaitRefused(5)).isTrue();
    }

    // ------------------------------------------------------------------ sending

    @Test
    void aRiderCannotSendDriverLocations() {
        Person rider = newRider();
        StompTestClient client = connect(rider.token());
        assertThat(client.awaitConnected(5)).isTrue();

        client.send("/app/driver/location", "{\"latitude\":12.97,\"longitude\":77.59}");

        assertThat(client.awaitRefused(5)).isTrue();
    }

    @Test
    void nobodyMaySendToOtherDestinations() {
        Person admin = newAdmin();
        double lat = 32.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat, lng);
        StompTestClient client = connect(driver.token());
        assertThat(client.awaitConnected(5)).isTrue();

        client.send("/topic/rides/anything/location", "{}");

        assertThat(client.awaitRefused(5)).as("a driver cannot publish to a topic directly").isTrue();
        api().post("/api/drivers/me/availability/offline", driver.token(), null);
    }

    // ------------------------------------------------------------------ a token revoked while connected

    @Test
    void aSocketStopsWorkingAfterItsUserIsSuspended() {
        Person admin = newAdmin();
        double lat = 33.0, lng = 70.0;
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, lat, lng);
        StompTestClient client = connect(driver.token());
        assertThat(client.awaitConnected(5)).isTrue();
        client.subscribe("/user/queue/errors");
        assertThat(client.awaitRefused(1)).isFalse();

        expect(200, api().post("/api/admin/users/" + driver.user().getId() + "/suspend", admin.token(), "{\"reason\":\"test\"}"));
        client.send("/app/driver/location", "{\"latitude\":12.97,\"longitude\":77.59}");

        assertThat(client.awaitRefused(5)).as("the very next frame of a revoked token is refused").isTrue();
    }

    // ------------------------------------------------------------------ fan-out through Redis

    @Test
    void aMessageFromAnotherServerReachesTheBrowsersConnectedHere() {
        Person rider = newRider();
        StompTestClient client = connect(rider.token());
        assertThat(client.awaitConnected(5)).isTrue();
        client.subscribe("/user/queue/ride-updates");

        // What a DIFFERENT application server does: publish to the shared Redis channel
        String envelope = "{\"kind\":\"USER\",\"target\":\"" + rider.user().getId()
                + "\",\"destination\":\"/queue/ride-updates\",\"payload\":{\"rideId\":\"from-server-b\",\"status\":\"DRIVER_ARRIVED\"}}";
        redis.convertAndSend(RedisRealtimeBridge.CHANNEL, envelope);

        String message = client.next("/user/queue/ride-updates", 5);
        assertThat(message).isNotNull().contains("from-server-b").contains("DRIVER_ARRIVED");
    }

    @Test
    void aMessageAddressedToAnotherUserIsNotDeliveredToMe() {
        Person me = newRider();
        Person someoneElse = newRider();
        StompTestClient client = connect(me.token());
        assertThat(client.awaitConnected(5)).isTrue();
        client.subscribe("/user/queue/ride-updates");

        redis.convertAndSend(RedisRealtimeBridge.CHANNEL, "{\"kind\":\"USER\",\"target\":\"" + someoneElse.user().getId()
                + "\",\"destination\":\"/queue/ride-updates\",\"payload\":{\"secret\":\"not for me\"}}");

        assertThat(client.next("/user/queue/ride-updates", 2)).isNull();
    }

    @Test
    void aMalformedMessageOnTheChannelIsIgnored() {
        Person rider = newRider();
        StompTestClient client = connect(rider.token());
        assertThat(client.awaitConnected(5)).isTrue();
        client.subscribe("/user/queue/ride-updates");

        redis.convertAndSend(RedisRealtimeBridge.CHANNEL, "this is not json");
        redis.convertAndSend(RedisRealtimeBridge.CHANNEL, "{\"kind\":\"USER\",\"target\":\"" + rider.user().getId()
                + "\",\"destination\":\"/queue/ride-updates\",\"payload\":{\"after\":\"garbage\"}}");

        assertThat(client.next("/user/queue/ride-updates", 5)).as("the listener survived the garbage").contains("garbage");
    }

}
