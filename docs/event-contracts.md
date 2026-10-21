# Event contracts

Events are the **published language** between services. Changing one carelessly breaks other teams' code at runtime, where no compiler can warn you. So events have written, tested contracts.

## 1. What is a contract here

| Piece | Where |
|---|---|
| JSON schemas (the written agreement) | `backend/platform/src/main/resources/contracts/events/*.json` |
| Java records that mirror them | `backend/platform/src/main/java/com/ridex/platform/contracts/` |
| Topic names, event type names | `Topics`, `EventEnvelope` |
| Tests that keep all three in agreement | `ContractSchemaTest`, `EventCodecTest` (platform module) |

### The envelope (every event)
```json
{
  "eventId": "7b2f...",            // unique; consumers use it to spot duplicates
  "type": "payment.failed",        // what happened
  "version": 1,                    // the shape of "data"
  "occurredAt": "2026-10-20T10:15:30Z",
  "key": "<ride id>",              // what it is about; also the Kafka message key
  "data": { ... }                  // the facts (see schemas)
}
```

### The events
| Type | Topic | Published by | Read by | Schema |
|---|---|---|---|---|
| `ride.status-changed` | `ride-events` | core | notification, payment, analytics, audit | `ride.status-changed.v1.json` |
| `payment.succeeded`, `payment.failed` | `payment-events` | payment | notification, core (saga), analytics, audit | `payment.v1.json` |
| `driver.application-reviewed` | `driver-events` | core | notification, audit | `driver.application-reviewed.v1.json` |

## 2. Rules for changing an event

The goal: **a producer can be released without waiting for any consumer, and the other way round.**

| Change | Safe? | How |
|---|---|---|
| Add an optional field | Yes | Consumers ignore unknown fields (tolerant reader). Add it to the schema as optional |
| Add a new event type | Yes | Consumers ignore types they do not know |
| Make an optional field required | No | Needs a new version |
| Rename or remove a field | **No** | Add the new field, keep the old one for a transition period, migrate consumers, then remove it in a new version |
| Change a field's type or meaning | **No** | New version |
| Change a topic name | **No** | Publish to both, migrate consumers, retire the old topic |

To make a breaking change: publish **version 2** next to version 1 (a new `version` in the envelope, a new schema file), move the consumers, and only then stop publishing version 1.

## 3. How consumers should read

* **Parse into a record that lists only the fields you use.** The shared codec ignores unknown properties, treats a missing number as its default, and keeps decimals exact (`252.00` stays `252.00`).
* **Check `type`** and ignore the ones you do not handle.
* **Check `version`** if you rely on a shape that may change.
* **Be idempotent.** The same event can arrive twice. Use `ProcessedEvents.runOnce(group, eventId, work)` so the work and the "done" marker commit together.
* **Throw `PoisonEventException`** for a message that can never be processed; it skips retries and goes straight to the dead-letter topic.

## 4. Money in events

Amounts are decimals (`252.00`), never floating point arithmetic on the consumer's side. The codec reads decimals as `BigDecimal`. Currency is a 3-letter code next to every amount.

## 5. Testing a contract

* **Producer side:** the records, serialised exactly as the outbox writes them, are validated against the schema.
* **Consumer side:** the sample events used by every service's tests (`EventTestSupport`) are validated against the same schema, so a consumer test can never pass against an event shape the producer does not send.
* **Drift:** every property in a schema must exist in the record and vice versa.
* **Breakage:** a test proves the validator catches a "harmless" field rename and a changed type.

The checker (`MiniJsonSchema`) is deliberately small: it supports the keywords our schemas use. For a larger system, replace it with a full JSON-Schema validator or a consumer-driven contract tool such as Pact; the *tests' intent* stays the same.
