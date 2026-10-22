package com.ridex.payment.ledger;

import java.util.UUID;

/** The kinds of ledger account. Wallets and earnings belong to a user and can never be negative; the rest are the company's own pots. */
public enum AccountType {
    USER_WALLET, DRIVER_EARNINGS, GATEWAY, REVENUE, PROMO, INCENTIVES;

    /** Fixed ids of the system accounts (created by the migration). */
    public static final UUID GATEWAY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID REVENUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID PROMO_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID INCENTIVES_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    public boolean mayNotGoNegative() {
        return this == USER_WALLET || this == DRIVER_EARNINGS;
    }
}
