-- Phase 15: online payments (Razorpay or the test provider), tips, cancellation fees, payouts, referrals, admin adjustments.

-- Two more system accounts. PAYOUT_HOLD keeps a driver's money aside while a withdrawal is waiting for a decision (so it cannot be
-- spent twice); ADJUSTMENT is the counterparty of a manual correction made by an administrator.
ALTER TABLE ledger_accounts DROP CONSTRAINT ledger_accounts_type_check;
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_accounts_type_check
    CHECK (type IN ('USER_WALLET', 'DRIVER_EARNINGS', 'GATEWAY', 'REVENUE', 'PROMO', 'INCENTIVES', 'PAYOUT_HOLD', 'ADJUSTMENT'));
INSERT INTO ledger_accounts (id, type) VALUES
    ('00000000-0000-0000-0000-000000000005', 'PAYOUT_HOLD'),
    ('00000000-0000-0000-0000-000000000006', 'ADJUSTMENT');

-- One row per checkout started with the payment provider. The provider's order id is unique, so "the same payment confirmed twice"
-- (the browser AND the webhook both tell us) credits the wallet once.
CREATE TABLE online_payments (
    id                  UUID          PRIMARY KEY,
    user_id             UUID          NOT NULL,
    purpose             VARCHAR(10)   NOT NULL CHECK (purpose IN ('TOPUP', 'RIDE')),
    ride_id             UUID,
    amount              NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)    NOT NULL DEFAULT 'INR',
    provider            VARCHAR(10)   NOT NULL,
    provider_order_id   VARCHAR(80)   NOT NULL UNIQUE,
    provider_payment_id VARCHAR(80),
    status              VARCHAR(10)   NOT NULL DEFAULT 'CREATED' CHECK (status IN ('CREATED', 'PAID', 'FAILED')),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    paid_at             TIMESTAMPTZ
);
CREATE INDEX idx_online_user ON online_payments (user_id, created_at DESC);

-- A rider may tip the driver once per ride, from the wallet.
CREATE TABLE tips (
    ride_id        UUID          PRIMARY KEY,
    rider_user_id  UUID          NOT NULL,
    driver_user_id UUID          NOT NULL,
    amount         NUMERIC(10,2) NOT NULL CHECK (amount > 0),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- A rider who cancels after the driver has arrived pays a small fee (from the wallet, when it holds enough).
CREATE TABLE cancellation_fees (
    ride_id        UUID          PRIMARY KEY,
    rider_user_id  UUID          NOT NULL,
    driver_user_id UUID          NOT NULL,
    amount         NUMERIC(10,2) NOT NULL,
    charged        BOOLEAN       NOT NULL,
    note           VARCHAR(120),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Driver withdrawals. REQUESTED holds the money; PAID settles it; REJECTED gives it back.
CREATE TABLE payout_requests (
    id             UUID          PRIMARY KEY,
    driver_user_id UUID          NOT NULL,
    amount         NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    upi_id         VARCHAR(80)   NOT NULL,
    status         VARCHAR(10)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'PAID', 'REJECTED')),
    requested_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_at     TIMESTAMPTZ,
    decided_by     UUID,
    note           VARCHAR(200),
    reference      VARCHAR(80)
);
CREATE INDEX idx_payout_status ON payout_requests (status, requested_at);
CREATE INDEX idx_payout_driver ON payout_requests (driver_user_id, requested_at DESC);

-- Referrals: every user has a code; a new rider applies someone's code once, and both are rewarded after the new rider's first paid ride.
CREATE TABLE referral_codes (
    user_id UUID        PRIMARY KEY,
    code    VARCHAR(12) NOT NULL UNIQUE
);
CREATE TABLE referrals (
    referee_user_id  UUID        PRIMARY KEY,
    referrer_user_id UUID        NOT NULL CHECK (referrer_user_id <> referee_user_id),
    code             VARCHAR(12) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    rewarded_at      TIMESTAMPTZ
);

-- Offers a rider may browse. An administrator can keep a code private (shared by hand) by turning this off.
ALTER TABLE coupons ADD COLUMN listed BOOLEAN NOT NULL DEFAULT TRUE;
