-- Tables every event-driven service needs (shared by the platform library).
-- IF NOT EXISTS: the core application created the same tables earlier in its own migrations (V13).

-- The OUTBOX: an event is inserted here in the SAME transaction as the change that caused it. A relay later copies
-- unpublished rows to Kafka. seq gives a strict order, so events of one key are published in the order they happened.
CREATE TABLE IF NOT EXISTS outbox_events (
    seq          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    topic        VARCHAR(100) NOT NULL,
    event_key    VARCHAR(100) NOT NULL,
    event_type   VARCHAR(60)  NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_events (seq) WHERE published_at IS NULL;

-- IDEMPOTENT CONSUMERS: (consumer group, event id) is written in the same transaction as the work, so a duplicate
-- delivery is recognised and skipped.
CREATE TABLE IF NOT EXISTS processed_events (
    consumer_group VARCHAR(60) NOT NULL,
    event_id       UUID        NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);
