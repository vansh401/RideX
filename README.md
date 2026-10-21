# RideX

Production-style ride-hailing platform (Uber/Ola-style) built to learn Java full-stack,
distributed systems and DevOps.

## Stack
Java 21 · Spring Boot 4 · PostgreSQL · Redis · Kafka · Flyway · Resilience4j · React + TypeScript

## Architecture (after Phase 8)

One API gateway in front of a core application plus four extracted services, and a shared infrastructure library. Each service has its own database.

| Service | Port | Does |
|---|---|---|
| **api-gateway** (`backend/api-gateway`) | 8000 | the only address the browser uses: routing, token check, rate limit, CORS, logging |
| core (`backend/ridex-app`) | 8080 | auth, users, rides, drivers, matching, ratings, WebSockets; issues tokens |
| notification-service | 8081 | turns events into stored and live notifications |
| payment-service | 8082 | charges riders through a protected payment gateway |
| pricing-service | 8083 | the fare formula (stateless) |
| analytics-service | 8084 | audit log, daily counts, dead letters, consumer lag |
| platform (library) | | event contracts, outbox, idempotent consumers, JWT validation |

Read: [service map](docs/service-map.md) · [microservices concepts](docs/microservices-concepts.md) · [gateway concepts](docs/gateway-concepts.md) · [phase 7 review](docs/phase-7-review.md) · [phase 8 review](docs/phase-8-review.md) · [event contracts](docs/event-contracts.md)

## Run it (Windows, PowerShell)

1. Start Docker Desktop, then the infrastructure: `docker compose -f infrastructure/docker-compose/docker-compose.yml up -d`
   (PostgreSQL, Redis, Kafka; needs your `.env` as before).
2. Set the database password for this shell: `$env:DB_PASSWORD = "<the ridex database password>"`
   (and `ADMIN_EMAIL` / `ADMIN_PASSWORD` the first time, to create the admin).
3. Build once: `.\scripts\build-all.ps1`
4. Start the backend: `.\scripts\start-services.ps1` (or `-Only core,pricing` on a small machine; booking needs pricing).
5. Check: `.\scripts\status.ps1`
6. Frontend: `cd frontend\ridex-web` then `npm run dev` → http://localhost:5173 (restart it after pulling Phase 8: it now proxies to the gateway on port 8000)
7. Stop: `.\scripts\stop-services.ps1`

Each service creates its own database on first start. Logs are in `logs\`.

## Tests

```
cd backend
.\mvnw.cmd install          # every module's tests, needs Docker (Testcontainers)
```

## Status
Phases 1 to 8 done: foundation, auth, rides and matching, Redis, WebSockets, Kafka and events, microservices, API gateway.
