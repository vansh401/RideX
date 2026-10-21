# API gateway, explained from zero

The "why" behind Phase 8. The [phase 8 review](phase-8-review.md) shows what was built; the [service map](service-map.md) is the one-page picture.

---

## 1. The problem a gateway solves

After Phase 7 the backend is six programs on six ports (core 8080, notification 8081, payment 8082, pricing 8083, analytics 8084). If the browser talked to them directly:

* **It would need to know all of them.** The frontend would contain "payments are on 8082, fares on 8083...". Moving one path from one service to another would mean changing and re-releasing the frontend.
* **Every service would have to repeat the same chores**: check the token, limit abusive callers, allow the right websites (CORS), log requests. Six copies drift apart; one gets forgotten.
* **Every service would be exposed to the internet** with all its internal and admin paths.
* **CORS would become a daily problem**: the page is on `localhost:5173`, each service on another port, and browsers refuse cross-origin calls unless every service says yes.

An **API gateway** is one front door. The browser knows one address. The gateway looks at each request and decides which service gets it, after doing the chores once.

```
browser ──►  GATEWAY :8000  ──►  core :8080            (auth, rides, drivers, users, ratings, WebSocket)
 (one          │              ──►  notification :8081
  address)     │              ──►  payment :8082
               │              ──►  pricing :8083
               └─ chores:     ──►  analytics :8084
                  routing, token check, rate limit, CORS, logging, correlation id
```

A gateway is *not* a place for business rules. If you find yourself writing "if the ride is completed then..." in the gateway, it belongs in a service.

---

## 2. Step 8.1: routing (routes, predicates, filters)

We use **Spring Cloud Gateway** (reactive: it handles thousands of waiting connections on a few threads, which suits a component that mostly forwards bytes).

A **route** has three parts:

| Part | Meaning | Example |
|---|---|---|
| **Predicate** | "Does this request belong to me?" | `Path=/api/payments/**` |
| **URI** | "Which service gets it?" | `http://localhost:8082` |
| **Filters** | "What happens to the request/response on the way?" | rate limiting |

Routes are tried in order (`order:`), first match wins. So specific routes come first and the catch-all `Path=/**` (the core) last. The routes live in `backend/api-gateway/src/main/resources/application.yml`; the service addresses are environment variables (`PAYMENT_URL`...), which is exactly what makes the gateway portable to Docker or Kubernetes later.

| Path | Goes to |
|---|---|
| `/api/auth/**` | core (strict rate limit) |
| `/api/notifications/**` | notification-service |
| `/api/payments/**`, `/api/admin/payments/**` | payment-service |
| `/api/fares/**` | pricing-service |
| `/api/admin/events/**`, `/api/admin/analytics/**` | analytics-service |
| `/ws/**` | core, as a WebSocket (`ws://`) |
| everything else | core |

**WebSockets** are an HTTP request that upgrades itself to a long-lived connection; the gateway passes the upgrade through and then relays frames both ways.

### Paths that must never be reachable from outside
Inside the platform there are paths meant only for other services: `/internal/quotes` (the core asks pricing for a fare) and `/mock-gateway/*` (the pretend payment company), plus `/actuator/*`. The catch-all route would happily forward them to the core, and a path-based route could one day expose them. So the gateway **blocks them explicitly with a 404** before routing (`blocked-paths`), whatever token the caller has. The services protect them too (service token), so there are two locks.

---

## 3. Step 8.2: where is the JWT validated?

Three choices exist:

| Option | Meaning | Verdict |
|---|---|---|
| Only in services | Each service checks the token | What we had. Works, but a request without a token still travels to a service and uses its resources |
| Only at the gateway | Services trust the gateway completely | Fast, but if anything else can reach a service (a mistake in the firewall, another service, a compromised container) the service has **no protection at all** |
| **Both** | Gateway checks first, services check again | **Defence in depth.** What we do |

### What the gateway does with a token (`JwtEdgeFilter`)
1. Public paths (login, register, health, public key, API docs, WebSocket handshake) need no token.
2. For everything else: needs `Authorization: Bearer ...`. The gateway verifies the **signature** with the core's public key (fetched from the JWKS URL, cached), the **expiry** and the **issuer**, then asks Redis whether the token was **revoked** (logout, suspension).
3. If anything fails: `401` in the standard JSON shape, and the request **never reaches a service**.
4. If it passes: the gateway forwards the request and adds two headers, `X-User-Id` and `X-User-Role`.

### Header propagation, and why services do not trust it
The two headers are handy for logs and tracing: any service can say *who* was calling without decoding anything. But **they are only a hint**:

* The gateway **removes** any `X-User-Id` / `X-User-Role` the client sent, so a client cannot claim to be an admin (a test proves it).
* The `Authorization` header is passed on **unchanged**, and every service verifies it again by itself. No service makes a security decision from `X-User-*`.

If services trusted the headers, then anyone who could reach a service directly could write `X-User-Role: ADMIN` and walk in. Re-checking the token costs a few microseconds and removes that whole class of attack.

### Authentication at the edge, authorization in the services
The gateway answers "**who** are you, and is your token still good?". It does not answer "**what** may you do?" ("may this rider see this ride?", "is this endpoint for admins?"). That needs business knowledge, so it stays in the service that owns the data. The one exception is the gateway's own admin page (`/gateway/services`), where the gateway checks the role itself.

### The WebSocket exception
A browser cannot add an `Authorization` header to a WebSocket handshake. So the handshake is allowed through, and the token is sent **inside** the first STOMP frame (`CONNECT`), where the core validates it (Phase 5). The gateway cannot look inside that frame. This is not a hole: nothing happens on a connection until `CONNECT` succeeds, and every later frame re-checks the token.

---

## 4. Step 8.3: what belongs in the gateway and what does not

| Concern | Gateway | Services | Why |
|---|---|---|---|
| Routing | yes | | The whole point |
| "Is there a valid token?" | yes | yes (again) | Cheap rejection at the edge, safety in depth |
| Role / ownership rules | | yes | Needs business knowledge |
| **Coarse rate limiting** per client and route | yes | | Stops floods before they cost anything |
| **Fine rate limiting** (5 wrong passwords per email, then lock for 15 minutes) | | yes (core) | Needs to know the *email* and the result |
| CORS | yes | | One place decides which websites may call the API |
| Request logging, correlation id | yes | each logs with the id | One id searches all logs |
| Input validation | | yes | The service knows what a valid ride looks like |
| Caching, response shaping | (not now) | | Can be added; keep it dumb |
| Business logic | **never** | yes | |

### Rate limiting (token bucket)
Each client address gets a bucket per route. The bucket holds up to `burst` tokens and refills `rate` tokens per second; every request takes one. Empty bucket → `429 Too Many Requests`. This allows short bursts (a page loading six requests) while capping the long-run rate. The counters live in **Redis**, so several gateway instances share one count. Login has a stricter bucket (5 per second, burst 15) than the rest of the API (30 per second, burst 60). If Redis is down the limiter lets requests through rather than taking the site down.

### The "everyone is one person" trap
Behind a gateway, every request reaches the core from the *gateway's* address. The core's per-client rate limit (Phase 4) would suddenly treat all users as one client and lock everyone out after a few requests. The fix has two halves:

1. The gateway **replaces** `X-Forwarded-For` with the real address of the connection (a client-written value is thrown away: `ClientAddressHeadersFilter`).
2. The core accepts that header (`server.forward-headers-strategy=native`) **only from internal addresses**, so a client reaching the core directly cannot choose its own identity.

### CORS
The browser enforces the *same-origin policy*: a page from `localhost:5173` may not call `localhost:8000` unless the server says so with CORS headers. The gateway carries the one allowed-origins list (`GATEWAY_ALLOWED_ORIGINS`). A *preflight* (`OPTIONS`, sent by the browser before a real request) needs no token. A request from a site that is not on the list is refused. Tokens travel in a header, not a cookie, so credentials are off.

### Logging and the correlation id
Every request gets an `X-Request-Id` (kept if the caller sent a sensible one, replaced if not). It is sent to the services, returned in the response, and every service puts it in its log lines, so one search finds a request's whole journey. The access log records method, path, status, duration, client and user id. **It never logs the query string or any header**, because those can carry secrets.

---

## 5. Step 8.4: service discovery

With a gateway, "how does it find the payment service?" Options:

| Approach | How it works | Good for |
|---|---|---|
| **Static configuration** (what we use) | Addresses in config/environment variables | A handful of services whose addresses rarely change |
| **Client-side discovery** (Eureka + Spring Cloud LoadBalancer) | Each service registers its address in a registry; callers ask the registry and pick an instance (`lb://payment-service`) | Many instances on machines that come and go, *without* an orchestrator |
| **Platform discovery** (Kubernetes DNS) | A `Service` object gives a stable name (`http://payment:8082`) and spreads traffic over healthy pods | Anything that runs on Kubernetes |

**Our decision:** keep addresses in configuration now, and move to **Kubernetes DNS** when we deploy there (a later phase). We do **not** add Eureka: it would be one more server to run and keep alive, and Kubernetes already does the same job (registration, health checks, load balancing) as part of the platform. Running both would duplicate it.

What makes the switch free: every route URI is a property (`PAYMENT_URL`). In Kubernetes it becomes `http://payment:8082` with no code change. If we needed discovery before Kubernetes, the change would be `uri: lb://payment-service` plus a discovery client, again no business code.

**Load balancing** (spreading requests over several instances of one service) is therefore done by the platform, not the gateway. **Health**: `GET /gateway/services` (admin only) asks each service's health endpoint and reports UP/DOWN, a poor man's registry view. A service that is down gives the caller a quick `503` with `Retry-After`; the other routes keep working.

---

## 6. Step 8.5: the frontend talks only to the gateway

In development the Vite dev server proxies `/api` and `/ws` to the gateway (`vite.config.ts` no longer lists individual services, only the gateway). From the browser's point of view everything is on `localhost:5173`: **same origin**, so no CORS at all. In production a reverse proxy (nginx) serves the built frontend and forwards `/api` and `/ws` to the gateway on the same domain, which gives the same property.

Why same-origin is worth having even though the gateway also does CORS:
* no preflight round trip before every request;
* no CORS misconfiguration possible;
* cookies/credentials would work if we ever chose to use them.

The gateway's CORS rules exist for the cases where a different website or app legitimately calls the API directly (a future mobile web view, a partner tool).

---

## 7. Trade-offs to know

| Gain | Price |
|---|---|
| One address, one place for edge rules | The gateway is on the path of **every** request: it must be fast, stateless and replicated. A single gateway is a single point of failure |
| Hides internal topology | One more thing to deploy, monitor and configure |
| Cheap rejection of bad traffic | A bug in the gateway affects everything. Keep it small, keep business logic out |

The usual mistake is to let the gateway grow: aggregation, transformation, orchestration, "just one small rule". Each is individually reasonable and together they make a second, hidden monolith that every team must change. Our gateway has no database, no business code and no dependency on the services (an architecture test enforces it).

---

## 8. Vocabulary

| Term | Meaning |
|---|---|
| API gateway | One entry point that routes to the right service and does cross-cutting chores |
| Route / predicate / filter | Where it goes / when this route applies / what happens on the way |
| Edge | The outermost boundary of your system, facing clients |
| Defence in depth | Several independent protections, so one failing is not fatal |
| Header propagation | Passing selected headers (identity, correlation id) on to the next hop |
| Correlation id | An id attached to a request so its trace can be followed through logs |
| Token bucket | Rate limiting with a refilling bucket; allows bursts, caps the average |
| CORS / preflight | Browser rule for cross-origin calls / the `OPTIONS` check before a real request |
| Same-origin | Page and API share scheme, host and port: no CORS needed |
| Service discovery | How a caller finds the current address of a service |
| Reverse proxy | A server that forwards client requests to other servers (nginx, a gateway) |
