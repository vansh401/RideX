# Microservices, explained from zero

This is the "why" behind Phase 7. Read it once from top to bottom; the [phase 7 review](phase-7-review.md) then shows exactly what was built, and the [service map](service-map.md) is the one-page picture.

---

## 1. What a microservice is (and what it is not)

A **monolith** is one program with one database. Everything, rides, payments, notifications, runs inside it and shares its memory and its transactions. That is not bad: until Phase 6 RideX was one, and it was easy to build, easy to test and fast.

A **microservice architecture** splits the system into several small programs ("services"). Each service:

* has **one business job** (price a trip, charge a rider, tell people things);
* is **started, deployed and scaled on its own**;
* **owns its data**: no other service reads or writes its database;
* talks to the others only through **explicit contracts** (HTTP APIs and events).

What it is **not**: "many small projects in one repository", or "one big program cut into pieces that still share a database". The second one is called a **distributed monolith**: all the costs of microservices (network, deployment, debugging) and none of the benefits. Avoiding it is the main idea of this phase.

### What you gain and what you pay

| Gain | Price |
|---|---|
| A failure stays in one place (payments down, rides still work) | Calls can fail, be slow, or arrive twice. Code must expect it |
| Scale only the busy part (more pricing instances at peak) | A transaction across two services no longer exists (section 8) |
| Teams release independently | More things to run, monitor and keep compatible |
| Each service can pick its own technology and database | Data is spread out; "join" queries across services are gone |

For a team of one on one laptop the honest answer is that a monolith is cheaper. We do it anyway **to learn** the patterns, and we do it **selectively** (section 2), exactly as a real company would.

---

## 2. Step 7.1: where to cut (boundaries)

The hardest question is not "how" but **"where"**. A bad cut costs more than no cut.

### The rule of thumb: cut where the coupling is already weak

Ask of any piece of code:

1. **Does it change for different reasons than its neighbours?** Pricing rules change when the business changes; ride state machines change when the product changes.
2. **Does it need the same database transaction as its neighbours?** If two things must succeed or fail *together*, keep them together.
3. **Can it fail without the user noticing?** Notifications can be late; a ride request cannot.
4. **Does it already communicate through events or a narrow interface?** Then the cut is nearly free.

Phase 6 prepared this: notifications, payments, analytics and audit already reacted to **events** and had no direct calls from the ride code. They were the natural first cuts.

### Bounded contexts

In domain-driven design, a *bounded context* is a part of the system inside which a word has one meaning. "Payment" means a charge with a status and an idempotency key *to the payment context*, and just "a thing that happens after a ride" *to the ride context*. A service boundary should follow a bounded context; the contexts talk through a small, deliberate vocabulary, the **published language**: our event schemas.

### What we cut and what we did not

| Part | Decision | Reason |
|---|---|---|
| Notifications | **Extracted** (`notification-service`) | Pure event consumer. Nobody waits for it. |
| Payments | **Extracted** (`payment-service`) | Different failure behaviour (external gateway), different data, already event-driven. |
| Pricing | **Extracted** (`pricing-service`) | Stateless pure function: the easiest possible service. |
| Analytics, audit, dead letters | **Extracted** (`analytics-service`) | Only listens. If it is down nobody notices. |
| Auth, users | **Stay in the core** | Every other piece needs "who is this?" on every request. See section 3. |
| Rides, drivers, matching, ratings | **Stay in the core** | One strong transaction: "accept this ride" touches the ride, the driver's availability and a lock. `FOR UPDATE SKIP LOCKED`, optimistic locking and a geo index all rely on one database. Splitting would trade easy correctness for a distributed saga with no benefit. |

> **"Why didn't you split everything?"** Because splitting is a cost, not a goal. Each cut must buy something (independent failure, scale, ownership). Rides, matching and drivers would only buy network hops inside the most latency-sensitive path, and would turn a single database transaction into a distributed one. Knowing where *not* to cut is the senior skill.

### Shared contracts, not shared code

Services need to agree on the **shape of events**. There are two wrong ways to do it:

* *Copy-paste the classes into every service.* The copies drift apart.
* *Put the domain classes in a shared library.* Now changing a field forces every service to be rebuilt and released together: a distributed monolith.

What we do instead:

* A **platform library** (`ridex-platform`) holds only **infrastructure and contracts**: the event envelope, the outbox, idempotent-consumer helper, JWT validation, error shape. No business rules. An automated test fails the build if it ever imports business code.
* The **contract itself** is a JSON schema in `contracts/events/`. Records in Java mirror it; contract tests check both agree ([event-contracts.md](event-contracts.md)).
* Consumers are **tolerant readers**: they ignore fields they do not know and need only the ones they use. That is what allows a producer to add a field without a coordinated release.

---

## 3. Step 7.2: data, tokens and "the monolith's database"

### Database per service

Each service has **its own database** (`ridex_db`, `notification_db`, `payment_db`, `analytics_db`). Not just its own tables in a shared database: its own database, so nobody *can* join across them.

Why this is the rule everything else depends on:

* If two services share tables, a change to a column in one breaks the other: they are coupled through the schema, invisibly.
* One service cannot lock, slow down or corrupt another's data.
* Each service can be moved, backed up and scaled independently.

The consequences you must accept:

* **No foreign keys across services.** `payments.ride_id` is just an id; the database cannot check that the ride exists. The service handles "unknown id" itself.
* **No joins across services.** To show "rider name + payment", ask each service and combine, or have an event deliver a copy of what you need.
* **Duplicated data is normal**, if it is a copy kept up to date by events (the core keeps its own small `rider_payment_blocks` table).

An automated test (`DatabasePerServiceTest`) reads every service's migrations and fails if one points a foreign key outside its own database or if two services create the same table.

### Migrating existing data: expand and contract

The notification and payment tables already existed in the core's database with real rows. You cannot "move" them atomically. The safe pattern is **expand / contract**:

1. **Expand.** Create the new table in the new service. Copy the old rows across (each service has a one-time importer that runs only if its table is empty and `LEGACY_DB_URL` is set). From now on new data goes to the new place. The old table is left alone.
2. **Verify.** Run both for a while. The old table is read by nothing.
3. **Contract.** Later, and by hand, drop the old table ([docs/sql/phase-7-contract.sql](sql/phase-7-contract.sql)).

Why not drop at once? Because step 3 is the only irreversible step. Between 1 and 3, a rollback is just "start the old version". This is the **strangler fig** idea: the new grows beside the old until the old can be removed safely.

### Token sharing: how does a service know who you are?

In a monolith, "who is calling?" is a method call. With several services, every one of them receives requests with a token and must decide whether to trust it, **without asking the core each time** (that would put the core in the middle of every request).

#### Option 1: shared secret (HS256). *What we had.*
The core signs tokens with a secret key; any service that knows the key can verify them. Problem: **knowing the key means being able to forge tokens.** Every extra service holding the secret is another place it can leak, and a compromised pricing service could mint an admin token.

#### Option 2: asymmetric keys (RS256). *What we use now.*
The core signs with a **private key** that never leaves it. It publishes the matching **public key** at `/.well-known/jwks.json` (a *JWKS*, JSON Web Key Set). Services download the public key and can **verify** a token but could never **create** one. Compromising a service does not compromise authentication.

```
core  --signs with PRIVATE key-->  token  ---->  browser ---->  any service
core  --publishes PUBLIC key at /.well-known/jwks.json
service --fetches public key, verifies signature, expiry and issuer--> trusts the claims (user id, role)
```

Every token carries a **key id** (`kid`), so keys can be **rotated**: publish a new key, start signing with it, keep the old public key until old tokens expire.

#### Logging out when tokens are self-contained
A signed token is valid until it expires, even if the user logged out or was suspended. Phase 4 solved that inside the core with a Redis revocation list. Services share the **same Redis** and only *read* it, so a revoked token is refused by every service immediately.

#### Service-to-service calls
When the core asks the pricing service for a quote there is no user involved. The call carries an `X-Service-Token` header (a secret shared by the two), compared in constant time, and is given the role `SERVICE`. A user token (even an admin's) cannot call internal endpoints. In production this would be mutual TLS or a service mesh; the idea, *a service proves who it is*, is the same.

---

## 4. Step 7.3: the notification service (the simplest extraction)

A **pure event consumer**: it has no one calling it for work. It reads `ride-events`, `payment-events` and `driver-events`, writes a row, and pushes a live message. It keeps its own database and exposes only "my notifications" over REST.

What it teaches:

* **Loose coupling in time.** If it is down for an hour, events wait in Kafka; when it returns it processes them in order. Nothing was lost, and the ride flow never noticed.
* **Idempotent consumers.** Kafka delivers *at least once*. The consumer records `(group, event id)` in the *same transaction* as its work, so a duplicate does nothing.
* **Retries and dead letters.** A failing message is retried with growing pauses; a message that can never work (garbage) goes straight to a `.DLT` topic so it cannot block those behind it.
* **How it reaches the browser.** The core owns the WebSocket connections. The service publishes to a Redis channel (`realtime:events`); every core server delivers it to its connected browsers.

---

## 5. Step 7.4: the payment service and resilience

The gateway (Stripe, Razorpay...) is somebody else's server, reached over a network. Networks and other people's servers fail in ways your own code never does. Resilience patterns exist to stop *their* bad day from becoming *your* outage.

### The four protections

| Pattern | Plain meaning | What it prevents |
|---|---|---|
| **Timeout** | Give up after N milliseconds | A hung gateway holding a thread forever; a few hung calls using up all threads |
| **Retry** | Try again after a short pause | A momentary glitch failing a payment. Only safe if the operation is *idempotent* |
| **Circuit breaker** | After too many failures, stop calling for a while | Piling more calls onto a gateway that is already struggling, and waiting for timeouts on every call |
| **Bulkhead** | At most N calls at the same time | One slow dependency consuming every thread, starving everything else |

#### The circuit breaker has three states
```
CLOSED  --failure rate too high-->  OPEN  --wait period passes-->  HALF_OPEN
  ^                                  (calls refused instantly)        |
  |__________ trial calls succeed ___________________________________|
                         (if the trial fails, back to OPEN)
```
Named after the electrical device: when something is wrong it *breaks the circuit* so the damage does not spread.

#### The order matters
Retry is outermost, then the circuit breaker, then the bulkhead: each retry attempt counts against the circuit, and a refused call (open circuit, full bulkhead) is **not** retried, since hammering them defeats their purpose.

### What counts as a failure?
A **decline** ("card refused") is a *normal answer*: the system worked and the answer was no. It is not retried and does not count against the circuit. Only **not getting an answer** (timeout, connection error, HTTP 5xx) is a failure.

### The hard case: "I do not know what happened"
Suppose we send a charge and the gateway **takes the money but its answer is lost** (timeout). If we assume failure and charge again, the rider pays twice. If we assume success we may never collect.

The answer is **idempotency keys**. Every charge carries a unique key; the gateway remembers it and returns the *original* result for a repeated key instead of charging again. So the payment service:

1. Treats "no answer" as **unknown**, not as failure: the payment stays `PENDING`.
2. Retries later with the **same key**. If the first attempt did go through, the gateway just says so.
3. After several outages (configurable) it gives up and marks the payment `FAILED`.

You can watch this: the admin Payments page has a panel that makes the test gateway return errors, hang, or *charge and answer too late*, and shows the circuit breaker react. A test (`aTimeoutAfterTheGatewayAlreadyChargedDoesNotChargeTwice`) proves the no-double-charge claim against a real database.

### Cascading failure
A **cascading failure** is when one slow service makes its callers slow, which makes *their* callers slow, until everything is down although only one thing was broken. Timeouts, bulkheads and circuit breakers are the firebreaks. The *design* choice that helps most: payment is **asynchronous**. A rider finishing a ride never waits for the gateway.

---

## 6. Step 7.5: synchronous vs asynchronous (REST vs Kafka)

Two services can talk in two styles. Choosing is a design decision, not a fashion.

| | Synchronous (REST/Feign) | Asynchronous (events) |
|---|---|---|
| Caller waits? | **Yes**: needs the answer to continue | **No**: fire and move on |
| Coupling in time | Both must be up at the same moment | Receiver can be down; it catches up |
| Failure handling | Caller must handle timeouts, retries, circuit breakers | Retries and dead letters in the consumer; the producer is not affected |
| Good for | **Questions**: "what does this cost?" | **Facts**: "this ride completed" |
| Latency | Adds the other service's latency to yours | Adds nothing to the caller |
| Consistency | Immediate | **Eventual** (section 7) |

### Our two choices
* **Pricing: synchronous.** A ride cannot be created without a fare, and the rider is waiting for the answer. So the core calls pricing over REST, protected by timeout, retry and circuit breaker. If pricing is down the answer is an honest **HTTP 503 with `Retry-After`**, never an invented price.
* **Payment, notifications, analytics: asynchronous.** Nobody is waiting; events are the right shape.

### Feign vs RestClient
Spring Cloud OpenFeign lets you declare a REST client as an interface (`@FeignClient`). It is popular, but ties the project to Spring Cloud versions. This project uses Spring's built-in `RestClient` wrapped by a small interface (`PricingClient`) which gives the same effect: the ride code depends on an interface, tests plug in a fake, and the HTTP details live in one class.

### Matching stays in the core
Matching is a *query + lock* over drivers' live positions in Redis and a row lock on the ride. Moving it out would add a network hop to the most latency-sensitive path and force the ride and the assignment to commit in different databases. It stays where its transaction is.

---

## 7. Step 7.6: services that only listen (eventual consistency)

The analytics service builds **read models** from events: daily counts, an audit log, dead letters. This is **CQRS** in miniature: the *write side* (the core) does not know the *read side* (analytics) exists.

### Eventual consistency
In a monolith, after a ride completes, every query immediately sees it. With events there is a gap: the ride is COMPLETED *now*; the daily count includes it a few hundred milliseconds later; the payment appears after that; the notification after that. The system is **eventually consistent**: given no new changes, all parts converge to the same truth.

How to live with it:

* **Design the UI for it.** The payment card on a completed ride says "Processing" until the event arrives (`404` until the payment exists is *expected*, not an error).
* **Make every step idempotent**, because "eventually" often includes "twice".
* **Know which data needs strong consistency** (a ride's state) and keep that in one database.
* **Expose lag.** The admin Events page shows each consumer group's *lag* (messages waiting). Lag 0 means converged.

### Replay
Because analytics is derived from events, it can be **rebuilt**: reset its offsets, empty its tables, replay. That is a superpower a shared database never gives you.

---

## 8. Step 7.7: workflows that span services

### Why distributed transactions are hard
In one database, a transaction is simple: do several changes, and either all happen or none (**ACID**). Across services there is no such thing by default.

* **Two-phase commit (2PC)** exists: a coordinator asks every participant "can you commit?" (*prepare*), then tells all "commit". It is avoided because:
  * it **blocks**: after voting yes, a participant holds its locks until the coordinator answers. If the coordinator dies, everyone waits;
  * it requires every participant (and the broker, the HTTP gateway...) to support it, and external systems like a payment provider do not;
  * it multiplies latency and **reduces availability**: the transaction works only if *all* participants are up.
* The fallacy is believing the network is reliable. **CAP theorem** in one sentence: when the network splits, you must choose between consistency and availability; real systems choose availability and repair afterwards.

### Sagas: a chain of local transactions with compensation
A **saga** replaces one big transaction with a sequence of small local ones. If a later step fails, earlier steps are **compensated**: a new action that counteracts them.

Two styles:

* **Choreography**: services react to each other's events; there is no central controller. Simple, loosely coupled; the overall flow is only visible by following the events. *This is what RideX uses.*
* **Orchestration**: a central "orchestrator" tells each service what to do next and handles failure. Easier to see and change; the orchestrator is a new component that knows the whole flow.

### The RideX saga: ride, charge, settle

```
core:     ride COMPLETED (local transaction) ──► event ride.status-changed
payment:  charge the rider (local transaction) ──► event payment.succeeded  or  payment.failed
core:     on payment.failed:  COMPENSATE → block the rider from booking until the money is settled
          on payment.succeeded: lift the block
```

**Why the compensation is a block and not a rollback:** the trip already happened. Nothing can make the rider "not have ridden". A compensation is rarely a technical undo; it is a *business* action that limits the damage. Others would be a refund (after a double charge), a credit note, or escalating to support.

Properties we guarantee, and how:

| Concern | How |
|---|---|
| The event is never lost | **Outbox**: saved in the same transaction as the ride change; a relay publishes it |
| The event is never processed twice | **Idempotent consumer**: `(group, event id)` stored with the work |
| A retry never charges twice | **Idempotency key** on the gateway call |
| Order within a ride | Kafka key = ride id, so one ride's events share a partition |
| Settling ride B does not unblock ride A | One block row per unpaid ride |

### Idempotency, the thread through everything
At every hop something can repeat: Kafka redelivers, the relay resends after a crash, the rider double-clicks, a timeout is retried. **Every operation that can repeat must be safe to repeat.** That single habit is most of distributed-systems engineering.

---

## 9. Testing across services

* **Unit tests** for rules inside a service.
* **Integration tests** per service against *real* PostgreSQL, Kafka and Redis (Testcontainers). Other services are simulated by publishing the events they would publish.
* **Contract tests** check events against the JSON schemas, both what producers write and what consumers read.
* **Architecture tests** fail the build if a service imports another, if the platform library gets business code, or if one database points into another.
* **Fault tests**: gateway errors, hangs, answers that arrive too late; the pricing service returning 500s or hanging.

Missing on purpose and worth knowing about: end-to-end tests across *all* processes at once (slow, flaky, and the contract + per-service tests cover the same risks more cheaply), and consumer-driven contract tools like Pact (the same idea, with tooling).

---

## 10. Vocabulary

| Term | Meaning |
|---|---|
| Bounded context | A part of the domain where each word has one precise meaning |
| Distributed monolith | Separate deployables that cannot change or fail independently |
| Database per service | Only the owner touches its data |
| Published language | The agreed shape of events/APIs between services |
| Tolerant reader | Ignore what you do not know, require only what you use |
| Strangler fig | Grow the new beside the old, remove the old when safe |
| Expand / contract | Add the new schema, migrate, only then remove the old |
| JWKS | A published set of public keys used to verify tokens |
| Circuit breaker / bulkhead / timeout / retry | The four resilience patterns (section 5) |
| Idempotency | Doing it twice has the same effect as doing it once |
| Outbox | Save the event with the change; publish afterwards |
| Eventual consistency | Parts agree after a short delay, not instantly |
| Saga | A multi-service process made of local transactions plus compensations |
| Compensation | A new action that counteracts an earlier one |
| 2PC | Two-phase commit: a distributed transaction protocol, rarely used because it blocks |
| API gateway | One public entry point that routes to the right service |
