package com.ridex.ridex_app.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ridex.ridex_app.support.AbstractIntegrationTest;

/**
 * The Redis building blocks, tested against a real Redis. Each test is about ONE property that the
 * rest of the application relies on: single-use tokens, expiry, mutual exclusion, safe unlocking.
 */
class RedisPrimitivesIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TokenStore store;

    @Autowired
    private DistributedLock locks;

    private static String unique(String prefix) {
        return "test:" + prefix + ":" + UUID.randomUUID();
    }

    private static <T> List<T> inParallel(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ expiry

    @Test
    void aValueDisappearsByItselfAfterItsTtl() throws Exception {
        String key = unique("ttl");
        store.put(key, "secret", Duration.ofMillis(400));
        assertThat(store.get(key)).contains("secret");
        assertThat(store.ttlSeconds(key)).isBetween(0L, 1L);

        Thread.sleep(700);

        assertThat(store.get(key)).as("Redis removed the key without any cleanup job").isEmpty();
    }

    @Test
    void takeReadsAndDeletesInOneStep() {
        String key = unique("take");
        store.put(key, "once", Duration.ofMinutes(1));

        assertThat(store.take(key)).contains("once");
        assertThat(store.take(key)).isEmpty();
        assertThat(store.get(key)).isEmpty();
    }

    @Test
    void ofManySimultaneousUsesOfASingleUseTokenExactlyOneWins() throws Exception {
        String key = unique("race");
        store.put(key, "user-1", Duration.ofMinutes(1));

        List<Optional<String>> results = inParallel(30, () -> store.take(key));

        // This is the whole point of GETDEL: no lock, no race, exactly one winner
        assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
    }

    // ------------------------------------------------------------------ counters

    @Test
    void aCounterCountsAndItsWindowStartsAtTheFirstHitOnly() throws Exception {
        String key = unique("counter");

        assertThat(store.increment(key, Duration.ofMillis(900))).isEqualTo(1);
        Thread.sleep(450);
        assertThat(store.increment(key, Duration.ofMillis(900))).isEqualTo(2);   // does NOT extend the window
        Thread.sleep(600);

        assertThat(store.increment(key, Duration.ofMillis(900)))
                .as("the window ended 900 ms after the FIRST hit, so counting restarted")
                .isEqualTo(1);
    }

    @Test
    void concurrentIncrementsNeverLoseACount() throws Exception {
        String key = unique("concurrent-counter");

        inParallel(40, () -> store.increment(key, Duration.ofMinutes(1)));

        assertThat(store.get(key)).contains("40");
    }

    // ------------------------------------------------------------------ distributed lock

    @Test
    void onlyOneOfManyCallersGetsTheLock() throws Exception {
        String key = unique("lock");

        List<Optional<DistributedLock.Handle>> results =
                inParallel(25, () -> locks.tryLock(key, Duration.ofSeconds(10)));

        assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
    }

    @Test
    void releasingTheLockLetsSomeoneElseTakeIt() {
        String key = unique("release");
        DistributedLock.Handle first = locks.tryLock(key, Duration.ofSeconds(10)).orElseThrow();
        assertThat(locks.tryLock(key, Duration.ofSeconds(10))).isEmpty();

        assertThat(first.release()).isTrue();

        assertThat(locks.tryLock(key, Duration.ofSeconds(10))).isPresent();
    }

    @Test
    void aLockExpiresOnItsOwnSoACrashedHolderCannotBlockForever() throws Exception {
        String key = unique("lease");
        locks.tryLock(key, Duration.ofMillis(300)).orElseThrow();   // the holder "crashes": never releases

        Thread.sleep(600);

        assertThat(locks.tryLock(key, Duration.ofSeconds(10))).isPresent();
    }

    @Test
    void anOwnerWhoseLeaseExpiredCannotReleaseTheNewOwnersLock() throws Exception {
        String key = unique("safe-release");
        DistributedLock.Handle slowOwner = locks.tryLock(key, Duration.ofMillis(300)).orElseThrow();
        Thread.sleep(600);   // the slow owner's lease ran out while it was still working
        DistributedLock.Handle newOwner = locks.tryLock(key, Duration.ofSeconds(10)).orElseThrow();

        boolean released = slowOwner.release();   // a naive "DEL key" would wrongly delete newOwner's lock

        assertThat(released).isFalse();
        assertThat(locks.tryLock(key, Duration.ofSeconds(10)))
                .as("the new owner must still hold the lock")
                .isEmpty();
        assertThat(newOwner.release()).isTrue();
    }
}
