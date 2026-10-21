-- Phase 12 (tracing): the trace context (W3C "traceparent") of the request that caused the event. The relay publishes the event later,
-- on another thread; without this column the trace would end at the outbox and the consumer would start a new, unrelated one.
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS traceparent VARCHAR(80);
