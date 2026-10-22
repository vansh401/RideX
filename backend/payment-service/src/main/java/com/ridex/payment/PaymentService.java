package com.ridex.payment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.payment.coupon.CouponService;
import com.ridex.payment.earnings.EarningsService;
import com.ridex.payment.gateway.GatewayProperties;
import com.ridex.payment.gateway.GatewayUnavailableException;
import com.ridex.payment.gateway.PaymentGateway;
import com.ridex.payment.ledger.AccountType;
import com.ridex.payment.ledger.LedgerAccounts;
import com.ridex.payment.ledger.LedgerService;
import com.ridex.platform.contracts.EventEnvelope;
import com.ridex.platform.contracts.PaymentEventData;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.kafka.OutboxService;
import com.ridex.platform.web.LogContext;
import com.ridex.platform.web.PageResponse;
import com.ridex.platform.web.Paging;
import com.ridex.platform.web.ResourceNotFoundException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Charges riders for completed rides and tells everybody the result (as an event).
 *
 * How "no double charging" is achieved, in layers:
 *  1. The consumer handles each event at most once in effect (processed_events).
 *  2. The database allows ONE payment per ride (unique ride_id).
 *  3. A payment that is not PENDING is never charged again by the automatic flow.
 *  4. Every charge carries an idempotency key; the gateway returns the original result for a repeated key. This is what
 *     makes it safe to try again after a timeout, when we do not know whether the first call went through.
 * Any single layer failing is caught by the next one.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository repository;
    private final PaymentGateway gateway;
    private final OutboxService outbox;
    private final GatewayProperties gatewayProperties;
    private final MeterRegistry metrics;
    private final LedgerService ledger;
    private final LedgerAccounts accounts;
    private final CouponService coupons;
    private final EarningsService earnings;
    private final BigDecimal commissionRate;

    public PaymentService(PaymentRepository repository, PaymentGateway gateway, OutboxService outbox, GatewayProperties gatewayProperties,
                          MeterRegistry metrics, LedgerService ledger, LedgerAccounts accounts, CouponService coupons, EarningsService earnings,
                          @Value("${ridex.payments.commission-rate:0.20}") BigDecimal commissionRate) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.coupons = coupons;
        this.earnings = earnings;
        this.commissionRate = commissionRate;
        this.repository = repository;
        this.gateway = gateway;
        this.outbox = outbox;
        this.gatewayProperties = gatewayProperties;
        this.metrics = metrics;
        for (String outcome : new String[] {"succeeded", "failed", "gateway_unavailable"}) {
            Counter.builder("ridex.payments").description("Payment attempts by outcome").tag("outcome", outcome).register(metrics);
        }
    }

    /** Called when a ride completes, without a coupon. */
    @Transactional
    public Payment chargeForCompletedRide(UUID rideId, UUID riderUserId, UUID driverUserId, BigDecimal amount, String currency) {
        return chargeForCompletedRide(rideId, riderUserId, driverUserId, amount, currency, null);
    }

    /**
     * Called when a ride completes. Safe to call twice for the same ride. If the rider asked for a coupon it is redeemed in the SAME transaction as
     * the payment: either the discount and the payment both happen, or neither does (and the event is handled again).
     */
    @Transactional
    public Payment chargeForCompletedRide(UUID rideId, UUID riderUserId, UUID driverUserId, BigDecimal amount, String currency, String couponCode) {
        Payment existing = repository.findByRideId(rideId).orElse(null);
        if (existing != null && existing.getStatus() != PaymentStatus.PENDING) {
            return existing;   // already charged or already failed: the automatic flow never charges twice
        }
        Payment payment = existing;
        if (payment == null) {
            payment = repository.saveAndFlush(Payment.start(rideId, riderUserId, driverUserId, amount, currency, Instant.now()));
            if (couponCode != null && !couponCode.isBlank()) {
                CouponService.Evaluation coupon = coupons.redeem(couponCode, riderUserId, rideId, amount);
                if (coupon.eligible()) {
                    payment.applyCoupon(coupon.code(), coupon.discount());
                } else {
                    log.info("Coupon not applied: {}", coupon.reason());   // the rider pays the full fare; a coupon problem never blocks a payment
                }
            }
        }
        attempt(payment);
        return payment;
    }

    /** The rider tries again after a failure. A new attempt number means a new idempotency key. */
    @Transactional
    public PaymentResponse retry(UUID riderUserId, UUID rideId) {
        Payment payment = repository.findByRideId(rideId)
                .filter(p -> p.getRiderUserId().equals(riderUserId))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        payment.retry(Instant.now());   // refuses anything but FAILED
        attempt(payment);
        return PaymentResponse.from(payment);
    }

    /** Another try for a payment that was waiting because the gateway did not answer. Same idempotency key as before. */
    @Transactional
    public void retryPending(UUID paymentId) {
        Payment payment = repository.findById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        attempt(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse forRide(UUID userId, boolean admin, UUID rideId) {
        return repository.findByRideId(rideId)
                .filter(p -> admin || p.getRiderUserId().equals(userId) || p.getDriverUserId().equals(userId))
                .map(PaymentResponse::from)
                // Not there yet (the ride just completed and the event is still travelling) looks like "not found"
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> list(PaymentStatus status, int page, int size) {
        Pageable pageable = Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(status == null ? repository.findAll(pageable) : repository.findByStatus(status, pageable),
                PaymentResponse::from);
    }

    private void attempt(Payment payment) {
        try (LogContext.Scope ignored = LogContext.ride(payment.getRideId())) {
            attemptCharge(payment);
        }
    }

    private void attemptCharge(Payment payment) {
        Instant now = Instant.now();
        BigDecimal net = payment.netAmount();
        UUID driverAccount = accounts.earningsOf(payment.getDriverUserId());

        // 1. A coupon that covered the whole fare: nothing to charge, only to book.
        if (net.signum() == 0) {
            bookRidePayment(payment, null, driverAccount);
            succeed(payment, null, "PROMO", now);
            return;
        }
        // 2. The wallet first: instant, free, and no card involved. The ledger locks the wallet and checks the balance in one step, so two rides
        //    completing at once cannot both spend the same money; if it is not enough the answer is INSUFFICIENT_FUNDS and the card is used instead.
        UUID wallet = accounts.walletOf(payment.getRiderUserId());
        if (bookRidePayment(payment, wallet, driverAccount)) {
            succeed(payment, null, "WALLET", now);
            return;
        }

        // 3. The card, through the gateway.
        PaymentGateway.ChargeResult result;
        try {
            result = gateway.charge(new PaymentGateway.ChargeRequest(
                    payment.getIdempotencyKey(), net, payment.getCurrency(), "RideX ride " + payment.getRideId()));
        } catch (GatewayUnavailableException e) {
            metrics.counter("ridex.payments", "outcome", "gateway_unavailable").increment();
            // NO answer: we do not know whether the gateway charged. Do not guess. Wait, then ask again with the same key.
            boolean gaveUp = payment.recordGatewayOutage(now, Duration.ofMillis(gatewayProperties.outageBackoffMs()), gatewayProperties.maxOutages());
            log.warn("Gateway did not answer for payment {} (outage {}){}", payment.getId(), payment.getGatewayOutages(),
                    gaveUp ? ": giving up" : ": will retry");
            if (gaveUp) {
                publish(payment, EventEnvelope.PAYMENT_FAILED);
            }
            return;
        }
        if (result.success()) {
            bookRidePayment(payment, null, driverAccount);   // the money came in from the gateway: GATEWAY pays the legs
            succeed(payment, result.reference(), "GATEWAY", now);
        } else {
            payment.markFailed(result.failureReason(), now);
            metrics.counter("ridex.payments", "outcome", "failed").increment();
            log.info("Payment failed for attempt {}", payment.getAttempts());   // no amounts, no card or bank data
            publish(payment, EventEnvelope.PAYMENT_FAILED);
        }
    }

    private void succeed(Payment payment, String reference, String method, Instant now) {
        payment.markSucceeded(reference, now);
        payment.paidWith(method);
        metrics.counter("ridex.payments", "outcome", "succeeded").increment();
        log.info("Payment succeeded for attempt {} ({})", payment.getAttempts(), method);   // no amounts, no card or bank data
        publish(payment, EventEnvelope.PAYMENT_SUCCEEDED);
        earnings.forgetAfterCommit(payment.getDriverUserId());   // the driver's cached earnings are out of date now
    }

    /**
     * The ledger side of a successful ride payment. Four legs that add up to zero:
     *   the payer (the rider's wallet, or GATEWAY when a card paid)   -net
     *   PROMO (the company pays what the coupon took off)               -discount
     *   the driver's earnings                                          +fare minus commission
     *   REVENUE (the company's commission)                             +commission
     * Keyed by the ride, so booking the same ride twice (a retry, a duplicate event) posts once.
     *
     * @param wallet the wallet to pay from, or null when somebody else (the gateway, or a coupon) paid
     * @return false only when a wallet was asked to pay and did not hold enough
     */
    private boolean bookRidePayment(Payment payment, UUID wallet, UUID driverAccount) {
        UUID payer = wallet != null ? wallet : AccountType.GATEWAY_ID;
        List<LedgerService.Leg> legs = ridePaymentLegs(payer, payment.netAmount(), payment.getDiscount(), payment.getAmount(), commissionRate,
                driverAccount);
        LedgerService.Posting posting = ledger.post("RIDE_PAYMENT", payment.getRideId().toString(), "ride-payment-" + payment.getRideId(),
                "Payment for a ride", legs);
        return posting.done();
    }

    /** Pure arithmetic, tested on its own. The legs always add up to zero because net + discount = fare = driver share + commission. */
    static List<LedgerService.Leg> ridePaymentLegs(UUID payer, BigDecimal net, BigDecimal discount, BigDecimal fare, BigDecimal commissionRate,
                                                   UUID driverAccount) {
        BigDecimal commission = fare.multiply(commissionRate).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal driverShare = fare.subtract(commission);
        List<LedgerService.Leg> legs = new java.util.ArrayList<>();
        if (net.signum() > 0) {
            legs.add(new LedgerService.Leg(payer, net.negate()));
        }
        if (discount.signum() > 0) {
            legs.add(new LedgerService.Leg(AccountType.PROMO_ID, discount.negate()));
        }
        legs.add(new LedgerService.Leg(driverAccount, driverShare));
        if (commission.signum() > 0) {
            legs.add(new LedgerService.Leg(AccountType.REVENUE_ID, commission));
        }
        return legs;
    }

    private void publish(Payment payment, String type) {
        // Saved in THIS transaction with the payment itself (outbox), published to Kafka afterwards
        outbox.append(Topics.PAYMENT_EVENTS, payment.getRideId().toString(), type,
                new PaymentEventData(payment.getId(), payment.getRideId(), payment.getRiderUserId(), payment.getDriverUserId(),
                        payment.getAmount(), payment.getCurrency(), payment.getStatus().name(), payment.getFailureReason(),
                        payment.getAttempts(), payment.getGatewayReference(), payment.getDiscount(), payment.getMethod(), null));
    }
}
