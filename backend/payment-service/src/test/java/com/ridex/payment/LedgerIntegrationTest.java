package com.ridex.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ridex.payment.ledger.AccountType;
import com.ridex.payment.ledger.LedgerAccounts;
import com.ridex.payment.ledger.LedgerService;
import com.ridex.payment.ledger.LedgerService.Leg;
import com.ridex.payment.ledger.LedgerService.Posting;
import com.ridex.payment.ledger.LedgerService.Status;

/** The ledger against a real PostgreSQL: the guarantees that decide whether money can be lost. */
class LedgerIntegrationTest extends PaymentTestBase {

    @Autowired
    private LedgerService ledger;
    @Autowired
    private LedgerAccounts accounts;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void template() {
        tx = new TransactionTemplate(transactionManager);
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    private Posting post(String key, Leg... legs) {
        return tx.execute(status -> ledger.post("TEST", "ref", key, "test", List.of(legs)));
    }

    /** A wallet with this much money in it (it came in from the gateway). */
    private UUID fundedWallet(String amount) {
        UUID wallet = accounts.walletOf(id());
        post("fund-" + wallet, new Leg(AccountType.GATEWAY_ID, money(amount).negate()), new Leg(wallet, money(amount)));
        return wallet;
    }

    @Test
    void aPostingMovesMoneyAndAnEntryRecordsTheBalanceAfterIt() {
        UUID wallet = fundedWallet("100.00");
        UUID driver = accounts.earningsOf(id());

        Posting posting = post("pay-" + id(), new Leg(wallet, money("30.00").negate()), new Leg(driver, money("24.00")), new Leg(AccountType.REVENUE_ID, money("6.00")));

        assertThat(posting.status()).isEqualTo(Status.POSTED);
        assertThat(ledger.balanceOf(wallet)).isEqualByComparingTo("70.00");
        assertThat(ledger.balanceOf(driver)).isEqualByComparingTo("24.00");
        assertThat(jdbc.queryForObject("select balance_after from ledger_entries where account_id = ? order by id desc limit 1", BigDecimal.class, wallet))
                .isEqualByComparingTo("70.00");
        assertThat(ledger.sumOfAllBalances()).as("money neither appears nor disappears").isEqualByComparingTo("0");
    }

    @Test
    void movementsThatDoNotAddUpToZeroAreRefusedBeforeAnythingIsWritten() {
        UUID wallet = fundedWallet("50.00");

        assertThatThrownBy(() -> post("bad-" + id(), new Leg(wallet, money("10.00").negate()), new Leg(AccountType.REVENUE_ID, money("9.99"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not zero");
        assertThatThrownBy(() -> post("one-" + id(), new Leg(wallet, money("10.00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.balanceOf(wallet)).isEqualByComparingTo("50.00");
    }

    @Test
    void theSameKeyIsPostedOnceHoweverOftenItIsAsked() {
        UUID wallet = fundedWallet("100.00");
        String key = "once-" + id();

        Posting first = post(key, new Leg(wallet, money("10.00").negate()), new Leg(AccountType.REVENUE_ID, money("10.00")));
        Posting second = post(key, new Leg(wallet, money("10.00").negate()), new Leg(AccountType.REVENUE_ID, money("10.00")));

        assertThat(first.status()).isEqualTo(Status.POSTED);
        assertThat(second.status()).isEqualTo(Status.DUPLICATE);
        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(ledger.balanceOf(wallet)).isEqualByComparingTo("90.00");
    }

    @Test
    void notEnoughMoneyIsAnAnswerNotAnExceptionAndChangesNothing() {
        UUID wallet = fundedWallet("20.00");

        Posting posting = post("poor-" + id(), new Leg(wallet, money("20.01").negate()), new Leg(AccountType.REVENUE_ID, money("20.01")));

        assertThat(posting.status()).isEqualTo(Status.INSUFFICIENT_FUNDS);
        assertThat(ledger.balanceOf(wallet)).isEqualByComparingTo("20.00");
    }

    @Test
    void fiftyRidersSpendingTheSameWalletAtOnceCanOnlySpendWhatIsThere() throws Exception {
        UUID wallet = fundedWallet("100.00");
        ExecutorService pool = Executors.newFixedThreadPool(25);
        List<Future<Posting>> results = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String key = "race-" + wallet + "-" + i;
            results.add(pool.submit(() -> post(key, new Leg(wallet, money("10.00").negate()), new Leg(AccountType.REVENUE_ID, money("10.00")))));
        }
        int posted = 0;
        int refused = 0;
        for (Future<Posting> result : results) {
            Status status = result.get(60, TimeUnit.SECONDS).status();
            if (status == Status.POSTED) {
                posted++;
            } else if (status == Status.INSUFFICIENT_FUNDS) {
                refused++;
            }
        }
        pool.shutdown();

        assertThat(posted).as("exactly the ten payments the wallet can cover").isEqualTo(10);
        assertThat(refused).isEqualTo(40);
        assertThat(ledger.balanceOf(wallet)).as("never negative").isEqualByComparingTo("0.00");
    }

    @Test
    void transfersInOppositeDirectionsAtTheSameTimeDoNotDeadlock() throws Exception {
        UUID a = fundedWallet("1000.00");
        UUID b = fundedWallet("1000.00");
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<Posting>> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            UUID from = i % 2 == 0 ? a : b;
            UUID to = i % 2 == 0 ? b : a;
            String key = "swap-" + a + "-" + i;
            results.add(pool.submit(() -> post(key, new Leg(from, money("5.00").negate()), new Leg(to, money("5.00")))));
        }
        for (Future<Posting> result : results) {
            assertThat(result.get(60, TimeUnit.SECONDS).done()).isTrue();   // a deadlock would time out here
        }
        pool.shutdown();

        assertThat(ledger.balanceOf(a).add(ledger.balanceOf(b))).as("the money moved but none was created").isEqualByComparingTo("2000.00");
    }

    @Test
    void theDatabaseItselfRefusesToEditOrDeleteHistory() {
        UUID wallet = fundedWallet("10.00");

        assertThatThrownBy(() -> jdbc.update("update ledger_entries set amount = amount + 1 where account_id = ?", wallet))
                .hasMessageContaining("never updated or deleted");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries where account_id = ?", wallet))
                .hasMessageContaining("never updated or deleted");
    }

    @Test
    void theDatabaseItselfRefusesAnUnbalancedTransactionAtCommit() {
        UUID wallet = accounts.walletOf(id());
        UUID transaction = UUID.randomUUID();

        // Someone bypasses the service and writes SQL by hand: one leg only
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("insert into ledger_transactions (id, type, idempotency_key) values (?, 'MANUAL', ?)", transaction, "manual-" + transaction);
            jdbc.update("insert into ledger_entries (transaction_id, account_id, amount, balance_after) values (?, ?, 5.00, 5.00)", transaction, wallet);
        })).rootCause().hasMessageContaining("does not add up to zero");
        assertThat(count("select count(*) from ledger_transactions where id = ?", transaction)).as("rolled back as a whole").isZero();
    }

    @Test
    void afterAllThatEveryBalanceStillEqualsTheSumOfItsEntries() {
        fundedWallet("10.00");

        assertThat(ledger.accountsWhoseBalanceDiffersFromTheirEntries()).isEmpty();
        assertThat(ledger.sumOfAllBalances()).isEqualByComparingTo("0");
    }
}
