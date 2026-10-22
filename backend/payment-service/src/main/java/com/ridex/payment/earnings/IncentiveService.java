package com.ridex.payment.earnings;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.ridex.payment.ledger.AccountType;
import com.ridex.payment.ledger.LedgerService;

/**
 * Driver incentives: a bonus for reaching a number of completed trips in a day (a "tier"). Incentives are RULES applied to facts: the facts are
 * the ride payments in the ledger, the rules are numbers in configuration, and the result is more ledger entries.
 *
 *   ridex.payments.incentives.tiers = 10:100,20:250     (10 trips: 100, 20 trips: 250; the highest tier reached pays, they do not add up)
 *
 * IDEMPOTENT: the bonus of a driver for a day has the key "incentive-<day>-<driver>", so running the job twice (a restart, two servers, an
 * administrator pressing the button) pays once. Each driver is paid in its own transaction: one failure does not stop the others.
 * The bonus is paid from the INCENTIVES account (the company's incentive budget, which shows how much was spent).
 */
@Service
public class IncentiveService {

    private static final Logger log = LoggerFactory.getLogger(IncentiveService.class);

    public record Tier(int trips, BigDecimal bonus) {
    }

    /** Where a driver stands today: trips done, the tier they are working toward, and what is already guaranteed. */
    public record Progress(LocalDate day, int tripsToday, List<Tier> tiers, Tier next, int tripsToNext, BigDecimal bonusSoFar) {
    }

    public record Result(LocalDate day, int driversPaid, BigDecimal totalPaid) {
    }

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final TransactionTemplate transaction;
    private final EarningsService earnings;
    private final List<Tier> tiers;
    private final ZoneId zone;

    public IncentiveService(JdbcTemplate jdbc, LedgerService ledger, PlatformTransactionManager transactionManager, EarningsService earnings,
                            @Value("${ridex.payments.incentives.tiers:10:100,20:250}") String tiers,
                            @Value("${ridex.payments.zone:Asia/Kolkata}") String zone) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.transaction = new TransactionTemplate(transactionManager);
        this.earnings = earnings;
        this.tiers = parse(tiers);
        this.zone = ZoneId.of(zone);
    }

    static List<Tier> parse(String spec) {
        List<Tier> parsed = new ArrayList<>();
        for (String part : spec.split(",")) {
            if (!part.isBlank()) {
                String[] pair = part.trim().split(":");
                parsed.add(new Tier(Integer.parseInt(pair[0]), new BigDecimal(pair[1]).setScale(2)));
            }
        }
        parsed.sort(Comparator.comparingInt(Tier::trips));
        return parsed;
    }

    /** The bonus for a number of trips: the highest tier reached, or zero. */
    static BigDecimal bonusFor(List<Tier> tiers, int trips) {
        BigDecimal bonus = BigDecimal.ZERO.setScale(2);
        for (Tier tier : tiers) {
            if (trips >= tier.trips()) {
                bonus = tier.bonus();
            }
        }
        return bonus;
    }

    /** Every night, shortly after midnight, for the day that just ended. */
    @Scheduled(cron = "0 10 0 * * *", zone = "${ridex.payments.zone:Asia/Kolkata}")
    public void nightly() {
        try {
            Result result = payFor(Instant.now().atZone(zone).toLocalDate().minusDays(1));
            log.info("Incentives for {}: {} drivers paid", result.day(), result.driversPaid());
        } catch (RuntimeException e) {
            log.error("The incentive run failed: {}", e.getClass().getSimpleName());
        }
    }

    public Progress progress(UUID driverUserId) {
        LocalDate today = Instant.now().atZone(zone).toLocalDate();
        List<UUID> account = jdbc.queryForList("select id from ledger_accounts where type = 'DRIVER_EARNINGS' and owner_id = ?", UUID.class, driverUserId);
        int trips = account.isEmpty() ? 0 : earnings.tripsOn(account.get(0), today);
        Tier next = tiers.stream().filter(t -> t.trips() > trips).findFirst().orElse(null);
        return new Progress(today, trips, tiers, next, next == null ? 0 : next.trips() - trips, bonusFor(tiers, trips));
    }

    public Result payFor(LocalDate day) {
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = from.plus(java.time.Duration.ofDays(1));
        // drivers who completed at least the smallest tier's number of trips that day
        int minimum = tiers.isEmpty() ? Integer.MAX_VALUE : tiers.get(0).trips();
        List<UUID> candidates = jdbc.queryForList("""
                select e.account_id from ledger_entries e join ledger_transactions t on t.id = e.transaction_id
                where t.type = 'RIDE_PAYMENT' and e.amount > 0 and e.account_id in (select id from ledger_accounts where type = 'DRIVER_EARNINGS')
                  and e.created_at >= ? and e.created_at < ?
                group by e.account_id having count(*) >= ?
                """, UUID.class, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), minimum);
        int paid = 0;
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (UUID account : candidates) {
            BigDecimal bonus = bonusFor(tiers, earnings.tripsOn(account, day));
            if (bonus.signum() == 0) {
                continue;
            }
            LedgerService.Posting posting = transaction.execute(status -> ledger.post("INCENTIVE", day.toString(), "incentive-" + day + "-" + account,
                    "Daily trips bonus", List.of(new LedgerService.Leg(AccountType.INCENTIVES_ID, bonus.negate()), new LedgerService.Leg(account, bonus))));
            if (posting != null && posting.status() == LedgerService.Status.POSTED) {
                paid++;
                total = total.add(bonus);
                UUID owner = jdbc.queryForObject("select owner_id from ledger_accounts where id = ?", UUID.class, account);
                earnings.forgetAfterCommit(owner);
            }
        }
        return new Result(day, paid, total);
    }
}
