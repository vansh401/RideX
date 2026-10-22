package com.ridex.payment.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.payment.earnings.IncentiveService;

/** The finance view for administrators (SecurityConfig: /api/admin/**). */
@RestController
public class FinanceAdminController {

    private final LedgerService ledger;
    private final JdbcTemplate jdbc;
    private final IncentiveService incentives;

    public FinanceAdminController(LedgerService ledger, JdbcTemplate jdbc, IncentiveService incentives) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.incentives = incentives;
    }

    /**
     * Where the company's money is: the balance of each system account. Reading the signs: GATEWAY is negative by the money that came in from
     * cards; REVENUE is the commission kept (minus refunds); PROMO and INCENTIVES are negative by what coupons and bonuses cost.
     */
    @GetMapping("/api/admin/ledger/summary")
    public Map<String, BigDecimal> summary() {
        Map<String, BigDecimal> result = new java.util.LinkedHashMap<>();
        for (String type : List.of("GATEWAY", "REVENUE", "PROMO", "INCENTIVES")) {
            result.put(type, jdbc.queryForObject("select balance from ledger_accounts where type = ? and owner_id is null", BigDecimal.class, type));
        }
        result.put("USER_WALLETS_TOTAL", jdbc.queryForObject("select coalesce(sum(balance), 0) from ledger_accounts where type = 'USER_WALLET'", BigDecimal.class));
        result.put("DRIVER_EARNINGS_TOTAL", jdbc.queryForObject("select coalesce(sum(balance), 0) from ledger_accounts where type = 'DRIVER_EARNINGS'", BigDecimal.class));
        return result;
    }

    public record Integrity(boolean ok, BigDecimal sumOfAllBalances, List<String> accountsOutOfStep) {
    }

    /** Is the ledger intact? Money cannot be created, so all balances must add up to zero, and every balance must equal the sum of its entries. */
    @GetMapping("/api/admin/ledger/integrity")
    public Integrity integrity() {
        BigDecimal sum = ledger.sumOfAllBalances();
        List<String> outOfStep = ledger.accountsWhoseBalanceDiffersFromTheirEntries();
        return new Integrity(sum.signum() == 0 && outOfStep.isEmpty(), sum, outOfStep);
    }

    /** Pay the incentives of a day now (the nightly job does it by itself; running it again pays nobody twice). */
    @PostMapping("/api/admin/incentives/run")
    public IncentiveService.Result runIncentives(@RequestParam LocalDate day) {
        return incentives.payFor(day);
    }
}
