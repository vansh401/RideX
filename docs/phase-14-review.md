# Phase 14 review: product features

Read [money-and-ledger-concepts.md](money-and-ledger-concepts.md) and [platform-features-concepts.md](platform-features-concepts.md) first (the ideas). This is the review: what changed, what was verified, what went wrong, what is missing, and how to defend it.

## 1. What changed, step by step

| Step | What we built | Main places |
|---|---|---|
| 14.1 Surge pricing | Core measures demand/supply per zone, pure `SurgePolicy`, publishes `surge:<cell>` (TTL 120 s) to Redis; pricing reads and sanitises it, falls back to 1.00; scheduled rides ignore it | `ridex-app/surge/*`, `pricing-service/SurgeLookup`, `platform/geo/SurgeZones` |
| 14.2 Wallet and transactions | Double-entry ledger (DB-enforced balance, append-only, idempotency keys, ordered row locks), wallet top-up and statement, wallet-first payment with card fallback | `payment-service/ledger`, `wallet`, `PaymentService`, migration `V2` |
| 14.3 Coupons | Percent/flat rules, caps, minimums, windows, per-rider and total limits; preview vs redemption under a row lock; platform-funded | `payment-service/coupon` |
| 14.4 Refunds and failures | Compensating refunds bounded by what was paid, card refunds via the gateway with retry and a fixed idempotency key, refunds triggered by complaint events | `payment-service/refund`, `MockGateway /refunds` |
| 14.5 Driver earnings | Summary, 30-day series, recent trips, Redis cache evicted after commit; daily incentive tiers paid once | `payment-service/earnings` |
| 14.6 Admin analytics | Fact tables built from events; six chart endpoints; business time zone; zero-filled days | `analytics-service` (`FactsConsumer`, `AdminAnalyticsController`) |
| 14.7 Complaints | Workflow with public and internal comments, 14-day window, one open per ride per person, refund by event | `ridex-app/complaint`, new topic `complaint-events` |
| 14.8 Scheduled rides, stops, sharing | `SCHEDULED` status and table-polling promoter; up to 3 stops (completion blocked until visited); `SharedRideMatcher` (pure, tested, **not wired**) | `Ride`, `ScheduledRidePromoter`, `SharedRideMatcher` |
| 14.9 Incentives and fraud | Daily incentive tiers; six fraud rules producing de-duplicated, event-time signals that a human reviews | `IncentiveService`, `FraudRules`, `FraudDetector` |
| 14.10 Polish | README (architecture, mermaid diagram), service map, deployment guide, interview walkthrough, these docs, and a frontend for every feature | `README.md`, `docs/*`, `frontend/ridex-web` |

Frontend added: Wallet, Earnings, Offers, Complaints, Reports (charts) and Fraud pages; booking now supports an offer code (with preview), "schedule for later", up to 3 stops and a surge notice; the ride page shows scheduled time, stops, a driver "Reached next stop" button and a complaint form.

## 2. What was verified

| Check | Result |
|---|---|
| Full backend build `mvnw verify` (needs Docker; Testcontainers) | **801 tests, 0 failures**: core 485, payment 67, analytics 22, pricing 27, gateway 40, notification 21, platform 36, architecture 103 |
| Frontend | `tsc` clean, `eslint` clean, **77 tests** pass, production build OK |
| New backend tests | ledger integration 9, money flow 24, coupon/legs/incentive rules, surge policy 9, matcher 8, ride features 13, complaints 7, analytics facts 10, fraud rules, gateway routes, permission matrix rows, `MoneyPolicyTest` (4 rules that keep the ledger's database guarantees, `MANDATORY` transactions, single writer of ledger tables, and "complaints reach payment by event" from being removed) |

**Not verified:** the new pages were type-checked, linted and built, but **not clicked through in a browser against the running stack**; the new endpoints were tested through Spring tests, not through a fresh Docker Compose or kind deployment; the migrations ran only on Testcontainers databases, not on an existing populated database.

## 3. What went wrong (and what it taught)

1. **Scheduled ride cancelled the moment it started searching.** The "no driver" timeout counted from `requested_at`. Fix: `search_started_at` and `Ride.searchWindowStart()`; a test promotes a ride late. *Lesson: a timeout needs the right start event.*
2. **An N+1 I introduced.** Adding `stops` to every ride response made each ride in a list load its stops lazily (48 queries for a page that must cost 3). The existing query-count test caught it. Fix: do not touch the collection when `stops_total = 0`, and `@BatchSize` for rides that do have stops. *Lesson: a new field on a shared DTO is a query-cost change.*
3. **A Phase 12 metric never counted.** `ridex_rides_requested_total` waited for a transition from `null`, which never happens. Fixed to count entries into `SEARCHING_DRIVER`.
4. **Rollback-only trap.** Insufficient funds as an exception poisoned the caller's transaction; it became a result value.
5. **Analytics mistakes:** `extract(epoch)` returns numeric not double; failed payments had no time to window by (added `event_at`); a helper hit a null final status.
6. **Existing tests needed updating, not hiding:** the state-machine table now has 9×9 moves (adds `SCHEDULED`); the timeout test sets `search_started_at`; and a registration test had a **pre-existing flake** (an unpadded random phone number could be too short), now zero-padded.
7. **A cache test (`CachingIntegrationTest`) failed once in the middle of a full run and passed alone and in the next full run.** I did not find the cause; a Redis connection error appeared in the same log. Treat it as a suspected order/load-dependent flake and watch for it.

## 4. Honest limits

- **Ride sharing is a matcher, not a feature.** No rider choice, no dispatch use, no seat accounting.
- No withdrawals or payouts; the wallet is practice money; the maximum balance is a soft cap (checked before the lock).
- Surge tables, step sizes and fraud thresholds are reasonable guesses, never tuned on data.
- Analytics are computed on demand (not cached or pre-aggregated); "top drivers" shows user ids, not names.
- Fraud signals only inform; there is no automatic action and no appeal flow.
- GitHub workflows from Phase 13 have still never run (no remote).
- The frontend is functional, not polished: no pagination on the coupon list, the complaint reply uses a plain input, the fraud page lists user ids.

## 5. Interview questions

See [interview-walkthrough.md](interview-walkthrough.md) for the long list. The four I would rehearse from this phase:

1. *How do you guarantee a ledger never loses or invents money?* Balanced entries enforced by a deferred DB trigger, append-only history, idempotency keys, ordered locks, an integrity endpoint, and a policy test that fails if those guarantees are dropped.
2. *Two requests redeem the last coupon at once. What happens?* The coupon row is locked (`for update`); one wins, the other is told `SOLD_OUT`; a CHECK constraint is the safety net.
3. *Why a table-polling promoter instead of timers?* Survives restarts and multiple replicas; each promotion is its own transaction that re-checks the status.
4. *Your change passed unit tests but broke a page's performance. How was it caught?* A test that counts SQL statements per page; the cause was a lazy collection added to a shared response.
