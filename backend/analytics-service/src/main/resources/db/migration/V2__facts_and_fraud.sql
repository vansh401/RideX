-- Phase 14: facts for the admin charts, and fraud signals. Like everything in this database, it is built ONLY from events and can be rebuilt by
-- replaying the topics.

-- One row per ride, kept up to date as the ride's events arrive. Charts are aggregations over these rows.
CREATE TABLE ride_facts (
    ride_id          UUID          PRIMARY KEY,
    rider_user_id    UUID,
    driver_user_id   UUID,
    category         VARCHAR(10),
    pickup_cell      VARCHAR(30),
    surge_multiplier NUMERIC(4,2),
    coupon_code      VARCHAR(40),
    fare             NUMERIC(12,2),
    requested_at     TIMESTAMPTZ   NOT NULL,
    accepted_at      TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ,
    cancelled_by     VARCHAR(10),
    final_status     VARCHAR(20)
);
CREATE INDEX idx_ride_facts_requested ON ride_facts (requested_at);
CREATE INDEX idx_ride_facts_rider ON ride_facts (rider_user_id, requested_at);
CREATE INDEX idx_ride_facts_driver ON ride_facts (driver_user_id, completed_at) WHERE driver_user_id IS NOT NULL;
CREATE INDEX idx_ride_facts_cell ON ride_facts (pickup_cell, requested_at);

CREATE TABLE payment_facts (
    ride_id        UUID          PRIMARY KEY,
    rider_user_id  UUID,
    driver_user_id UUID,
    amount         NUMERIC(12,2) NOT NULL,
    discount       NUMERIC(12,2) NOT NULL DEFAULT 0,
    method         VARCHAR(10),
    status         VARCHAR(10)   NOT NULL,
    paid_at        TIMESTAMPTZ,
    refunded       NUMERIC(12,2) NOT NULL DEFAULT 0,
    -- When the latest event about this payment happened (a failed payment has no paid_at, but it has a time)
    event_at       TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_payment_facts_rider ON payment_facts (rider_user_id, paid_at);
CREATE INDEX idx_payment_facts_paid ON payment_facts (paid_at);
CREATE INDEX idx_payment_facts_event ON payment_facts (event_at);

CREATE TABLE complaints_daily (
    day         DATE        NOT NULL,
    category    VARCHAR(20) NOT NULL,
    event_count BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (day, category)
);

-- FRAUD SIGNALS: a rule noticed a pattern worth a human's attention. A signal is NOT a verdict: nothing is blocked automatically.
CREATE TABLE fraud_signals (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule         VARCHAR(40)  NOT NULL,
    subject_type VARCHAR(8)   NOT NULL CHECK (subject_type IN ('USER', 'RIDE', 'PAIR')),
    subject_id   VARCHAR(80)  NOT NULL,
    severity     INTEGER      NOT NULL CHECK (severity BETWEEN 1 AND 5),
    details      VARCHAR(500) NOT NULL,
    ride_id      UUID,
    -- The same pattern seen again (the next event of the same burst, or a replay) must not create a second signal
    dedupe_key   VARCHAR(160) NOT NULL UNIQUE,
    status       VARCHAR(10)  NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'CONFIRMED', 'DISMISSED')),
    reviewed_by  UUID,
    reviewed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_fraud_status_created ON fraud_signals (status, created_at DESC);
CREATE INDEX idx_fraud_subject ON fraud_signals (subject_type, subject_id);
