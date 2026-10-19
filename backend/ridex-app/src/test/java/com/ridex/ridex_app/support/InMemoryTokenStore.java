package com.ridex.ridex_app.support;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.ridex.ridex_app.redis.TokenStore;

/**
 * A TokenStore that lives in a map, for unit tests that must not need Redis. It honours expiry
 * against a clock the test controls ({@link #advance(Duration)}), so "the link expired" can be tested
 * without waiting. Real Redis behaviour is covered by the Testcontainers tests.
 */
public class InMemoryTokenStore implements TokenStore {

    private record Entry(String value, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private Instant now = Instant.parse("2026-01-01T10:00:00Z");

    public void advance(Duration time) {
        now = now.plus(time);
    }

    public boolean contains(String key) {
        return get(key).isPresent();
    }

    public int size() {
        entries.values().removeIf(e -> !e.expiresAt().isAfter(now));
        return entries.size();
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        entries.put(key, new Entry(value, now.plus(ttl)));
    }

    @Override
    public Optional<String> take(String key) {
        Optional<String> value = get(key);
        entries.remove(key);
        return value;
    }

    @Override
    public Optional<String> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null || !entry.expiresAt().isAfter(now)) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void delete(String... keys) {
        for (String key : keys) {
            entries.remove(key);
        }
    }

    @Override
    public long ttlSeconds(String key) {
        Entry entry = entries.get(key);
        return entry == null ? 0 : Math.max(0, Duration.between(now, entry.expiresAt()).toSeconds());
    }

    @Override
    public long increment(String key, Duration ttl) {
        Optional<String> current = get(key);
        long next = current.map(Long::parseLong).orElse(0L) + 1;
        Instant expiry = current.isPresent() ? entries.get(key).expiresAt() : now.plus(ttl);
        entries.put(key, new Entry(String.valueOf(next), expiry));
        return next;
    }
}
