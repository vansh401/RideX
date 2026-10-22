package com.ridex.payment.gateway;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A pretend payment company, reachable over HTTP at /mock-gateway/charges. No money moves, but it behaves like a real one in
 * the ways that matter:
 *
 * - It keeps its own books (mock_gateway_charges), one row per Idempotency-Key.
 * - A repeated key returns the ORIGINAL result and does not create another charge. (Stripe and Razorpay work this way. It
 *   is why "we asked twice because the first answer timed out" is safe.)
 * - It declines charges above a limit, so failures can be tried.
 * - It can MISBEHAVE on demand (see Chaos), so every protection in HttpPaymentGateway can be seen working.
 *
 * In reality this would be somebody else's server. It lives inside this service only so the project runs on one laptop.
 */
@RestController
@RequestMapping("/mock-gateway")
public class MockGateway {

    /** How the pretend gateway behaves right now. */
    public enum ChaosMode {
        /** Works normally. */
        NORMAL,
        /** Answers every call with an HTTP 500, without doing anything. */
        ERROR,
        /** Waits {@code delayMs} BEFORE doing the work, then answers. The caller may give up first: nothing was charged. */
        SLOW,
        /** Does the work (the charge is recorded!) and THEN waits {@code delayMs}. The caller times out, but the money moved. */
        SLOW_AFTER_PROCESSING
    }

    public record Chaos(ChaosMode mode, long delayMs) {
    }

    record ChargeBody(BigDecimal amount, String currency, String description) {
    }

    record ChargeResponse(boolean success, String reference, String failureReason) {
    }

    private final JdbcTemplate jdbc;
    private final BigDecimal declineAbove;
    private final AtomicReference<Chaos> chaos = new AtomicReference<>(new Chaos(ChaosMode.NORMAL, 0));
    private final AtomicInteger calls = new AtomicInteger();

    public MockGateway(JdbcTemplate jdbc, MockGatewayProperties properties) {
        this.jdbc = jdbc;
        this.declineAbove = properties.declineAboveAmount();
    }

    public Chaos chaos() {
        return chaos.get();
    }

    public void setChaos(Chaos next) {
        chaos.set(next);
    }

    /** How many HTTP calls reached the gateway (a call refused by the circuit breaker never gets here). */
    public int calls() {
        return calls.get();
    }

    @PostMapping("/charges")
    public ResponseEntity<ChargeResponse> charge(@RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody ChargeBody body)
            throws InterruptedException {
        calls.incrementAndGet();
        Chaos behaviour = chaos.get();
        switch (behaviour.mode()) {
            case ERROR -> {
                return ResponseEntity.status(500).build();
            }
            case SLOW -> Thread.sleep(behaviour.delayMs());
            default -> {
                // NORMAL and SLOW_AFTER_PROCESSING do the work first
            }
        }
        ChargeResponse response = process(idempotencyKey, body);
        if (behaviour.mode() == ChaosMode.SLOW_AFTER_PROCESSING) {
            Thread.sleep(behaviour.delayMs());   // the charge is already recorded; the answer arrives too late
        }
        return ResponseEntity.ok(response);
    }

    record RefundBody(String chargeReference, BigDecimal amount, String currency) {
    }

    /** Give money back. Same rules as a charge: one row per Idempotency-Key, so asking twice refunds once; and it can misbehave on demand. */
    @PostMapping("/refunds")
    public ResponseEntity<ChargeResponse> refund(@RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody RefundBody body)
            throws InterruptedException {
        calls.incrementAndGet();
        Chaos behaviour = chaos.get();
        switch (behaviour.mode()) {
            case ERROR -> {
                return ResponseEntity.status(500).build();
            }
            case SLOW -> Thread.sleep(behaviour.delayMs());
            default -> {
                // NORMAL and SLOW_AFTER_PROCESSING do the work first
            }
        }
        List<String> earlier = jdbc.queryForList("select reference from mock_gateway_refunds where idempotency_key = ?", String.class, idempotencyKey);
        String reference = earlier.isEmpty() ? "mock_rf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) : earlier.get(0);
        if (earlier.isEmpty()) {
            jdbc.update("""
                    insert into mock_gateway_refunds (idempotency_key, charge_reference, amount, reference) values (?, ?, ?, ?)
                    on conflict (idempotency_key) do nothing
                    """, idempotencyKey, body.chargeReference(), body.amount(), reference);
            reference = jdbc.queryForObject("select reference from mock_gateway_refunds where idempotency_key = ?", String.class, idempotencyKey);
        }
        if (behaviour.mode() == ChaosMode.SLOW_AFTER_PROCESSING) {
            Thread.sleep(behaviour.delayMs());
        }
        return ResponseEntity.ok(new ChargeResponse(true, reference, null));
    }

    private ChargeResponse process(String key, ChargeBody body) {
        // 1. Already seen this key? Return what we said last time. Do not charge again.
        List<ChargeResponse> earlier = jdbc.query(
                "select success, reference, failure_reason from mock_gateway_charges where idempotency_key = ?",
                (rs, n) -> new ChargeResponse(rs.getBoolean("success"), rs.getString("reference"), rs.getString("failure_reason")), key);
        if (!earlier.isEmpty()) {
            return earlier.get(0);
        }
        // 2. A new charge: decide, and write it down. The primary key stops two racing requests with the same key from both
        //    being recorded (the loser re-reads the winner's row).
        boolean approved = body.amount().compareTo(declineAbove) <= 0;
        String reference = approved ? "mock_ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) : null;
        String reason = approved ? null : "card_declined: amount is above the limit of this test card";
        int inserted = jdbc.update("""
                insert into mock_gateway_charges (idempotency_key, amount, currency, success, reference, failure_reason)
                values (?, ?, ?, ?, ?, ?) on conflict (idempotency_key) do nothing
                """, key, body.amount(), body.currency(), approved, reference, reason);
        if (inserted == 0) {
            return process(key, body);   // someone else just recorded it: return their result
        }
        return new ChargeResponse(approved, reference, reason);
    }
}
