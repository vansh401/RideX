package com.ridex.payment.extras;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.payment.Payment;
import com.ridex.payment.PaymentRepository;
import com.ridex.payment.PaymentStatus;
import com.ridex.payment.earnings.EarningsService;
import com.ridex.payment.ledger.LedgerAccounts;
import com.ridex.payment.ledger.LedgerService;
import com.ridex.platform.web.BadRequestException;
import com.ridex.platform.web.ConflictException;
import com.ridex.platform.web.ResourceNotFoundException;

/** A rider thanks the driver after a paid ride: money moves from the rider's wallet to the driver's earnings, in full (no commission on a tip). One tip per ride. */
@Service
public class TipService {

    static final BigDecimal MIN = new BigDecimal("1");
    static final BigDecimal MAX = new BigDecimal("500");

    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final LedgerAccounts accounts;
    private final EarningsService earnings;

    public TipService(JdbcTemplate jdbc, PaymentRepository payments, LedgerService ledger, LedgerAccounts accounts, EarningsService earnings) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.ledger = ledger;
        this.accounts = accounts;
        this.earnings = earnings;
    }

    @Transactional
    public BigDecimal tip(UUID riderUserId, UUID rideId, BigDecimal amount) {
        if (amount == null || amount.scale() > 2 || amount.compareTo(MIN) < 0 || amount.compareTo(MAX) > 0) {
            throw new BadRequestException("A tip is between " + MIN + " and " + MAX + " rupees");
        }
        Payment payment = payments.findByRideId(rideId).filter(p -> p.getRiderUserId().equals(riderUserId))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new ConflictException("You can tip after the ride is paid");
        }
        int inserted = jdbc.update("insert into tips (ride_id, rider_user_id, driver_user_id, amount) values (?, ?, ?, ?) on conflict (ride_id) do nothing",
                rideId, riderUserId, payment.getDriverUserId(), amount);
        if (inserted == 0) {
            throw new ConflictException("You already tipped for this ride");
        }
        LedgerService.Posting posting = ledger.post("TIP", rideId.toString(), "tip-" + rideId, "Tip for the driver",
                List.of(new LedgerService.Leg(accounts.walletOf(riderUserId), amount.negate()),
                        new LedgerService.Leg(accounts.earningsOf(payment.getDriverUserId()), amount)));
        if (!posting.done()) {
            throw new BadRequestException("Not enough money in your wallet for this tip. Add money first");   // rolls the tip row back too
        }
        earnings.forgetAfterCommit(payment.getDriverUserId());
        return amount;
    }

    @Transactional(readOnly = true)
    public BigDecimal tipFor(UUID rideId) {
        List<BigDecimal> rows = jdbc.queryForList("select amount from tips where ride_id = ?", BigDecimal.class, rideId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
