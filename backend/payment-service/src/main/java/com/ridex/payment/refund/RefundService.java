package com.ridex.payment.refund;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.payment.Payment;
import com.ridex.payment.PaymentRepository;
import com.ridex.payment.PaymentStatus;
import com.ridex.payment.gateway.GatewayUnavailableException;
import com.ridex.payment.gateway.PaymentGateway;
import com.ridex.payment.ledger.AccountType;
import com.ridex.payment.ledger.LedgerAccounts;
import com.ridex.payment.ledger.LedgerService;
import com.ridex.platform.contracts.EventEnvelope;
import com.ridex.platform.contracts.PaymentEventData;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.kafka.OutboxService;
import com.ridex.platform.web.ConflictException;
import com.ridex.platform.web.ResourceNotFoundException;

/**
 * Refunds: giving money back after the fact, as a COMPENSATION (a new, opposite movement: history is never edited).
 *
 * RULES
 *  - Only a payment that SUCCEEDED can be refunded, and never more than the rider actually paid (the fare minus the coupon) minus earlier
 *    refunds. The limit is enforced in the Payment entity, and again by a CHECK constraint in the database.
 *  - Idempotent by key: one refund per complaint (or per admin request). A complaint event delivered twice refunds once.
 *  - The company bears the cost of a refund (REVENUE pays). Taking it back from the driver is a policy decision, not a technical one.
 *
 * WHERE THE MONEY GOES
 *  WALLET    instant: REVENUE -> the rider's wallet. Always possible; and the only option when the ride was paid from the wallet.
 *  ORIGINAL  back to the card, through the gateway. The gateway may be down, so this is the same story as a charge: the refund stays PENDING and
 *            is retried with the SAME idempotency key (the gateway refunds once however often it is asked), up to a limit, then FAILED.
 *            The amount is RESERVED on the payment from the start, so two refunds cannot together exceed what was paid while one is waiting.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    static final int MAX_ATTEMPTS = 5;
    private static final Duration BACKOFF = Duration.ofSeconds(5);

    public record Refund(UUID id, UUID paymentId, UUID rideId, UUID userId, BigDecimal amount, String reason, String source, String destination,
                         String status, int attempts, String failureReason, Instant createdAt) {
    }

    private static final RowMapper<Refund> MAPPER = (rs, n) -> new Refund(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
            rs.getObject("ride_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("reason"),
            rs.getString("source"), rs.getString("destination"), rs.getString("status"), rs.getInt("attempts"), rs.getString("failure_reason"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final LedgerService ledger;
    private final LedgerAccounts accounts;
    private final OutboxService outbox;

    public RefundService(JdbcTemplate jdbc, PaymentRepository payments, PaymentGateway gateway, LedgerService ledger, LedgerAccounts accounts,
                         OutboxService outbox) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.gateway = gateway;
        this.ledger = ledger;
        this.accounts = accounts;
        this.outbox = outbox;
    }

    /**
     * @param amount            how much; null means everything that can still be refunded
     * @param destination       WALLET or ORIGINAL (honoured only if the ride was paid by card)
     * @param expectedRider     if not null, the refund must go to this person (a complaint refunds only the rider who complained)
     */
    @Transactional
    public Refund request(UUID rideId, BigDecimal amount, String reason, String source, String sourceRef, String destination, String idempotencyKey,
                          UUID expectedRider) {
        List<Refund> earlier = jdbc.query("select * from refunds where idempotency_key = ?", MAPPER, idempotencyKey);
        if (!earlier.isEmpty()) {
            return earlier.get(0);
        }
        Payment payment = payments.findByRideId(rideId).orElseThrow(() -> new ResourceNotFoundException("There is no payment for this ride"));
        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new ConflictException("Only a payment that succeeded can be refunded");
        }
        if (expectedRider != null && !payment.getRiderUserId().equals(expectedRider)) {
            throw new ConflictException("A refund goes to the rider who paid");
        }
        BigDecimal toRefund = amount == null ? payment.refundable() : amount;
        Instant now = Instant.now();
        payment.recordRefund(toRefund, now);   // reserves the amount; refuses more than what is left of what was paid

        boolean toCard = "ORIGINAL".equals(destination) && "GATEWAY".equals(payment.getMethod()) && payment.getGatewayReference() != null;
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into refunds (id, payment_id, ride_id, user_id, amount, reason, source, source_ref, destination, status, idempotency_key)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, id, payment.getId(), rideId, payment.getRiderUserId(), toRefund, reason, source, sourceRef, toCard ? "ORIGINAL" : "WALLET", idempotencyKey);
        execute(id, payment);
        return jdbc.queryForObject("select * from refunds where id = ?", MAPPER, id);
    }

    /** Try to complete one pending refund. Safe to call again for the same refund (the keys make every step idempotent). */
    private void execute(UUID refundId, Payment payment) {
        Refund refund = jdbc.queryForObject("select * from refunds where id = ?", MAPPER, refundId);
        Instant now = Instant.now();
        String key = "refund-" + refundId;
        String gatewayReference = null;
        if ("ORIGINAL".equals(refund.destination())) {
            PaymentGateway.RefundResult result;
            try {
                result = gateway.refund(new PaymentGateway.RefundRequest(key, payment.getGatewayReference(), refund.amount(), payment.getCurrency()));
            } catch (GatewayUnavailableException e) {
                int attempts = refund.attempts() + 1;
                if (attempts >= MAX_ATTEMPTS) {
                    fail(refund, payment, "The payment provider could not be reached", now);
                } else {
                    jdbc.update("update refunds set attempts = ?, next_attempt_at = ?, updated_at = ? where id = ?", attempts,
                            Timestamp.from(now.plus(BACKOFF.multipliedBy(attempts))), Timestamp.from(now), refundId);
                    log.warn("Refund {} waits for the payment provider (attempt {})", refundId, attempts);
                }
                return;
            }
            if (!result.success()) {
                fail(refund, payment, result.failureReason(), now);
                return;
            }
            gatewayReference = result.reference();
            ledger.post("REFUND", refund.rideId().toString(), key, "Refund to the card", List.of(
                    new LedgerService.Leg(AccountType.REVENUE_ID, refund.amount().negate()), new LedgerService.Leg(AccountType.GATEWAY_ID, refund.amount())));
        } else {
            UUID wallet = accounts.walletOf(refund.userId());
            ledger.post("REFUND", refund.rideId().toString(), key, "Refund to the wallet", List.of(
                    new LedgerService.Leg(AccountType.REVENUE_ID, refund.amount().negate()), new LedgerService.Leg(wallet, refund.amount())));
        }
        jdbc.update("update refunds set status = 'SUCCEEDED', next_attempt_at = null, gateway_reference = ?, updated_at = ? where id = ?",
                gatewayReference, Timestamp.from(now), refundId);
        log.info("Refund {} succeeded", refundId);   // no amounts
        outbox.append(Topics.PAYMENT_EVENTS, payment.getRideId().toString(), EventEnvelope.PAYMENT_REFUNDED,
                new PaymentEventData(payment.getId(), payment.getRideId(), payment.getRiderUserId(), payment.getDriverUserId(), payment.getAmount(),
                        payment.getCurrency(), payment.getStatus().name(), null, payment.getAttempts(), payment.getGatewayReference(),
                        payment.getDiscount(), payment.getMethod(), refund.amount()));
    }

    private void fail(Refund refund, Payment payment, String reason, Instant now) {
        payment.undoRefund(refund.amount(), now);   // the reserved amount can be refunded some other way
        jdbc.update("update refunds set status = 'FAILED', next_attempt_at = null, failure_reason = ?, updated_at = ? where id = ?",
                reason, Timestamp.from(now), refund.id());
        log.warn("Refund {} failed: {}", refund.id(), reason);
    }

    /** Pending refunds whose waiting time is over are tried again. */
    @Scheduled(fixedDelayString = "${ridex.payments.retry-interval-ms:3000}")
    public void retryDue() {
        try {
            List<UUID> due = jdbc.queryForList("select id from refunds where status = 'PENDING' and next_attempt_at is not null and next_attempt_at <= now() order by next_attempt_at limit 20", UUID.class);
            for (UUID id : due) {
                retryOne(id);
            }
        } catch (RuntimeException e) {
            log.warn("Refund retry pass failed: {}", e.getClass().getSimpleName());
        }
    }

    @Transactional
    public void retryOne(UUID refundId) {
        Refund refund = jdbc.queryForObject("select * from refunds where id = ?", MAPPER, refundId);
        if (!"PENDING".equals(refund.status())) {
            return;
        }
        execute(refundId, payments.findById(refund.paymentId()).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<Refund> latest(UUID rideId) {
        return rideId == null ? jdbc.query("select * from refunds order by created_at desc limit 50", MAPPER)
                : jdbc.query("select * from refunds where ride_id = ? order by created_at desc", MAPPER, rideId);
    }
}
