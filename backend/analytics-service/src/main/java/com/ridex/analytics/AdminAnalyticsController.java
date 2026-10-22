package com.ridex.analytics;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.platform.web.BadRequestException;

/**
 * Aggregations for the admin charts: revenue and rides over time, by category, by hour of day, by zone, and the best drivers. Everything is SQL over
 * the facts tables (never over other services' tables). Each endpoint answers one chart, so the browser asks only for what it draws.
 *
 * "Day" and "hour" mean the business's time zone (configuration), not UTC: a ride at 00:30 in Bengaluru belongs to that day's chart, not to the
 * previous UTC day. Days without any ride are returned with zeros, so a chart has a point for every day and a gap looks like a gap.
 *
 * Money here is "gross booking value" (the fares of completed rides) and what was actually collected (after coupons), because they differ and the
 * difference is the cost of the coupons.
 */
@RestController
@RequestMapping("/api/admin/analytics")
public class AdminAnalyticsController {

    public record Overview(int days, long ridesRequested, long ridesCompleted, long ridesCancelled, double cancellationRate, BigDecimal grossBookings,
                           BigDecimal averageFare, Double averageSecondsToAccept, long ridesWithSurge, BigDecimal collected, BigDecimal couponDiscounts,
                           BigDecimal refunds, long paymentsSucceeded, long paymentsFailed, long complaints) {
    }

    public record Day(LocalDate day, long requested, long completed, long cancelled, BigDecimal grossBookings) {
    }

    public record CategoryRow(String category, long rides, BigDecimal grossBookings, BigDecimal averageFare) {
    }

    public record HourRow(int hour, long rides) {
    }

    public record ZoneRow(String zone, long requests, long completed, BigDecimal averageSurge) {
    }

    public record DriverRow(UUID driverUserId, long rides, BigDecimal grossBookings) {
    }

    private final JdbcTemplate jdbc;
    private final ZoneId zone;

    public AdminAnalyticsController(JdbcTemplate jdbc, @Value("${ridex.analytics.zone:Asia/Kolkata}") String zone) {
        this.jdbc = jdbc;
        this.zone = ZoneId.of(zone);
    }

    private static int clampDays(int days) {
        if (days < 1 || days > 365) {
            throw new BadRequestException("days must be between 1 and 365");
        }
        return days;
    }

    private LocalDate today() {
        return Instant.now().atZone(zone).toLocalDate();
    }

    /** The start of the first day of the window, in the business time zone. */
    private Instant since(int days) {
        return today().minusDays(days - 1L).atStartOfDay(zone).toInstant();
    }

    @GetMapping("/overview")
    public Overview overview(@RequestParam(defaultValue = "30") int days) {
        java.sql.Timestamp from = java.sql.Timestamp.from(since(clampDays(days)));
        return jdbc.query("""
                select count(*) as requested,
                       count(*) filter (where final_status = 'RIDE_COMPLETED') as completed,
                       count(*) filter (where final_status = 'CANCELLED') as cancelled,
                       coalesce(sum(fare) filter (where final_status = 'RIDE_COMPLETED'), 0) as gross,
                       coalesce(avg(fare) filter (where final_status = 'RIDE_COMPLETED'), 0) as avg_fare,
                       avg(extract(epoch from accepted_at - requested_at)) filter (where accepted_at is not null) as accept_seconds,
                       count(*) filter (where surge_multiplier > 1) as surged
                from ride_facts where requested_at >= ?
                """, rs -> {
                    rs.next();
                    long requested = rs.getLong("requested");
                    long completed = rs.getLong("completed");
                    long cancelled = rs.getLong("cancelled");
                    long finished = completed + cancelled;
                    BigDecimal[] money = jdbc.queryForObject("""
                            select coalesce(sum(amount - discount) filter (where status = 'SUCCEEDED'), 0),
                                   coalesce(sum(discount) filter (where status = 'SUCCEEDED'), 0), coalesce(sum(refunded), 0)
                            from payment_facts where event_at >= ?
                            """, (r, n) -> new BigDecimal[] {r.getBigDecimal(1), r.getBigDecimal(2), r.getBigDecimal(3)}, from);
                    long succeeded = count("select count(*) from payment_facts where status = 'SUCCEEDED' and event_at >= ?", from);
                    long failed = count("select count(*) from payment_facts where status = 'FAILED' and event_at >= ?", from);
                    long complaints = count("select coalesce(sum(event_count), 0) from complaints_daily where day >= ?", Date.valueOf(today().minusDays(days - 1L)));
                    return new Overview(days, requested, completed, cancelled, finished == 0 ? 0 : (double) cancelled / finished, rs.getBigDecimal("gross"),
                            rs.getBigDecimal("avg_fare").setScale(2, java.math.RoundingMode.HALF_UP), rs.getBigDecimal("accept_seconds") == null ? null : rs.getBigDecimal("accept_seconds").doubleValue(),
                            rs.getLong("surged"), money[0], money[1], money[2], succeeded, failed, complaints);
                }, from);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @GetMapping("/daily")
    public List<Day> daily(@RequestParam(defaultValue = "30") int days) {
        clampDays(days);
        LocalDate from = today().minusDays(days - 1L);
        return jdbc.query("""
                select d::date as day, coalesce(r.requested, 0) as requested, coalesce(r.completed, 0) as completed, coalesce(r.cancelled, 0) as cancelled,
                       coalesce(r.gross, 0) as gross
                from generate_series(?::date, ?::date, interval '1 day') d
                left join (
                    select (requested_at at time zone ?)::date as day, count(*) as requested,
                           count(*) filter (where final_status = 'RIDE_COMPLETED') as completed,
                           count(*) filter (where final_status = 'CANCELLED') as cancelled,
                           sum(fare) filter (where final_status = 'RIDE_COMPLETED') as gross
                    from ride_facts where requested_at >= ? group by 1) r on r.day = d::date
                order by 1
                """, (rs, n) -> new Day(rs.getDate("day").toLocalDate(), rs.getLong("requested"), rs.getLong("completed"), rs.getLong("cancelled"),
                rs.getBigDecimal("gross")), Date.valueOf(from), Date.valueOf(today()), zone.getId(), java.sql.Timestamp.from(since(days)));
    }

    @GetMapping("/categories")
    public List<CategoryRow> categories(@RequestParam(defaultValue = "30") int days) {
        return jdbc.query("""
                select category, count(*) as rides, coalesce(sum(fare), 0) as gross, coalesce(avg(fare), 0) as avg_fare
                from ride_facts where final_status = 'RIDE_COMPLETED' and category is not null and requested_at >= ?
                group by category order by gross desc
                """, (rs, n) -> new CategoryRow(rs.getString("category"), rs.getLong("rides"), rs.getBigDecimal("gross"),
                rs.getBigDecimal("avg_fare").setScale(2, java.math.RoundingMode.HALF_UP)), java.sql.Timestamp.from(since(clampDays(days))));
    }

    /** Demand over the day: how many rides were requested in each hour (0 to 23), so the busy hours show. */
    @GetMapping("/hours")
    public List<HourRow> hours(@RequestParam(defaultValue = "30") int days) {
        return jdbc.query("""
                select h as hour, coalesce(r.rides, 0) as rides
                from generate_series(0, 23) h
                left join (select extract(hour from requested_at at time zone ?)::int as hour, count(*) as rides
                           from ride_facts where requested_at >= ? group by 1) r on r.hour = h
                order by h
                """, (rs, n) -> new HourRow(rs.getInt("hour"), rs.getLong("rides")), zone.getId(), java.sql.Timestamp.from(since(clampDays(days))));
    }

    @GetMapping("/zones")
    public List<ZoneRow> zones(@RequestParam(defaultValue = "7") int days, @RequestParam(defaultValue = "10") int limit) {
        return jdbc.query("""
                select pickup_cell, count(*) as requests, count(*) filter (where final_status = 'RIDE_COMPLETED') as completed,
                       coalesce(avg(surge_multiplier), 1) as avg_surge
                from ride_facts where pickup_cell is not null and requested_at >= ?
                group by pickup_cell order by requests desc limit ?
                """, (rs, n) -> new ZoneRow(rs.getString("pickup_cell"), rs.getLong("requests"), rs.getLong("completed"),
                rs.getBigDecimal("avg_surge").setScale(2, java.math.RoundingMode.HALF_UP)), java.sql.Timestamp.from(since(clampDays(days))), Math.min(Math.max(limit, 1), 50));
    }

    @GetMapping("/top-drivers")
    public List<DriverRow> topDrivers(@RequestParam(defaultValue = "30") int days, @RequestParam(defaultValue = "10") int limit) {
        return jdbc.query("""
                select driver_user_id, count(*) as rides, coalesce(sum(fare), 0) as gross
                from ride_facts where final_status = 'RIDE_COMPLETED' and driver_user_id is not null and requested_at >= ?
                group by driver_user_id order by rides desc, gross desc limit ?
                """, (rs, n) -> new DriverRow(rs.getObject("driver_user_id", UUID.class), rs.getLong("rides"), rs.getBigDecimal("gross")),
                java.sql.Timestamp.from(since(clampDays(days))), Math.min(Math.max(limit, 1), 50));
    }
}
