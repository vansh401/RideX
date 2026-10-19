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
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

/**
 * Brute-force protection and per-address limits, with low limits so the tests are quick.
 * (Rate limiting is switched off for the rest of the test suite; see application-test.properties.)
 */
@TestPropertySource(properties = {
        "ridex.rate-limit.enabled=true",
        "ridex.rate-limit.login-max-failures=3",
        "ridex.rate-limit.login-lockout-minutes=1",
        "ridex.rate-limit.auth-limit=1000",
        "ridex.rate-limit.sensitive-limit=3",
        "ridex.rate-limit.sensitive-window-seconds=60",
        "ridex.rate-limit.api-limit=1000"
})
class RateLimitingIntegrationTest extends AbstractApiTest {

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void forgetEarlierCounters() {
        Set<String> keys = redis.keys("rl:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private Response login(String email, String password) {
        return api().post("/api/auth/login", null, json("{'email':'%s','password':'%s'}", email, password));
    }

    @Test
    void afterTooManyWrongPasswordsLoginAnswers429EvenForTheCorrectPassword() {
        User user = createUser(Role.RIDER);

        for (int i = 0; i < 3; i++) {
            assertThat(login(user.getEmail(), "Wrong-" + i).status()).isEqualTo(401);
        }

        Response locked = login(user.getEmail(), PASSWORD);   // the RIGHT password now
        assertThat(locked.status()).isEqualTo(429);
        assertThat(locked.<String>json("$.message")).contains("Too many failed login attempts");
        assertThat(Long.parseLong(locked.header("Retry-After"))).isBetween(1L, 60L);
    }

    @Test
    void lockingOneEmailDoesNotLockOtherAccounts() {
        User victim = createUser(Role.RIDER);
        User other = createUser(Role.RIDER);
        for (int i = 0; i < 3; i++) {
            login(victim.getEmail(), "Wrong-" + i);
        }

        assertThat(login(victim.getEmail(), PASSWORD).status()).isEqualTo(429);
        assertThat(login(other.getEmail(), PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void aSuccessfulLoginForgetsEarlierMistakes() {
        User user = createUser(Role.RIDER);
        login(user.getEmail(), "Wrong-1");
        login(user.getEmail(), "Wrong-2");

        assertThat(login(user.getEmail(), PASSWORD).status()).isEqualTo(200);

        // the counter started again from zero: two more mistakes are still fine
        assertThat(login(user.getEmail(), "Wrong-3").status()).isEqualTo(401);
        assertThat(login(user.getEmail(), "Wrong-4").status()).isEqualTo(401);
    }

    @Test
    void anEmailThatDoesNotExistIsLockedTooSoGuessingCannotRevealWhichEmailsExist() {
        String email = "nobody-" + System.nanoTime() + "@test.ridex";

        for (int i = 0; i < 3; i++) {
            assertThat(login(email, "Wrong-" + i).status()).isEqualTo(401);
        }

        assertThat(login(email, "Wrong-again").status()).isEqualTo(429);
    }

    @Test
    void endpointsThatSendEmailHaveTheirOwnStricterLimit() {
        for (int i = 0; i < 3; i++) {
            assertThat(api().post("/api/auth/forgot-password", null, json("{'email':'someone%d@test.ridex'}", i)).status())
                    .isEqualTo(202);
        }

        Response blocked = api().post("/api/auth/forgot-password", null, "{\"email\":\"someone9@test.ridex\"}");

        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.header("Retry-After")).isNotNull();
    }

    @Test
    void theLimitedResponseHasTheSameJsonShapeAsEveryOtherError() {
        for (int i = 0; i < 3; i++) {
            api().post("/api/auth/resend-verification", null, "{\"email\":\"x@test.ridex\"}");
        }

        Response blocked = api().post("/api/auth/resend-verification", null, "{\"email\":\"x@test.ridex\"}");

        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.<Integer>json("$.status")).isEqualTo(429);
        assertThat(blocked.<String>json("$.error")).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(blocked.<String>json("$.path")).isEqualTo("/api/auth/resend-verification");
    }
}
