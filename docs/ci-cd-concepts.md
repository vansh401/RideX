# CI/CD: concepts (Phase 13)

Phases 1 to 12 built a system that is built, tested, packaged and deployed **by hand**, by whoever remembers the commands. That does not scale and it is not safe: people forget a step, test on their machine only, deploy from the wrong branch, or paste a password into a terminal. Phase 13 turns the steps into a **pipeline**: the same steps, in the same order, on a clean machine, every time, triggered by a change in git.

| Term | Meaning | In RideX |
|---|---|---|
| **CI** (continuous integration) | Every change is built and tested automatically, on a clean machine, before it is merged | `ci.yml` runs on every pull request and every push to main |
| **CD** (continuous delivery) | Every change that passed CI becomes a **release candidate** that *can* be deployed with one decision | `cd.yml` builds and publishes images for every commit on main |
| **Continuous deployment** | ...and it **is** deployed with no human step | staging works this way |
| **Delivery with approval** | the last step, to production, waits for a person | production waits for a reviewer |

## 1. GitHub Actions in one page

- A **workflow** is a YAML file in `.github/workflows/`. It starts on an **event** (`pull_request`, `push`, a tag, a schedule, another workflow).
- A workflow has **jobs**. Each job runs on its **own fresh virtual machine** (a *runner*, here `ubuntu-latest`). Jobs run **in parallel** unless one `needs:` another.
- A job has **steps**: either a shell command (`run:`) or a reusable **action** (`uses: actions/checkout@v4`).
- Anything one job produces and another needs must be handed over explicitly (an **artifact**, a registry, an output), because the machines are different and are thrown away.
- **Matrix** (`strategy.matrix`): one job definition, run once per combination (six services, seven images).
- **Caching**: a clean machine downloads the internet every time. `setup-java` with `cache: maven` and `setup-node` with `cache: npm` restore the dependency folders (keyed on the lock files). Docker layers are cached with `cache-from/cache-to: type=gha`.
- **Concurrency**: `concurrency.group` allows one run per group. For pull requests, a new push cancels the old run; deployments are never cancelled halfway.
- **Reusable workflows** (`workflow_call`): `cd.yml` calls `ci.yml`, so the deployment can only start after exactly the checks a pull request needs.

## 2. The CI pipeline (13.1, 13.2)

```
 pull request / push to main
        │
        ├─ backend-unit ........... fast tests, no Docker, plus architecture + policy tests   (~1 min locally)
        ├─ backend-integration .... 6 machines in parallel, one per service, real PostgreSQL/Kafka/Redis
        ├─ frontend ............... npm ci, lint, tests, production build
        ├─ docker-build ........... all 7 images build (nothing pushed)
        ├─ manifests .............. Kubernetes manifests render for staging and production
        ├─ monitoring-config ...... alert rules unit-tested with promtool
        ├─ workflows .............. actionlint checks the workflow files themselves
        └─ ci-ok .................. one verdict; THIS is the check branch protection requires
```

**Why unit and integration tests are separate jobs.** The unit tests need nothing and finish in about a minute: that is the feedback a developer waits for. The integration tests start real databases in containers and take minutes. Run in one job, a typo would cost ten minutes to discover. We split them with a JUnit **tag**: the six test base classes that need Docker carry `@Tag("integration")` (tags are inherited by the test classes that extend them). The unit job runs Maven with `-DexcludedGroups=integration`, the integration job with `-Dgroups=integration`. The two sets are complementary and together they are the full suite (378 unit + 295 integration = 673 tests at the end of Phase 13; when the split was first measured it was 356 + 295 = 651, the same total as the full build before it), so nothing is skipped and nothing runs twice. Locally `mvnw verify` still runs everything.

**Testcontainers in a pipeline.** Testcontainers starts containers from the test code, so the machine needs Docker. GitHub's Ubuntu runners have it, so no extra setup is needed (on other CI systems you may need a Docker-in-Docker service or a remote daemon). Containers are removed when the tests end. One machine per service keeps them from fighting for memory and ports, and gives parallelism.

**Flaky tests.** A test that sometimes fails without a code change is *flaky*; the usual cause here is timing (a rate-limit bucket that refills while the machine is busy). Ignoring them teaches people to ignore red builds; retrying silently hides them. The integration job uses `-Dsurefire.rerunFailingTestsCount=1`: a test that fails once and then passes is reported as a **flake** in the log, a test that fails twice fails the build. The right long-term fix is to remove the timing dependence (a controllable clock).

**The Maven wrapper and the executable bit.** The runners are Linux. `mvnw` was committed from Windows without the executable permission, so the first CI run would have failed with `Permission denied`. It is fixed in git (`git update-index --chmod=+x`) and the workflows call `bash mvnw` anyway. A `.gitattributes` file keeps shell scripts on Unix line endings, because a script with Windows line endings fails on Linux with `bad interpreter`.

**Mockito as a Java agent.** Mockito used to attach itself to the running JVM at test start, by launching a helper process. On a machine low on memory this failed at random (we saw it several times) and the JDK warns it will stop working. Surefire now starts the test JVM with `-javaagent:` Mockito, set up once in the parent `pom.xml`. A pipeline must not depend on luck.

**`npm ci` and lock files.** `npm install` may pick newer versions than the ones you tested; `npm ci` installs exactly `package-lock.json` and fails if it disagrees with `package.json`. In CI, always the second.

## 3. Build and push images (13.3)

**Registry.** A registry stores container images. We use **GitHub Container Registry** (`ghcr.io`). The pipeline logs in with the **short-lived `GITHUB_TOKEN`** of the run (no personal token to leak or rotate); the job that pushes is the **only** job with `packages: write`.

**Tagging by commit.** An image tag is a name pointing to an image **digest** (a hash of its content). We tag each image `sha-<first 12 characters of the commit>`: it is unique, it tells you exactly which code runs, and it can never move. We never deploy `latest` (it moves, so "what is running?" has no answer and a rollback has nothing to roll back to), and a policy test fails the build if a workflow mentions it.

**Build once, deploy many.** The images are built **one time**, for the commit. Staging runs exactly those. For a release you push a git tag `v1.4.0`; the pipeline does **not** rebuild: `scripts/ci/promote.sh` adds the name `1.4.0` to the image that already exists (same digest) and production runs that. If it rebuilt, production would run an image nobody tested (different timestamps, maybe different dependency versions). We tested this against a real registry: the version tag and the commit tag resolve to the same `sha256:` digest, and promoting a tag that was never built fails.

**A release must come from main.** The pipeline checks that the tagged commit is an ancestor of `main`, so a tag on a stray branch (never reviewed, never in staging) cannot be released.

**Idempotent runs.** `scripts/ci/image-exists.sh` skips the build when the commit's image is already in the registry (a re-run, or a tag on a commit main already built).

**Supply chain.** `provenance` and `sbom` attach a record of where and how the image was built and the list of everything inside it. A **Trivy** scan fails the pipeline on *critical* vulnerabilities that have a fix, before any deployment. Third-party actions are pinned to a version (a policy test forbids `@main`) and **Dependabot** opens pull requests to update them, the Maven and npm dependencies, and the Docker base images.

## 4. Deploy to Kubernetes (13.4)

```
 push to main ──▶ CI ──▶ images ──▶ deploy-staging  (automatic)  ──▶ smoke test
 push tag v1.4.0 ─▶ CI ──▶ images (retag only) ──▶ deploy-production (waits for approval) ──▶ smoke test
```

**What "deploy" is.** `scripts/ci/deploy.sh <env> <tag>`:
1. **Render** (`render.sh`): takes the environment's kustomize overlay, and in a **temporary copy** rewrites the placeholder image lines to `ghcr.io/<owner>/ridex-<service>:<tag>`, then `kubectl kustomize`. The tracked files are never edited by the pipeline. It refuses `latest`, `dev` and anything that is not `sha-<hex>` or a version.
2. **Safety net**: no placeholder or local image may remain in the rendered YAML.
3. Check that the **namespace** exists (an administrator creates it once; see below), create or update the **Secret** `ridex-secrets` from the environment's secrets (values go through files, never onto a command line), and optionally a registry **pull secret**.
4. `kubectl apply`.
5. **Wait**: `kubectl rollout status` for every StatefulSet and Deployment. With `maxUnavailable: 0` (Phase 10) the old pods keep serving until the new ones are Ready, so a bad image never takes the site down.
6. **On failure**: print the pod state and events, and `kubectl rollout undo` every Deployment, then fail the job.
7. **Smoke test** (`smoke.sh`): "the pods are Ready" does not prove the site works. The script checks the website, `/api/health`, that a protected path answers 401 from the gateway, and that internal paths are not exposed; it retries for a while because the ingress may need a moment.

**Least privilege: what the pipeline may do in the cluster.** The deployment job needs a credential. A cluster-admin kubeconfig would let anyone who can change a workflow do anything to the cluster, so the pipeline has its own ServiceAccount (`infrastructure/k8s/ci/deployer.yaml`) with a **Role in one namespace** that lists exactly the kinds of objects RideX contains. It can apply and roll back workloads, write the two secrets it manages and read pods and events; it cannot delete workloads, open a shell in a pod, read logs, list other secrets, touch another namespace, create namespaces or change permissions. Because namespaces are cluster-scoped, an administrator creates them once and `deploy.sh` drops the Namespace object from what it applies. We tested this with the real identity: the API server validated all 36 staging and 38 production objects as that ServiceAccount (it checks permissions per object even in a dry run), and `kubectl auth can-i` confirmed each denied action. The test also caught a bug no reading would have: the script asked `kubectl get namespace`, which a namespaced credential is forbidden to do, so it wrongly decided the namespace was missing; it now asks for the `default` ServiceAccount of the namespace instead.

**Testing the failure path.** The most important behaviour of `deploy.sh` is what happens when a deployment is bad, and that is the one thing a successful pipeline run never exercises. `scripts/ci/selftest.sh` runs the real script against a cluster with a stand-in application: a good version deploys, a version whose image does not exist makes the script fail, roll back, and leave the good pod serving the whole time.

**Staging vs production.** Staging deploys **every commit on main** with no human in the loop: it is the place to find out. Production deploys only from a **version tag**, in a job attached to the **`production` environment**, which is configured (in GitHub) with **required reviewers**: the job stops and waits until a person clicks *Approve*. That is the difference between continuous deployment and delivery with approval.

**Deployment concurrency.** Two deployments to the same environment must not run at once and a deployment must not be cancelled in the middle (`cancel-in-progress: false`).

**Rollback.** Automatic: `rollout undo` when a rollout fails. Manual: re-run the production deployment for the previous tag (the images still exist: tags are immutable), or `kubectl rollout undo deployment/<name> -n ridex-prod`. This is why every release keeps its own immutable image tag.

## 5. Environments, secrets, and the frontend (13.5)

**GitHub Environments** (`staging`, `production`) are named deployment targets with their own:
- **secrets** (only jobs that declare `environment: staging` can read staging's), so a pull request or the CI jobs can never read the production kubeconfig;
- **variables** (non-secret: `STAGING_URL`, `PRODUCTION_URL`);
- **protection rules**: required reviewers, a wait timer, and which branches or tags may deploy (production: only `v*` tags).

Secrets used (set per environment; the names are the same, the values differ):

| Secret | What it is |
|---|---|
| `KUBE_CONFIG` | base64 of a kubeconfig whose user may only touch the environment's namespace (least privilege) |
| `DB_PASSWORD`, `SERVICE_TOKEN` | become the Kubernetes Secret `ridex-secrets` |

Rules the pipeline follows (and a policy test enforces): secrets are passed to scripts only as **environment variables** (never written inside a `run:` command, where they could leak into the process list or a log); no step echoes them; GitHub masks secret values in logs, but masking is a safety net, not a plan. A kubeconfig with a long-lived token is the simple option; the better one is **OpenID Connect**: the cloud provider trusts GitHub's identity token for a specific repository and environment and hands out short-lived credentials, so there is **no stored credential** at all.

**Branch protection** (a repository setting): require pull requests, require the `CI passed` check, require up-to-date branches, forbid force-push to main. The single `ci-ok` job exists so that one name is required instead of a list that must be edited whenever a job is added (a job that is *skipped* counts as passed for a required check; `ci-ok` fails if any needed job did not succeed).

**The frontend pipeline.** `frontend` runs `npm ci`, `npm run lint`, `npm test`, `npm run build` (which type-checks with `tsc` before bundling), and keeps the `dist` folder as an artifact. The website image (`ridex-web`) is built from the same Dockerfile as locally (nginx serving the bundle) and travels through the same build-once, promote, deploy path as the backend images.

## 6. What a pipeline cannot do for you

- It runs the tests you wrote. Green means "nothing we thought of broke".
- A pipeline is code with the keys to production: review changes to `.github/` like production code (a `CODEOWNERS` entry for it is a good idea once you have a team).
- Secrets in a repository's settings are only as safe as who can edit workflows; that is why `pull_request_target` is banned here and why production sits behind a human approval.
