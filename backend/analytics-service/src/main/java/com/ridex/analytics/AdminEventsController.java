package com.ridex.analytics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.platform.web.PageResponse;
import com.ridex.platform.web.Paging;


/** The admin window into the event system: what happened, what failed, and whether consumers keep up. */
@RestController
@RequestMapping("/api/admin")
public class AdminEventsController {

    public record AuditEntry(long id, UUID eventId, String topic, int partition, long offset, String key,
                             String type, String payload, Instant occurredAt, Instant receivedAt) {
    }

    public record DeadLetter(long id, String sourceTopic, Integer partition, Long offset, String key,
                             String failedGroup, String error, String payload, Instant createdAt) {
    }

    public record DailyCount(java.time.LocalDate day, String status, long count) {
    }

    public record PaymentDay(java.time.LocalDate day, String outcome, long count, java.math.BigDecimal totalAmount) {
    }

    private final JdbcTemplate jdbc;
    private final ConsumerLagService lagService;

    public AdminEventsController(JdbcTemplate jdbc, ConsumerLagService lagService) {
        this.jdbc = jdbc;
        this.lagService = lagService;
    }

    @GetMapping("/events/audit")
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
    public List<ConsumerLagService.Lag> lag() {
        return lagService.lag();
    }

    @GetMapping("/analytics/payments-per-day")
    public List<PaymentDay> paymentsPerDay(@RequestParam(defaultValue = "14") int days) {
        int window = Math.min(Math.max(days, 1), 90);
        return jdbc.query("""
                select day, outcome, event_count, total_amount from payment_outcomes_daily
                where day >= current_date - ?::int order by day, outcome
                """,
                (rs, n) -> new PaymentDay(rs.getDate("day").toLocalDate(), rs.getString("outcome"), rs.getLong("event_count"), rs.getBigDecimal("total_amount")),
                window);
    }

    @GetMapping("/analytics/ride-status-per-day")
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
