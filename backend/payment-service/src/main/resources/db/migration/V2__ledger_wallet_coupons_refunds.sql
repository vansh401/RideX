-- Phase 14: the money domain. Everything here belongs to the payment service; other services only know ids.

-- =====================================================================================================================
-- THE LEDGER (double entry)
--
-- Money is never "set" and never "updated in place". Every movement is a TRANSACTION made of two or more ENTRIES, each one
-- an amount added to (+) or taken from (-) an ACCOUNT, and the entries of one transaction ALWAYS add up to zero: money does not
-- appear or disappear, it only moves from one account to another. The balance of an account is the sum of its entries (kept
-- in a column too, so reading it is cheap, and updated in the same transaction as the entries).
--
-- Accounts: a wallet per rider, an earnings account per driver, and a few SYSTEM accounts that stand for "the outside world"
-- and for the company's own pots. System accounts may be negative (GATEWAY is negative by exactly the money that came in from
-- cards); wallets and earnings can never be.
-- =====================================================================================================================
CREATE TABLE ledger_accounts (
    id         UUID          PRIMARY KEY,
    type       VARCHAR(20)   NOT NULL CHECK (type IN ('USER_WALLET', 'DRIVER_EARNINGS', 'GATEWAY', 'REVENUE', 'PROMO', 'INCENTIVES')),
    owner_id   UUID,                                   -- the user (rider or driver); NULL for system accounts
    currency   VARCHAR(3)    NOT NULL DEFAULT 'INR',
    balance    NUMERIC(14,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_account_owner CHECK ((type IN ('USER_WALLET', 'DRIVER_EARNINGS')) = (owner_id IS NOT NULL)),
    -- The database itself refuses to let a wallet or an earnings account go below zero, whatever the application does
    CONSTRAINT chk_account_not_negative CHECK (type NOT IN ('USER_WALLET', 'DRIVER_EARNINGS') OR balance >= 0)
);
CREATE UNIQUE INDEX ux_account_owner ON ledger_accounts (type, owner_id) WHERE owner_id IS NOT NULL;
CREATE UNIQUE INDEX ux_account_system ON ledger_accounts (type) WHERE owner_id IS NULL;

INSERT INTO ledger_accounts (id, type) VALUES
    ('00000000-0000-0000-0000-000000000001', 'GATEWAY'),      -- the outside world: cards and banks
    ('00000000-0000-0000-0000-000000000002', 'REVENUE'),      -- what the company keeps (commission)
    ('00000000-0000-0000-0000-000000000003', 'PROMO'),        -- pays for coupons (the company's marketing cost)
    ('00000000-0000-0000-0000-000000000004', 'INCENTIVES');   -- pays driver bonuses

CREATE TABLE ledger_transactions (
    id              UUID         PRIMARY KEY,
    type            VARCHAR(30)  NOT NULL,                 -- TOPUP, RIDE_PAYMENT, REFUND, INCENTIVE ...
    reference       VARCHAR(100),                          -- what it is about: a ride id, a refund id ...
    -- The same request can arrive twice (a retry, a duplicate event). Posting with a key that already exists does nothing the
    -- second time, and the unique index makes that true even for two requests racing each other.
    idempotency_key VARCHAR(120) NOT NULL UNIQUE,
    description     VARCHAR(200),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_tx_type_created ON ledger_transactions (type, created_at DESC);

CREATE TABLE ledger_entries (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id UUID          NOT NULL REFERENCES ledger_transactions (id),
    account_id     UUID          NOT NULL REFERENCES ledger_accounts (id),
    amount         NUMERIC(14,2) NOT NULL CHECK (amount <> 0),   -- + into the account, - out of it
    balance_after  NUMERIC(14,2) NOT NULL,                       -- the account's balance right after this entry (a statement line)
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_account ON ledger_entries (account_id, id DESC);
CREATE INDEX idx_entries_transaction ON ledger_entries (transaction_id);

-- THE LEDGER IS APPEND-ONLY. A mistake is corrected by a new, opposite transaction, never by editing history.
CREATE FUNCTION ledger_is_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger rows are never updated or deleted (post a correcting transaction instead)';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_entries_immutable BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION ledger_is_immutable();
CREATE TRIGGER trg_transactions_immutable BEFORE UPDATE OR DELETE ON ledger_transactions FOR EACH ROW EXECUTE FUNCTION ledger_is_immutable();

-- EVERY TRANSACTION BALANCES. Checked by the database when the transaction COMMITS (DEFERRED: all entries of a transaction are inserted
-- one by one, and only the complete set has to add up to zero). The application checks too, but this is the guarantee that holds even if
-- someone writes a new code path, or runs a manual SQL statement.
CREATE FUNCTION ledger_check_balanced() RETURNS trigger AS $$
BEGIN
    IF (SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE transaction_id = NEW.transaction_id) <> 0 THEN
        RAISE EXCEPTION 'ledger transaction % does not add up to zero', NEW.transaction_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER trg_transaction_balanced AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_check_balanced();

-- =====================================================================================================================
-- PAYMENTS get a method, a coupon and refund bookkeeping
-- =====================================================================================================================
ALTER TABLE payments
    ADD COLUMN method          VARCHAR(10)   CHECK (method IN ('WALLET', 'GATEWAY', 'PROMO')),
    ADD COLUMN coupon_code     VARCHAR(40),
    ADD COLUMN discount        NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    ADD COLUMN refunded_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    ADD CONSTRAINT chk_payment_discount CHECK (discount <= amount),
    ADD CONSTRAINT chk_payment_refund_limit CHECK (refunded_amount <= amount - discount);

-- =====================================================================================================================
-- COUPONS
-- =====================================================================================================================
CREATE TABLE coupons (
    id             UUID          PRIMARY KEY,
    code           VARCHAR(40)   NOT NULL UNIQUE,         -- stored in UPPER case
    description    VARCHAR(200),
    discount_type  VARCHAR(8)    NOT NULL CHECK (discount_type IN ('PERCENT', 'FLAT')),
    value          NUMERIC(10,2) NOT NULL CHECK (value > 0),
    max_discount   NUMERIC(10,2) CHECK (max_discount > 0),   -- cap for PERCENT coupons
    min_fare       NUMERIC(10,2) NOT NULL DEFAULT 0,
    valid_from     TIMESTAMPTZ   NOT NULL,
    valid_until    TIMESTAMPTZ   NOT NULL,
    max_total_uses INTEGER       CHECK (max_total_uses > 0),  -- NULL = unlimited
    per_user_limit INTEGER       NOT NULL DEFAULT 1 CHECK (per_user_limit > 0),
    used_count     INTEGER       NOT NULL DEFAULT 0 CHECK (used_count >= 0),
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_coupon_window CHECK (valid_until > valid_from),
    CONSTRAINT chk_coupon_percent CHECK (discount_type <> 'PERCENT' OR value <= 100),
    -- the database also refuses to count past the limit: a last line of defence behind the row lock
    CONSTRAINT chk_coupon_limit CHECK (max_total_uses IS NULL OR used_count <= max_total_uses)
);

CREATE TABLE coupon_redemptions (
    id         UUID          PRIMARY KEY,
    coupon_id  UUID          NOT NULL REFERENCES coupons (id),
    user_id    UUID          NOT NULL,
    ride_id    UUID          NOT NULL UNIQUE,              -- a ride uses at most one coupon, once
    discount   NUMERIC(10,2) NOT NULL CHECK (discount >= 0),
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_redemptions_user ON coupon_redemptions (coupon_id, user_id);

-- =====================================================================================================================
-- REFUNDS
-- =====================================================================================================================
CREATE TABLE refunds (
    id              UUID          PRIMARY KEY,
    payment_id      UUID          NOT NULL REFERENCES payments (id),
    ride_id         UUID          NOT NULL,
    user_id         UUID          NOT NULL,                -- who gets the money back
    amount          NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    reason          VARCHAR(200),
    source          VARCHAR(12)   NOT NULL CHECK (source IN ('ADMIN', 'COMPLAINT')),
    source_ref      VARCHAR(100),                          -- the complaint id, or the admin's user id
    destination     VARCHAR(10)   NOT NULL CHECK (destination IN ('WALLET', 'ORIGINAL')),
    status          VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    -- One refund per complaint (or per admin request): the same event twice must not refund twice
    idempotency_key VARCHAR(120)  NOT NULL UNIQUE,
    attempts        INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    gateway_reference VARCHAR(100),
    failure_reason  VARCHAR(255),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_refunds_payment ON refunds (payment_id);
CREATE INDEX idx_refunds_due ON refunds (next_attempt_at) WHERE status = 'PENDING' AND next_attempt_at IS NOT NULL;

-- The pretend gateway's own refund book, one row per Idempotency-Key, like its charges
CREATE TABLE mock_gateway_refunds (
    idempotency_key VARCHAR(120)  PRIMARY KEY,
    charge_reference VARCHAR(100) NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    reference       VARCHAR(100)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
