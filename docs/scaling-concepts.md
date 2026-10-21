# Scaling and failure: concepts (Phase 11)

Phase 10 ran RideX in Kubernetes with one copy of everything. Phase 11 asks the questions that make Kubernetes worth using: what happens with **several copies**, when the **load grows**, when something **dies**, and with **WebSockets**. Every number below was measured on the local kind cluster (see [phase-11-review.md](phase-11-review.md) for how to repeat it).

## 1. Several copies, and who gets the traffic (11.1)

A Deployment with `replicas: 3` runs three identical pods. Something has to decide which pod gets which request. In RideX there are **two** such deciders, and they work differently:

| Hop | Who chooses | When it chooses | Result measured |
|---|---|---|---|
| browser → gateway pods | the **ingress controller** (nginx) | for **every request** (layer 7) | 50.0 % / 50.0 % in every run, even for ONE client |
| gateway → pricing pods | the **Service** (kube-proxy rules, layer 4) | for every new **TCP connection** | uneven: 27 / 35 / 38 % and 38 / 35 / 28 % |

**Why the second hop is uneven.** A Kubernetes Service does not look at requests; it picks a pod when a TCP connection is opened. The gateway keeps a **pool** of connections open and reuses them (that is what makes HTTP fast). Each pooled connection therefore stays on the pod it first met, for its whole life. How many connections each gateway pod opened to each pricing pod is luck.

**Ways to fix it**, in the order you would try them:

1. **Do nothing.** With many connections and similar requests the spread is good enough, and the autoscaler corrects overloaded pods anyway.
2. **Switch pooling off** (a new connection per request). We measured it: the spread became even (35.5 / 31.9 / 32.6 % and 33.2 / 33.4 / 33.4 %), but every request pays for a TCP handshake: one client dropped to 47 requests per second with a median of 17 ms. Fine to demonstrate, wrong for production.
3. **Limit how long a pooled connection lives** (`max-life-time`), so connections are re-balanced from time to time.
4. **Client-side load balancing**: the caller asks DNS for all pod addresses (a *headless* Service) and chooses per request (Spring Cloud LoadBalancer).
5. **A service mesh** (Istio, Linkerd): a sidecar proxy per pod balances per request, adds retries and mutual TLS. The most power, the most moving parts.

**Making it visible.** Every service answers with an `X-Instance` header (its pod name) and the gateway adds `X-Gateway-Instance`. The load generator (`scripts/loadtest/LoadTest.java`) counts them. Without this you are guessing. Real systems expose the same thing as logs and metrics labelled by pod.

## 2. Making the core able to run in two copies

Gateway and pricing were stateless, so more copies just worked. The core was not: it kept two things on one disk.

- **The token signing key.** Two copies that each generated their own key would sign tokens the other cannot verify. Fix: **one key in a Kubernetes Secret** (`ridex-jwt-key`), mounted into every copy.
- **Uploaded driver documents.** Each pod now writes to its own scratch folder, so a document uploaded through one pod is invisible to the other. This is **not fixed**, only acknowledged: the real fix is **object storage** (S3 or similar). The scale overlay is an experiment, not a production design.

The update strategy also changes from `Recreate` (needed while one pod owned the disk) to `RollingUpdate` with `maxUnavailable: 0`.

## 3. The Horizontal Pod Autoscaler (11.2)

An HPA changes `replicas` for you. Every 15 seconds it reads the average CPU use of the pods from **metrics-server** and computes:

```
desiredReplicas = ceil( currentReplicas × currentUtilization / targetUtilization )
```

- **Utilization is a percentage of the CPU request**, not of the machine. With a request of 50 m, "100 %" is 50 millicores. Setting requests honestly is therefore part of autoscaling.
- **Behaviour policies** shape the reaction: scale **up** immediately but at most 2 pods per 15 s; scale **down** only after a quiet 60 s (production uses 300 s) and one pod at a time, so the count does not flap.
- It needs **metrics-server** (an add-on). Without it the HPA shows `<unknown>` and does nothing.

**What we measured** (60 clients sending fare estimates for 180 s, pricing at 3 pods, limit 5 locally):

| Time | CPU (% of request) | Pods |
|---|---|---|
| before | 11 % | 3 |
| 30 s | 360 % | wanted 5 (the formula said 16, capped at 5) |
| 45 to 105 s | 1000 to 1350 % | 5 existing, only 3 ready: new JVMs are still starting |
| 120 s | 1261 % | 5 ready |
| load stops at 180 s | falls to 6 % | 5, then 4, then 3 by about 300 s |

**Lessons.** (1) The HPA **reacts**; it does not prevent trouble. It took about 90 seconds from the first scale-up decision until the new pods were ready, and in that window 180 of 47,418 requests (0.38 %) timed out. A JVM that starts in seconds (native image, CDS, warm pool) shortens this; so does a higher minimum. (2) The cap matters: the demand needed far more than 5 pods. (3) Locally the maximum is 5 because the Docker VM has 4 GB; the production overlay allows **10** with the same mechanism.

## 4. Failure experiments (11.3)

We killed one pod under steady load (10 clients, about 50 to 90 s, the pod deleted 12 s in). "Failed" counts timeouts and error responses.

| Killed | Copies | Requests | Failed | Success |
|---|---|---|---|---|
| pricing | 3 | 10,085 | 0 | 100 % |
| gateway | 2 | 10,338 | 0 | 100 % |
| core | 2 | 6,294 | 6 | 99.90 % |
| redis | **1** | 10,923 | 12 | 99.89 % |
| kafka | **1** | 14,400 | 0 | 100 % |
| postgres | **1** | 12,215 | 44 | 99.64 % |

How to read it:

- **Stateless services with copies hide a pod death.** Kubernetes removes the dead pod from the Service's endpoints and the others carry the load. The core lost a handful of requests because the survivor took double load for a while and some in-flight requests were on the dead pod.
- **Single-copy stores are single points of failure**, but a deleted pod is replaced in seconds, so the damage here is small (timeouts while Postgres or Redis restarts). A lost *node or disk* would be a real outage, not 44 requests. That is why production uses a database with a primary and replicas, Redis Sentinel or Cluster, and three Kafka brokers.
- **Kafka showed no errors** because nothing on that request path waits for it. Events wait in the **outbox** (Phase 6) and are published once Kafka is back. This is the outbox pattern earning its keep.
- **Redis** is more forgiving than expected thanks to Phase 4's decisions about what each feature does without Redis.
- Ingress-nginx retries only idempotent requests on another pod, so a request caught on a dying gateway pod is not blindly repeated.

An honest correction from the experiment itself: our first run of the core, Redis, Kafka and Postgres tests returned over 95 % HTTP 429. The **core has its own per-address rate limiter** (600 requests a minute per client address) and the whole load came from two gateway addresses. We raised that limit in the scale overlay only (test only) and reran. The first results were true measurements of the wrong thing. Always check *what* failed before believing a failure rate.

## 5. WebSockets across replicas (11.4)

A WebSocket is **one long TCP connection** from the browser to one core pod. Consequences:

- **Placement is per connection.** With 12 connections and two core pods we saw 7 and 6.
- **A live update must reach connections on other pods.** The pod that handles a REST call (say "driver accepted the ride") may not be the pod that holds the rider's socket. Phase 5's **Redis pub/sub bridge** solves it: the handling pod publishes the event to the channel `realtime:events`, and **every** core pod delivers it to the sessions it holds. We verified it: the REST call was handled by one pod and all 12 sockets received both updates, including the 6 held by the other pod.
- **A pod death cuts its sockets.** 5 of the 12 dropped when their pod was deleted. The client reconnected within 1 second, through the gateway, and may land on the other pod. After the failure, a new REST call was handled by the surviving pod and all 12 sockets received the update again.
- **Messages sent while a socket was down are lost.** The bridge is fire-and-forget pub/sub. The safety net is REST: on reconnect the client must fetch the current state (the ride) instead of assuming it saw everything.
- **Sticky sessions are not needed** for STOMP over WebSocket here, because after the upgrade the connection itself is the session, and the bridge removes the dependence on which pod handled the request.
- **Observability limit.** The core sets `X-Instance` on the upgrade response, but the gateway's WebSocket proxy does not forward the backend's handshake headers. So the demo counts established connections per pod inside the pods (`/proc/net/tcp`). An attempt to add the pod name to the STOMP `CONNECTED` frame did not work: Spring's broker produces an internal `CONNECT_ACK` message that is converted to the frame without custom headers, so the interceptor was removed.

## 6. Things to remember

- Replicas give **availability** (a pod can die) and **capacity** (more pods, more throughput) only if the component is **stateless or its state is shared**.
- Load balancing has **levels**; each level has its own algorithm and its own blind spots.
- An autoscaler **follows** load with a delay; plan minimums and startup time for that delay.
- Test failure on purpose, and **check what the failure rate is made of** before drawing conclusions.
