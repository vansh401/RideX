package com.ridex.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The money rules that must survive a careless edit. The ledger's guarantees live in the DATABASE (triggers and checks), so the
 * tests read the migration that creates them; a refactor that drops a trigger would otherwise pass every functional test that
 * happens to use correct amounts.
 */
class MoneyPolicyTest {

    private static final Path PAYMENT = Path.of("..", "payment-service", "src", "main");

    private static String read(Path file) throws IOException {
        assumeThat(Files.isRegularFile(file)).as(file + " must exist").isTrue();
        return Files.readString(file);
    }

    @Test
    void theLedgerRefusesUnbalancedAndEditedHistoryInTheDatabase() throws IOException {
        String sql = read(PAYMENT.resolve("resources/db/migration/V2__ledger_wallet_coupons_refunds.sql")).toLowerCase();
        assertThat(sql).as("a deferred constraint trigger checks that a transaction's entries sum to zero at commit")
                .contains("create constraint trigger trg_transaction_balanced").contains("deferrable initially deferred");
        assertThat(sql).as("entries and transactions are append-only")
                .contains("trg_entries_immutable").contains("trg_transactions_immutable");
        assertThat(sql).as("wallets and earnings can never be negative").contains("chk_account_not_negative");
        assertThat(sql).as("a refund can never exceed what was paid").contains("chk_payment_refund_limit");
    }

    @Test
    void aLedgerMovementCanOnlyHappenInsideTheCallersTransaction() throws IOException {
        String source = read(PAYMENT.resolve("java/com/ridex/payment/ledger/LedgerService.java"));
        assertThat(source).as("post(...) joins the business transaction instead of starting its own")
                .containsPattern(Pattern.compile("Propagation\\.MANDATORY"));
    }

    @Test
    void onlyTheLedgerPackageWritesLedgerTables() throws IOException {
        try (var files = Files.walk(PAYMENT.resolve("java"))) {
            var offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/ledger/"))
                    .filter(p -> {
                        try {
                            String text = Files.readString(p).toLowerCase();
                            return text.contains("insert into ledger_") || text.contains("update ledger_") || text.contains("delete from ledger_");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(Path::toString).toList();
            assertThat(offenders).as("money moves only through LedgerService.post").isEmpty();
        }
    }

    @Test
    void complaintsReachThePaymentServiceByEventNotByACall() throws IOException {
        try (var files = Files.walk(Path.of("..", "ridex-app", "src", "main", "java", "com", "ridex", "ridex_app", "complaint"))) {
            var offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String text = Files.readString(p);
                    return text.contains("RestClient") || text.contains("WebClient") || text.contains("RestTemplate") || text.contains("com.ridex.payment");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(Path::toString).toList();
            assertThat(offenders).as("a resolved complaint publishes an event; the payment service does the refund").isEmpty();
        }
    }
}
