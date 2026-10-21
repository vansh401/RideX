# Observability: concepts (Phase 12)

Phases 1 to 11 built a system and made it run in several copies. Phase 12 answers the question you will be asked at 3 a.m.: **"what is it doing right now, and why is it slow or broken?"** You cannot attach a debugger to production, so the system has to tell you. It does that in three ways, usually called the three pillars:

| Pillar | Question it answers | Shape of the data | Tool here |
|---|---|---|---|
| **Logs** | What happened, in this one case? | Text events with details | JSON on stdout (read with `docker logs`; a log system in production) |
| **Metrics** | How much, how fast, how often, over time? | Numbers sampled every 15 s | Micrometer, Prometheus, Grafana |
| **Traces** | Where did the time go for this one request, across services? | A tree of timed steps (spans) | OpenTelemetry, Jaeger |

They complement each other. A metric tells you **that** something is wrong (error rate up since 14:02). A trace tells you **where** (the pricing call inside the booking request takes 4 s). A log tells you **why** (the exact error). The glue between them is **ids**: a request id, a trace id, a ride id that appear in all three.

## 1. Structured logging (12.1)

**Plain text** (`2026-10-21 18:04 INFO Ride status changed: SEARCHING -> DRIVER_OFFERED`) is good for a human reading one file, bad for a machine searching a million lines from twelve containers. **Structured** logging writes each line as one JSON object:

```json
{"@timestamp":"2026-10-21T18:04:14Z","log":{"level":"INFO","logger":"RideMetrics"},"service":{"name":"ridex-app"},
 "message":"Ride status changed: SEARCHING_DRIVER -> DRIVER_ASSIGNED",
 "requestId":"6f1c…","traceId":"a58e…","userId":"2043…","rideId":"32f9…"}
```

Every field can be filtered on: "all lines with `rideId=32f9…` from every service, sorted by time" is a one-line query in Loki, Elasticsearch or CloudWatch, and the story of that ride appears across the core, the payment service and the notification service.

**How it works here.** Spring Boot has structured logging built in; the profile `json` (file `platform/src/main/resources/application-json.properties`) switches it on: `logging.structured.format.console=ecs` (Elastic Common Schema, a widely understood field naming). Compose and Kubernetes start the services with `SPRING_PROFILES_ACTIVE=json`; on a developer machine you keep readable text.

**MDC: the logging context.** MDC (Mapped Diagnostic Context) is a per-thread map of key/values that the logging library attaches to every line written by that thread. We fill it at the edges of the work:

| Key | Set by | Meaning |
|---|---|---|
| `requestId` | `RequestIdFilter` (from the gateway's `X-Request-Id`) | one HTTP request, across gateway and services |
| `traceId`, `spanId` | the tracing library | one trace, across HTTP **and Kafka** |
| `userId` | the authentication filters, after the token is verified | who acts (a UUID, never a name or email) |
| `rideId` | `RideLogContextConfig` (URL has `{rideId}`), `RideService.request`, payment code, and `KafkaLogContextInterceptor` for events | which ride |
| `eventId`, `eventType` | `KafkaLogContextInterceptor` | which event is being handled |

Two rules make MDC safe: **always remove what you set** (`finally`), because threads are reused and the next request would inherit the values; and remember **MDC is per thread**, so work handed to another thread loses it unless the context is copied (that is why trace ids need the tracing library, which does the copying).

**What never to log.** Logs are copied to other systems, kept for months and read by many people; a secret in a log is leaked for good.
- Never: passwords, tokens and keys, one-time codes, card or bank data, full email addresses and phone numbers, request or response bodies.
- Ids are fine: user id, ride id, payment id. If you must mention an address, mask it: `LogContext.maskEmail("asha.rao@example.com")` gives `a***@example.com`.
- **Exceptions** are logged with their stack trace, and their *message* can contain data (a mail server error quoting the address). Think about what an exception can carry.
- Use levels honestly: `ERROR` = somebody should look; `WARN` = something unexpected that the system handled; `INFO` = business events worth a line (a ride changed status); `DEBUG` = off in production.

`LogHygieneTest` reads every logging call in the main code of all services and fails if an argument looks sensitive (password, token, otp, email, payload ...). It found three real mistakes the first time it ran (log lines that printed email addresses); they now use `maskEmail`.

## 2. Metrics (12.2)

A **metric** is a number with a name, labels and a time. Applications do not store history; they expose the **current** values, and a server (Prometheus) **pulls** them regularly ("scraping") and stores the series. Pull has a nice property: if a service cannot be scraped, that is itself a signal (`up == 0`).

**Micrometer** is the measuring library in Spring (like SLF4J for logs): code records into a `MeterRegistry`, and a registry implementation (`micrometer-registry-prometheus`) exposes it as text at `/actuator/prometheus`:

```
http_server_requests_seconds_count{application="ridex-app",method="POST",status="201",uri="/api/rides"} 12
hikaricp_connections_active{application="ridex-app",pool="HikariPool-1"} 1
ridex_rides{application="ridex-app",status="RIDE_STARTED"} 4
```

**The four metric types** (learn these, they are asked in every interview):

| Type | Behaviour | Example | How you read it |
|---|---|---|---|
| **Counter** | only goes up (resets on restart) | requests served, payments failed, dead letters | `rate(x_total[5m])` = per second; `increase(x[1h])` = how many in an hour |
| **Gauge** | goes up and down | active rides, DB connections in use, outbox backlog, heap used | read directly; `max_over_time` for peaks |
| **Timer / histogram** | counts events and spreads their durations into **buckets** | request latency | `histogram_quantile(0.95, rate(x_bucket[5m]))` = p95 |
| **Summary** | like a histogram but computes percentiles inside each application | (avoid) | cannot be added up across copies, which is why we use histograms |

**Why percentiles, not averages.** If 99 requests take 10 ms and one takes 10 s, the average is 110 ms and describes nobody. p99 says "1 % of users wait 10 s". Percentiles must be computed from **buckets** (`management.metrics.distribution.percentiles-histogram.http.server.requests=true`) so Prometheus can combine the copies of a service correctly; you cannot average the p95 of two pods.

**Labels and cardinality.** Every distinct combination of label values is a separate time series that costs memory. `status`, `method`, `uri` (as a *template*, `/api/rides/{rideId}`) are fine. A label holding a user id or ride id would create millions of series and kill Prometheus. That is why ride ids go in **logs and traces**, never in metric labels.

**What we measure.**
- **Free from Spring/Boot:** HTTP server requests (count, duration histogram, status, uri), JVM (heap, GC, threads), Tomcat, **HikariCP database pool** (`hikaricp_*`), Kafka client metrics (`kafka_consumer_fetch_manager_records_lag_max` = consumer lag), Spring Kafka listener timings, Spring Cloud Gateway route timings.
- **Added by us:**
  - `ridex_rides{status}` (gauge: rides per status, "active rides" is the non-final ones), `ridex_ride_status_changes_total{status}`, `ridex_rides_requested_total` (counters, counted *after* commit so a rolled-back change is never counted).
  - `ridex_payments_total{outcome=succeeded|failed|gateway_unavailable}`.
  - `ridex_outbox_pending` (gauge: events saved but not confirmed by Kafka), `ridex_outbox_published_total`, `ridex_outbox_publish_failures_total`.
  - `ridex_dead_letters_total{topic}` (counter, registered at 0 so it is visible before the first failure).
- Gauges that need the database (rides per status, outbox backlog) read it at most every 10 s however often Prometheus scrapes, and report `NaN` ("unknown") if the database is unreachable instead of a stale number.

**Security of the endpoint.** `/actuator/prometheus` is open (no token) because Prometheus must read it, but it is only reachable **inside the network**: the gateway blocks `/actuator/**` for outside traffic and the ingress/nginx never route it. Exposed metrics reveal internals (paths, versions, load) and must never be public.

## 3. Dashboards (12.3)

A dashboard is a set of queries drawn as panels. Good dashboards follow a method rather than a pile of graphs:
- **RED** for every service that handles requests: **R**ate (requests/s), **E**rrors (share failing), **D**uration (latency percentiles).
- **USE** for resources: **U**tilization, **S**aturation, **E**rrors (DB pool active vs max vs waiting, heap used, Kafka lag).
- Plus **business** panels (active rides, payments by outcome), because "the servers are green and nobody can book a ride" is the failure that matters.

The dashboard **RideX overview** has a `Service` selector, six headline numbers (services up, requests/s, error rate, p95, active rides, outbox backlog) and panels for each of the above. It is provisioned from files (`infrastructure/observability/grafana/`) so it is code in git, reviewed like code, and identical on every machine. Panels have descriptions that explain what to conclude.

## 4. Distributed tracing (12.4)

A request to book a ride touches the gateway, the core, the pricing service, the database, and then, minutes later and elsewhere, the payment and notification services through Kafka. Logs of each service do not tell you which pieces belong together or where the time went. A **trace** does.

- **Trace**: the whole story of one request, identified by a `traceId`.
- **Span**: one timed step inside it (an HTTP call, a database query, handling an event), with a `spanId`, a parent, a start and a duration. Spans form a tree.
- **Context propagation**: for the tree to cross a process boundary, the caller must **send** the trace context and the callee must **continue** it. On HTTP this is the W3C header `traceparent: 00-<traceId>-<spanId>-<flags>`. Spring does it automatically for incoming requests, for outgoing calls made with its HTTP clients, and for the gateway's proxied requests (`spring.cloud.gateway.server.webflux.observability.enabled=true`).
- **Sampling**: tracing everything is expensive. `management.tracing.sampling.probability` decides what share of traces is **sent** to the backend (1.0 in the demo; 1 to 10 % in production). The trace id exists for every request either way, so logs always carry it.
- **Export**: spans are sent by **OpenTelemetry** (the vendor-neutral standard) using OTLP to a backend; **Jaeger** stores and shows them.

**Propagation through Kafka, and why the outbox breaks it.** Kafka messages have headers, and Spring Kafka can put a `traceparent` header on a message it sends and continue it in the listener. But RideX does not send from the request: the **outbox relay** sends later, on a scheduler thread that has no request and no trace. A naive setup would give every consumed event a brand-new unrelated trace. The fix, in three steps:
1. When the event is saved (`OutboxService.append`), the current `traceparent` is stored in a new column `outbox_events.traceparent`, in the same transaction.
2. When the relay publishes the row, `TraceContextCarrier.publishWithin` starts a span `outbox publish <type>` **as a child of the stored context** and writes the new span's `traceparent` into the Kafka headers.
3. The listener (`spring.kafka.listener.observation-enabled=true`) reads the header and continues the trace.

The result is one trace: gateway → core (HTTP, pricing call, DB) → `outbox publish` → payment/notification/analytics consuming the event. The same idea applies to any "work queue" that decouples producer from consumer: the trace context must travel **in the data**.

## 5. What to alert on (12.5)

An alert interrupts a human. Bad alerts teach people to ignore alerts. Rules of thumb used in `alerts.yml`:

1. **Alert on symptoms, not causes.** Users feel errors and slowness; they do not feel CPU at 80 %. Page on "5 % of requests fail", not on "pod restarted".
2. **Exception: causes that will become symptoms soon and give you time to act**: the DB pool nearly full, the outbox growing, a consumer falling behind, heap near its limit.
3. **Every alert must have an action.** If the answer to "what do I do?" is "nothing", it is a graph, not an alert. Each of our alerts has an `action` annotation.
4. **Wait before firing (`for:`).** A condition must hold for minutes; a 10-second blip should not wake anyone.
5. **Guard ratios with minimum traffic.** 1 failed request out of 2 is "50 % errors" and means nothing.
6. **Severity levels.** `critical` = someone acts now (service down, many errors, payments failing); `warning` = look during working hours.
7. **Group and inhibit.** Alertmanager sends one message for a group of alerts and suppresses the noise that a bigger alert explains (if the service is down, its high-latency alert is irrelevant).
8. **Test your alerts.** `alerts.test.yml` feeds fake series to `promtool test rules` and checks which alerts fire and what they say.

| Alert | Fires when | Severity |
|---|---|---|
| `ServiceDown` | a service cannot be scraped for 1 min | critical |
| `HighErrorRate` | over 5 % of requests are 5xx for 5 min, with real traffic | critical |
| `PaymentsFailing` | over 30 % of payment attempts not successful for 10 min | critical |
| `HighLatency` | p95 above 1 s for 10 min | warning |
| `DatabasePoolNearlyFull`, `RequestsWaitingForADatabaseConnection` | pool above 90 %, or threads waiting | warning |
| `JvmHeapAlmostFull` | heap above 90 % for 10 min | warning |
| `OutboxBacklog` | over 100 events waiting for 5 min | warning |
| `DeadLetters` | any message moved to a dead-letter topic | warning |
| `KafkaConsumerLagging` | consumer over 1000 messages behind for 10 min | warning |
| `RidesStuckSearching` | over 20 rides waiting for a driver for 10 min | warning |

**Service level objectives (SLOs)** are the mature form of this: "99.5 % of booking requests succeed within 1 s over 30 days" defines an *error budget*, and alerts fire when the budget burns too fast (burn-rate alerts). We use simple thresholds here; SLO burn rates are the next step.

## 6. How the pieces connect

```
 browser ──▶ gateway ──▶ core ──▶ pricing           each service exposes /actuator/prometheus  ──▶ Prometheus ──▶ Grafana
               │          │  └──▶ DB (outbox row + traceparent)                                      │
               │          ▼                                                                          └─▶ rules ──▶ Alertmanager ──▶ email
               │      outbox relay ──▶ Kafka ──▶ payment / notification / analytics
               ▼                                  (continue the trace from the message header)
        spans (OTLP) from every service ───────────────────────────────────────────────────────────────▶ Jaeger
        JSON log lines (requestId, traceId, userId, rideId) on stdout ──▶ docker logs / a log system in production
```

## 7. In Kubernetes

The same applications and the same metrics; only the plumbing changes: Prometheus finds pods through service discovery (annotations or a `ServiceMonitor` from the Prometheus Operator), logs are collected from the nodes (Promtail/Fluent Bit to Loki/Elasticsearch), spans go to an OpenTelemetry Collector, and alert rules become `PrometheusRule` objects. The HPA of Phase 11 can then scale on request rate instead of CPU (custom metrics through an adapter).
