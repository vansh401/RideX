# RideX interview walkthrough

A 10-minute story you can tell, then the questions that usually follow. Every claim below is something you can open in this repository.

## The 90-second pitch

> RideX is a ride-hailing platform I built in 14 phases: a Spring Boot core (users, rides, dispatch), plus pricing, payment, notification and analytics services behind an API gateway, communicating through Kafka with an outbox. Money is a double-entry ledger with idempotency keys and database-enforced invariants. Real-time driver tracking uses Redis GEO and WebSockets. It runs in Docker Compose and on a local Kubernetes cluster, has metrics, traces, dashboards and alerts, and a GitHub Actions pipeline with automatic rollback. The React frontend covers rider, driver and admin.

## The tour (what to open, in order)

1. `docs/service-map.md`: who owns which data. *Each service has its own database; nobody reads another's tables.*
2. A ride's life: request → `RideService` → outbox row in the **same transaction** → relay publishes to Kafka → payment/notification/analytics consume. Open `events/` and `docs/event-contracts.md`.
3. Dispatch: Redis GEO nearest drivers, offer with expiry, `FOR UPDATE SKIP LOCKED` so a driver gets one offer at a time (`DispatchRepository`).
4. Money: `docs/money-and-ledger-concepts.md`, then `LedgerService` and the migration with the deferred trigger.
5. Reliability: retries + dead-letter topics (Phase 7), circuit breaker around the gateway (Phase 8), autoscaling and failure experiments (Phase 11).
6. Operability: `docs/observability-concepts.md`, `docs/ci-cd-concepts.md`.
7. Product features: `docs/platform-features-concepts.md`.

## Questions you should be able to answer

**Why microservices for a project this size?** Honestly: to learn the problems they create (distributed data, eventual consistency, failure isolation, deployment). A modular monolith would be the right call for a real team of three. I can say what I'd merge first (notification into core) and why.

**How do you avoid losing an event when the database commits but Kafka is down?** Outbox: the event is a row written in the business transaction; a relay publishes it and marks it sent. At-least-once delivery, so every consumer is idempotent (`ProcessedEvents` keyed by event id).

**How do you make a wallet safe under concurrency?** Row locks in a fixed order (no deadlocks), idempotency keys (a retried request cannot move money twice), a deferred DB trigger that rejects a transaction whose entries do not sum to zero, append-only entries, and an integrity endpoint that re-adds everything.

**Why not throw an exception for insufficient funds?** In Spring, an exception through a `@Transactional` boundary marks the transaction rollback-only even if caught. A result value (`INSUFFICIENT_FUNDS`) keeps the caller's transaction usable.

**How does surge pricing work, and what if it breaks?** Core measures demand/supply per zone and publishes a multiplier to Redis with a TTL; pricing reads it and falls back to 1.00 on anything odd. Step limits prevent jumps, a cap prevents abuse. The failure mode is "no surge", never "no price".

**A scheduled ride was cancelled the instant it started searching. Why?** The driver-search timeout counted from the request time (yesterday). Fix: a `search_started_at` column; the window is measured from when searching actually began. Found by a test that promotes a ride late.

**How do you avoid refunding twice?** The refund is reserved against the payment inside a transaction (refunded ≤ paid), card refunds use a fixed idempotency key so a retry is safe, and complaints reach the payment service by event, with the same event processed once.

**How are the admin charts computed?** From fact tables the analytics service builds out of events (upserts with `coalesce`, idempotent, replayable), in the business time zone, with zero-filled days.

**Is fraud detection "AI"?** No. Six transparent rules produce signals for a human; the dismissed share of a rule is its false-positive rate. Thresholds are guesses until measured.

**What would you do next?** Wire ride sharing into dispatch, real payouts, pre-aggregated analytics, per-region surge tuning from real data, and run the pipeline on a real GitHub repository.

## Honest limits (say these before they are asked)

- The GitHub workflows have never run on GitHub (no remote); they are linted and policy-tested locally.
- Ride sharing exists as a tested matcher only; it is not part of booking or dispatch.
- Payments use a mock gateway; the wallet is practice money; no payouts.
- Fraud thresholds and surge tables are reasonable guesses, not tuned on data.
- Analytics queries run on demand and are not cached.
