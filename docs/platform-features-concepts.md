# Platform features: surge, scheduling, stops, sharing, complaints, analytics, fraud

## 1. Surge pricing (step 14.1)

Price follows scarcity: when many people want rides and few drivers are free, the multiplier rises, which dampens demand and attracts drivers.

**Who does what** (each service keeps to its data):

| Service | Job |
|---|---|
| core (`ridex-app`) | **Measures** demand (rides requested in the last 10 minutes per ~2 km zone, a 0.02° grid cell) and supply (drivers in the zone from Redis GEO), runs `SurgePolicy`, **publishes** `surge:<cell>` to Redis with a 120 s TTL and a set `surge:zones` |
| pricing | **Reads** `surge:<cell>`, sanitises it to [1.0, cap] and applies it. A missing or garbage value means 1.00: **pricing never fails because surge is unavailable** |

`SurgePolicy` is pure (easy to test): ignore zones below 5 requests; ratio = demand ÷ drivers; a table maps ratio to a *target* (e.g. ≥3 → 2.0); the multiplier moves toward the target in **steps** (+0.3 up, −0.2 down) so prices do not jump; hard cap 3.0. The TTL is a safety net: if the core stops, surge disappears by itself within two minutes.

The multiplier used for a ride is stored with it and included in the ride event, so analytics can chart surge later. **Scheduled rides ignore surge** (the rider booked a known price).

## 2. Scheduled rides (14.8)

New status `SCHEDULED`, deliberately **not** "active": it does not block the rider from booking another ride, and the driver search has not started.

- Rules: at least 15 minutes ahead, at most 7 days, at most 5 scheduled per rider.
- A `ScheduledRidePromoter` polls the **table** (`status = SCHEDULED and scheduled_for <= now + 10 min`), not in-memory timers. A restart loses nothing. Each ride is promoted in its own transaction that re-checks the status, so if two servers race, the loser sees the ride is no longer `SCHEDULED` and does nothing.
- If the rider is still on another ride, promotion waits and tries again; after 15 minutes past the booked time the ride is cancelled as missed. Promotion moves the ride to `SEARCHING_DRIVER` and records `search_started_at`. **Lesson from a real bug here:** the "no driver found" timeout used to count from `requested_at`, so a ride booked yesterday was cancelled the second it was promoted. The window now starts at `search_started_at` (`Ride.searchWindowStart()`), with a test that promotes a ride late.

## 3. Multiple stops (14.8)

Up to 3 stops. Pricing adds each extra leg's distance and 3 minutes per stop. The driver presses "Reached next stop"; **completing the trip is refused until every stop is visited** (so a driver cannot skip stops and still be paid for them).

## 4. Ride sharing (14.8): concept plus a tested matcher

`SharedRideMatcher` is a pure function that decides whether two ride requests can share a car: pickups within 2 km of each other, and neither rider's detour too long (rider A at most 1.35× their direct distance, rider B 1.5×). A shared-fare discount exists. **It is not connected to dispatch**: no endpoint lets a rider choose "share", and dispatch never calls the matcher. That would need pooling windows, seat accounting and a re-pricing flow; the pure core is built and tested so that work can start from correct, tested rules.

## 5. Complaints (14.7)

Workflow: `OPEN → IN_REVIEW → RESOLVED | REJECTED`. Rules enforced on the server:

- Only a rider or driver **of that ride** can complain, within 14 days of the ride, **one open complaint per ride per person**.
- Comments are **public or internal**; internal notes never reach the complainant (the view is built per role).
- Resolving can include a refund; that is published as an event and executed by the payment service, bounded by what was paid. If the refund fails, it stays visible and retries; the complaint is not blocked on the payment gateway.

## 6. Admin analytics (14.6)

The analytics service never reads other services' tables. It builds its own **fact tables** from events: `ride_facts` (one row per ride, upserted as each status event arrives; `coalesce` keeps what earlier events already told us), `payment_facts`, `complaints_daily`. Every update is idempotent, so replaying the topic rebuilds the same data.

Chart endpoints (`/api/admin/analytics/overview|daily|categories|hours|zones|top-drivers`) are SQL over those tables. "Day" and "hour" use the **business time zone** (Asia/Kolkata), not UTC, and days with no rides return zeros so charts have no holes. The numbers are not cached (they are computed on demand; at real scale you would pre-aggregate).

## 7. Fraud detection (14.9)

Six rules, each a pure function with thresholds that are **guesses to be tuned**: a rider cancelling ≥4 times within an hour, a driver with ≥3 cancellations within a day, a trip "completed" in under 90 s with a real fare (≥₹100), the same rider-driver pair ≥3 times a day, ≥3 coupons a day, and refund-heavy riders (≥3 refunds in 30 days returning ≥50% of what was paid).

Principles: a rule produces a **signal** for a human, never an automatic ban (false positives hurt real people; `fraud/summary` shows each rule's dismissed share, which is its false-positive rate). Signals are **de-duplicated** by a `dedupe_key` (the same pattern raising 50 alerts is noise) and use **event time**, not "now", so a replay does not invent new signals.

Real fraud systems add device fingerprints, payment-network signals and machine learning; those need data and consent this project does not have.
