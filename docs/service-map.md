# RideX service map

The one-page picture of Phase 7. For the reasons behind it read [microservices-concepts.md](microservices-concepts.md).

## 1. The services

| Service | Port | Database | Job | Talks to |
|---|---|---|---|---|
| **api-gateway** | 8000 | none (Redis for counters) | The one entry point: routing, token check, rate limit, CORS, logging | routes to everything; reads the core's public key |
| **core** (`ridex-app`) | 8080 | `ridex_db` | Auth, users, riders, drivers, rides, matching, ratings, WebSockets. Issues the tokens | publishes `ride-events`, `driver-events` and `complaint-events`; measures demand and writes surge multipliers to Redis; calls **pricing** (REST); consumes `payment-events` (saga) |
| **notification-service** | 8081 | `notification_db` | Turns events into stored and live notifications; emails receipts, refunds, complaint outcomes | consumes the event topics; pushes to the core's WebSockets through Redis; asks the **core** for a person's name and email (the one lookup it makes, with the service token) |
| **payment-service** | 8082 | `payment_db` | Wallet and double-entry ledger, coupons, refunds, tips, receipts, driver earnings, incentives and payouts, referrals; online payments through Razorpay (test checkout without keys), with a circuit breaker | consumes `ride-events` and `complaint-events`; publishes `payment-events`; calls the **gateway** (HTTP, protected); caches earnings in Redis |
| **pricing-service** | 8083 | none (stateless) | The fare formula | answers the core (and riders) over REST |
| **analytics-service** | 8084 | `analytics_db` | Audit log, dead letters, consumer lag; report facts and charts; fraud signals | consumes every topic (including `complaint-events`) and every `.DLT` topic |
| **platform** (library) | none | none | Event contracts, outbox, idempotent consumers, JWT validation, error shape. **No business logic** | linked into every service |

Infrastructure (Docker): PostgreSQL 17 (one server, four databases), Redis 7, Kafka (KRaft, one broker).

## 2. Who talks to whom

```mermaid
flowchart LR
    B[Browser] -->|"/api/*, /ws (one address)"| GW{{API gateway :8000<br/>token check, rate limit, CORS, logging}}
    GW -->|"auth, users, rides, drivers, ratings, /ws"| C[core :8080]
    GW -->|"/api/notifications"| N[notification :8081]
    GW -->|"/api/payments, /api/admin/payments"| P[payment :8082]
    GW -->|"/api/fares"| PR[pricing :8083]
    GW -->|"/api/admin/events, /api/admin/analytics"| A[analytics :8084]

    C -->|"REST: /internal/quotes (X-Service-Token)<br/>timeout + retry + circuit breaker"| PR
    C -->|publishes| K[(Kafka)]
    P -->|publishes| K
    K -->|ride, payment, driver events| N
    K -->|ride events| P
    K -->|payment events: saga| C
    K -->|everything| A
    P -->|"HTTP + Idempotency-Key<br/>timeout, retry, circuit breaker, bulkhead"| G[(Payment gateway)]
    N -->|"PUBLISH realtime:events"| R[(Redis)]
    R -->|"SUBSCRIBE: deliver to WebSockets"| C
    N & P & PR & A -.->|"fetch public key: /.well-known/jwks.json"| C
    N & P & PR & A & C -.->|"read revocation list"| R
```

Solid lines are work; dotted lines are the token machinery. Every service verifies tokens **locally** with the core's public key: the core is not called per request.

## 3. Kafka topics and consumer groups

| Topic | Key | Published by | Event types |
|---|---|---|---|
| `ride-events` | ride id | core | `ride.status-changed` |
| `payment-events` | ride id | payment | `payment.succeeded`, `payment.failed` |
| `driver-events` | driver user id | core | `driver.application-reviewed` |
| `<topic>.DLT` | same | any consumer's error handler | messages that failed for good |

| Group | Service | Reads | Does |
|---|---|---|---|
| `notifications` | notification | ride, payment, driver | stores and pushes notifications |
| `payments` | payment | ride | charges the rider when a ride completes |
| `ride-payment-saga` | core | payment | blocks / unblocks a rider (compensation) |
| `analytics` | analytics | ride, payment | daily counts |
| `audit` | analytics | ride, payment, driver | append-only log |
| `dead-letters` | analytics | all `.DLT` topics | parks failures for admins |

## 4. Who owns which table

| Database | Tables |
|---|---|
| `ridex_db` (core) | users, rider/driver profiles, vehicles, documents, rides, ride history, ratings, driver availability, `rider_payment_blocks`, `outbox_events`, `processed_events`, app info. *Legacy, no longer used: `notifications`, `payments`, `mock_gateway_charges`, `event_audit_log`, `ride_status_counts_daily`, `dead_letter_events` (see [phase-7-contract.sql](sql/phase-7-contract.sql))* |
| `notification_db` | `notifications`, `processed_events`, `outbox_events` (unused) |
| `payment_db` | `payments`, `mock_gateway_charges`, `outbox_events`, `processed_events` |
| `analytics_db` | `event_audit_log`, `ride_status_counts_daily`, `payment_outcomes_daily`, `dead_letter_events`, `processed_events` |

## 5. Endpoint ownership (what the gateway routes)

| Path | Service |
|---|---|
| `/api/auth/**`, `/api/users/**`, `/api/riders/**`, `/api/drivers/**`, `/api/rides/**`, `/api/admin/{users,drivers,vehicles,rides,stats,outbox}`, `/ws`, `/.well-known/jwks.json` | core |
| `/api/notifications/**` | notification |
| `/api/payments/**`, `/api/admin/payments/**` | payment |
| `/api/fares/**` | pricing |
| `/api/admin/events/**`, `/api/admin/analytics/**` | analytics |

Since Phase 8 the **api-gateway** (port 8000) does this routing; the Vite proxy only forwards `/api` and `/ws` to it, so the browser sees one origin. See [gateway-concepts.md](gateway-concepts.md). Since Phase 9 the **web** container (nginx) does this in Docker: it serves the built frontend and forwards `/api` and `/ws` to the gateway (the real file is `frontend/ridex-web/nginx.conf`; see [docker-concepts.md](docker-concepts.md)). In essence:

```nginx
location /api { proxy_pass http://gateway:8000; }
location /ws  { proxy_pass http://gateway:8000; proxy_http_version 1.1;
                proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; }
location /    { root /usr/share/nginx/html; try_files $uri /index.html; }   # the built frontend
```

## 6. What happens when a service is down

| Down | What still works | What does not | After it returns |
|---|---|---|---|
| **notification** | Rides, payments, everything else | No notifications arrive | It reads the events it missed, in order, and creates the notifications |
| **payment** | Booking and completing rides | Rides complete but nobody is charged yet; the payment card says "processing" | Reads the missed `ride completed` events and charges each rider once |
| **pricing** | Everything already in progress; rides can still be accepted, completed, paid | **Booking a new ride**: HTTP 503 with `Retry-After`. After a few failures the circuit opens and answers instantly | The circuit goes half-open after 10 s, tries a call, closes |
| **analytics** | Everything | Admin event pages | Catches up from its saved position |
| **Kafka** | Auth, rides (state changes are saved with outbox rows) | Events pile up in the **outbox** instead of being lost | The relay publishes the backlog in order |
| **Redis** | Parts that do not use it | Matching, rate limiting, the revocation list and live WebSocket fan-out depend on it (see [phase-4-review.md](phase-4-review.md) for each one's failure rule) | Resumes |
| **payment gateway** | Everything but charging | Payments wait as `PENDING` and are retried with the same idempotency key; after repeated outages they `FAIL` and the saga blocks the rider | Pending payments complete automatically |
| **gateway** | Each service still works on its own port (and the services keep checking tokens), but browsers have no single address | The frontend gets connection errors | Resumes at once; it holds no state |
| **core** | Other services keep verifying tokens with the public key they cached (a few minutes) | Login, rides, new tokens, WebSockets. When the cached key expires and cannot be refreshed, services refuse tokens too | Everything resumes |

## 7. Ports and start order

1. Docker: `docker compose up -d postgres redis kafka mailpit` (infrastructure only), or `docker compose up -d --build` for the whole system in containers (see [phase-9-review.md](phase-9-review.md)). Java services started this way are not published on 8080 to 8084; only the web (3000) and the gateway (8000) are.
2. **core** first (then the gateway, which fetches the same public key): it creates the signing key and publishes the public key the others fetch.
3. Then pricing, notification, payment, analytics in any order. (A service started before the core simply retries its key fetch on the first request.)
4. `npm run dev` in `frontend/ridex-web`.

Scripts: `scripts/build-all.ps1`, `scripts/start-services.ps1 [-Only core,pricing]`, `scripts/status.ps1`, `scripts/stop-services.ps1`.

## 8. Environment variables

| Variable | Used by | Meaning |
|---|---|---|
| `DB_PASSWORD` | all with a database | password of the `ridex` PostgreSQL user |
| `SERVICE_TOKEN` | core, pricing | shared secret for internal calls (generated by `start-services.ps1` into the git-ignored `data/`) |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | core | creates the first admin if none exists |
| `JWKS_URI` | gateway, services | where to fetch the public key (default `http://localhost:8080/.well-known/jwks.json`) |
| `CORE_URL`, `NOTIFICATION_URL`, `PAYMENT_URL`, `ANALYTICS_URL`, `CORE_WS_URL` | gateway | where each service is (defaults are the localhost ports) |
| `GATEWAY_ALLOWED_ORIGINS`, `GATEWAY_RATE`, `GATEWAY_BURST`, `GATEWAY_AUTH_RATE`, `GATEWAY_AUTH_BURST` | gateway | CORS origins and rate limits |
| `PRICING_URL` | core, gateway | where the pricing service is (default `http://localhost:8083`) |
| `KAFKA_BOOTSTRAP`, `REDIS_HOST`, `REDIS_PORT` | all | infrastructure addresses |
| `LEGACY_DB_URL` | notification, payment, analytics | JDBC URL of the core database, for the one-time copy of old rows (leave empty to skip) |
