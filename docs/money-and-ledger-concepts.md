# Money in RideX: wallet, ledger, coupons, refunds, earnings

Phase 14 turned "a payment row with an amount" into a system that can answer: *where did every rupee go, and can the books ever be wrong?* This document explains the ideas. The code is in `backend/payment-service` (packages `ledger`, `wallet`, `coupon`, `refund`, `earnings`).

## 1. Why a ledger, not a balance column

The naive wallet is `users.balance`, changed with `balance = balance + x`. It fails three ways: you cannot say **why** it changed, two concurrent updates can lose one (or overdraw), and a bug silently creates or destroys money.

A **double-entry ledger** fixes this with one rule: money is never created or destroyed, only **moved between accounts**.

- **Account**: a named place money can be (a rider's wallet, a driver's earnings, `PLATFORM_REVENUE`, `PROMO` for coupon budget, `GATEWAY` for the card network).
- **Transaction**: one business event ("ride 42 paid").
- **Entry**: one line of a transaction, `+` into an account or `−` out of it. **The entries of a transaction always sum to zero.**
- A balance is *derived*: the sum of the account's entries (we keep a running `balance_after` for fast statements).

Example, a ₹200 ride, rider pays ₹150 from the wallet plus ₹50 coupon, commission 20% of the ₹200 total:

| Account | Entry |
|---|---|
| rider wallet | −150 |
| PROMO (platform pays the discount) | −50 |
| driver earnings | +160 |
| platform revenue | +40 |
| **sum** | **0** |

### What makes it trustworthy (defence in depth)

| Guarantee | Enforced by |
|---|---|
| Entries of a transaction sum to zero | a **deferred constraint trigger** in PostgreSQL, checked at commit; the application cannot bypass it |
| History cannot be edited | **append-only triggers** (UPDATE/DELETE on entries raise an error); corrections are new, compensating transactions |
| The same request twice moves money once | an **idempotency key** (unique per transaction); the second call returns `DUPLICATE`, not a second movement |
| No overdraft | `INSUFFICIENT_FUNDS` is checked under a row lock |
| No deadlocks | accounts are locked **in id order** whenever a transaction touches several |
| Can be audited | `GET /api/admin/ledger/integrity` re-adds everything and reports any non-zero transaction or balance mismatch |

**A design trap worth knowing:** "not enough money" is a normal business outcome, so `LedgerService.post` *returns* a status (`POSTED`, `DUPLICATE`, `INSUFFICIENT_FUNDS`) instead of throwing. In Spring, an exception that crosses a `@Transactional` boundary marks the whole transaction rollback-only, even if the caller catches it, and the later commit fails with `UnexpectedRollbackException`. A result value avoids that.

`post` requires an existing transaction (`Propagation.MANDATORY`): a ledger movement must be part of the caller's business transaction (payment succeeded **and** money moved, or neither).

## 2. Paying for a ride

Order of attempts when a ride completes (`PaymentService`):

1. Work out the **discount** (coupon) and the **net** amount the rider owes.
2. Take as much as possible from the **wallet**.
3. Charge the **card** (gateway) for the remainder. If the gateway fails, the wallet part is **not** kept: either the payment succeeds as a whole, or it fails and can be retried.
4. Split the fare: commission (20% of the ride total, configurable) to the platform, the rest to the driver.
5. Publish `payment.succeeded` (now with discount, method and amounts) after commit, through the outbox.

`ridePaymentLegs(...)` is a **pure function** (inputs in, list of legs out), so the arithmetic is tested without a database, including rounding: legs are computed so they always add up to the total to the paisa.

## 3. Coupons

A coupon is a rule: percent or flat, optional cap (`maxDiscount`), minimum fare, validity window, total uses, uses per rider.

- `evaluate` / `discountFor` are pure and used by both the **preview** (`GET /api/coupons/{code}/preview?fare=`: "what would I save?", uses nothing) and the real redemption.
- **Redemption is a counting problem, and counting is where concurrency hurts.** "Only the first 100 riders": 120 requests all read `used=99` and all succeed. The fix: `select ... for update` on the coupon row. Redemptions of one coupon queue for a few milliseconds; different coupons do not wait for each other. A `CHECK (used_count <= max_total_uses)` and `UNIQUE (ride_id)` are the nets underneath (the unique key also makes redeeming idempotent).
- The discount is **platform-funded**: it comes out of `PROMO`, so the driver's earnings are calculated on the full fare. The rider pays less, the driver is not punished.

## 4. Refunds

A refund is **not** an edit of the old payment. It is a new, compensating movement.

- Bounded by what was actually paid: `refundable = paid − already refunded`. A reservation is recorded on the payment inside the same transaction, so two simultaneous refunds cannot together exceed it.
- Wallet-paid money returns to the wallet at once. Card-paid money goes back through the **gateway** (`POST /refunds`). If the gateway is down, the refund stays `PENDING` and a scheduler retries with the **same idempotency key**, so a retry after a timeout cannot refund twice.
- Complaints trigger refunds by **event** (`complaint.resolved` with a refund amount), not by a synchronous call: the complaint service and the payment service stay independent (saga-style compensation).

## 5. Driver earnings and incentives

- `GET /api/driver/earnings`: today, 7 days, 30 days, bonuses, a 30-day daily series and recent trips, all from ledger entries on the driver's account. Cached in **Redis** for 60 s (cache-aside), evicted **after commit** of any new earning, so a rolled-back transaction can never evict-then-reload stale data.
- **Incentives**: daily tiers (`10:100,20:250` = 10 trips earns ₹100, 20 trips earns ₹250). A nightly job pays yesterday's bonus once per driver per day (idempotency key `incentive:<driver>:<day>`), so re-running the job is harmless. `POST /api/admin/incentives/run` runs a given day on demand.

## 6. Things deliberately NOT built

No withdrawals/payouts to bank accounts, no real card network, no tax invoices, no multi-currency. The wallet has a soft maximum balance, checked before the lock (two simultaneous top-ups could pass it slightly; a hard cap would need the check under the lock).
