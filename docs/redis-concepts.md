# Redis, explained from zero

This document assumes you have never used Redis. Read it top to bottom once, then use the
"where in our code" lines to find each idea in the project. Every example below is something RideX
really does.

---

## 1. What Redis is

**Redis is a database that keeps its data in memory (RAM).**

PostgreSQL stores data on disk, in tables, and gives strong guarantees (transactions, constraints,
relationships). Redis stores data in RAM, as simple *key → value* pairs, and is extremely fast
(roughly 100,000+ operations per second on one machine, each in well under a millisecond).

Think of it this way:

| | PostgreSQL | Redis |
|---|---|---|
| Where data lives | Disk (durable) | Memory (fast; can be lost on crash unless configured) |
| Shape of data | Tables, rows, relations | Keys with values (string, number, hash, set, sorted set, geo...) |
| Good at | The truth: users, rides, payments | Speed: counters, expiring secrets, caches, live positions, locks |
| Speed | milliseconds | tenths of a millisecond |
| Queries | Powerful SQL | You must know the key you want |

**Rule we follow in RideX:** *anything we cannot afford to lose lives in PostgreSQL. Anything that is
temporary, fast-changing or derived lives in Redis.* If Redis were wiped, users and rides would be
fine; people would only have to log in again, request a new link, and drivers' apps would re-send their
location within seconds.

### Keys and values

A key is a string, like `verify:token:ab12...`. We use `:` to group keys into namespaces. All our keys
are listed in one file: [`RedisKeys.java`](../backend/ridex-app/src/main/java/com/ridex/ridex_app/redis/RedisKeys.java).

### TTL: the superpower

**TTL = time to live.** You can tell Redis: "delete this key after 15 minutes". Redis does it by
itself. No cleanup job, no `DELETE ... WHERE expires_at < now()`, no rows piling up.

```
SET verify:token:abc  user-42  EX 900     # gone after 900 seconds
TTL verify:token:abc                      # how many seconds are left
```

This is why short-lived secrets belong in Redis: "valid for 15 minutes" is the *same thing* as
"exists for 15 minutes".

---

## 2. The commands we use (a small vocabulary)

| Command | Meaning | Where we use it |
|---|---|---|
| `SET key value EX n` | Store, expire after *n* seconds | Verification/reset tokens, OTPs, heartbeats |
| `GET key` | Read | Everywhere |
| `DEL key` | Delete | Removing old links, logout |
| `GETDEL key` | **Read and delete in one atomic step** | Single-use tokens |
| `INCR key` | Add 1 (creates at 1) | Rate-limit counters, OTP attempts |
| `PEXPIRE key ms` | Set expiry in milliseconds | Inside the counter script |
| `SET key value NX PX ms` | Store **only if it does not exist**, with expiry | The distributed lock |
| `MGET k1 k2 ...` | Read many keys in one round trip | Heartbeat checks, revocation check |
| `GEOADD key lon lat member` | Save a position | Driver locations |
| `GEOSEARCH key ... BYRADIUS` | Find members near a point, sorted by distance | Nearby-driver search |
| `ZREM key member` | Remove from a sorted set | Removing stale drivers |
| Lua script (`EVAL`) | Run several commands as **one atomic step** | Counter + expiry, safe unlock |

---

## 3. Atomicity: why Redis is safe under concurrency

Redis executes **one command at a time**, in order. That sounds like a limitation, but it is the reason
it is easy to reason about: a single command can never be interrupted halfway.

**Problem it solves.** A password-reset link must work *once*. The naive approach:

```
1. value = GET reset:token:abc       # two clicks at the same instant both read it
2. if value exists: change password
3. DEL reset:token:abc               # both then delete it. Link used twice!
```

**Fix: `GETDEL`.** It does step 1 and 3 as a single command. Of 30 simultaneous requests, exactly one
receives the value and the other 29 receive nothing. No locks needed.

Where: `RedisTokenStore.take()`. Proven by `RedisPrimitivesIntegrationTest.ofManySimultaneousUsesOfASingleUseTokenExactlyOneWins`
(30 threads, exactly 1 winner) and by 8 simultaneous HTTP clicks on a verification link in
`VerificationWithRedisIntegrationTest`.

**When one command is not enough, use a Lua script.** "Increment a counter and, if it is new, start its
expiry" is two commands. If the server crashed between them, you would have a counter that never expires. A
Lua script runs all its commands as one uninterruptible unit:

```lua
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
return count
```

Where: `RedisTokenStore.increment()` and `RedisRateLimiter`.

---

## 4. What we use Redis for in RideX (step by step)

### 4.1 Redis itself (Step 4.1)

* `docker-compose.yml` has a `redis:7-alpine` container on port 6379.
* `spring-boot-starter-data-redis` gives us **Lettuce** (the client library) and `StringRedisTemplate`
  (a ready-made object with methods like `opsForValue().set(...)`).
* Memory policy: Redis is started with `--maxmemory 128mb --maxmemory-policy volatile-ttl`. If memory
  ever fills up, Redis may delete **only keys that have an expiry** (soonest-to-expire first). The driver
  location index has no expiry, so it is never evicted. (The default policy, `noeviction`, would instead
  refuse writes; `allkeys-lru` could delete our location index. Choosing the policy is a real decision.)

### 4.2 Verification and reset tokens, and OTP (Step 4.2)

**Before:** tokens were rows in `email_verification_tokens` and `password_reset_tokens` with an
`expires_at` column, a `used_at` column and a `FOR UPDATE` lock to stop double use. Expired rows stayed
forever unless we wrote a cleanup job.

**Now:** a token is one Redis key with a TTL.

```
verify:token:<sha256 of token>  →  user id        (expires in 15 min)
verify:user:<user id>           →  that hash      (so the newest link replaces the old one)
```

* *Expired* = the key is gone. *Already used* = `GETDEL` removed it. Both look the same: "invalid, expired
  or already used". (We lost the ability to say which; that is a small, honest trade-off.)
* We store the **hash** of the token, never the token. If someone could read Redis they still could not
  use the links. (Same idea as storing password hashes.)
* The migration `V11` drops the two old tables.

**OTP (one-time password)** is a 6-digit code emailed alongside the link, entered on the
verification page. Six digits is only 1,000,000 possibilities, so unlike the 256-bit link token it
*can* be guessed. We defend it three ways:

1. It expires in 10 minutes (TTL).
2. **At most 5 wrong attempts** (a Redis counter). After that the code is destroyed, even if the next
   guess is right.
3. The code is compared in constant time and stored only as a hash.

Code: [`EmailOtpService`](../backend/ridex-app/src/main/java/com/ridex/ridex_app/auth/EmailOtpService.java),
[`EmailVerificationService`](../backend/ridex-app/src/main/java/com/ridex/ridex_app/auth/EmailVerificationService.java).
Endpoint: `POST /api/auth/verify-otp`.

### 4.3 Caching (Step 4.3)

A **cache** keeps a copy of an expensive answer so the next request skips the work.

```
@Cacheable("riderProfile")          → first call runs the method and stores the result in Redis;
                                      later calls return the stored copy
@CacheEvict("riderProfile")         → delete the copy when the data changes
```

What we cache, and why those two:

| Cache | TTL | Why safe |
|---|---|---|
| `adminStats` (dashboard numbers) | 15 s | Nobody needs the exact count to the second. We never evict; the short TTL *is* the strategy. |
| `riderProfile` (saved places etc.) | 10 min | Read on every booking screen, changes rarely, and every change goes through one service that evicts. |

What we deliberately do **not** cache: anything about who is allowed in (account status, driver
approval), prices, ride state. A stale answer there is a bug, not an inconvenience.

> **"There are only two hard things in computer science: cache invalidation and naming things."**
> *Invalidation* means: when the real data changes, the cached copy must disappear, or users see old data.

Three protections we built in:

1. **Every entry has a TTL.** If some code path forgets to evict, the stale value still dies on its own.
2. **Transaction-aware eviction.** Evicting *inside* a database transaction is a race: another request can
   re-fill the cache with the OLD data between our evict and our commit. We configure
   `RedisCacheManager.transactionAware()`, which applies cache changes only **after commit**.
3. **A test that shows the problem.** `CachingIntegrationTest.writingAroundTheApplicationLeavesAStaleCopyUntilItIsEvicted`
   edits the row with raw SQL and proves the API serves the stale copy until evicted. Lesson: *every write
   must go through the code that evicts.*

Cache values are stored as plain JSON of one known class (no `@class` names inside), readable in
`redis-cli` and safe from "deserialize any class" attacks.

### 4.4 Driver location with Redis GEO (Step 4.4)

**The problem.** Every online driver's app sends its position every ~10 seconds. With 1,000 drivers that is
100 writes per second to PostgreSQL, rows that are overwritten seconds later, and a table that is hot
for no reason. We also never need yesterday's position.

**The idea.** Positions are temporary and read constantly, so they go in Redis.

**How GEO works.** `GEOADD drivers:geo <longitude> <latitude> <driverId>` converts a coordinate into one
number (a *geohash*: it interleaves the bits of latitude and longitude, so nearby places get nearby
numbers) and stores the driver in a **sorted set** using that number as the *score*. A search for
"within 5 km" turns into a few score-range lookups. No scanning of every driver.

Heads-up: Redis takes **(longitude, latitude)**, the opposite order of how people say coordinates. This is
a classic bug. Our code is commented where it matters.

**Heartbeat and stale drivers.** A sorted-set member cannot expire by itself, so each driver also gets
`driver:hb:<id>` with a TTL of 90 seconds, refreshed on every location update. If the driver closes the
app, the heartbeat expires.

* Searches check the heartbeat of every result and **ignore** drivers without one, removing them from
  the index on the spot ("lazy cleanup").
* A scheduled job (`StaleDriverCleanup`, every 60 s) purges the rest.

Going online writes to the database (durable fact: "this driver is online") and, **after the database
commits**, to Redis. Going offline removes them from Redis. Heartbeats write **only to Redis**, so the
database no longer takes a write every few seconds per driver.

### 4.5 Nearby-driver search (Step 4.5)

Matching now has three layers, each with one job:

```
1. Redis GEOSEARCH   →  "who is near the pickup?"   (fast, ordered by distance, only fresh heartbeats)
2. Redis lock        →  "is someone else assigning this driver right now?"   (cheap early check)
3. PostgreSQL        →  "is this driver REALLY free?" (approved, online, right vehicle, no active ride)
                        + row lock FOR UPDATE SKIP LOCKED
```

Redis knows *where* drivers are, but not whether they are approved, suspended or on a trip. PostgreSQL knows
that. Using both gives speed **and** correctness. We try up to 20 nearest candidates, in order, and stop at
the first one that passes all layers.

If Redis is unreachable, the search returns "nobody". The ride stays in `SEARCHING_DRIVER` and the
background job retries in 5 seconds. **A Redis outage delays rides; it never loses them or crashes.**

### 4.6 Distributed lock (Step 4.6)

**Race condition**: two things happen at the same instant and the result depends on who is faster. Two
riders request at the same moment; both see driver D as nearest; both try to assign D.

A Java `synchronized` block only protects one server's memory. With two servers it protects nothing. We need
a lock that all servers share. Redis can host one:

```
SET lock:driver-assign:<driverId>  <random token>  NX  PX 15000
```

* `NX` = "only if the key does not exist". Of many callers, exactly one succeeds.
* `PX 15000` = "expire in 15 s". If the server holding the lock crashes, the lock frees itself.
  This is called a **lease**.
* **The random token matters.** To release, we delete the key **only if it still holds our token** (a small
  Lua script). Without that check: our lease expires, someone else takes the lock, and then our slow
  `DEL` deletes *their* lock. Tested in
  `anOwnerWhoseLeaseExpiredCannotReleaseTheNewOwnersLock`.

We hold the lock until the **database transaction ends** (commit or rollback), not just until the method
returns. Releasing earlier would open a gap where the assignment is not yet committed.

**Honest limits (say these in an interview).** A lease can expire while the holder is still working (a long
GC pause), and one Redis instance is a single point of failure. So the lock is a *performance* tool that
prevents most collisions early. **The database is still the final authority**: the row lock
(`SKIP LOCKED`), the partial unique indexes on `rides`, and `@Version` optimistic locking all still apply.
*Defence in depth.*

### 4.7 Rate limiting and brute-force protection (Step 4.7)

A **rate limiter** counts how often something happens in a window of time and refuses beyond a limit.
Without one, anyone can try a million passwords or make us send a million emails.

**Fixed window** (what we use): key `rl:auth:<ip>` is incremented per request; the first request starts
a 60-second expiry; if the count exceeds the limit the answer is **HTTP 429 Too Many Requests** with a
`Retry-After` header saying how many seconds until the window resets.

* *Weakness:* at a window boundary a client can send the limit at the end of one window and again at the
  start of the next, so up to twice the limit in a short moment.
* **Sliding window** (a sorted set of timestamps, or two weighted counters) fixes that but costs more
  memory and work. For stopping password guessing and email spam, fixed is enough.

Our limits (all in `application.properties`, prefix `ridex.rate-limit`):

| Scope | Default | Why |
|---|---|---|
| `register`, `forgot-password`, `resend-verification`, `verify-otp` | 5 per hour per IP | These send email or check guessable codes |
| every `/api/auth/**` call | 30 per minute per IP | General abuse |
| every other `/api/**` call | 600 per minute per IP | Runaway clients |

**Brute-force lockout for login.** After 5 wrong passwords for the same email *from the same IP*,
further attempts get 429 for 15 minutes, **even with the correct password**.

*Why the key is email + IP and not just the email:* locking by email alone would let an attacker lock **you**
out of your own account just by guessing wrong passwords for you (a denial-of-service). With the IP in the
key, the attacker only locks *themselves* out. An unknown email is counted the same way, so the system
cannot be used to learn which emails exist.

**Fail open.** If Redis is down, the limiter *allows* requests (and logs a warning). Losing rate limiting
for a while is better than refusing every login.

**Client IP.** We use the TCP peer address and deliberately ignore `X-Forwarded-For`, because any client
can set that header to any value and pick its own bucket. Behind a real reverse proxy you configure the
proxy and Spring (`server.forward-headers-strategy`) instead.

### 4.8 Token revocation (Step 4.8)

**The gap from Step 2.5.** An access token is a **signed, self-contained** pass (a JWT). The server checks
the signature and the expiry date, not a database, which is what makes it fast and stateless. The cost:
once issued, it is valid for its full 15 minutes even if you suspend the user, log them out, or they reset
a stolen password. We close that gap with a small "deny list" in Redis.

```
revoked:user:<userId>  →  1760900000123      (a timestamp in milliseconds)
revoked:jti:<tokenId>  →  1                   (one specific token)
```

On every request the JWT filter does one `MGET` of those two keys:

* If the token's id is on the list → rejected (logout cancels exactly this token).
* If the token was **issued at or before** the user's timestamp → rejected (suspension, password reset
  and "refresh token reuse detected" cancel *every* token the user had so far).
* Tokens issued **after** the timestamp are fine, so reinstating a user and logging in again works.

**Why entries do not pile up:** each one is given a TTL equal to the token lifetime (+1 min). After that the
tokens it cancels would have expired anyway, and Redis deletes the entry. A deny list that never grows.

**Why a millisecond claim?** A standard JWT's `iat` (issued at) has *whole-second* precision. A token
created in the same second as a suspension could be misjudged. We add our own `iat_ms` claim.

**Fail open (decision).** If Redis is down, the filter accepts the token and logs a warning. Failing closed
would make a Redis outage take down the entire API. The exposure is bounded: tokens live 15 minutes and
every write endpoint re-checks the account in PostgreSQL.

---

## 5. Redis vs PostgreSQL: how to choose

Ask three questions about a piece of data:

1. **Can I lose it?** (If yes: Redis is allowed.) Tokens: yes, user logs in again. Ride history: **no**.
2. **Does it expire naturally?** (If yes: TTL makes Redis ideal.) Reset links, OTPs, heartbeats: yes.
3. **Is it hit constantly and changed constantly?** (Counters, positions, caches: Redis.)

Do **not** put in Redis: money, rides, anything needing relations, multi-row transactions, or SQL
reporting.

## 6. Failure handling summary (what happens if Redis dies?)

| Feature | Behaviour when Redis is down |
|---|---|
| Rate limiting | Fails **open** (requests allowed) |
| Token revocation check | Fails **open** (token accepted, warning logged) |
| Driver matching | Returns "nobody"; rides wait and are retried by the scheduler |
| Registration / verification / reset | Fails (500): these need the token store. Users retry later |
| Cache | The `@Cacheable` method throws (the default). A production setup adds a cache error handler to fall back to the database |
| Driver going online | Database state is saved; the next heartbeat re-adds them to the index |

## 7. Glossary

* **TTL**: time to live; how long until a key expires.
* **Atomic**: happens completely or not at all, with nothing in between.
* **GETDEL / NX / PX**: read-and-delete / only-if-absent / expire in milliseconds.
* **Lua script**: a tiny program Redis runs as one atomic unit.
* **Lease**: a lock with an expiry, so a crashed owner cannot block everyone forever.
* **Race condition**: a bug whose outcome depends on which of two simultaneous actions wins.
* **Cache invalidation**: making sure a cached copy disappears when the real data changes.
* **Fixed window / sliding window**: two ways to count requests over time.
* **Fail open / fail closed**: when the safety check itself is broken, allow everything / refuse everything.
* **Geohash**: a number made from latitude and longitude such that nearby places have nearby numbers.
* **Sorted set**: a Redis collection of members, each with a numeric score, kept ordered by score.
* **JWT / jti / iat**: a signed token / its unique id / when it was issued.
* **429**: the HTTP status for "too many requests".
* **Eviction policy**: what Redis deletes when it runs out of memory.

## 8. Look at it yourself with redis-cli

```bash
docker exec -it ridex-redis redis-cli
```

```
KEYS *                                  # everything (fine for development, never in production)
GET verify:user:<a user id>             # a verification hash
TTL otp:verify:<a user id>              # seconds until the OTP expires
ZRANGE drivers:geo 0 -1 WITHSCORES      # online drivers and their geohash scores
GEOPOS drivers:geo <driver profile id>  # a driver's position
GEOSEARCH drivers:geo FROMLONLAT 77.59 12.97 BYRADIUS 5 km ASC WITHDIST
GET "ridex:cache:riderProfile::<user id>"
SCAN 0 MATCH rl:* COUNT 100             # rate-limit counters
```
