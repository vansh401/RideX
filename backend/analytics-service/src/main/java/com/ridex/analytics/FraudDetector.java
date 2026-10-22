package com.ridex.analytics;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Looks at the facts after each event and records a SIGNAL when a rule (see FraudRules) fires. "Now" is always the time of the EVENT, not the
 * wall clock, so replaying history gives the same signals as seeing it live.
 *
 * Duplicates are prevented by a dedupe key (rule + subject + a time bucket): the fourth cancellation and the fifth of the same burst are one
 * signal, not two, and replaying a topic creates nothing new. The key is unique in the database, so even two consumers racing cannot create two.
 */
@Component
public class FraudDetector {

    private static final Logger log = LoggerFactory.getLogger(FraudDetector.class);

    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;

    public FraudDetector(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /** After a ride event: the ride's facts are up to date. */
    public void afterRideEvent(UUID rideId, Instant at) {
        jdbc.query("select rider_user_id, driver_user_id, accepted_at, completed_at, cancelled_at, cancelled_by, fare from ride_facts where ride_id = ?", rs -> {
            UUID rider = rs.getObject("rider_user_id", UUID.class);
            UUID driver = rs.getObject("driver_user_id", UUID.class);
            Timestamp accepted = rs.getTimestamp("accepted_at");
            Timestamp completed = rs.getTimestamp("completed_at");
            String cancelledBy = rs.getString("cancelled_by");
            BigDecimal fare = rs.getBigDecimal("fare");

            if ("RIDER".equals(cancelledBy) && accepted != null && rider != null) {
                int n = count("select count(*) from ride_facts where rider_user_id = ? and cancelled_by = 'RIDER' and accepted_at is not null and cancelled_at > ? and cancelled_at <= ?",
                        rider, ts(at.minus(FraudRules.RIDER_CANCEL_WINDOW)), ts(at));
                if (FraudRules.riderCancelBurst(n)) {
                    signal("RIDER_CANCEL_BURST", "USER", rider.toString(), 2, n + " rides cancelled by the rider after a driver accepted, within an hour",
                            rideId, "rider-cancel:" + rider + ":" + bucket(at, Duration.ofHours(1)));
                }
            }
            if ("DRIVER".equals(cancelledBy) && accepted != null && driver != null) {
                int n = count("select count(*) from ride_facts where driver_user_id = ? and cancelled_by = 'DRIVER' and accepted_at is not null and cancelled_at > ? and cancelled_at <= ?",
                        driver, ts(at.minus(FraudRules.DRIVER_CANCEL_WINDOW)), ts(at));
                if (FraudRules.driverCancelPattern(n)) {
                    signal("DRIVER_CANCEL_PATTERN", "USER", driver.toString(), 1, n + " rides cancelled by the driver after accepting, within a day",
                            rideId, "driver-cancel:" + driver + ":" + bucket(at, Duration.ofDays(1)));
                }
            }
            if (completed != null && accepted != null) {
                Duration took = Duration.between(accepted.toInstant(), completed.toInstant());
                if (FraudRules.instantCompletion(took, fare)) {
                    signal("INSTANT_COMPLETION", "RIDE", rideId.toString(), 3, "A trip with a fare of " + fare + " was completed " + took.toSeconds()
                            + " seconds after the driver accepted it", rideId, "instant:" + rideId);
                }
            }
            if (completed != null && rider != null && driver != null) {
                int n = count("select count(*) from ride_facts where rider_user_id = ? and driver_user_id = ? and completed_at > ? and completed_at <= ?",
                        rider, driver, ts(at.minus(FraudRules.PAIR_WINDOW)), ts(at));
                if (FraudRules.repeatPair(n)) {
                    signal("REPEAT_PAIR", "PAIR", rider + ":" + driver, 2, "The same rider and driver completed " + n + " rides within a day",
                            rideId, "pair:" + rider + ":" + driver + ":" + bucket(at, Duration.ofDays(1)));
                }
            }
        }, rideId);
    }

    /** After a payment event. */
    public void afterPaymentEvent(UUID rideId, Instant at) {
        jdbc.query("select rider_user_id from payment_facts where ride_id = ?", rs -> {
            UUID rider = rs.getObject("rider_user_id", UUID.class);
            if (rider == null) {
                return;
            }
            int coupons = count("select count(*) from payment_facts where rider_user_id = ? and discount > 0 and paid_at > ? and paid_at <= ?",
                    rider, ts(at.minus(FraudRules.COUPON_WINDOW)), ts(at));
            if (FraudRules.couponAbuse(coupons)) {
                signal("COUPON_ABUSE", "USER", rider.toString(), 2, "Coupons used on " + coupons + " rides within a day", rideId,
                        "coupon:" + rider + ":" + bucket(at, Duration.ofDays(1)));
            }
            int refunds = count("select count(*) from payment_facts where rider_user_id = ? and refunded > 0 and paid_at > ?", rider, ts(at.minus(FraudRules.REFUND_WINDOW)));
            BigDecimal paid = jdbc.queryForObject("select coalesce(sum(amount - discount), 0) from payment_facts where rider_user_id = ? and status = 'SUCCEEDED' and paid_at > ?",
                    BigDecimal.class, rider, ts(at.minus(FraudRules.REFUND_WINDOW)));
            BigDecimal refunded = jdbc.queryForObject("select coalesce(sum(refunded), 0) from payment_facts where rider_user_id = ? and paid_at > ?",
                    BigDecimal.class, rider, ts(at.minus(FraudRules.REFUND_WINDOW)));
            if (FraudRules.refundHeavy(refunds, refunded, paid)) {
                signal("REFUND_HEAVY", "USER", rider.toString(), 2, refunds + " refunds returned " + refunded + " of " + paid + " paid in a month", rideId,
                        "refunds:" + rider + ":" + bucket(at, Duration.ofDays(30)));
            }
        }, rideId);
    }

    private void signal(String rule, String subjectType, String subjectId, int severity, String details, UUID rideId, String dedupeKey) {
        int inserted = jdbc.update("""
                insert into fraud_signals (rule, subject_type, subject_id, severity, details, ride_id, dedupe_key)
                values (?, ?, ?, ?, ?, ?, ?) on conflict (dedupe_key) do nothing
                """, rule, subjectType, subjectId, severity, details, rideId, dedupeKey);
        if (inserted > 0) {
            Counter.builder("ridex.fraud.signals").description("Fraud signals raised, by rule").tag("rule", rule).register(metrics).increment();
            log.info("Fraud signal {} for {} {}", rule, subjectType, subjectId);   // ids only
        }
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    /** The number of the time slot an instant falls in: two events of one burst share it. */
    static long bucket(Instant at, Duration size) {
        return at.toEpochMilli() / size.toMillis();
    }

    /** The rules that can fire, for the admin screen. */
    public static List<String> rules() {
        return List.of("RIDER_CANCEL_BURST", "DRIVER_CANCEL_PATTERN", "INSTANT_COMPLETION", "REPEAT_PAIR", "COUPON_ABUSE", "REFUND_HEAVY");
    }
}
