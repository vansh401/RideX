# RideX

Production-style ride-hailing platform (Uber/Ola-style) built to learn Java full-stack,
distributed systems and DevOps.

## Stack
Java 21 · Spring Boot 4 · PostgreSQL · Redis · Kafka · Flyway · Resilience4j · React + TypeScript · Docker + nginx

## Architecture (after Phase 9)

One API gateway in front of a core application plus four extracted services, and a shared infrastructure library. Each service has its own database. Everything can run in Docker.

| Service | Port | Does |
|---|---|---|
| **web** (nginx, `frontend/ridex-web`) | 3000 (Docker) | serves the React app, forwards `/api` and `/ws` to the gateway |
| **api-gateway** (`backend/api-gateway`) | 8000 | the only address the browser uses: routing, token check, rate limit, CORS, logging |
| core (`backend/ridex-app`) | 8080 | auth, users, rides, drivers, matching, ratings, WebSockets; issues tokens |
| notification-service | 8081 | turns events into stored and live notifications |
| payment-service | 8082 | charges riders through a protected payment gateway |
| pricing-service | 8083 | the fare formula (stateless) |
| analytics-service | 8084 | audit log, daily counts, dead letters, consumer lag |
| platform (library) | | event contracts, outbox, idempotent consumers, JWT validation |

Read: [service map](docs/service-map.md) · [microservices concepts](docs/microservices-concepts.md) · [gateway concepts](docs/gateway-concepts.md) · [docker concepts](docs/docker-concepts.md) · [phase 7 review](docs/phase-7-review.md) · [phase 8 review](docs/phase-8-review.md) · [phase 9 review](docs/phase-9-review.md) · [event contracts](docs/event-contracts.md)

## Run it (Windows, PowerShell)

### Option A: everything in Docker (one command)

1. Start Docker Desktop (give it at least 4 GB of memory).
2. Once: `.\scripts\docker-init.ps1` (creates the git-ignored secret files; on the first run it also creates `infrastructure\docker-compose\.env` from the example).
3. `cd infrastructure\docker-compose`, then `docker compose up -d --build` (the first build takes several minutes).
4. Open **http://localhost:3000**. Mail the app sends is caught at http://localhost:8025.
5. Check it: `.\scripts\docker-smoke.ps1`. Stop it: `docker compose down` (keeps your data; `docker compose down -v` deletes it).

Troubleshooting and the reasoning: [phase 9 review](docs/phase-9-review.md).

### Option B: Java services as local processes (lighter on memory, for development)

1. Start only the infrastructure: `cd infrastructure\docker-compose`, then `docker compose up -d postgres redis kafka mailpit`.
2. Set the database password for this shell: `$env:DB_PASSWORD = "<the ridex database password>"`
   (and `ADMIN_EMAIL` / `ADMIN_PASSWORD` the first time, to create the admin).
3. Build once: `.\scripts\build-all.ps1`
4. Start the backend: `.\scripts\start-services.ps1` (or `-Only core,pricing,gateway` on a small machine; booking needs pricing).
5. Check: `.\scripts\status.ps1`
6. Frontend: `cd frontend\ridex-web`, then `npm run dev` → http://localhost:5173
7. Stop: `.\scripts\stop-services.ps1`

Use **one option at a time**: both use the same ports and the same data volumes (switching keeps your data). Each service creates its own database on first start. Logs of option B are in `logs\`.

## Tests

```
cd backend
.\mvnw.cmd install          # every module's tests, needs Docker (Testcontainers); stop the Option A containers first (memory)
```

## Status
Phases 1 to 9 done: foundation, auth, rides and matching, Redis, WebSockets, Kafka and events, microservices, API gateway, Docker.
