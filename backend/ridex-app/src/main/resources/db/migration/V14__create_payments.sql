-- Step 6.5: payments with a MOCK gateway (no real money moves).

CREATE TABLE payments (
    id               UUID          PRIMARY KEY,
    -- One payment per ride. The database enforces it, so two consumers handling the same completed ride at the
    -- same moment can never create (and charge) two payments.
    ride_id          UUID          NOT NULL UNIQUE REFERENCES rides (id),
    rider_user_id    UUID          NOT NULL REFERENCES users (id),
    driver_user_id   UUID          NOT NULL REFERENCES users (id),
    amount           NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency         VARCHAR(3)    NOT NULL,
    status           VARCHAR(12)   NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    attempts         INTEGER       NOT NULL DEFAULT 1,
    -- Sent to the gateway with every charge. Asking twice with the same key never charges twice.
    idempotency_key  VARCHAR(100)  NOT NULL,
    gateway_reference VARCHAR(100),
    failure_reason   VARCHAR(255),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_payments_rider  ON payments (rider_user_id, created_at DESC);
CREATE INDEX idx_payments_status ON payments (status, created_at DESC);

-- The mock gateway's own books: what it "charged". One row per idempotency key, which is how a real
-- gateway (Stripe, Razorpay) makes retries safe. Tests count these rows to prove nobody is charged twice.
CREATE TABLE mock_gateway_charges (
    idempotency_key VARCHAR(100)  PRIMARY KEY,
    amount          NUMERIC(12,2) NOT NULL,
    currency        VARCHAR(3)    NOT NULL,
    success         BOOLEAN       NOT NULL,
    reference       VARCHAR(100),
    failure_reason  VARCHAR(255),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
