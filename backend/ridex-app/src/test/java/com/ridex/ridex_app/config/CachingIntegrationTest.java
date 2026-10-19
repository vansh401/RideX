package com.ridex.ridex_app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ridex.ridex_app.rider.RiderProfileService;
import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;

/** What caching buys, and the invalidation problem it creates, both demonstrated on real Redis. */
@TestPropertySource(properties = {
        "ridex.cache.enabled=true",
        "ridex.cache.admin-stats-ttl-seconds=2"
})
class CachingIntegrationTest extends AbstractApiTest {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RiderProfileService riderProfileService;

    @BeforeEach
    void emptyCaches() {
        Set<String> keys = redis.keys("ridex:cache:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private Person riderWithProfile() {
        Person rider = newRider();
        riderProfileService.createFor(rider.user());
        return rider;
    }

    private static String place(String label) {
        return "{\"label\":\"" + label + "\",\"address\":\"1 Test Road\",\"latitude\":12.971599,\"longitude\":77.594566}";
    }

    // ------------------------------------------------------------------ rider profile: cache + evict

    @Test
    void theSecondReadComesFromRedisAsPlainJson() {
        Person rider = riderWithProfile();

        api().get("/api/riders/me", rider.token());

        String key = "ridex:cache:riderProfile::" + rider.user().getId();
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.opsForValue().get(key))
                .as("readable JSON with no class names inside")
                .contains("savedPlaces")
                .doesNotContain("@class");
        assertThat(redis.getExpire(key)).isBetween(1L, 10L * 60);   // every cache entry has a TTL
    }

    @Test
    void changingTheProfileEvictsTheCachedCopySoTheNextReadIsFresh() {
        Person rider = riderWithProfile();
        assertThat(api().get("/api/riders/me", rider.token()).<java.util.List<Object>>json("$.savedPlaces")).isEmpty();

        expect(201, api().post("/api/riders/me/places", rider.token(), place("Home")));

        Response after = api().get("/api/riders/me", rider.token());
        assertThat(after.<java.util.List<Object>>json("$.savedPlaces")).hasSize(1);
        assertThat(after.<String>json("$.savedPlaces[0].label")).isEqualTo("Home");
    }

    @Test
    void oneRidersCacheEntryIsNeverServedToAnotherRider() {
        Person first = riderWithProfile();
        Person second = riderWithProfile();
        expect(201, api().post("/api/riders/me/places", first.token(), place("Only first")));

        api().get("/api/riders/me", first.token());   // fills first's entry

        assertThat(api().get("/api/riders/me", second.token()).<java.util.List<Object>>json("$.savedPlaces")).isEmpty();
    }

    @Test
    void writingAroundTheApplicationLeavesAStaleCopyUntilItIsEvicted() {
        // This is the classic cache-invalidation bug, shown on purpose. Rule: ALL writes must go through the
        // code that evicts. Anything that edits the database directly (a script, a manual fix) is invisible
        // to the cache, so readers see old data until the entry expires.
        Person rider = riderWithProfile();
        api().get("/api/riders/me", rider.token());   // cached: no emergency contact yet

        jdbc.update("update rider_profiles set emergency_contact_name = 'Changed in SQL', emergency_contact_phone = '+919800000000' "
                + "where user_id = ?", rider.user().getId());

        assertThat(api().get("/api/riders/me", rider.token()).<Object>json("$.emergencyContact"))
                .as("stale: the cache has not heard about the SQL update")
                .isNull();

        cacheManager.getCache(CacheConfig.RIDER_PROFILE).evict(rider.user().getId());

        assertThat(api().get("/api/riders/me", rider.token()).<String>json("$.emergencyContact.name"))
                .isEqualTo("Changed in SQL");
    }

    // ------------------------------------------------------------------ admin stats: short TTL only

    @Test
    void adminStatsAreCachedForTheTtlAndThenRecomputed() throws Exception {
        Person admin = newAdmin();
        int before = api().get("/api/admin/stats", admin.token()).<Integer>json("$.usersByRole.RIDER");

        createUser(com.ridex.ridex_app.user.Role.RIDER);   // a new rider appears in the database

        assertThat(api().get("/api/admin/stats", admin.token()).<Integer>json("$.usersByRole.RIDER"))
                .as("within the TTL the dashboard shows the cached number")
                .isEqualTo(before);

        Thread.sleep(2500);   // the 2-second TTL passes; Redis drops the entry by itself

        assertThat(api().get("/api/admin/stats", admin.token()).<Integer>json("$.usersByRole.RIDER"))
                .isEqualTo(before + 1);
    }
}
