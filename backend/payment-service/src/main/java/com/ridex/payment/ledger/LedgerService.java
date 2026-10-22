package com.ridex.payment.ledger;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONLY code that moves money. Everything else (a ride payment, a top-up, a refund, a bonus) describes the movement as a list of
 * legs and asks for it to be posted.
 *
 * What posting guarantees:
 *  ATOMIC       all legs happen or none do (one database transaction).
 *  BALANCED     the legs add up to zero (checked here, and again by the database when the transaction commits).
 *  NO OVERDRAFT a wallet or earnings account never goes below zero. The accounts are locked (SELECT ... FOR UPDATE) BEFORE the balance
 *               is looked at, so two payments racing for the same wallet cannot both see "enough money". Accounts are always locked in the
 *               same order (by id), so two postings that touch the same accounts in opposite order cannot deadlock.
 *  IDEMPOTENT   the same key posts once. A retry, a duplicate event or a user pressing the button twice returns "duplicate".
 *
 * Not enough money is an ANSWER, not an exception: the result is {@link Status#INSUFFICIENT_FUNDS}. Throwing would mark the caller's
 * surrounding transaction "rollback only" even if the caller catches it (a classic Spring trap), and it is not exceptional: "pay from the
 * wallet if it covers it, otherwise charge the card" is normal control flow.
 */
@Service
public class LedgerService {

    public enum Status { POSTED, DUPLICATE, INSUFFICIENT_FUNDS }

    /** One side of a movement: a signed amount for an account. */
    public record Leg(UUID accountId, BigDecimal amount) {
    }

    public record Posting(Status status, UUID transactionId) {
        public boolean done() {
            return status == Status.POSTED || status == Status.DUPLICATE;
        }
    }

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Joins the caller's transaction (so a payment and its ledger entries commit together) and refuses to run without one. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Posting post(String type, String reference, String idempotencyKey, String description, List<Leg> legs) {
        validate(legs);

        // 1. Lock every account involved, in id order
        List<UUID> ids = legs.stream().map(Leg::accountId).distinct().sorted().toList();
        Map<UUID, Account> accounts = new HashMap<>();
        jdbc.query("select id, type, balance from ledger_accounts where id = any(?::uuid[]) order by id for update",
                rs -> {
                    accounts.put(rs.getObject("id", UUID.class), new Account(AccountType.valueOf(rs.getString("type")), rs.getBigDecimal("balance")));
                }, (Object) ids.stream().map(UUID::toString).toArray(String[]::new));
        if (accounts.size() != ids.size()) {
            throw new IllegalArgumentException("a leg names an account that does not exist");
        }

        // 2. Already posted? (the accounts are locked, so nobody can post the same key at the same moment)
        List<UUID> earlier = jdbc.queryForList("select id from ledger_transactions where idempotency_key = ?", UUID.class, idempotencyKey);
        if (!earlier.isEmpty()) {
            return new Posting(Status.DUPLICATE, earlier.get(0));
        }

        // 3. Would any wallet go below zero?
        Map<UUID, BigDecimal> change = new HashMap<>();
        for (Leg leg : legs) {
            change.merge(leg.accountId(), leg.amount(), BigDecimal::add);
        }
        for (Map.Entry<UUID, BigDecimal> e : change.entrySet()) {
            Account account = accounts.get(e.getKey());
            if (account.type().mayNotGoNegative() && account.balance().add(e.getValue()).signum() < 0) {
                return new Posting(Status.INSUFFICIENT_FUNDS, null);
            }
        }

        // 4. Write the transaction, its entries, and the new balances
        UUID transactionId = UUID.randomUUID();
        jdbc.update("insert into ledger_transactions (id, type, reference, idempotency_key, description, created_at) values (?, ?, ?, ?, ?, ?)",
                transactionId, type, reference, idempotencyKey, description, Timestamp.from(Instant.now()));
        Map<UUID, BigDecimal> running = new HashMap<>();
        accounts.forEach((id, account) -> running.put(id, account.balance()));
        for (Leg leg : legs) {
            BigDecimal after = running.merge(leg.accountId(), leg.amount(), BigDecimal::add);
            jdbc.update("insert into ledger_entries (transaction_id, account_id, amount, balance_after) values (?, ?, ?, ?)",
                    transactionId, leg.accountId(), leg.amount(), after);
        }
        running.forEach((id, balance) -> jdbc.update("update ledger_accounts set balance = ? where id = ?", balance, id));
        return new Posting(Status.POSTED, transactionId);
    }

    private static void validate(List<Leg> legs) {
        if (legs == null || legs.size() < 2) {
            throw new IllegalArgumentException("a movement needs at least two legs");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Leg leg : legs) {
            if (leg.amount().signum() == 0 || leg.amount().scale() > 2) {
                throw new IllegalArgumentException("every leg is a non-zero amount with at most 2 decimals");
            }
            sum = sum.add(leg.amount());
        }
        if (sum.signum() != 0) {
            throw new IllegalArgumentException("the legs add up to " + sum + ", not zero: money cannot appear or disappear");
        }
    }

    /** The balance of an account that must exist. */
    public BigDecimal balanceOf(UUID accountId) {
        return jdbc.queryForObject("select balance from ledger_accounts where id = ?", BigDecimal.class, accountId);
    }

    /** The total of every balance: always zero if the ledger is intact (used by the tests and by the admin integrity check). */
    public BigDecimal sumOfAllBalances() {
        return jdbc.queryForObject("select coalesce(sum(balance), 0) from ledger_accounts", BigDecimal.class);
    }

    /** Does every account's stored balance equal the sum of its entries? Empty list = yes (the admin integrity check). */
    public List<String> accountsWhoseBalanceDiffersFromTheirEntries() {
        return jdbc.query("""
                select a.id, a.balance, coalesce(sum(e.amount), 0) as entries
                from ledger_accounts a left join ledger_entries e on e.account_id = a.id
                group by a.id, a.balance having a.balance <> coalesce(sum(e.amount), 0)
                """, (rs, n) -> rs.getObject("id") + " balance " + rs.getBigDecimal("balance") + " but entries " + rs.getBigDecimal("entries"));
    }

    private record Account(AccountType type, BigDecimal balance) {
    }
}
