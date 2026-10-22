package com.ridex.analytics;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.platform.security.ServicePrincipal;
import com.ridex.platform.web.BadRequestException;
import com.ridex.platform.web.PageResponse;
import com.ridex.platform.web.Paging;
import com.ridex.platform.web.ResourceNotFoundException;

/** The fraud review queue for administrators: the signals the rules raised, a decision for each, and a risk score per user. Nothing here blocks anybody. */
@RestController
@RequestMapping("/api/admin/fraud")
public class FraudController {

    public record Signal(long id, String rule, String subjectType, String subjectId, int severity, String details, UUID rideId, String status,
                         Instant createdAt, Instant reviewedAt) {
    }

    public record ReviewRequest(String status) {
    }

    public record Risk(String userId, int score, long openSignals, List<Signal> signals) {
    }

    private final JdbcTemplate jdbc;

    public FraudController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Signal map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Signal(rs.getLong("id"), rs.getString("rule"), rs.getString("subject_type"), rs.getString("subject_id"), rs.getInt("severity"),
                rs.getString("details"), rs.getObject("ride_id", UUID.class), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant());
    }

    @GetMapping("/signals")
    public PageResponse<Signal> signals(@RequestParam(required = false) String status, @RequestParam(required = false) String rule,
                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = Paging.of(page, size, Sort.unsorted());
        String where = " where (?::text is null or status = ?) and (?::text is null or rule = ?)";
        Long total = jdbc.queryForObject("select count(*) from fraud_signals" + where, Long.class, status, status, rule, rule);
        List<Signal> rows = jdbc.query("select * from fraud_signals" + where + " order by severity desc, created_at desc limit ? offset ?",
                FraudController::map, status, status, rule, rule, pageable.getPageSize(), pageable.getOffset());
        return PageResponse.from(new PageImpl<>(rows, pageable, total == null ? 0 : total), s -> s);
    }

    /** How many signals each rule has raised and how they were decided: the false-positive rate of a rule is its dismissed share. */
    @GetMapping("/summary")
    public List<Map<String, Object>> summary() {
        return jdbc.queryForList("""
                select rule, count(*) as total, count(*) filter (where status = 'NEW') as open,
                       count(*) filter (where status = 'CONFIRMED') as confirmed, count(*) filter (where status = 'DISMISSED') as dismissed
                from fraud_signals group by rule order by total desc
                """);
    }

    @PostMapping("/signals/{id}/review")
    public Signal review(@AuthenticationPrincipal ServicePrincipal admin, @PathVariable long id, @RequestBody ReviewRequest request) {
        if (!List.of("CONFIRMED", "DISMISSED").contains(request.status())) {
            throw new BadRequestException("The decision is CONFIRMED or DISMISSED");
        }
        int updated = jdbc.update("update fraud_signals set status = ?, reviewed_by = ?, reviewed_at = now() where id = ? and status = 'NEW'", request.status(), admin.id(), id);
        if (updated == 0 && jdbc.queryForObject("select count(*) from fraud_signals where id = ?", Integer.class, id) == 0) {
            throw new ResourceNotFoundException("Signal not found");
        }
        return jdbc.queryForObject("select * from fraud_signals where id = ?", FraudController::map, id);
    }

    /** The open signals about a person (as a user, or as one side of a pair) and their sum: a number to sort a review queue by, not a verdict. */
    @GetMapping("/users/{userId}")
    public Risk risk(@PathVariable String userId) {
        List<Signal> signals = jdbc.query("""
                select * from fraud_signals where (subject_id = ? or (subject_type = 'PAIR' and subject_id like ?)) and created_at > now() - interval '30 days'
                order by created_at desc limit 50
                """, FraudController::map, userId, "%" + userId + "%");
        List<Signal> open = signals.stream().filter(s -> "NEW".equals(s.status()) || "CONFIRMED".equals(s.status())).toList();
        return new Risk(userId, open.stream().mapToInt(Signal::severity).sum(), signals.stream().filter(s -> "NEW".equals(s.status())).count(), signals);
    }
}
