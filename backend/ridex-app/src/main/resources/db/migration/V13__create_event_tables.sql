-- Phase 6: event-driven design.

-- ---------------------------------------------------------------------------------------------
-- The OUTBOX. An event is inserted here in the SAME database transaction as the change that caused
-- it (for example the ride becoming COMPLETED). Either both are saved or neither is, so an event can
-- never be lost because Kafka happened to be down at that moment. A relay then copies unpublished
-- rows to Kafka. seq gives a strict order, so events of one ride are published in the order they
-- happened.
CREATE TABLE outbox_events (
    seq          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    topic        VARCHAR(100) NOT NULL,
    event_key    VARCHAR(100) NOT NULL,
    event_type   VARCHAR(60)  NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

-- The relay only ever looks at rows not yet published. Partial index: it stays tiny.
CREATE INDEX idx_outbox_unpublished ON outbox_events (seq) WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- IDEMPOTENT CONSUMERS. Kafka delivers "at least once": the same event can arrive twice (a consumer
-- crashed after working but before confirming). Each consumer group writes (group, event id) here in
-- the same transaction as its work. The primary key makes a second attempt a no-op.
CREATE TABLE processed_events (
    consumer_group VARCHAR(60) NOT NULL,
    event_id       UUID        NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);

-- ---------------------------------------------------------------------------------------------
-- AUDIT consumer: an append-only record of every event, with where it came from in Kafka.
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

-- ---------------------------------------------------------------------------------------------
-- ANALYTICS consumer: how many times each status happened per day, built only from events.
CREATE TABLE ride_status_counts_daily (
    day         DATE        NOT NULL,
    status      VARCHAR(30) NOT NULL,
    event_count BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (day, status)
);

-- ---------------------------------------------------------------------------------------------
-- DEAD LETTERS: messages that could not be processed even after several tries. They are parked here (and in
-- a Kafka ".DLT" topic) so one bad message never blocks the ones behind it, and a person can look at it.
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
