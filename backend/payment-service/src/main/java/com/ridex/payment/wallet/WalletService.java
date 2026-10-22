package com.ridex.payment.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.payment.gateway.GatewayUnavailableException;
import com.ridex.payment.gateway.PaymentGateway;
import com.ridex.payment.ledger.AccountType;
import com.ridex.payment.ledger.LedgerAccounts;
import com.ridex.payment.ledger.LedgerService;
import com.ridex.platform.web.BadRequestException;
import com.ridex.platform.web.PageResponse;
import com.ridex.platform.web.Paging;
import com.ridex.platform.web.ServiceUnavailableException;

/**
 * The rider's wallet: prepaid money that pays for rides without a card, instantly. Its balance and its statement come from the ledger, so
 * they cannot drift from the truth.
 *
 * TOP-UP is "a card payment whose result is wallet money": charge the card through the gateway, then post GATEWAY -> wallet in the ledger.
 * It is IDEMPOTENT end to end, because a phone on a bad network presses the button twice: the client sends an Idempotency-Key; the gateway
 * returns the original result for a repeated key (no second charge), and the ledger posts a repeated key once (no second credit).
 */
@Service
public class WalletService {

    public record Wallet(BigDecimal balance, String currency) {
    }

    public record Entry(long id, String type, BigDecimal amount, BigDecimal balanceAfter, String description, String reference, Instant at) {
    }

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final LedgerAccounts accounts;
    private final PaymentGateway gateway;
    private final BigDecimal minTopUp;
    private final BigDecimal maxTopUp;
    private final BigDecimal maxBalance;

    public WalletService(JdbcTemplate jdbc, LedgerService ledger, LedgerAccounts accounts, PaymentGateway gateway,
                         @Value("${ridex.payments.wallet.min-top-up:10}") BigDecimal minTopUp,
                         @Value("${ridex.payments.wallet.max-top-up:10000}") BigDecimal maxTopUp,
                         @Value("${ridex.payments.wallet.max-balance:50000}") BigDecimal maxBalance) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.gateway = gateway;
        this.minTopUp = minTopUp;
        this.maxTopUp = maxTopUp;
        this.maxBalance = maxBalance;
    }

    @Transactional(readOnly = true)
    public Wallet wallet(UUID userId) {
        List<BigDecimal> balance = jdbc.queryForList("select balance from ledger_accounts where type = 'USER_WALLET' and owner_id = ?", BigDecimal.class, userId);
        return new Wallet(balance.isEmpty() ? BigDecimal.ZERO.setScale(2) : balance.get(0), "INR");
    }

    @Transactional
    public Wallet topUp(UUID userId, BigDecimal amount, String idempotencyKey) {
        if (amount == null || amount.scale() > 2 || amount.compareTo(minTopUp) < 0 || amount.compareTo(maxTopUp) > 0) {
            throw new BadRequestException("A top-up is between " + minTopUp + " and " + maxTopUp + " (at most 2 decimals)");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80) {
            throw new BadRequestException("The Idempotency-Key header is required (any unique text, up to 80 characters)");
        }
        if (wallet(userId).balance().add(amount).compareTo(maxBalance) > 0) {
            throw new BadRequestException("A wallet can hold at most " + maxBalance);
        }
        PaymentGateway.ChargeResult charge;
        try {
            charge = gateway.charge(new PaymentGateway.ChargeRequest("topup-" + userId + "-" + idempotencyKey, amount, "INR", "RideX wallet top-up"));
        } catch (GatewayUnavailableException e) {
            // No answer: we do not know whether the card was charged. The client retries with the SAME key, which is safe.
            throw new ServiceUnavailableException("The payment provider is not available right now. Please try again", 10);
        }
        if (!charge.success()) {
            throw new BadRequestException("The payment was declined: " + charge.failureReason());
        }
        UUID wallet = accounts.walletOf(userId);
        ledger.post("TOPUP", charge.reference(), "topup-" + userId + "-" + idempotencyKey, "Wallet top-up",
                List.of(new LedgerService.Leg(AccountType.GATEWAY_ID, amount.negate()), new LedgerService.Leg(wallet, amount)));
        return wallet(userId);
    }

    @Transactional(readOnly = true)
    public PageResponse<Entry> statement(UUID userId, int page, int size) {
        Pageable pageable = Paging.of(page, size, Sort.unsorted());
        List<UUID> account = jdbc.queryForList("select id from ledger_accounts where type = 'USER_WALLET' and owner_id = ?", UUID.class, userId);
        if (account.isEmpty()) {
            return PageResponse.from(new PageImpl<Entry>(List.of(), pageable, 0), e -> e);
        }
        Long total = jdbc.queryForObject("select count(*) from ledger_entries where account_id = ?", Long.class, account.get(0));
        List<Entry> rows = jdbc.query("""
                select e.id, t.type, e.amount, e.balance_after, t.description, t.reference, e.created_at
                from ledger_entries e join ledger_transactions t on t.id = e.transaction_id
                where e.account_id = ? order by e.id desc limit ? offset ?
                """, (rs, n) -> new Entry(rs.getLong("id"), rs.getString("type"), rs.getBigDecimal("amount"), rs.getBigDecimal("balance_after"),
                rs.getString("description"), rs.getString("reference"), rs.getTimestamp("created_at").toInstant()),
                account.get(0), pageable.getPageSize(), pageable.getOffset());
        return PageResponse.from(new PageImpl<>(rows, pageable, total == null ? 0 : total), e -> e);
    }
}
