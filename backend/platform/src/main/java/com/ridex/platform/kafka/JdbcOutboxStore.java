package com.ridex.platform.kafka;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcOutboxStore implements OutboxStore {

    private static final long ADVISORY_LOCK_ID = 6_001L;

    private final JdbcTemplate jdbc;

    public JdbcOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryLockForRelay() {
        // A PostgreSQL advisory lock, released automatically when the transaction ends
        return Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_ID));
    }

    @Override
    public List<Pending> findUnpublished(int limit) {
        return jdbc.query("""
                select seq, event_id, topic, event_key, event_type, payload
                from outbox_events where published_at is null order by seq limit ?
                """,
                (rs, n) -> new Pending(rs.getLong("seq"), rs.getObject("event_id", UUID.class), rs.getString("topic"),
                        rs.getString("event_key"), rs.getString("event_type"), rs.getString("payload")),
                limit);
    }

    @Override
    public void markPublished(long seq) {
        jdbc.update("update outbox_events set published_at = now() where seq = ?", seq);
    }

    @Override
    public long countUnpublished() {
        Long n = jdbc.queryForObject("select count(*) from outbox_events where published_at is null", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public int deletePublishedOlderThanDays(int days) {
        return jdbc.update("delete from outbox_events where published_at is not null and published_at < now() - make_interval(days => ?)", days);
    }
}
