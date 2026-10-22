package com.ridex.payment.online;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.payment.Payment;
import com.ridex.payment.PaymentRepository;
import com.ridex.payment.PaymentService;
import com.ridex.payment.PaymentStatus;
import com.ridex.payment.wallet.WalletService;
import com.ridex.platform.web.BadRequestException;
import com.ridex.platform.web.ConflictException;
import com.ridex.platform.web.ResourceNotFoundException;


/**
 * Paying online through the provider's checkout. The only thing an online payment ever does is put money into the rider's WALLET:
 *
 *   TOPUP  the rider adds money.
 *   RIDE   the rider pays the part of a finished ride that the wallet could not cover; once the money is in the wallet the failed
 *          payment is simply retried, and the normal wallet-first logic books it.
 *
 * Confirmation can arrive twice (the browser tells us AND the provider's webhook does, in any order, or the browser never does because the
 * tab was closed). The provider's order id is unique and the ledger posts each key once, so the wallet is credited exactly once.
 */
@Service
public class OnlinePaymentService {

    private static final Logger log = LoggerFactory.getLogger(OnlinePaymentService.class);
    private static final BigDecimal ONE_RUPEE = new BigDecimal("1.00");

    public record Order(UUID id, String provider, String publicKey, String orderId, String checkoutUrl, BigDecimal amount, String currency, String purpose, UUID rideId) {
    }

    public record Confirmation(String status, String purpose, BigDecimal amount, UUID rideId) {
    }

    private record Row(UUID id, UUID userId, String purpose, UUID rideId, BigDecimal amount, String status) {
    }

    private final JdbcTemplate jdbc;
    private final CheckoutProvider provider;
    private final WalletService wallets;
    private final PaymentService payments;
    private final PaymentRepository paymentRepository;

    public OnlinePaymentService(JdbcTemplate jdbc, CheckoutProvider provider, WalletService wallets, PaymentService payments,
                                PaymentRepository paymentRepository) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.wallets = wallets;
        this.payments = payments;
        this.paymentRepository = paymentRepository;
    }

    public CheckoutProvider provider() {
        return provider;
    }

    @Transactional
    public Order createOrder(UUID userId, String purpose, BigDecimal amount, UUID rideId) {
        BigDecimal toPay;
        if ("TOPUP".equals(purpose)) {
            wallets.checkTopUpAllowed(userId, amount);
            toPay = amount;
        } else if ("RIDE".equals(purpose)) {
            if (rideId == null) {
                throw new BadRequestException("rideId is required to pay for a ride");
            }
            Payment payment = paymentRepository.findByRideId(rideId).filter(p -> p.getRiderUserId().equals(userId))
                    .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
            if (payment.getStatus() != PaymentStatus.FAILED) {
                throw new ConflictException("This ride does not need a payment");
            }
            BigDecimal shortfall = payment.netAmount().subtract(wallets.wallet(userId).balance());
            if (shortfall.signum() <= 0) {
                throw new ConflictException("Your wallet already covers this ride. Press retry to pay it");
            }
            toPay = shortfall.max(ONE_RUPEE).setScale(2, RoundingMode.UP);
        } else {
            throw new BadRequestException("purpose must be TOPUP or RIDE");
        }
        UUID id = UUID.randomUUID();
        String returnPath = "RIDE".equals(purpose) ? "/rides/" + rideId : "/rider/wallet";   // where a hosted checkout brings the rider back to
        CheckoutProvider.ProviderOrder order = provider.createOrder(toPay, "INR", "rx_" + id.toString().substring(0, 8), returnPath);
        jdbc.update("""
                insert into online_payments (id, user_id, purpose, ride_id, amount, currency, provider, provider_order_id)
                values (?, ?, ?, ?, ?, 'INR', ?, ?)
                """, id, userId, purpose, rideId, toPay, provider.name(), order.orderId());
        return new Order(id, provider.name(), provider.publicKey(), order.orderId(), order.checkoutUrl(), toPay, "INR", purpose, rideId);
    }

    /** The browser says the payment finished. The signature proves the provider said so. */
    @Transactional
    public Confirmation confirm(UUID userId, String orderId, String paymentId, String signature) {
        Row row = lock(orderId).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!row.userId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found");   // somebody else's order looks like no order
        }
        if (!provider.verify(orderId, paymentId, signature)) {
            throw new BadRequestException("The payment could not be verified");
        }
        return settle(row, orderId, paymentId);
    }

    /** The provider says so itself (a webhook). Safe to receive before, after or instead of the browser's confirmation. */
    @Transactional
    public void onWebhook(String rawBody, String signature) {
        // The provider checks the signature (throws for a bad one) and says whether this event means "paid". Never log the body: it holds payment details.
        provider.paidFromWebhook(rawBody, signature).ifPresent(paid -> lock(paid.orderId()).ifPresent(row -> settle(row, paid.orderId(), paid.paymentId())));
    }

    private Confirmation settle(Row row, String orderId, String paymentId) {
        if ("PAID".equals(row.status())) {
            return new Confirmation("PAID", row.purpose(), row.amount(), row.rideId());   // already credited: nothing more to do
        }
        jdbc.update("update online_payments set status = 'PAID', provider_payment_id = ?, paid_at = ? where id = ?", paymentId, java.sql.Timestamp.from(Instant.now()), row.id());
        wallets.creditFromGateway(row.userId(), row.amount(), paymentId, "online-" + orderId,
                "RIDE".equals(row.purpose()) ? "Online payment for a ride" : "Wallet top-up");
        if ("RIDE".equals(row.purpose()) && row.rideId() != null) {
            try {
                payments.retry(row.userId(), row.rideId());   // the wallet now holds the money, so the normal flow books the ride
            } catch (ConflictException | ResourceNotFoundException e) {
                log.info("The ride payment needed no retry: {}", e.getMessage());   // already paid, or already succeeded some other way
            }
        }
        return new Confirmation("PAID", row.purpose(), row.amount(), row.rideId());
    }

    private java.util.Optional<Row> lock(String orderId) {
        List<Row> rows = jdbc.query("select id, user_id, purpose, ride_id, amount, status from online_payments where provider_order_id = ? for update",
                (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("purpose"),
                        rs.getObject("ride_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("status")), orderId);
        return rows.stream().findFirst();
    }
}
