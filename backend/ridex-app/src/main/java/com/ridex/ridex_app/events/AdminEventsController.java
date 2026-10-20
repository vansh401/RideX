package com.ridex.ridex_app.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.common.Paging;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The admin window into the event system: what happened, what failed, and whether consumers keep up. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin: events", description = "Audit log, dead letters, consumer lag, outbox and event-based analytics")
public class AdminEventsController {

    public record AuditEntry(long id, UUID eventId, String topic, int partition, long offset, String key,
                             String type, String payload, Instant occurredAt, Instant receivedAt) {
    }

    public record DeadLetter(long id, String sourceTopic, Integer partition, Long offset, String key,
                             String failedGroup, String error, String payload, Instant createdAt) {
    }

    public record DailyCount(java.time.LocalDate day, String status, long count) {
    }

    private final JdbcTemplate jdbc;
    private final ConsumerLagService lagService;
    private final OutboxEventRepository outbox;

    public AdminEventsController(JdbcTemplate jdbc, ConsumerLagService lagService, OutboxEventRepository outbox) {
        this.jdbc = jdbc;
        this.lagService = lagService;
        this.outbox = outbox;
    }

    @GetMapping("/events/audit")
    @Operation(summary = "The audit log, newest first", description = "Optional filter by key (a ride id).")
    public PageResponse<AuditEntry> audit(@RequestParam(required = false) String key,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = Paging.of(page, size, org.springframework.data.domain.Sort.unsorted());
        String where = key == null || key.isBlank() ? "" : " where event_key = ?";
        Object[] args = key == null || key.isBlank() ? new Object[0] : new Object[] {key.trim()};
        Long total = jdbc.queryForObject("select count(*) from event_audit_log" + where, Long.class, args);
        Object[] pagedArgs = java.util.Arrays.copyOf(args, args.length + 2);
        pagedArgs[args.length] = pageable.getPageSize();
        pagedArgs[args.length + 1] = pageable.getOffset();
        List<AuditEntry> rows = jdbc.query("""
                select id, event_id, topic, partition_no, offset_no, event_key, event_type, payload, occurred_at, received_at
                from event_audit_log""" + where + " order by id desc limit ? offset ?",
                (rs, n) -> new AuditEntry(rs.getLong("id"), rs.getObject("event_id", UUID.class), rs.getString("topic"),
                        rs.getInt("partition_no"), rs.getLong("offset_no"), rs.getString("event_key"), rs.getString("event_type"),
                        rs.getString("payload"), rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("received_at").toInstant()),
                pagedArgs);
        Page<AuditEntry> result = new PageImpl<>(rows, pageable, total == null ? 0 : total);
        return PageResponse.from(result, e -> e);
    }

    @GetMapping("/events/dead-letters")
    @Operation(summary = "Messages that failed for good", description = "Newest first.")
    public PageResponse<DeadLetter> deadLetters(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = Paging.of(page, size, org.springframework.data.domain.Sort.unsorted());
        Long total = jdbc.queryForObject("select count(*) from dead_letter_events", Long.class);
        List<DeadLetter> rows = jdbc.query("""
                select id, source_topic, partition_no, offset_no, event_key, failed_group, error_message, payload, created_at
                from dead_letter_events order by id desc limit ? offset ?
                """,
                (rs, n) -> new DeadLetter(rs.getLong("id"), rs.getString("source_topic"), (Integer) rs.getObject("partition_no"),
                        (Long) rs.getObject("offset_no"), rs.getString("event_key"), rs.getString("failed_group"),
                        rs.getString("error_message"), rs.getString("payload"), rs.getTimestamp("created_at").toInstant()),
                pageable.getPageSize(), pageable.getOffset());
        return PageResponse.from(new PageImpl<>(rows, pageable, total == null ? 0 : total), e -> e);
    }

    @GetMapping("/events/lag")
    @Operation(summary = "How far behind is each consumer group?", description = "0 means fully caught up.")
    public List<ConsumerLagService.Lag> lag() {
        return lagService.lag();
    }

    @GetMapping("/events/outbox")
    @Operation(summary = "Events saved but not yet published to Kafka", description = "Normally 0 or very small.")
    public Map<String, Long> outbox() {
        return Map.of("pending", outbox.countByPublishedAtIsNull());
    }

    @GetMapping("/analytics/ride-status-per-day")
    @Operation(summary = "How often each ride status happened per day, from events", description = "The last N days (default 14).")
    public List<DailyCount> perDay(@RequestParam(defaultValue = "14") int days) {
        int window = Math.min(Math.max(days, 1), 90);
        return jdbc.query("""
                select day, status, event_count from ride_status_counts_daily
                where day >= current_date - ?::int order by day, status
                """,
                (rs, n) -> new DailyCount(rs.getDate("day").toLocalDate(), rs.getString("status"), rs.getLong("event_count")),
                window);
    }
}
