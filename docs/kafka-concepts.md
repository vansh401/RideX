# Kafka and event-driven design, explained from zero

Everything here is something RideX really does. Each part ends with where to find it in the code.

---

## 1. The problem: one thing happens, many things must react

A ride is completed. Now:
* the rider must be told, and the driver;
* the rider must be charged;
* the analytics numbers must change;
* an audit trail must record it.

### The direct way (what we had in Phases 3 to 5)
`RideService.complete()` calls the notification code, then the payment code, then analytics...

Problems:
1. **Coupling.** The ride code now knows about notifications, payments and analytics. Add a fifth reaction and you edit the ride code again.
2. **One failure breaks everything.** If payments is slow or throws, the *ride completion* is slow or fails.
3. **One transaction cannot span everything.** If the ride commits and the next call crashes, the rider was never charged and nobody noticed.

### The event way
The ride code does one thing: it records **"ride X became COMPLETED"** as an **event** and stops. Anyone who cares *subscribes*.
New reactions are added without touching the ride code; a slow consumer does not slow the ride; and the events are stored, so a
consumer that was down catches up later.

Price: things become **eventually consistent**. The payment appears a fraction of a second *after* the ride completes, not in the same instant. The UI must be honest about it (our ride page shows "Processing...").

## 2. Kafka in one picture

**Kafka is a distributed, append-only log.** Producers append events to the end of a log. Consumers read the log at their own pace. Events are *not* deleted when read; they are kept for a retention period (7 days by default).

```
Producer --> [ topic: ride-events ]
              partition 0:  e0 e1 e2 e3 e4 ...        <- offsets 0,1,2,3,4
              partition 1:  e0 e1 e2 ...
              partition 2:  e0 e1 e2 e3 ...
                  |             |             |
        group "notifications"  group "analytics"  group "payments"     <- each reads ALL of it, independently
```

| Term | Meaning |
|---|---|
| **Broker** | A Kafka server. A cluster has several; we run one in development. |
| **Topic** | A named log of one kind of event (`ride-events`, `payment-events`). |
| **Partition** | A topic is split into partitions (we use 3). Each is its own ordered log and can live on a different broker and be read by a different consumer. This is how Kafka scales. |
| **Offset** | A message's position number inside its partition (0, 1, 2...). |
| **Key** | Chosen by the producer. Same key always goes to the same partition. |
| **Producer** | Writes messages. **Consumer**: reads them. |
| **Consumer group** | A set of consumers that *share* a topic: each message goes to **one** member. Different groups each get **everything** and track their own offset. |
| **Commit** | A consumer tells Kafka "I have handled up to offset N". After a restart it resumes there. |
| **KRaft** | Kafka managing itself (no ZooKeeper). Our container runs one node as both broker and controller. |

### Ordering: the rule that matters
Kafka guarantees order **only inside one partition**. So we use the **ride id as the key**: every event of one ride lands in the same partition, in order
(searching, assigned, accepted ... completed), while different rides spread over partitions and are processed in parallel.
Proven by `KafkaFundamentalsIntegrationTest` and `OutboxIntegrationTest.theRelayDeliversThemToKafkaKeyedByRideAndInOrder`.

### Queue vs log
A classic queue (RabbitMQ) hands each message to one reader and deletes it. A Kafka log keeps it, so **many independent readers** can each read everything, and a *new* reader can start from the beginning ("replay"). That is why "notifications", "analytics", "audit" and "payments" are four groups on the same topic.

## 3. Topic design (Step 6.2)

| Topic | Key | Holds |
|---|---|---|
| `ride-events` | ride id | one event per ride status change |
| `payment-events` | ride id | `payment.succeeded`, `payment.failed` |
| `ride-events.DLT`, `payment-events.DLT` | original key | messages that failed for good (section 7) |

Design rules we followed:
* **One topic per kind of fact**, not per consumer. Consumers come and go; the facts stay.
* **Events are facts in the past tense** ("ride.status-changed", "payment.succeeded"), not commands ("charge this rider").
* **Every event is wrapped in an envelope** with `eventId`, `type`, `version`, `occurredAt`, `key`, `data`. The version lets the shape evolve; the eventId lets consumers spot duplicates.
* **Do not put secrets in Kafka.** Messages are retained for days and readable by every consumer. That is why the verification and password-reset **emails (which carry tokens) were deliberately NOT moved to Kafka**; they stay in-process. Notifications contain no secrets, so they moved.

### REST vs events: which to use when
* **REST** (a request that needs an answer *now*): "show me my ride", "request a ride". The caller waits for the result.
* **Events** (something happened, others may react): "ride completed". The producer does not wait and does not care who listens.
* **WebSocket push** (Phase 5) is still used for instant screen updates: ephemeral, latency-critical, and fine to lose. Kafka is for things that must not be lost.

Code: `events/Topics`, `events/EventEnvelope`, `events/EventCodec`.

## 4. The dual-write problem and the outbox pattern (Step 6.2)

Completing a ride must (a) save to PostgreSQL and (b) publish to Kafka. They are **two different systems; no single transaction covers both.**

* Save, then send: crash between them = the ride is complete but **nobody is ever told**.
* Send, then save: the save fails/rolls back = everyone was told about **something that did not happen**.

### The outbox
1. Inside the **same database transaction** as the ride change, insert a row into `outbox_events`. Both commit or both roll back.
2. A separate **relay** reads unpublished rows, sends them to Kafka, and marks them published *after* Kafka confirmed.

```
Ride change + outbox row  --(one DB transaction)-->  PostgreSQL
Relay (every 500 ms): read rows not yet published -> send to Kafka -> wait for the ack -> mark published
```

Consequences:
* **Kafka down? Nothing is lost.** Events wait in the table and flow when it returns.
* **At-least-once delivery.** If the relay crashes after Kafka confirmed but before marking, the event is sent again. So consumers must cope with duplicates (section 6).
* **Order is kept.** The relay sends in `seq` order and *stops* at the first failure (skipping ahead would deliver later events first). A PostgreSQL **advisory lock** lets only one server relay at a time.
* The rows are deleted a day after publishing.

Proven by `OutboxIntegrationTest` (a rolled-back transaction leaves no event; a committed one does) and `OutboxRelayTest` (a failing broker marks nothing and sends nothing out of order).

Code: `events/OutboxService`, `OutboxRelay`, `RideEventOutboxListener`, migration `V13`.

## 5. Consumers: independent groups (Step 6.3)

| Group | Reads | Does |
|---|---|---|
| `notifications` | ride-events, payment-events | creates notifications (stored + pushed live) |
| `analytics` | ride-events | counts statuses per day (table `ride_status_counts_daily`) |
| `audit` | ride-events, payment-events | append-only log of every event with topic, partition, offset |
| `payments` | ride-events | charges the rider when a ride completes |
| `dead-letters` | the `.DLT` topics | parks failures where an admin can read them |

They do not know each other. Each keeps its own offset. If `analytics` is down for an hour the others are unaffected, and it catches up afterwards.

**Step 6.6, notifications through Kafka.** In Phase 5 an in-process listener created notifications right after the ride transaction (the idea from Step 2.7: one class tells another). Now `NotificationEventConsumer` does it from the event. The ride code no longer knows notifications exist. The cost: notifications arrive a fraction of a second later. The live ride screen does not wait for them (it uses the immediate WebSocket push).

Code: `notification/NotificationEventConsumer`, `analytics/AnalyticsConsumer`, `audit/AuditConsumer`, `payment/PaymentConsumer`.

## 6. At-least-once delivery and idempotent consumers (Step 6.4)

Delivery guarantees in messaging:
* **At most once**: may lose messages, never duplicates.
* **At least once**: never loses, **may duplicate**. (What we use.)
* **Exactly once**: very hard across systems; in practice you build *at-least-once + idempotency*.

How a duplicate happens: a consumer handles an event, then crashes **before** committing its offset. Kafka redelivers it.

**Idempotent** = doing it twice has the same effect as once. Our recipe (`ProcessedEvents`):

```sql
BEGIN;
INSERT INTO processed_events (consumer_group, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING;   -- 0 rows? duplicate: stop
-- ... do the work (create the notification, count the status, ...) ...
COMMIT;      -- the marker and the work are saved together or not at all
```

Because the marker and the work share one transaction: a crash in the middle rolls back both, so the retry really does the work; a redelivery after success finds the marker and skips.

Proven by `IdempotencyIntegrationTest`: the same event is delivered 3 more times and notifications, analytics counts and audit rows stay at one.

## 7. Failures: retries and dead-letter topics (Step 6.4)

A message can fail to process. Two kinds:
* **Temporary** (database busy, a row not visible yet): trying again may work.
* **Permanent** (broken JSON): trying again never helps.

Our rule (`KafkaConfig`):
1. Retry with growing pauses (0.5 s, 1 s, 2 s...), up to 4 attempts.
2. After the last attempt, publish the message to `<topic>.DLT` (**dead-letter topic**) together with the error, original topic, partition, offset and consumer group; then **move on**.
3. Permanent errors (`PoisonEventException`) skip step 1 and go straight to the DLT.

Why not retry forever? **A poison message would block its whole partition** and every event behind it would wait. Parking it lets everything else flow while a human investigates. The `DeadLetterConsumer` stores each one in `dead_letter_events`, and the admin Events page lists them.

(This uses *blocking* retries, simple and ordered. The alternative, retry topics with delays, avoids pausing the partition but breaks ordering and is more complex.)

Proven by `DeadLetterIntegrationTest`: garbage goes to the DLT of every group; a ride processed *after* the poison message still works; temporary failures are retried before giving up.

## 8. Payments and idempotency keys (Step 6.5)

A payment is the classic place where duplicates hurt: **charging twice**. Defence in depth, each layer catching the failure of the one before:

1. The consumer handles each event once in effect (`processed_events`).
2. The database allows **one payment per ride** (`UNIQUE ride_id`).
3. The automatic flow never charges a payment that is not `PENDING`.
4. Every charge carries an **idempotency key** (`ride-<id>-attempt-<n>`). A real gateway (Stripe, Razorpay) returns the *original* result when it sees a key twice and does not charge again. So "we asked twice because the first answer timed out" is safe. Our `MockPaymentGateway` behaves the same way (table `mock_gateway_charges`, one row per key).

A **retry** after a failure is a *new attempt* and therefore a **new key**; reusing the old key would just repeat the old decline.

Payment states: `PENDING` to `SUCCEEDED` (final, can never be charged again) or `FAILED` (can be retried by the rider).
The mock gateway declines amounts above a limit (`ridex.payments.mock.decline-above-amount`) so failures can be tried. No real money ever moves.

The payment result is itself an event (`payment.succeeded` / `payment.failed`, again through the outbox), which the notification consumer turns into "Payment received" / "Earning credited" / "Payment failed".

Code: `payment/*`, migration `V14`. Tests: `PaymentTest`, `PaymentIntegrationTest`.

## 9. Consumer lag (Step 6.7)

**Lag = newest offset in a partition minus the offset the group has committed**, summed over partitions.
* Lag 0: the group has handled everything.
* Lag growing: consumers are down or too slow. It is the first number you look at when a Kafka system misbehaves.

`GET /api/admin/events/lag` computes it with Kafka's admin API and the admin Events page shows it with the outbox backlog and the dead letters. `ConsumersIntegrationTest` asserts lag reaches 0 after a ride.

## 10. Kafka compared with what we already use

| | Redis pub/sub (Phase 5) | Kafka | RabbitMQ |
|---|---|---|---|
| Stores messages | No (fire and forget) | **Yes** (days) | Until consumed |
| Replay | No | **Yes** | No |
| Many independent readers | Yes, but live only | **Yes, each with own offset** | Needs one queue per reader |
| Ordering | Per channel | **Per partition (by key)** | Per queue |
| Best for | instant UI hints | **business events that must not be lost** | task queues, routing |

We use each where it fits: Redis pub/sub for "tell the open browser now", Kafka for "this happened, make sure everyone who matters hears it".

## 11. Glossary
* **Event**: a record that something happened. **Command**: a request to do something.
* **Producer / consumer / broker / topic / partition / offset / key / consumer group / commit**: see section 2.
* **Outbox**: a table that holds events until they are safely sent. **Relay**: the job that sends them.
* **Dual write**: updating two systems that cannot share a transaction.
* **At-least-once**: delivered one or more times. **Idempotent**: safe to repeat.
* **Idempotency key**: an id that makes a repeated request count once.
* **Dead-letter topic (DLT)**: where messages that failed for good are parked.
* **Poison message**: one that can never be processed.
* **Rebalance**: Kafka redividing partitions among a group's consumers when one joins or leaves.
* **Lag**: how far behind a consumer group is.
* **Eventual consistency**: all parts agree after a short delay, not instantly.
* **Retention**: how long Kafka keeps messages.

## 12. See it yourself

```bash
# What is in the topic? (each line is one event)
docker exec ridex-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic ride-events --from-beginning --property print.key=true

# Consumer groups and their lag
docker exec ridex-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups

# Topics and partitions
docker exec ridex-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe
```

Then walk a ride through and watch events appear, open the admin **Events** page, and look at **Payments**.
