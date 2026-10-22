package com.ridex.payment.earnings;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.ridex.platform.tx.AfterCommit;

import tools.jackson.databind.json.JsonMapper;

/**
 * The driver earnings dashboard: totals for today, the last 7 and 30 days, a per-day chart, and the latest trips. All of it is read from the
 * LEDGER: the earnings account of the driver and its entries are the truth, so the dashboard can never disagree with what the driver is owed.
 *
 * CACHING. The dashboard is opened often (a driver glances at it between rides) and costs several aggregate queries, while the numbers change
 * only when a ride is paid. So the answer is cached in Redis (cache-aside): look in Redis; on a miss compute and store for 60 seconds.
 *  - A short TTL is the safety net: even if an invalidation is lost, the dashboard is at most a minute old.
 *  - Invalidation is the precision: when a payment credits the driver, the cached copy is deleted AFTER the database transaction commits
 *    (deleting earlier would let a reader re-fill the cache with the old numbers before the commit).
 *  - Redis is an optimisation, never a dependency: if it is down the numbers are simply computed every time.
 */
@Service
public class EarningsService {

    private static final Logger log = LoggerFactory.getLogger(EarningsService.class);
    private static final long TTL_SECONDS = 60;

    public record Day(LocalDate day, BigDecimal amount, int trips) {
    }

    public record Trip(UUID rideId, BigDecimal amount, Instant at) {
    }

    public record Summary(String currency, BigDecimal balance, BigDecimal today, int tripsToday, BigDecimal last7Days, int trips7Days,
                          BigDecimal last30Days, int trips30Days, BigDecimal bonuses30Days, List<Day> daily, List<Trip> recent, Instant generatedAt) {
    }

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final JsonMapper mapper;
    private final ZoneId zone;

    public EarningsService(JdbcTemplate jdbc, StringRedisTemplate redis, JsonMapper mapper, @Value("${ridex.payments.zone:Asia/Kolkata}") String zone) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.mapper = mapper;
        this.zone = ZoneId.of(zone);
    }

    static String key(UUID driver) {
        return "earnings:summary:" + driver;
    }

    public Summary summary(UUID driverUserId) {
        try {
            String cached = redis.opsForValue().get(key(driverUserId));
            if (cached != null) {
                return mapper.readValue(cached, Summary.class);
            }
        } catch (RuntimeException e) {
            log.debug("Earnings cache read failed: {}", e.getClass().getSimpleName());
        }
        Summary fresh = compute(driverUserId, Instant.now());
        try {
            redis.opsForValue().set(key(driverUserId), mapper.writeValueAsString(fresh), java.time.Duration.ofSeconds(TTL_SECONDS));
        } catch (RuntimeException e) {
            log.debug("Earnings cache write failed: {}", e.getClass().getSimpleName());
        }
        return fresh;
    }

    /** Throw the cached copy away once the transaction that changed the numbers has committed. */
    public void forgetAfterCommit(UUID driverUserId) {
        AfterCommit.run(() -> {
            try {
                redis.delete(key(driverUserId));
            } catch (RuntimeException e) {
                log.debug("Earnings cache eviction failed: {}", e.getClass().getSimpleName());
            }
        });
    }

    Summary compute(UUID driverUserId, Instant now) {
        List<UUID> accounts = jdbc.queryForList("select id from ledger_accounts where type = 'DRIVER_EARNINGS' and owner_id = ?", UUID.class, driverUserId);
        if (accounts.isEmpty()) {
            BigDecimal zero = BigDecimal.ZERO.setScale(2);
            return new Summary("INR", zero, zero, 0, zero, 0, zero, 0, zero, List.of(), List.of(), now);
        }
        UUID account = accounts.get(0);
        LocalDate today = now.atZone(zone).toLocalDate();
        Timestamp startOfToday = Timestamp.from(today.atStartOfDay(zone).toInstant());
        Timestamp start7 = Timestamp.from(today.minusDays(6).atStartOfDay(zone).toInstant());
        Timestamp start30 = Timestamp.from(today.minusDays(29).atStartOfDay(zone).toInstant());

        BigDecimal balance = jdbc.queryForObject("select balance from ledger_accounts where id = ?", BigDecimal.class, account);
        // One query for the whole window; the three totals are then taken from the per-day rows
        List<Day> daily = jdbc.query("""
                select (e.created_at at time zone ?)::date as day, sum(e.amount) as amount, count(*) as trips
                from ledger_entries e join ledger_transactions t on t.id = e.transaction_id
                where e.account_id = ? and t.type = 'RIDE_PAYMENT' and e.created_at >= ?
                group by 1 order by 1
                """, (rs, n) -> new Day(rs.getDate("day").toLocalDate(), rs.getBigDecimal("amount"), rs.getInt("trips")),
                zone.getId(), account, start30);
        BigDecimal todayTotal = BigDecimal.ZERO.setScale(2);
        BigDecimal week = BigDecimal.ZERO.setScale(2);
        BigDecimal month = BigDecimal.ZERO.setScale(2);
        int tripsToday = 0;
        int trips7 = 0;
        int trips30 = 0;
        for (Day d : daily) {
            month = month.add(d.amount());
            trips30 += d.trips();
            if (!d.day().isBefore(today.minusDays(6))) {
                week = week.add(d.amount());
                trips7 += d.trips();
            }
            if (d.day().equals(today)) {
                todayTotal = todayTotal.add(d.amount());
                tripsToday += d.trips();
            }
        }
        BigDecimal bonuses = jdbc.queryForObject("""
                select coalesce(sum(e.amount), 0) from ledger_entries e join ledger_transactions t on t.id = e.transaction_id
                where e.account_id = ? and t.type = 'INCENTIVE' and e.created_at >= ?
                """, BigDecimal.class, account, start30);
        List<Trip> recent = jdbc.query("""
                select t.reference, e.amount, e.created_at from ledger_entries e join ledger_transactions t on t.id = e.transaction_id
                where e.account_id = ? and t.type = 'RIDE_PAYMENT' order by e.id desc limit 10
                """, (rs, n) -> new Trip(UUID.fromString(rs.getString("reference")), rs.getBigDecimal("amount"), rs.getTimestamp("created_at").toInstant()), account);
        // fill days without trips with zero, so a chart has a point for every day
        List<Day> filled = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            LocalDate day = today.minusDays(29 - i);
            filled.add(daily.stream().filter(d -> d.day().equals(day)).findFirst().orElse(new Day(day, BigDecimal.ZERO.setScale(2), 0)));
        }
        return new Summary("INR", balance, todayTotal, tripsToday, week, trips7, month, trips30, bonuses, filled, recent, now);
    }

    /** Trips the driver completed on a day (used by the incentive rules). */
    public int tripsOn(UUID driverAccount, LocalDate day) {
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = from.plus(1, ChronoUnit.DAYS);
        Integer n = jdbc.queryForObject("""
                select count(*) from ledger_entries e join ledger_transactions t on t.id = e.transaction_id
                where e.account_id = ? and t.type = 'RIDE_PAYMENT' and e.created_at >= ? and e.created_at < ?
                """, Integer.class, driverAccount, Timestamp.from(from), Timestamp.from(to));
        return n == null ? 0 : n;
    }
}
