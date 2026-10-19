# Ride history: choosing indexes from real queries (step 3.9)

**The rule:** an index is not decoration. Each one here exists because a specific query needs it, and
the numbers below were measured, not guessed.

## How this was measured
A throwaway PostgreSQL 17 (not your dev database) with all 10 migrations applied and generated data:
**200,040 rides** (170,000 completed, 30,000 cancelled, 40 in progress), 2,000 riders, 300 drivers,
spread over a year; the `rides` table is 46 MB. Each query ran with
`EXPLAIN (ANALYZE, BUFFERS)`, once with the index and, for the important ones, once without it.
"Buffers" = 8 KB pages touched: a direct measure of how much work the database did.

## 1. The queries come from the code, not from imagination

| Screen / job | Repository method | SQL shape |
|---|---|---|
| Rider "My rides" | `RideRepository.findByRiderId` | `WHERE rider_id = ? ORDER BY requested_at DESC LIMIT 20` |
| Driver "Ride history" | `findByDriverId` | `WHERE driver_profile_id = ? ORDER BY requested_at DESC LIMIT 20` |
| Admin ride list | `findByStatus` | `WHERE status = ? ORDER BY requested_at DESC LIMIT 20` |
| "Am I already on a ride?" | `findFirstByRiderIdAndStatusIn` | `WHERE rider_id = ? AND status IN (...) LIMIT 1` |
| Dispatch job | `findIdsWithExpiredOffer`, `findIdsByStatusOldestFirst` | `WHERE status = 'DRIVER_ASSIGNED' AND offer_expires_at < now()` |

Every one has the same pattern: **equality on a column, then sort by time, then LIMIT**. The index that
serves that pattern perfectly is `(equality column, requested_at DESC)`.

## 2. Results

| Query | Plan | Time | Pages touched |
|---|---|---|---|
| Rider history, page 1, **with** `idx_rides_rider_requested` | Index Scan, already sorted, stops after 20 rows | **0.032 ms** | **19** |
| Same, **index dropped** | Parallel Seq Scan of the whole table + sort | 15.5 ms | 5,985 |
| Admin list by status, **with** `idx_rides_status_requested` | Index Scan | **0.062 ms** | **23** |
| Same, **index dropped** | Parallel Seq Scan of 170,000 rows + sort | 24.7 ms | 5,985 |
| Driver history, `idx_rides_driver_requested` | Index Scan | 0.059 ms | 23 |
| Rider page 5 (`OFFSET 80`) | Bitmap scan of that rider's ~100 rides + sort | 0.125 ms | ~9 |
| `count(*)` for `totalElements` | Bitmap Index Scan (counts from the index) | 0.072 ms | 9 |
| The page as Hibernate runs it (rides + rider + driver + driver's user + vehicle) | Index Scan, then primary-key lookups | 0.154 ms | ~150 |
| "Is this rider on a ride now?" | `uq_rides_one_active_per_rider` | **0.014 ms** | **2** |
| Dispatch: expired offers | `idx_rides_offer_expiry` (partial) | 0.046 ms | 2 |

**Reading it:** the index version touches about **300 times fewer pages** (19 vs 5,985) and is about
**500 times faster** (0.032 ms vs 15.5 ms) on a table of 200,000 rows. And the gap *grows with the table*:
the indexed query stays flat (it only reads the newest 20 entries of one rider) while the unindexed one
reads everything. (A B-tree index grows only logarithmically: ten times more rides adds roughly one more
page read to the indexed query, but ten times more work to the full scan. This was measured at 200,000 rows,
not at larger sizes.)

## 3. Why `(rider_id, requested_at DESC)` and not two separate indexes
One composite index answers "this rider's rides" **and** "newest first" in a single descent. With a
separate index on `rider_id` only, PostgreSQL would find the rows and then still have to **sort** them.
Column order matters: equality column first, sort column second. The reverse order cannot serve
`WHERE rider_id = ?`.

## 4. Partial indexes: the same speed at 1/500th of the size

| Index | Size |
|---|---|
| `idx_rides_driver_requested` (full) | 10 MB |
| `idx_rides_rider_requested` (full) | 7.9 MB |
| `idx_rides_status_requested` (full) | 7.9 MB |
| `idx_rides_offer_expiry` (**partial**: only `DRIVER_ASSIGNED`) | **16 kB** |
| `idx_rides_searching` (**partial**: only `SEARCHING_DRIVER`) | **16 kB** |
| `uq_rides_one_active_per_rider` (**partial unique**: only unfinished rides) | **16 kB** |
| `uq_rides_one_active_per_driver` (**partial unique**) | **16 kB** |

Nearly every ride is finished, and the dispatch job and the "one active ride" rules only care about the
few that are not. A partial index (`CREATE INDEX ... WHERE status = ...`) contains only those rows, so it is
tiny, fits in memory, and is cheap to maintain. The two `uq_*` indexes do double duty: **they enforce a
business rule** (a rider or driver has at most one ride in progress) **and** answer the "who is busy?" lookup.

## 5. The honest cost of indexes
Every index makes `INSERT` and `UPDATE` slightly slower and uses disk. Rides are written a few times each
(request, offer, accept, arrive, start, complete) and read many times, so the trade is clearly good here.
That is also why there is **no** index on, say, `rides.category`: no query filters by it, so it would cost
and buy nothing. Indexes follow queries; add one when a real query needs it, and prove it with `EXPLAIN`.

## 6. OFFSET pagination and its limit (and the way out)
The API uses `page` and `size` (SQL `OFFSET`/`LIMIT`). That is fine for history, because one rider has
hundreds of rides, not millions. But on the admin list the cost grows with depth, because the database
must still walk past all the skipped rows:

| Admin list (170,000 completed rides) | Time |
|---|---|
| Page 1 | 0.062 ms |
| `OFFSET 100000` (page 5,000 of size 20) | **55.9 ms** (walks 100,020 index entries) |
| Keyset: `WHERE requested_at < <last row seen>` | **0.056 ms** (jumps straight to the place) |

For feeds that can be scrolled very deeply, **keyset pagination** ("rides older than the last one I saw")
stays constant. We keep OFFSET because it supports "jump to page 7" and the data is small; the upgrade path
is known and measured.

## 7. How to reproduce
```bash
docker run -d --name explain -e POSTGRES_PASSWORD=x -e POSTGRES_DB=explain -p 55432:5432 postgres:17
# apply backend/ridex-app/src/main/resources/db/migration/V1..V10 in order, load test data, then:
#   EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM rides WHERE rider_id = '<id>' ORDER BY requested_at DESC LIMIT 20;
docker rm -f explain
```
The application-level proof that a page costs a fixed number of SQL statements (no N+1) is the test
`RideDataIntegrationTest` (it counts real statements through Hibernate's statistics).
