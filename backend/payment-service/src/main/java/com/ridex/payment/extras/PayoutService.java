package com.ridex.payment.extras;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.payment.earnings.EarningsService;
import com.ridex.payment.ledger.AccountType;
import com.ridex.payment.ledger.LedgerAccounts;
import com.ridex.payment.ledger.LedgerService;
import com.ridex.platform.web.BadRequestException;
import com.ridex.platform.web.ConflictException;
import com.ridex.platform.web.PageResponse;
import com.ridex.platform.web.Paging;
import com.ridex.platform.web.ResourceNotFoundException;

/**
 * A driver withdraws earnings. Real bank payouts are not free (they need the payment provider's payouts product), so the transfer itself is
 * done by an administrator OUTSIDE the system (UPI/bank) and then recorded here. What the system guarantees is the money bookkeeping:
 *
 *   request  driver earnings  ->  PAYOUT_HOLD   (the money is set aside at once, so it cannot be spent or withdrawn twice)
 *   paid     PAYOUT_HOLD      ->  GATEWAY        (it left the company for the outside world; the administrator records the transfer reference)
 *   rejected PAYOUT_HOLD      ->  driver earnings (given back)
 * Every step is one ledger transaction with its own idempotency key, so pressing a button twice posts once.
 */
@Service
public class PayoutService {

    public record Payout(UUID id, UUID driverUserId, BigDecimal amount, String upiId, String status, Instant requestedAt, Instant decidedAt,
                         String note, String reference) {
    }

    private static final Pattern UPI = Pattern.compile("^[A-Za-z0-9._-]{2,}@[A-Za-z0-9]{2,}$");
    private static final RowMapper<Payout> MAPPER = (rs, n) -> new Payout(rs.getObject("id", UUID.class), rs.getObject("driver_user_id", UUID.class),
            rs.getBigDecimal("amount"), rs.getString("upi_id"), rs.getString("status"), rs.getTimestamp("requested_at").toInstant(),
            rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(), rs.getString("note"), rs.getString("reference"));

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final LedgerAccounts accounts;
    private final EarningsService earnings;
    private final BigDecimal minimum;

    public PayoutService(JdbcTemplate jdbc, LedgerService ledger, LedgerAccounts accounts, EarningsService earnings,
                         @Value("${ridex.payments.payout.minimum:100}") BigDecimal minimum) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.earnings = earnings;
        this.minimum = minimum;
    }

    @Transactional
    public Payout request(UUID driverUserId, BigDecimal amount, String upiId) {
        if (amount == null || amount.scale() > 2 || amount.compareTo(minimum) < 0) {
            throw new BadRequestException("The smallest withdrawal is " + minimum + " rupees");
        }
        if (upiId == null || !UPI.matcher(upiId.trim()).matches()) {
            throw new BadRequestException("Enter a valid UPI id, like name@bank");
        }
        UUID id = UUID.randomUUID();
        LedgerService.Posting posting = ledger.post("PAYOUT_REQUEST", id.toString(), "payout-request-" + id, "Withdrawal requested",
                List.of(new LedgerService.Leg(accounts.earningsOf(driverUserId), amount.negate()), new LedgerService.Leg(AccountType.PAYOUT_HOLD_ID, amount)));
        if (!posting.done()) {
            throw new BadRequestException("You cannot withdraw more than your balance");
        }
        jdbc.update("insert into payout_requests (id, driver_user_id, amount, upi_id) values (?, ?, ?, ?)", id, driverUserId, amount, upiId.trim());
        earnings.forgetAfterCommit(driverUserId);
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<Payout> mine(UUID driverUserId) {
        return jdbc.query("select * from payout_requests where driver_user_id = ? order by requested_at desc limit 50", MAPPER, driverUserId);
    }

    @Transactional(readOnly = true)
    public PageResponse<Payout> list(String status, int page, int size) {
        Pageable pageable = Paging.of(page, size, Sort.unsorted());
        Long total = jdbc.queryForObject("select count(*) from payout_requests where (?::text is null or status = ?)", Long.class, status, status);
        List<Payout> rows = jdbc.query("select * from payout_requests where (?::text is null or status = ?) order by requested_at desc limit ? offset ?",
                MAPPER, status, status, pageable.getPageSize(), pageable.getOffset());
        return PageResponse.from(new PageImpl<>(rows, pageable, total == null ? 0 : total), p -> p);
    }

    /** The administrator did the transfer outside the system and records its reference. */
    @Transactional
    public Payout markPaid(UUID adminId, UUID id, String reference) {
        Payout payout = lock(id);
        if (!"REQUESTED".equals(payout.status())) {
            throw new ConflictException("This withdrawal was already decided");
        }
        if (reference == null || reference.isBlank() || reference.length() > 80) {
            throw new BadRequestException("Enter the transfer reference (up to 80 characters)");
        }
        ledger.post("PAYOUT_PAID", id.toString(), "payout-paid-" + id, "Withdrawal paid",
                List.of(new LedgerService.Leg(AccountType.PAYOUT_HOLD_ID, payout.amount().negate()), new LedgerService.Leg(AccountType.GATEWAY_ID, payout.amount())));
        jdbc.update("update payout_requests set status = 'PAID', decided_at = ?, decided_by = ?, reference = ? where id = ?",
                Timestamp.from(Instant.now()), adminId, reference.trim(), id);
        return get(id);
    }

    @Transactional
    public Payout reject(UUID adminId, UUID id, String note) {
        Payout payout = lock(id);
        if (!"REQUESTED".equals(payout.status())) {
            throw new ConflictException("This withdrawal was already decided");
        }
        if (note == null || note.isBlank() || note.length() > 200) {
            throw new BadRequestException("Give the driver a reason (up to 200 characters)");
        }
        ledger.post("PAYOUT_REJECTED", id.toString(), "payout-rejected-" + id, "Withdrawal returned",
                List.of(new LedgerService.Leg(AccountType.PAYOUT_HOLD_ID, payout.amount().negate()),
                        new LedgerService.Leg(accounts.earningsOf(payout.driverUserId()), payout.amount())));
        jdbc.update("update payout_requests set status = 'REJECTED', decided_at = ?, decided_by = ?, note = ? where id = ?",
                Timestamp.from(Instant.now()), adminId, note.trim(), id);
        earnings.forgetAfterCommit(payout.driverUserId());
        return get(id);
    }

    private Payout lock(UUID id) {
        return jdbc.query("select * from payout_requests where id = ? for update", MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Withdrawal not found"));
    }

    private Payout get(UUID id) {
        return jdbc.queryForObject("select * from payout_requests where id = ?", MAPPER, id);
    }
}
