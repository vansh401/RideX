# Phase 12 review: observability

Read [observability-concepts.md](observability-concepts.md) first; it explains the ideas (logs, metrics, traces, alerting). This is the review: what changed, how to run and check it, what went wrong, what is still missing, and how to defend it in an interview.

## 1. What changed, step by step

| Step | What we built | Where |
|---|---|---|
| 12.1 Structured logging | Profile `json`: one ECS-format JSON object per line. Logging context (MDC) filled with `requestId`, `traceId`/`spanId`, `userId`, `rideId`, `eventId`, `eventType`. Ride story logged as business events. `maskEmail` for the rare time an address must be mentioned. **LogHygieneTest** fails the build when a log call prints something sensitive | `platform/.../web/LogContext`, `application-json.properties`, `KafkaLogContextInterceptor`, both authentication filters, `RideLogContextConfig`, `LogHygieneTest` |
| 12.2 Actuator, Micrometer, Prometheus | `/actuator/prometheus` on every service and the gateway, metrics tagged with the service name, latency histograms. Our own metrics: rides per status, ride status changes, payments by outcome, outbox backlog, outbox publish counters, dead letters | `RideMetrics`, `PaymentService`, `OutboxMetrics`, `OutboxRelay`, `PlatformKafkaAutoConfiguration`, every `application.properties` |
| 12.3 Grafana dashboards | Provisioned from files: **RideX overview**, 19 panels (rate/errors/duration per service, DB pool, heap, Kafka lag, outbox, dead letters, rides by status, payments, gateway routes) | `infrastructure/observability/grafana/` |
| 12.4 OpenTelemetry tracing | Spans for HTTP, database, Redis, Kafka; sent to **Jaeger**. The trace **survives the outbox** (the `traceparent` is saved in the outbox row and restored by the relay). Outgoing HTTP calls of the core and the payment service propagate the trace | `TraceContextCarrier`, migration `V16`/`V1001` (`outbox_events.traceparent`), `HttpPricingClient`, `GatewayConfig` (payment) |
| 12.5 Alerts | 11 Prometheus alert rules in two severities, Alertmanager routing alerts by email to Mailpit, **unit tests of the rules** with `promtool` | `prometheus/alerts.yml`, `alerts.test.yml`, `alertmanager/alertmanager.yml` |
| Run it | A Docker Compose **overlay** (the main file is unchanged), scripts to start, stop, check the config and smoke-test everything | `docker-compose.observability.yml`, `scripts/obs-*.ps1` |

## 2. Run it and see it

```
docker stop ridex-control-plane                 # the kind cluster would take the memory
cd infrastructure\docker-compose ; docker compose build ; cd ..\..
.\scripts\obs-up.ps1                            # (-Only core,gateway,pricing,payment,notification,web on a small machine)
.\scripts\obs-smoke.ps1 -Alerts                 # proves metrics, dashboard queries, logs, traces, alert email
```

Then open Grafana (http://localhost:3001), Jaeger (http://localhost:16686), Prometheus (http://localhost:9090, Alerts tab), Mailpit (http://localhost:8025). To follow one ride by hand: request a ride, copy its id from `docker logs ridex-core`, search the logs of the other services for that `rideId`, and open the `traceId` from the same line in Jaeger.

`.\scripts\obs-test-rules.ps1` checks `prometheus.yml`, the alert rules (5 scenarios on fake data), and `alertmanager.yml` without starting the stack. Remove everything: `.\scripts\obs-down.ps1 -Volumes`, then `docker rmi` the four images.

## 3. What was verified (measured on this laptop)

- **Metrics:** Prometheus scrapes every running service (analytics was parked to fit memory). All 19 dashboard panels return data, apart from "server error ratio by service", which is empty by design while there are no 5xx errors. Spring's own Kafka consumer lag, HikariCP pool and JVM metrics are exported with no extra code.
- **Logs:** every line of the core is a valid JSON object; a ride's lines carry its `rideId`, `traceId` and `userId`; the payment service logs "Event received" with the same ride id; no email address, password or token appears in the core log.
- **Traces:** a single trace of 14 spans crosses **api-gateway, ridex-app, pricing-service, payment-service, notification-service**: gateway → core (HTTP, security, Redis) → pricing call → `outbox publish ride.status-changed` → both consumers (`ride-events process`). Health checks and scheduler ticks are no longer traced.
- **Alerts:** stopping the pricing service made `ServiceDown` fire within the `for: 1m` plus scrape time, an email `[FIRING:1] ServiceDown pricing-service` arrived in Mailpit, and after the restart the alert resolved and a `[RESOLVED]` email followed.
- **Tests:** 651 in the full build (platform 36, core 433, notification 21, payment 19, pricing 19, analytics 7, gateway 39, architecture 77). New: `TraceContextCarrierTest` (with a real OpenTelemetry tracer), `OutboxMetricsTest`, `RideMetricsTest`, `StructuredLoggingTest` (real JSON output), `LogHygieneTest`, `ObservabilityPolicyTest`, extra cases in the outbox relay and permission matrix tests.

## 4. What went wrong on the way (and what it taught)

1. **The trace stopped at the outbox.** The relay publishes on a scheduler thread, so events had no trace. Fix: store the `traceparent` with the event (new column) and restore it as the parent of an `outbox publish` span. Test: the same trace id with a new span id after the round trip.
2. **The pricing service was missing from the trace.** The core built its client with `RestClient.builder()`, which Spring does not instrument. Fix: pass the application's `ObservationRegistry` (and the same for the payment gateway client). Lesson: auto-instrumentation only covers clients built through the framework's builders.
3. **Jaeger showed nothing at first, and I almost blamed the services.** The spans were arriving; Jaeger 2 no longer serves the old `/api/services` (it has `/api/v3` in OTLP-JSON) and the query tool I used got a 404. For the same reason Grafana's Jaeger data source cannot work with Jaeger 2, so it is not provisioned (the dashboard links to the Jaeger UI instead).
4. **Noise.** The first traces were dominated by Docker health checks, Prometheus scrapes, Spring Security's four spans per request and the outbox relay's tick every 500 ms (a trace per run). Fix: an `ObservationPredicate` that ignores `/actuator` and `/api/health`, and turning off scheduled-task and Spring Security observations. Lesson: tracing 100 % is useless if most of it is noise; decide what is worth a span.
5. **A starter did something I did not ask for.** `spring-boot-starter-opentelemetry` also pushes metrics over OTLP (to `localhost:4318`), failing with a warning every minute. We use Prometheus, so it is switched off.
6. **LogHygieneTest found three real leaks on its first run**: log lines printing email addresses (`PasswordResetEmailListener`, `VerificationEmailListener`, `AdminSeeder`). They now use `LogContext.maskEmail`.
7. **A gauge that read `NaN` in a test.** Micrometer holds the object a gauge reads from only weakly; with nothing else referencing it, it was garbage-collected. In the application Spring keeps the bean alive, but the gauges now use `strongReference(true)`.
8. **Two mistakes of my own in the smoke script**, both PowerShell: a variable `$alerts` clashing with the switch `-Alerts` (variable names are case-insensitive), and `@($null).Count` being 1.
9. **Grafana did not notice a changed dashboard file** until it was restarted (Docker bind-mount file sync). The dashboard `version` is bumped when the JSON changes.
10. **Environment.** The kind cluster and the monitoring stack do not fit in the 4 GB Docker VM together, so the cluster is paused during Phase 12. One full build failed because Mockito could not attach its agent to the test JVM (an environment hiccup; same tests passed minutes before and after), and one gateway rate-limit test failed once under load (it passed 3 of 3 alone). These time-based tests should be made deterministic with a controllable clock.

## 5. What is still not good enough (say this in the interview)

- **Logs are not collected.** They go to stdout and `docker logs`. A real setup ships them to Loki, Elasticsearch or CloudWatch and links a log line to its trace. We made them *ready* (JSON, ids), not collected.
- **No exemplars or log-to-trace links in Grafana.** Jumping from a slow latency bucket to a trace needs exemplars and a trace data source that speaks Jaeger 2 or Tempo.
- **Traces are kept in memory** in Jaeger and lost on restart. Sampling is 100 % in the demo; production would sample 1 to 10 %, or use tail-based sampling to keep the slow and failed ones.
- **Postgres, Redis and Kafka themselves are not monitored** (no exporters). We see them only through their clients (DB pool, Kafka lag, Lettuce spans).
- **Thresholds are guesses.** They come from reasoning, not from an SLO and a measured baseline. The next step is SLOs with burn-rate alerts.
- **Alerts go to a mail catcher.** A real receiver (pager, chat) with credentials from a secret store is needed, and on-call routing.
- **Kubernetes is not wired.** The metrics, logs and trace headers work there too, but the Prometheus discovery, collectors and `PrometheusRule` objects are described, not built. The kind cluster was paused, not used, in this phase.
- **The metrics endpoint has no authentication**, protected by the network only (gateway blocks it, ingress never routes it). In Kubernetes a NetworkPolicy should allow only Prometheus.
- **WebSocket traffic is not traced**, and the browser sends no trace context (no frontend instrumentation).
- **analytics was not part of the live demo** (parked for memory); its config is identical to the others and covered by the policy tests.

## 6. Interview questions

**Q: What is observability, and how is it different from monitoring?**
Monitoring watches known failure modes with known checks. Observability is the ability to ask new questions about a running system from the data it emits (logs, metrics, traces) without shipping new code. We emit all three and connect them with ids.

**Q: The three pillars, and when do you use each?**
Metrics tell you *that* something is wrong and how big it is (error rate up). Traces tell you *where* in the chain of services (the pricing call takes 4 s). Logs tell you *why* (the error and its details). You usually move metrics → trace → logs, using the trace id.

**Q: Why JSON logs, and what is MDC?**
A log system can index fields only if they are fields. MDC is a per-thread map attached to every log line; we put requestId, traceId, userId and rideId there at the edges (filters, interceptors) and remove them in `finally`, because threads are reused. It is per thread, so work handed to another thread loses it unless the context is copied.

**Q: What must never be logged?**
Passwords, tokens, keys, one-time codes, card and bank data, full emails and phone numbers, request bodies, and exceptions that quote them. Ids are fine. We enforce it with a test that scans every logging call, and it found three leaks.

**Q: Counter, gauge, histogram?**
Counter only goes up and is read as a rate; gauge goes up and down (active rides, DB connections); histogram spreads observations into buckets so percentiles can be computed and combined across instances. Summaries cannot be combined across instances.

**Q: Why percentiles and not the average?**
The average hides the slow minority: 99 requests at 10 ms and one at 10 s average 110 ms. p95/p99 describe what the unlucky users get. They are computed from histogram buckets with `histogram_quantile`, which is why we enable percentile histograms.

**Q: What is cardinality and why does it matter?**
Each distinct label combination is a separate time series. Labelling a metric with a user or ride id creates millions of series and kills Prometheus. High-cardinality ids belong in logs and traces.

**Q: Pull or push?**
Prometheus pulls: a service that cannot be scraped is itself a signal (`up == 0`), targets need no knowledge of the monitoring system, and you can curl the endpoint to debug. Push suits short-lived jobs (Pushgateway).

**Q: Explain a trace, a span and context propagation.**
A trace is one request's whole story; spans are timed steps in a tree; propagation passes the trace and parent span ids across process boundaries, on HTTP in the `traceparent` header and on Kafka in a message header.

**Q: How does a trace cross Kafka when events leave through an outbox?**
It does not by itself, because the relay publishes later on another thread. We store the `traceparent` in the outbox row in the same transaction, and the relay starts an `outbox publish` span as its child and writes the new `traceparent` into the Kafka headers. The consumer, with listener observation on, continues the trace. The test proves the same trace id and a new span id.

**Q: What would you alert on, and what not?**
Symptoms users feel (error rate, latency, payments failing, service down) and causes that become symptoms soon with time to act (DB pool, outbox backlog, consumer lag). Not on every cause (CPU at 80 %). Each alert needs an action, a `for:` duration, minimum traffic for ratios, and a severity. Group and inhibit to avoid storms (if the service is down, its latency alert is noise).

**Q: How did you test the alerts?**
`promtool test rules` feeds fake series and checks which alerts fire, when, and with which text. Then a live test stops a service and checks the alert fires, the email arrives, and the resolution is sent.

**Q: Tell me about something that went wrong.**
The first trace of a ride showed the gateway and the core but nothing from pricing, and consumers started unrelated traces. Two causes: the HTTP client was built without the observation registry (so no `traceparent` header went out), and the outbox broke the context. I fixed both and wrote a test with a real OpenTelemetry tracer that checks the trace id survives the round trip. Also, the first dashboard of traces was 90 % health checks; I excluded them with an observation predicate.

**Q: How would you run this in production?**
Prometheus Operator with `ServiceMonitor`/`PrometheusRule`, long-term metrics storage, logs collected to Loki or Elasticsearch, an OpenTelemetry Collector in front of the trace backend with tail sampling, Grafana with exemplars linking metrics to traces, SLO-based burn-rate alerts to a pager, and NetworkPolicies limiting who can read `/actuator/prometheus`.
