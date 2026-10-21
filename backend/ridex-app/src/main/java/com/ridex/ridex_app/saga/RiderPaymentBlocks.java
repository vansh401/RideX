package com.ridex.ridex_app.saga;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Riders who owe money for a finished ride. This is the core's memory of the "compensation" in the ride-payment saga:
 * the ride cannot be undone, so the rider cannot book again until the payment is settled.
 */
@Component
public class RiderPaymentBlocks {

    private final JdbcTemplate jdbc;

    public RiderPaymentBlocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The oldest unpaid ride of this rider, if any. */
    public Optional<UUID> firstUnpaidRide(UUID riderUserId) {
        List<UUID> rides = jdbc.query(
                "select ride_id from rider_payment_blocks where rider_user_id = ? order by created_at limit 1",
                (rs, n) -> rs.getObject("ride_id", UUID.class), riderUserId);
        return rides.stream().findFirst();
    }

    /** Idempotent: blocking twice for the same ride changes nothing. */
    public void block(UUID riderUserId, UUID rideId, BigDecimal amount, String currency, String reason) {
        jdbc.update("""
                insert into rider_payment_blocks (rider_user_id, ride_id, amount, currency, reason) values (?, ?, ?, ?, ?)
                on conflict (rider_user_id, ride_id) do update set reason = excluded.reason
                """, riderUserId, rideId, amount, currency, reason);
    }

    /** Idempotent: releasing a block that is not there changes nothing. */
    public void release(UUID riderUserId, UUID rideId) {
        jdbc.update("delete from rider_payment_blocks where rider_user_id = ? and ride_id = ?", riderUserId, rideId);
    }
}
