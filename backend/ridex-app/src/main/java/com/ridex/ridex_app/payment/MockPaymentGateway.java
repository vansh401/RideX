package com.ridex.ridex_app.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A pretend gateway: no money moves, but it behaves like a real one in the ways that matter.
 *
 * - It keeps its own books (mock_gateway_charges), one row per idempotency key.
 * - A repeated key returns the ORIGINAL result and does not create another charge. (Stripe and Razorpay work
 *   this way. It is why "we asked twice because the first answer timed out" is safe.)
 * - It declines charges above a limit, so failures can be tried: ridex.payments.mock.decline-above-amount.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    private final JdbcTemplate jdbc;
    private final BigDecimal declineAbove;

    public MockPaymentGateway(JdbcTemplate jdbc,
                              @Value("${ridex.payments.mock.decline-above-amount:5000}") BigDecimal declineAbove) {
        this.jdbc = jdbc;
        this.declineAbove = declineAbove;
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        // 1. Already seen this key? Return what we said last time. Do not charge again.
        List<ChargeResult> earlier = jdbc.query(
                "select success, reference, failure_reason from mock_gateway_charges where idempotency_key = ?",
                (rs, n) -> new ChargeResult(rs.getBoolean("success"), rs.getString("reference"), rs.getString("failure_reason")),
                request.idempotencyKey());
        if (!earlier.isEmpty()) {
            return earlier.get(0);
        }

        // 2. A new charge: decide, and write it down. The primary key stops two racing requests with the same key
        //    from both being recorded (the loser re-reads the winner's row).
        boolean approved = request.amount().compareTo(declineAbove) <= 0;
        String reference = approved ? "mock_ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) : null;
        String reason = approved ? null : "card_declined: amount is above the limit of this test card";
        int inserted = jdbc.update("""
                insert into mock_gateway_charges (idempotency_key, amount, currency, success, reference, failure_reason)
                values (?, ?, ?, ?, ?, ?) on conflict (idempotency_key) do nothing
                """, request.idempotencyKey(), request.amount(), request.currency(), approved, reference, reason);
        if (inserted == 0) {
            return charge(request);   // someone else just recorded it: return their result
        }
        return new ChargeResult(approved, reference, reason);
    }
}
