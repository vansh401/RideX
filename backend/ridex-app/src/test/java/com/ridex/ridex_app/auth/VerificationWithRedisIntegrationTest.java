package com.ridex.ridex_app.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ridex.ridex_app.redis.RedisKeys;
import com.ridex.ridex_app.redis.TokenStore;
import com.ridex.ridex_app.security.OpaqueTokens;
import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserStatus;

/** Email verification (link and 6-digit code) and password reset, end to end with real Redis. */
class VerificationWithRedisIntegrationTest extends AbstractApiTest {

    @Autowired
    private EmailOtpService otpService;

    @Autowired
    private EmailVerificationService verificationService;

    @Autowired
    private TokenStore store;

    private User pendingUser() {
        int n = Math.abs(UUID.randomUUID().hashCode());
        Response registered = api().post("/api/auth/register", null, json(
                "{'fullName':'Pending Person','email':'pending%d@test.ridex','phone':'+9195%08d','password':'%s','role':'RIDER'}",
                n, n % 100_000_000, PASSWORD));
        expect(201, registered);
        return userRepository.findByEmail("pending" + n + "@test.ridex").orElseThrow();
    }

    private void assertStatus(User user, UserStatus expected) {
        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(expected);
    }

    @Test
    void registeringStoresAVerificationTokenAndCodeInRedisNotInThePostgresTables() {
        User user = pendingUser();

        assertThat(store.get(RedisKeys.verifyUser(user.getId()))).isPresent();
        assertThat(store.get(RedisKeys.otp(user.getId()))).isPresent();
        assertThat(store.ttlSeconds(RedisKeys.verifyUser(user.getId()))).isBetween(14L * 60, 15L * 60);
        assertThat(store.ttlSeconds(RedisKeys.otp(user.getId()))).isBetween(9L * 60, 10L * 60);
    }

    @Test
    void theCodeFromTheEmailVerifiesTheAccountOverTheRealEndpoint() {
        User user = pendingUser();
        String code = otpService.issue(user.getId());   // what the email would have contained

        expect(204, api().post("/api/auth/verify-otp", null,
                json("{'email':'%s','code':'%s'}", user.getEmail(), code)));

        assertStatus(user, UserStatus.ACTIVE);
        assertThat(api().login(user.getEmail(), PASSWORD)).isNotBlank();
        assertThat(api().post("/api/auth/verify-otp", null,
                json("{'email':'%s','code':'%s'}", user.getEmail(), code)).status())
                .as("a code works once").isEqualTo(400);
    }

    @Test
    void aWrongCodeIsRefusedWith400AndFiveWrongGuessesKillTheRealCode() {
        User user = pendingUser();
        String code = otpService.issue(user.getId());
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            assertThat(api().post("/api/auth/verify-otp", null,
                    json("{'email':'%s','code':'%s'}", user.getEmail(), wrong)).status()).isEqualTo(400);
        }
        Response lateButCorrect = api().post("/api/auth/verify-otp", null,
                json("{'email':'%s','code':'%s'}", user.getEmail(), code));

        assertThat(lateButCorrect.status()).isEqualTo(400);
        assertStatus(user, UserStatus.PENDING_VERIFICATION);
    }

    @Test
    void aMalformedCodeIsRejectedBeforeItCountsAsAGuess() {
        Response response = api().post("/api/auth/verify-otp", null,
                "{\"email\":\"a@test.ridex\",\"code\":\"12ab\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.<String>json("$.fieldErrors.code")).contains("6 digits");
    }

    @Test
    void theLinkTokenWorksOnceEvenWhenTwoRequestsArriveAtTheSameInstant() throws Exception {
        User user = pendingUser();
        String raw = OpaqueTokens.generate();
        store.put(RedisKeys.verifyToken(OpaqueTokens.sha256(raw)), user.getId().toString(), Duration.ofMinutes(5));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Callable<Integer> click = () -> {
                    ready.countDown();
                    go.await();
                    return api().post("/api/auth/verify-email", null, json("{'token':'%s'}", raw)).status();
                };
                futures.add(pool.submit(click));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            assertThat(statuses.stream().filter(s -> s == 204)).as("exactly one click succeeds").hasSize(1);
            assertThat(statuses.stream().filter(s -> s == 400)).hasSize(7);
        } finally {
            pool.shutdownNow();
        }
        assertStatus(user, UserStatus.ACTIVE);
    }

    @Test
    void anUnusedLinkStopsWorkingOnceTheAccountIsVerifiedByCode() {
        User user = pendingUser();
        String raw = OpaqueTokens.generate();
        store.put(RedisKeys.verifyToken(OpaqueTokens.sha256(raw)), user.getId().toString(), Duration.ofMinutes(5));
        store.put(RedisKeys.verifyUser(user.getId()), OpaqueTokens.sha256(raw), Duration.ofMinutes(5));
        String code = otpService.issue(user.getId());

        verificationService.verifyWithCode(user.getEmail(), code);

        assertThat(api().post("/api/auth/verify-email", null, json("{'token':'%s'}", raw)).status()).isEqualTo(400);
    }

    @Test
    void aSuspendedAccountIsNotUnsuspendedByAVerificationLink() {
        User user = createUser(Role.RIDER);
        user.suspend();
        userRepository.save(user);
        String raw = OpaqueTokens.generate();
        store.put(RedisKeys.verifyToken(OpaqueTokens.sha256(raw)), user.getId().toString(), Duration.ofMinutes(5));

        expect(204, api().post("/api/auth/verify-email", null, json("{'token':'%s'}", raw)));

        assertStatus(user, UserStatus.SUSPENDED);
    }

    @Test
    void theOldTokenTablesAreGone() {
        Integer tables = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_name in ('email_verification_tokens', 'password_reset_tokens')
                """, Integer.class);

        assertThat(tables).isZero();
    }
}
