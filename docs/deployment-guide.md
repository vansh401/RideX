# Deployment guide

Three ways to run RideX, from lightest to most realistic. Pick by how much memory you have.

| Option | What runs | When to use | Details |
|---|---|---|---|
| A. Docker Compose | everything in containers: Postgres ×3, Kafka, Redis, 6 Java services, gateway, frontend, observability stack | a full demo with one command | README "Option A", `docs/docker-concepts.md` |
| B. Local processes | only infrastructure in Docker; Java services started from Maven | day-to-day development on a low-RAM laptop | README "Option B" |
| C. Kubernetes (kind) | the same images on a local cluster with ingress, probes, HPA, network policies | to practise real deployment, scaling and rollback | README "Option C", `docs/kubernetes-concepts.md`, `docs/scaling-concepts.md` |

## Configuration that matters in Phase 14

| Setting | Service | Meaning | Default |
|---|---|---|---|
| `ridex.payments.commission-rate` | payment | platform share of each ride total | 0.20 |
| `ridex.payments.wallet.*` (min/max top-up, max balance) | payment | wallet limits | see `application.properties` |
| `ridex.payments.incentives.tiers` | payment | `trips:bonus` pairs per day | `10:100,20:250` |
| `ridex.analytics.zone` | analytics | time zone for "day" and "hour" | Asia/Kolkata |
| `ridex.surge.enabled`, `ridex.scheduling.enabled` | core | background jobs on/off (off in tests) | on |

Secrets (database passwords, JWT keys, Grafana admin) live in git-ignored files or Kubernetes Secrets, never in tracked files.

## Order of operations for a fresh environment

1. Start infrastructure (Postgres, Kafka, Redis). Flyway creates every schema when each service first starts; Phase 14 adds `payment-service V2`, `ridex-app V17` and `analytics-service V2`.
2. Start services in any order: they retry their dependencies, and `readiness` turns green only when the service can really work.
3. Check `GET /actuator/health/readiness` on each, then open the frontend.
4. Smoke checks: `/api/admin/ledger/integrity` returns no problems; `/api/admin/analytics/overview` answers; riders can top up the wallet.

## Rollback

- Application: Kubernetes keeps the previous ReplicaSet (`kubectl rollout undo`); the pipeline does this automatically when the new version fails its smoke test (`scripts/ci/deploy.sh`).
- Database: migrations are forward-only. Phase 14 migrations only **add** (columns, tables, a status value), so the previous version of the code keeps working against the new schema; that is what makes a code rollback safe. A destructive migration would need an expand/contract plan.
- Contracts: new event fields are optional, so old consumers ignore them and old producers' events still validate.
