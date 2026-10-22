package com.ridex.payment.coupon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.platform.web.BadRequestException;
import com.ridex.platform.web.ConflictException;

/**
 * Coupons: the rules, and the redemption that applies them safely under concurrency.
 *
 * THE RULES (evaluated in this order, so the rider is told the first thing that is wrong):
 * the coupon exists and is switched on, today is inside its dates, the fare reaches the minimum, the total number of uses is not used up,
 * and this rider has not used it more times than allowed. A PERCENT coupon takes a share of the fare, capped by {@code maxDiscount}; a
 * FLAT coupon takes a fixed amount. Never more than the fare itself: a ride cannot cost less than nothing.
 *
 * THE CONCURRENCY PROBLEM. "Only the first 100 riders" and "once per rider" are counting rules, and counting is where concurrent requests
 * go wrong: 120 riders check "99 used, 1 left" at the same moment and all 120 get in. Redemption therefore LOCKS the coupon's row
 * ({@code select ... for update}) for the rest of its transaction: redemptions of ONE coupon take turns (a few milliseconds each), redemptions
 * of different coupons do not wait for each other. Under the lock the counts are read, checked and increased as one step. Two more nets:
 * the database refuses a used_count above the limit (CHECK), and refuses a second redemption for the same ride (UNIQUE ride_id), which also
 * makes redeeming idempotent: the same completed-ride event handled twice gives the same answer.
 */
@Service
public class CouponService {

    public enum Reason { OK, UNKNOWN, INACTIVE, NOT_STARTED, EXPIRED, FARE_TOO_LOW, SOLD_OUT, ALREADY_USED }

    public record Evaluation(boolean eligible, Reason reason, BigDecimal discount, String code) {
        static Evaluation no(Reason reason, String code) {
            return new Evaluation(false, reason, BigDecimal.ZERO.setScale(2), code);
        }
    }

    public record Coupon(UUID id, String code, String description, String discountType, BigDecimal value, BigDecimal maxDiscount,
                         BigDecimal minFare, Instant validFrom, Instant validUntil, Integer maxTotalUses, int perUserLimit, int usedCount,
                         boolean active) {
    }

    /** What a rider sees when browsing offers. */
    public record Offer(String code, String description, String discountType, BigDecimal value, BigDecimal maxDiscount, BigDecimal minFare,
                        Instant validUntil, int usesLeft) {
    }

    public record NewCoupon(String code, String description, String discountType, BigDecimal value, BigDecimal maxDiscount, BigDecimal minFare,
                            Instant validFrom, Instant validUntil, Integer maxTotalUses, Integer perUserLimit) {
    }

    private static final RowMapper<Coupon> MAPPER = (rs, n) -> new Coupon(rs.getObject("id", UUID.class), rs.getString("code"),
            rs.getString("description"), rs.getString("discount_type"), rs.getBigDecimal("value"), rs.getBigDecimal("max_discount"),
            rs.getBigDecimal("min_fare"), rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_until").toInstant(),
            (Integer) rs.getObject("max_total_uses"), rs.getInt("per_user_limit"), rs.getInt("used_count"), rs.getBoolean("active"));

    private final JdbcTemplate jdbc;

    public CouponService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ the rules (pure: no database, tested on their own)

    static Evaluation evaluate(Coupon coupon, int usesByThisUser, BigDecimal fare, Instant now) {
        String code = coupon.code();
        if (!coupon.active()) {
            return Evaluation.no(Reason.INACTIVE, code);
        }
        if (now.isBefore(coupon.validFrom())) {
            return Evaluation.no(Reason.NOT_STARTED, code);
        }
        if (!now.isBefore(coupon.validUntil())) {
            return Evaluation.no(Reason.EXPIRED, code);
        }
        if (fare.compareTo(coupon.minFare()) < 0) {
            return Evaluation.no(Reason.FARE_TOO_LOW, code);
        }
        if (coupon.maxTotalUses() != null && coupon.usedCount() >= coupon.maxTotalUses()) {
            return Evaluation.no(Reason.SOLD_OUT, code);
        }
        if (usesByThisUser >= coupon.perUserLimit()) {
            return Evaluation.no(Reason.ALREADY_USED, code);
        }
        return new Evaluation(true, Reason.OK, discountFor(coupon, fare), code);
    }

    static BigDecimal discountFor(Coupon coupon, BigDecimal fare) {
        BigDecimal discount;
        if ("PERCENT".equals(coupon.discountType())) {
            discount = fare.multiply(coupon.value()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (coupon.maxDiscount() != null) {
                discount = discount.min(coupon.maxDiscount());
            }
        } else {
            discount = coupon.value();
        }
        return discount.min(fare).setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ for riders

    /** What would this coupon give on this fare? Nothing is counted or locked: it is only a preview. */
    @Transactional(readOnly = true)
    public List<Offer> offersFor(UUID userId) {
        return jdbc.query("""
                select c.code, c.description, c.discount_type, c.value, c.max_discount, c.min_fare, c.valid_until,
                       c.per_user_limit - coalesce((select count(*) from coupon_redemptions r where r.coupon_id = c.id and r.user_id = ?), 0) as uses_left
                from coupons c
                where c.listed and c.active and c.valid_from <= now() and c.valid_until > now()
                  and (c.max_total_uses is null or c.used_count < c.max_total_uses)
                order by c.valid_until
                """, (rs, n) -> new Offer(rs.getString("code"), rs.getString("description"), rs.getString("discount_type"), rs.getBigDecimal("value"),
                rs.getBigDecimal("max_discount"), rs.getBigDecimal("min_fare"), rs.getTimestamp("valid_until").toInstant(), rs.getInt("uses_left")), userId)
                .stream().filter(o -> o.usesLeft() > 0).toList();
    }

    @Transactional
    public void setListed(UUID id, boolean listed) {
        if (jdbc.update("update coupons set listed = ? where id = ?", listed, id) == 0) {
            throw new com.ridex.platform.web.ResourceNotFoundException("Coupon not found");
        }
    }

    @Transactional(readOnly = true)
    public Evaluation preview(String code, UUID userId, BigDecimal fare) {
        String normalised = normalise(code);
        Optional<Coupon> coupon = find(normalised, false);
        if (coupon.isEmpty()) {
            return Evaluation.no(Reason.UNKNOWN, normalised);
        }
        return evaluate(coupon.get(), usesBy(coupon.get().id(), userId), fare, Instant.now());
    }

    /**
     * Use the coupon for a ride. Joins the caller's transaction (the payment), so the discount and the payment are saved together.
     * A rider who asked for a coupon that turns out not to apply simply pays the full fare ({@code eligible = false}): the ride is over,
     * and a coupon problem must never block the payment.
     */
    @Transactional
    public Evaluation redeem(String code, UUID userId, UUID rideId, BigDecimal fare) {
        String normalised = normalise(code);
        List<BigDecimal> already = jdbc.queryForList("select discount from coupon_redemptions where ride_id = ?", BigDecimal.class, rideId);
        if (!already.isEmpty()) {
            return new Evaluation(true, Reason.OK, already.get(0), normalised);   // the same ride, handled again
        }
        Optional<Coupon> locked = find(normalised, true);   // the lock: from here on, other redemptions of THIS coupon wait
        if (locked.isEmpty()) {
            return Evaluation.no(Reason.UNKNOWN, normalised);
        }
        Coupon coupon = locked.get();
        Evaluation result = evaluate(coupon, usesBy(coupon.id(), userId), fare, Instant.now());
        if (result.eligible()) {
            jdbc.update("insert into coupon_redemptions (id, coupon_id, user_id, ride_id, discount) values (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), coupon.id(), userId, rideId, result.discount());
            jdbc.update("update coupons set used_count = used_count + 1 where id = ?", coupon.id());
        }
        return result;
    }

    // ------------------------------------------------------------------ for admins

    @Transactional
    public Coupon create(NewCoupon in) {
        String code = normalise(in.code());
        if (code.length() < 3 || !code.matches("[A-Z0-9_-]{3,40}")) {
            throw new BadRequestException("A coupon code is 3 to 40 letters, digits, - or _");
        }
        if (!List.of("PERCENT", "FLAT").contains(in.discountType())) {
            throw new BadRequestException("The type is PERCENT or FLAT");
        }
        if (in.value() == null || in.value().signum() <= 0 || ("PERCENT".equals(in.discountType()) && in.value().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BadRequestException("The value must be above 0 (and at most 100 for a percentage)");
        }
        if (in.validFrom() == null || in.validUntil() == null || !in.validUntil().isAfter(in.validFrom())) {
            throw new BadRequestException("The coupon must end after it starts");
        }
        if (find(code, false).isPresent()) {
            throw new ConflictException("A coupon with this code already exists");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into coupons (id, code, description, discount_type, value, max_discount, min_fare, valid_from, valid_until, max_total_uses, per_user_limit)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, code, in.description(), in.discountType(), in.value(), in.maxDiscount(),
                in.minFare() == null ? BigDecimal.ZERO : in.minFare(), Timestamp.from(in.validFrom()), Timestamp.from(in.validUntil()),
                in.maxTotalUses(), in.perUserLimit() == null ? 1 : in.perUserLimit());
        return find(code, false).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Coupon> list() {
        return jdbc.query("select * from coupons order by created_at desc", MAPPER);
    }

    @Transactional
    public void setActive(UUID id, boolean active) {
        if (jdbc.update("update coupons set active = ? where id = ?", active, id) == 0) {
            throw new com.ridex.platform.web.ResourceNotFoundException("Coupon not found");
        }
    }

    // ------------------------------------------------------------------ helpers

    private Optional<Coupon> find(String code, boolean forUpdate) {
        return jdbc.query("select * from coupons where code = ?" + (forUpdate ? " for update" : ""), MAPPER, code).stream().findFirst();
    }

    private int usesBy(UUID couponId, UUID userId) {
        Integer n = jdbc.queryForObject("select count(*) from coupon_redemptions where coupon_id = ? and user_id = ?", Integer.class, couponId, userId);
        return n == null ? 0 : n;
    }

    static String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
