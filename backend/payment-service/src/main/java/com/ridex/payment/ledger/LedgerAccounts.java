package com.ridex.payment.ledger;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Finds, and creates when needed, the ledger account of a user. A rider's wallet and a driver's earnings account appear on first use. */
@Component
public class LedgerAccounts {

    private final JdbcTemplate jdbc;

    public LedgerAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID walletOf(UUID userId) {
        return ensure(AccountType.USER_WALLET, userId);
    }

    public UUID earningsOf(UUID userId) {
        return ensure(AccountType.DRIVER_EARNINGS, userId);
    }

    /**
     * "Insert, ignoring a duplicate, then read" is safe when two requests create the same account at the same moment: the unique index
     * lets exactly one insert win and both then read the same row.
     */
    private UUID ensure(AccountType type, UUID owner) {
        jdbc.update("""
                insert into ledger_accounts (id, type, owner_id) values (?, ?, ?)
                on conflict (type, owner_id) where owner_id is not null do nothing
                """, UUID.randomUUID(), type.name(), owner);
        return jdbc.queryForObject("select id from ledger_accounts where type = ? and owner_id = ?", UUID.class, type.name(), owner);
    }
}
