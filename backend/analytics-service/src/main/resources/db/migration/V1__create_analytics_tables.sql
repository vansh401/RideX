-- The analytics service owns its own database. It is built ONLY from events: there are no foreign keys, and it never reads
-- another service's tables. Everything here can be rebuilt by replaying the Kafka topics.

-- AUDIT: an append-only record of every event, with where it came from in Kafka.
CREATE TABLE event_audit_log (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    topic        VARCHAR(100) NOT NULL,
    partition_no INTEGER      NOT NULL,
    offset_no    BIGINT       NOT NULL,
    event_key    VARCHAR(100) NOT NULL,
    event_type   VARCHAR(60)  NOT NULL,
    payload      TEXT         NOT NULL,
    occurred_at  TIMESTAMPTZ  NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_key ON event_audit_log (event_key, id);

-- ANALYTICS: how many times each status happened per day.
CREATE TABLE ride_status_counts_daily (
    day         DATE        NOT NULL,
    status      VARCHAR(30) NOT NULL,
    event_count BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (day, status)
);

-- PAYMENTS, from events: how many payments ended each way per day, and how much money moved. Money is NUMERIC, never a float.
CREATE TABLE payment_outcomes_daily (
    day          DATE          NOT NULL,
    outcome      VARCHAR(12)   NOT NULL,
    event_count  BIGINT        NOT NULL DEFAULT 0,
    total_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (day, outcome)
);

-- DEAD LETTERS: messages that could not be processed even after several tries (by ANY service). Parked here so one bad
-- message never blocks the ones behind it, and a person can look at it.
CREATE TABLE dead_letter_events (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_topic   VARCHAR(100) NOT NULL,
    partition_no   INTEGER,
    offset_no      BIGINT,
    event_key      VARCHAR(100),
    failed_group   VARCHAR(60),
    error_message  TEXT,
    payload        TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_dead_letters_created ON dead_letter_events (created_at DESC);
