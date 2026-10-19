package com.ridex.ridex_app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ridex.ridex_app.redis.RedisKeys;
import com.ridex.ridex_app.redis.TokenStore;
import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

/**
 * Closing the gap from Step 2.5: an access token is a signed, self-contained pass, so on its own it
 * stays valid until it expires. Redis lets us cancel passes that are still within their 15 minutes.
 */
class TokenRevocationIntegrationTest extends AbstractApiTest {

    @Autowired
    private TokenStore store;

    private Response me(String token) {
        return api().get("/api/users/me", token);
    }

    @Test
    void aSuspendedUsersStillValidTokenStopsWorkingImmediately() {
        Person admin = newAdmin();
        Person rider = newRider();
        assertThat(me(rider.token()).status()).isEqualTo(200);

        expect(200, api().post("/api/admin/users/" + rider.user().getId() + "/suspend", admin.token(),
                "{\"reason\":\"fraud\"}"));

        // Before Phase 4 this still answered until the token expired. /api/users/me is a read that the
        // old design only blocked because it re-checks the database; this endpoint is the strictest case:
        assertThat(me(rider.token()).status()).isEqualTo(401);
        assertThat(api().get("/api/rides", rider.token()).status())
                .as("plain reads of other endpoints are cut off too")
                .isEqualTo(401);
    }

    @Test
    void aTokenIssuedAfterReinstatementWorksEvenInTheSameSecond() {
        Person admin = newAdmin();
        Person rider = newRider();
        expect(200, api().post("/api/admin/users/" + rider.user().getId() + "/suspend", admin.token(),
                "{\"reason\":\"test\"}"));
        expect(200, api().post("/api/admin/users/" + rider.user().getId() + "/reinstate", admin.token(), null));

        String fresh = tokenOf(rider.user());

        assertThat(me(fresh).status()).isEqualTo(200);
        assertThat(me(rider.token()).status()).as("the old token stays cancelled").isEqualTo(401);
    }

    @Test
    void otherUsersAreNotAffectedBySomeoneElsesRevocation() {
        Person admin = newAdmin();
        Person victim = newRider();
        Person bystander = newRider();

        expect(200, api().post("/api/admin/users/" + victim.user().getId() + "/suspend", admin.token(),
                "{\"reason\":\"test\"}"));

        assertThat(me(bystander.token()).status()).isEqualTo(200);
    }

    @Test
    void logoutCancelsTheAccessTokenThatMadeTheCall() {
        User user = createUser(Role.RIDER);
        Response login = api().post("/api/auth/login", null,
                json("{'email':'%s','password':'%s'}", user.getEmail(), PASSWORD));
        String access = login.json("$.accessToken");
        String refresh = login.json("$.refreshToken");
        assertThat(me(access).status()).isEqualTo(200);

        expect(204, api().post("/api/auth/logout", access, json("{'refreshToken':'%s'}", refresh)));

        assertThat(me(access).status()).as("a logged-out token must not keep working").isEqualTo(401);
        assertThat(api().post("/api/auth/refresh", null, json("{'refreshToken':'%s'}", refresh)).status())
                .as("and neither may the refresh token").isEqualTo(401);
    }

    @Test
    void loggingOutOneSessionDoesNotCancelAnotherDevicesToken() {
        User user = createUser(Role.RIDER);
        String phone = tokenOf(user);
        Response laptop = api().post("/api/auth/login", null,
                json("{'email':'%s','password':'%s'}", user.getEmail(), PASSWORD));

        expect(204, api().post("/api/auth/logout", laptop.json("$.accessToken"),
                json("{'refreshToken':'%s'}", laptop.<String>json("$.refreshToken"))));

        assertThat(me(phone).status()).isEqualTo(200);
    }

    @Test
    void resettingAPasswordCancelsTheTokensOfTheOldPassword() {
        User user = createUser(Role.RIDER);
        String old = tokenOf(user);
        // Put a reset token where the emailed link would have created one (the email itself is not testable here)
        String rawToken = OpaqueTokens.generate();
        store.put(RedisKeys.resetToken(OpaqueTokens.sha256(rawToken)), user.getId().toString(), Duration.ofMinutes(5));

        expect(204, api().post("/api/auth/reset-password", null,
                json("{'token':'%s','newPassword':'BrandNew123'}", rawToken)));

        assertThat(me(old).status()).as("whoever held the old session is logged out now").isEqualTo(401);
        assertThat(api().login(user.getEmail(), "BrandNew123")).isNotBlank();
    }

    @Test
    void revocationEntriesExpireByThemselves() {
        Person admin = newAdmin();
        Person rider = newRider();
        expect(200, api().post("/api/admin/users/" + rider.user().getId() + "/suspend", admin.token(),
                "{\"reason\":\"test\"}"));

        // They only need to outlive the 15-minute access tokens they cancel: no cleanup job, no growth
        assertThat(store.ttlSeconds(RedisKeys.revokedUser(rider.user().getId())))
                .isBetween(14L * 60, 17L * 60);
    }
}
