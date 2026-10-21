# Phase 11 review: scaling and failure

Read [scaling-concepts.md](scaling-concepts.md) first; it explains the ideas and holds the measured tables. This is the review: what changed, how to repeat it, what went wrong, what is still not good enough, and how to defend it in an interview.

## 1. What changed, step by step

| Step | What we built | Where |
|---|---|---|
| 11.1 Copies and traffic distribution | A **scale overlay** (2 gateway, 3 pricing, 2 core pods) on top of dev. `X-Instance` (pod name) on every service response, `X-Gateway-Instance` on every gateway response. A load generator that counts which pods answered. A demo that measures the spread at the ingress and at the gateway-to-service hop, with and without connection reuse | `infrastructure/k8s/overlays/scale/`, `InstanceHeaderFilter`, `GatewayInstanceFilter`, `scripts/loadtest/LoadTest.java`, `scripts/k8s-scale-out.ps1`, `k8s-scale-in.ps1`, `k8s-lb-demo.ps1` |
| 11.2 Autoscaling and load test | `HorizontalPodAutoscaler` for pricing (3 to 5 locally; prod overlay 3 to 10). Installer for **metrics-server**. A demo that loads pricing and prints CPU, current and wanted pods every 15 s | `overlays/scale/hpa.yaml`, `overlays/prod/hpa.yaml`, `scripts/k8s-metrics-server.ps1`, `k8s-autoscale-demo.ps1` |
| 11.3 Failure experiments | Kill one pod of pricing, gateway, core, redis, kafka, postgres under steady load and report requests, failures and success rate | `scripts/k8s-failure-experiments.ps1` |
| 11.4 WebSockets across replicas | A dependency-free Node WebSocket/STOMP client and a driver that opens 12 sockets, changes a ride via REST, kills the core pod holding most sockets, and checks delivery before and after | `scripts/loadtest/ws-cross-pod.mjs`, `scripts/k8s-websocket-demo.ps1` |
| Tests | 5 new Kubernetes policy tests; unit tests for both instance-header filters | `KubernetesPolicyTest`, `InstanceHeaderFilterTest`, `GatewayInstanceHeaderTest` |

Application code changed only a little: the two header filters, plus nothing else (the core already had the Redis realtime bridge from Phase 5).

## 2. Run it and see it

```
.\scripts\k8s-up.ps1                      # Phase 10 cluster, if not running
.\scripts\k8s-scale-out.ps1               # 11.1  several copies
.\scripts\k8s-lb-demo.ps1 -ConnectionPerRequest
.\scripts\k8s-metrics-server.ps1          # 11.2  once
.\scripts\k8s-autoscale-demo.ps1
.\scripts\k8s-failure-experiments.ps1     # 11.3  (about 12 minutes)
.\scripts\k8s-websocket-demo.ps1          # 11.4
.\scripts\k8s-scale-in.ps1                # back to one copy
```

Remove metrics-server with `.\scripts\k8s-metrics-server.ps1 -Remove`. Do not build images while the cluster runs: the Docker VM has 4 GB and the engine hangs.

## 3. Results (measured on this laptop)

- **Ingress to gateway:** 50.0 % / 50.0 % in every run, even for one client (per-request balancing).
- **Gateway to pricing, connections reused:** uneven, for example 27 / 35 / 38 % and 38 / 35 / 28 % (per-connection balancing). **Reuse off:** 35.5 / 31.9 / 32.6 % and 33.2 / 33.4 / 33.4 %, but one client fell to 47 req/s.
- **Autoscaling:** pricing 3 to 5 pods under 60 clients (CPU 360 % then 1000 to 1350 % of request), about 90 s until the new pods were ready, 0.38 % of requests timed out in that window, then back to 3 pods about 2 minutes after the load stopped.
- **Killing one pod under load:** pricing 0 failed of 10,085; gateway 0 of 10,338; core 6 of 6,294 (99.90 %); redis 12 of 10,923 (99.89 %); kafka 0 of 14,400; postgres 44 of 12,215 (99.64 %).
- **WebSockets:** 12 sockets split 7 / 6 over two core pods. All 12 got both live updates although REST was handled by one pod. Killing the pod with the most sockets dropped 5 of them; all reconnected in 1 s and all 12 got the next update.
- **Tests:** full backend build 623 tests (platform 25, core 428, notification 21, payment 19, pricing 19, analytics 7, gateway 39, architecture 65 of which 28 are Kubernetes policy).

## 4. What went wrong on the way (and what it taught)

1. **A correct-looking test of the wrong thing.** The first kill experiments for core, Redis, Kafka and Postgres reported "4 % success". Almost every answer was HTTP 429: the **core has its own rate limiter** (600 requests a minute per client address, Phase 4) and all the load arrived from two gateway addresses. Fix: raise it in the scale overlay only (`RIDEX_RATELIMIT_APILIMIT`, test only, a policy test checks it exists nowhere else), rerun. Lesson: look at *what* the failures are before reading anything into a failure rate.
2. **The Postgres experiment did not hurt at first.** `/api/users/me` does not query the database on each call, so killing Postgres cost almost nothing. Switched to `GET /api/rides` (a real database read). Lesson: an experiment must exercise the component it kills.
3. **A feature that passed its unit test and did not work.** I added a `server-instance` header to the STOMP `CONNECTED` frame. The unit test fed a `CONNECTED` message to the interceptor and passed; in the real server the broker emits a `CONNECT_ACK` that is converted to the frame without custom headers, so the header never appeared. The WebSocket demo even printed "PASS" because it could not tell the pods apart and counted them as one. Fixes: the interceptor and its test were deleted; the client now refuses to pass without evidence, and the driver counts established connections per pod from `/proc/net/tcp`. Lesson: test through the real stack, and make a demo fail when it cannot prove its claim.
4. **The gateway swallows the backend's handshake headers** on WebSocket upgrades, so `X-Instance` from the core is not visible through the gateway.
5. **Memory.** Seven JVM pods plus Postgres, Kafka and Redis use about 3.4 GB of a 3.75 GB VM. Notification, payment and analytics are parked at 0 in the scale overlay. Running a build with the cluster up hung Docker earlier (recovered with `wsl --shutdown`).
6. **Flaky timing tests under load.** In the full build `GatewayBehindProxyTest.eachRealClientBehindTheProxyGetsItsOwnRateLimitBucket` failed once (a time-based token bucket refilled while the machine was swamped); it passed 3 of 3 alone and in the rerun of the module. The earlier `CachingIntegrationTest` flake is the same family. They are not caused by Phase 11 but should be made deterministic (a controllable clock).

## 5. What is still not good enough (say this in the interview)

- **10 pods were not demonstrated.** The local limit is 5; the prod overlay allows 10 and renders, but was not run.
- **The core is only partly stateless.** Uploaded documents live in a per-pod scratch folder, so they are not shared. Needs object storage.
- **Single-copy Postgres, Redis, Kafka.** Fine for a laptop; a real deployment needs a replicated database, Redis Sentinel/Cluster and three brokers.
- **NetworkPolicies are not enforced** by kind's default network plugin; they are written, tested for structure, not for effect.
- **Connection pinning at the gateway** is documented and measured, not fixed. Candidate: limit pooled connection lifetime, or client-side balancing.
- **Autoscaling reacts late.** About 90 s until new JVM pods are ready; users saw timeouts meanwhile. A higher minimum, faster startup or scaling on request rate would help.
- **Lost live messages.** The Redis bridge is pub/sub, so events during a reconnect are lost; the REST fetch after reconnect is the safety net.
- **Test-only settings** (raised rate limits) live in the scale overlay; they must never reach dev/staging/prod (a policy test enforces it).

## 6. Interview questions

**Q: How is traffic spread over replicas in your system?**
Two levels. The ingress controller (layer 7) picks a gateway pod for every request: measured 50/50. The gateway calls a Kubernetes Service; kube-proxy (layer 4) picks a pod per new TCP connection. The gateway reuses pooled connections, so the spread is uneven.

**Q: Why was the second level uneven and how would you fix it?**
Connection-level balancing plus connection reuse pins a connection to a pod for its whole life. Options: limit connection lifetime, disable reuse (even, but slower: measured 47 req/s for one client), client-side balancing with a headless Service, or a service mesh.

**Q: How does an HPA decide?**
`desired = ceil(current × currentUtilization / target)`, with CPU measured as a percentage of the pod's request, read every 15 s from metrics-server. Behaviour policies limit step size and add stabilization windows. Ours: target 70 %, scale up at most 2 pods per 15 s, scale down one pod per 30 s after 60 s of calm.

**Q: What did the autoscaling demo show and what is its weakness?**
3 to 5 pods under load and back down. The weakness is latency: the formula asked for 16 pods at once, was capped at 5, and the new pods needed about 90 s to be ready, during which 0.38 % of requests timed out.

**Q: Why can the core run in several copies now when it could not before?**
It had state on a disk: the JWT signing key and uploads. The key is now one Secret shared by all copies. Uploads are still per pod (a known gap: object storage). The update strategy changed from Recreate to RollingUpdate.

**Q: What happens when you kill a pod?**
With copies, almost nothing: the endpoint is removed and others serve (0 failures of 10,085 for pricing). With a single copy, requests that need it fail until it is back: 44 of 12,215 for Postgres here, but a lost node or disk would be a real outage.

**Q: Why did Kafka's death cause no errors?**
Nothing on the request path waits for Kafka; events sit in the transactional outbox (Phase 6) and are published later.

**Q: How do WebSockets work with several replicas?**
A socket is one long TCP connection pinned to one pod. The Redis pub/sub bridge makes every pod deliver each event to its own sockets, so it does not matter which pod handled the REST call. We saw 12 of 12 delivered with sockets on both pods.

**Q: What happens to the sockets when their pod dies?**
They drop; the client reconnects (1 s in the demo) to any pod. Events published meanwhile are lost, so the client must refetch state over REST after reconnecting.

**Q: Do you need sticky sessions?**
Not for this design. Authentication is a token on the CONNECT frame and event delivery goes through Redis, so no pod needs to know about another's sessions.

**Q: Tell me about something that went wrong.**
The first failure experiments were meaningless because the core's own rate limiter returned 429 for almost everything. I noticed because the error breakdown said 429, not timeouts, raised the limit in a test-only overlay and reran. Also, a header interceptor passed its unit test but did nothing in the real server, which taught me to test through the real stack and to make demos fail when they cannot prove their claim.

**Q: How would this look in production?**
Min 3 gateway and pricing pods spread across zones (topology spread), HPA to 10 with a 300 s scale-down window, PodDisruptionBudgets, replicated Postgres/Redis/Kafka, object storage for uploads, enforced NetworkPolicies, a mesh or client-side balancing if connection pinning matters, and load tests on a real cluster.
