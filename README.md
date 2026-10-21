# RideX

Production-style ride-hailing platform (Uber/Ola-style) built to learn Java full-stack,
distributed systems and DevOps.

## Stack
Java 21 · Spring Boot 4 · PostgreSQL · Redis · Kafka · Flyway · Resilience4j · React + TypeScript · Docker + nginx + Kubernetes (kind, kustomize, HPA) · Micrometer, Prometheus, Grafana, OpenTelemetry, Jaeger · GitHub Actions

## Architecture (after Phase 13)

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

Read: [service map](docs/service-map.md) · [microservices concepts](docs/microservices-concepts.md) · [gateway concepts](docs/gateway-concepts.md) · [docker concepts](docs/docker-concepts.md) · [phase 7 review](docs/phase-7-review.md) · [phase 8 review](docs/phase-8-review.md) · [phase 9 review](docs/phase-9-review.md) · [kubernetes concepts](docs/kubernetes-concepts.md) · [phase 10 review](docs/phase-10-review.md) · [event contracts](docs/event-contracts.md)

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

### Option C: in a local Kubernetes cluster (kind)

1. Stop Option A (`docker compose down`): the cluster needs about 3 GB inside Docker's VM. Build the images once if they do not exist: `cd infrastructure\docker-compose`, then `docker compose build`.
2. Get **kind** (a local cluster in one Docker container): download `kind-windows-amd64` from https://github.com/kubernetes-sigs/kind/releases and save it as `tools\kind.exe` (git-ignored). `kubectl` comes with Docker Desktop.
3. Once: `.\scripts\k8s-init.ps1` (creates the git-ignored dev secrets).
4. `.\scripts\k8s-up.ps1` (cluster, images, ingress controller, RideX; the first run downloads about 1.2 GB).
5. Open **http://ridex.localhost:8088**. Check it: `.\scripts\k8s-smoke.ps1` (add `-Chaos` to break things on purpose).
6. Remove it all: `.\scripts\k8s-down.ps1`.

Rebuilt an image? `kind load docker-image ridex/core:dev --name ridex`, then `kubectl rollout restart deployment/core -n ridex-dev`. Learn it: [kubernetes concepts](docs/kubernetes-concepts.md), [phase 10 review](docs/phase-10-review.md).

#### Scaling and failure experiments (Phase 11)

With the cluster from option C running (it needs about 3 to 3.5 GB; stop other containers first). Needs Java 21 (load generator) and Node.js (WebSocket demo).

1. `.\scripts\k8s-scale-out.ps1`: several copies (2 gateways, 3 pricing, 2 core; notification, payment and analytics are parked to save memory).
2. `.\scripts\k8s-lb-demo.ps1` (add `-ConnectionPerRequest`): which pod answers?
3. `.\scripts\k8s-metrics-server.ps1` once, then `.\scripts\k8s-autoscale-demo.ps1`: pricing grows from 3 to 5 pods under load and shrinks again.
4. `.\scripts\k8s-failure-experiments.ps1`: kill pods under load and measure what users saw.
5. `.\scripts\k8s-websocket-demo.ps1`: live updates across core pods, and a pod killed.
6. Back to one copy: `.\scripts\k8s-scale-in.ps1`. Do not build images while the cluster runs (the Docker VM runs out of memory).

Learn it: [scaling concepts](docs/scaling-concepts.md), [phase 11 review](docs/phase-11-review.md).

### Observability: metrics, dashboards, traces, alerts (Phase 12)

Works on top of option A (Docker Compose). Stop the kind cluster first (`docker stop ridex-control-plane`); the monitoring containers and the system need about 3.5 GB in Docker's VM.

1. `docker compose build` once in `infrastructure\docker-compose` (the images contain the Phase 12 code).
2. `.\scripts\obs-up.ps1` (add `-Only core,gateway,pricing,payment,notification,web` on a small machine). It creates a random Grafana admin password in the git-ignored `infrastructure\docker-compose\secrets\` and starts the system plus Prometheus, Alertmanager, Grafana and Jaeger. The first run pulls about 450 MB of images.
3. Look: **Grafana http://localhost:3001** (dashboard "RideX overview"), **Prometheus http://localhost:9090**, **Jaeger http://localhost:16686** (traces), **Mailpit http://localhost:8025** (alert emails), Alertmanager http://localhost:9093. Logs are JSON: `docker logs ridex-core`.
4. Prove it: `.\scripts\obs-smoke.ps1` (add `-Alerts` to stop the pricing service and watch an alert email arrive; about 6 minutes). Check the alert rules without starting anything: `.\scripts\obs-test-rules.ps1`.
5. Stop: `.\scripts\obs-down.ps1` (data kept; `-Volumes` also deletes the monitoring history).

Without `obs-up` nothing changes: logs stay readable text, traces are not sent, and the metrics endpoint `/actuator/prometheus` is simply there. Learn it: [observability concepts](docs/observability-concepts.md), [phase 12 review](docs/phase-12-review.md).

### CI/CD: GitHub Actions pipeline (Phase 13)

The pipeline lives in `.github/workflows/`: **`ci.yml`** (every pull request and push to main: unit tests, integration tests with Testcontainers, frontend, image builds, manifest and alert-rule checks, workflow lint) and **`cd.yml`** (main: build and push images tagged by commit, deploy to **staging**; version tag `v1.2.3`: add the version name to the same images, deploy to **production** after an approval). Dependabot keeps dependencies and actions current.

Things to run yourself, without GitHub:

- Fast tests only / Docker tests only: `cd backend`, then `.\mvnw.cmd verify "-DexcludedGroups=integration"` or `.\mvnw.cmd verify "-Dgroups=integration"` (plain `verify` still runs everything).
- Render the manifests the way the pipeline does: `bash scripts/ci/render.sh staging ghcr.io/my-org sha-0123456789ab`.
- Test the deployment logic, including the automatic rollback of a bad version, on the kind cluster: `bash scripts/ci/selftest.sh`.
- Check the pipeline files: `docker run --rm -v "${PWD}:/repo:ro" -w /repo rhysd/actionlint:1.7.7`.

To switch it on for real, follow [docs/github-setup.md](docs/github-setup.md) (repository, environments, secrets, branch protection, a limited cluster credential). Learn it: [CI/CD concepts](docs/ci-cd-concepts.md), [phase 13 review](docs/phase-13-review.md).

Use **one option at a time**: they use the same ports or the same memory (A and B also share data volumes; switching keeps your data). Each service creates its own database on first start. Logs of option B are in `logs\`.

## Tests

```
cd backend
.\mvnw.cmd install          # every module's tests, needs Docker (Testcontainers); stop the Option A containers first (memory)
```

## Status
Phases 1 to 13 done: foundation, auth, rides and matching, Redis, WebSockets, Kafka and events, microservices, API gateway, Docker, Kubernetes, scaling and failure experiments, observability, CI/CD.
