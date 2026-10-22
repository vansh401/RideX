package com.ridex.payment.extras;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.payment.ledger.AccountType;
import com.ridex.payment.ledger.LedgerAccounts;
import com.ridex.payment.ledger.LedgerService;

/**
 * A rider who cancels AFTER the driver has arrived made the driver drive for nothing, so a small fee compensates them (split like a fare:
 * the driver keeps the commission-free share). It is charged from the wallet only. A wallet can never go below zero, so when it holds less
 * than the fee the fee is WAIVED and recorded as such; we never leave a rider in debt and never block a cancellation.
 */
@Service
public class CancellationFeeService {

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final LedgerAccounts accounts;
    private final BigDecimal fee;
    private final BigDecimal commissionRate;

    public CancellationFeeService(JdbcTemplate jdbc, LedgerService ledger, LedgerAccounts accounts,
                                  @Value("${ridex.payments.cancellation-fee:30}") BigDecimal fee,
                                  @Value("${ridex.payments.commission-rate:0.20}") BigDecimal commissionRate) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.fee = fee.setScale(2, RoundingMode.HALF_UP);
        this.commissionRate = commissionRate;
    }

    /** True when the fee was actually taken. Safe to call twice for the same ride. */
    @Transactional
    public boolean onRiderCancelledAfterArrival(UUID rideId, UUID riderUserId, UUID driverUserId) {
        if (fee.signum() <= 0) {
            return false;
        }
        int inserted = jdbc.update("""
                insert into cancellation_fees (ride_id, rider_user_id, driver_user_id, amount, charged) values (?, ?, ?, ?, false)
                on conflict (ride_id) do nothing
                """, rideId, riderUserId, driverUserId, fee);
        if (inserted == 0) {
            return false;   // this cancellation was already handled
        }
        BigDecimal commission = fee.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);
        LedgerService.Posting posting = ledger.post("CANCEL_FEE", rideId.toString(), "cancel-fee-" + rideId, "Cancellation fee",
                List.of(new LedgerService.Leg(accounts.walletOf(riderUserId), fee.negate()),
                        new LedgerService.Leg(accounts.earningsOf(driverUserId), fee.subtract(commission)),
                        new LedgerService.Leg(AccountType.REVENUE_ID, commission)));
        if (posting.done()) {
            jdbc.update("update cancellation_fees set charged = true where ride_id = ?", rideId);
            return true;
        }
        jdbc.update("update cancellation_fees set note = 'Waived: the wallet held less than the fee' where ride_id = ?", rideId);
        return false;
    }
}
