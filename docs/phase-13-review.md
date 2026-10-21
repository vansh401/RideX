# Phase 13 review: CI/CD

Read [ci-cd-concepts.md](ci-cd-concepts.md) first (the ideas) and [github-setup.md](github-setup.md) (how to switch it on). This is the review: what changed, **what was and was not verified**, what went wrong, what is still missing, and how to defend it in an interview.

## 1. What changed, step by step

| Step | What we built | Where |
|---|---|---|
| 13.1 Build and unit tests | Workflow **CI**: parallel jobs, Maven and npm **caching**, concurrency (a new push cancels the old pull-request run), least-privilege token, a single required check `CI passed`. Tests split with a JUnit tag; Mockito loaded as a Java agent so test JVMs start reliably | `.github/workflows/ci.yml`, `@Tag("integration")` on 6 test base classes, `backend/pom.xml` |
| 13.2 Integration tests | A job per service (matrix) running the tests that need Docker (**Testcontainers** on GitHub's Ubuntu runners), flaky tests retried once and reported as flakes | `ci.yml` job `backend-integration` |
| 13.3 Build and push images | Workflow **CD**: 7 images, tagged `sha-<commit>`, pushed to GitHub Container Registry with the run's `GITHUB_TOKEN`, provenance + SBOM, Trivy scan; **release = add a version name to the existing image** (build once, deploy many); a release tag must be on main | `.github/workflows/cd.yml`, `scripts/ci/image-exists.sh`, `promote.sh` |
| 13.4 Deploy to Kubernetes | `render.sh` (the overlay with the pipeline's images, in a temporary copy), `deploy.sh` (secrets, apply, wait, **automatic rollback**), `smoke.sh`; **staging deploys every commit on main; production only from a `v*` tag, behind an approval**. A least-privilege cluster identity for the pipeline | `scripts/ci/*.sh`, `infrastructure/k8s/ci/deployer.yaml` |
| 13.5 Environments, secrets, frontend | GitHub **Environments** `staging` / `production` (their own secrets, variables, protection rules), frontend job (lint, tests, build, artifact), Dependabot, the one-time setup guide | `docs/github-setup.md`, `ci.yml` job `frontend`, `.github/dependabot.yml` |
| Guard rails | **22 policy tests** on the pipeline itself (permissions, pinned actions, no secrets in commands, who may push images, how production is reached, scripts exist, line endings, the deployer's rights), plus `actionlint` in CI and locally | `CiPolicyTest` |

## 2. What was verified, and what was NOT

**There is no GitHub repository attached to this project, so the workflows have never run.** That is the honest headline. What was checked, with real tools:

| Verified here (how) | Result |
|---|---|
| Workflow syntax, expressions and the shell in `run:` steps | **actionlint 1.7.7** (official image): no findings, on both workflows |
| Pipeline policy | 22 tests; a **mutation check** (an action pinned to `@main`, a secret written inside a `run:` command, production deploying on every push) was caught by the right test each time, then the files were restored byte for byte |
| Test split | unit run **356** + integration run **295** = **651** = the full build before the split; nothing skipped, nothing twice. (With the 22 policy tests: 378 + 295 = 673.) |
| The CI commands themselves | unit job command: 1 min 9 s, green. Integration job command (all modules): green, 295 tests. Frontend: `npm ci --dry-run` consistent, lint clean, **76 tests** pass, production build OK. Alert rules: `promtool`/`amtool` via the portable script |
| `render.sh` | staging 37 objects, prod 39; the right 7 images with a lower-cased owner; refuses `latest`, `dev`, `v1`, empty; tracked files untouched |
| `image-exists.sh`, `promote.sh` | against a **real registry** (`registry:2`): a version tag resolves to exactly the same digest as the commit tag; promoting an image that was never built fails |
| `deploy.sh` | server-side dry run on the kind cluster: **36 staging and 38 production objects** validated by the API server, **as the restricted pipeline identity**; the full real run in `selftest.sh`: a good version deploys, a bad one fails, rolls back and the good pod serves throughout |
| The deployer's rights | `kubectl auth can-i` for 12 actions, as the ServiceAccount: delete, exec, logs, list secrets, other namespaces, namespaces, ClusterRoleBinding all **denied**; patch, secret write, watch **allowed** |
| `smoke.sh` | against the real ingress: passes on the working system, fails (exit 1) for a wrong host and for a closed port |

**Not verified (needs a real GitHub repository and a registry):**
- that the workflows **run** on GitHub's machines (for example `setup-java`'s Maven cache, the `gha` Docker cache, `pwsh` on the runner, the `docker://` actionlint step);
- that the pinned action versions exist as written (`@v4`, `@v3`, `@v6`: they are the majors I know; Dependabot will move them, and a typo shows up on the first run);
- pushing to `ghcr.io`, provenance/SBOM attachments, and the **Trivy scan** step (only the image tag `0.76.1` was confirmed to exist);
- environment approvals, the `KUBE_CONFIG`-from-secret step, and the deployment to a real remote cluster;
- the timing claims (about 6 minutes for CI on GitHub's machines is an estimate from the local numbers).

Plan for the first real run: open a pull request first (CI only), fix what is red, then merge and watch staging, and only then cut `v0.1.0`.

## 3. What went wrong on the way (and what it taught)

1. **`mvnw` was committed without the executable bit** (mode 100644, because it came from Windows): the first CI run would have died with `Permission denied`. Fixed in the git index, the workflows also call `bash mvnw`, and `.gitattributes` keeps `*.sh` and `mvnw` on Unix line endings. Lesson: "works on my machine" includes the file permissions of a different operating system.
2. **Mockito's self-attaching agent failed at random** on a memory-starved machine ("Could not self-attach to current VM using external process"), three times in the last two phases and again at the start of this one (150 errors in one module). It spawns a helper process; a pipeline cannot depend on that. Fix: load Mockito as a `-javaagent` in surefire, once, in the parent pom. The warning is gone and the tests are stable. (The core module had its own copy of the surefire settings that silently overrode the parent; it now inherits.)
3. **`deploy.sh` had two bugs that only testing with the real thing could find.** (a) `kubectl apply -f <(...)`, a Linux-only construct, failed with the Windows `kubectl`. (b) With the real limited identity, `kubectl get namespace` is forbidden (it is a cluster-wide permission), so the script concluded the namespace did not exist. Both are fixed; (b) only appeared because I tested as the restricted ServiceAccount instead of as an administrator.
4. **The first design applied the Namespace object** from the overlay, which a namespace-limited credential cannot do. The pipeline now drops it; an administrator creates namespaces once.
5. **A server-side dry run cannot validate into a namespace that does not exist.** The script now says so and falls back to a client-side check instead of printing a wall of errors.
6. **Vitest and the Vite 8 bundler crashed with "out of memory"** when run beside Docker Desktop and the kind cluster on an 8 GB laptop (workers died at start; `npm test` printed "6 errors" with all tests passing). Not a code problem: with Docker shut down, 76 tests pass and the build finishes in 25 s. I confirmed this rather than assuming it, by freeing memory and rerunning, and by reading the error text (heap out of memory, worker exit codes).
7. **The Trivy version I first wrote was two years old**; an old scanner may not be able to fetch the current vulnerability database. Changed to a current tag, checked against the registry.
8. **A bad `perl` one-liner mangled `deploy.sh`** (a copy of my replacement text landed before the shebang). `bash -n` and a re-read caught it before it was used; the lesson is to edit shell scripts with an editor tool, not a regex over several lines.
9. **A safety check stopped an `rm -rf` whose target came from a command substitution.** I printed the path, looked at what was inside (only the test kubeconfig and CA), and removed those literal files.

## 4. What is still not good enough (say this in the interview)

- **Nothing has run on GitHub** (section 2). The pipeline is tested piece by piece, not as a whole.
- **The six backend images each compile the whole backend** inside the Dockerfile. The Docker layer cache shares that work between them (one cache scope), but a better design builds the jars once in CI and has the Dockerfile only copy them.
- **Trivy only gates on critical, fixable vulnerabilities**, and the scan runs after the push (the image is in the registry before it is scanned); a stricter flow scans first, pushes second.
- **Images are deployed by tag, not by digest.** Tags in our registry are not overwritten by the pipeline, but a digest reference (`image@sha256:...`) cannot be changed at all.
- **Secrets are applied as Kubernetes Secrets from CI.** A secret manager (External Secrets, Vault, sealed secrets) is better: the pipeline would then never see the values.
- **A stored kubeconfig token** instead of OIDC (described in `github-setup.md`, not built).
- **Database migrations run when the new core starts**, not as a separate step. A bad migration is found in production's rollout, and `rollout undo` cannot undo a migration. Expand-and-contract migrations (never break the previous version) are the discipline that makes this safe.
- **No deployment strategy beyond rolling updates** (no canary or blue-green), and the smoke test is small. It proves the site answers, not that a ride can be booked.
- **No notifications** (chat/email) of a failed pipeline or a pending approval.
- **Flaky tests** are retried once rather than fixed; the timing-dependent rate-limit tests need a controllable clock.
- **The staging and production overlays still carry placeholder image names and versions** (the pipeline overrides them in a copy). Their `images:` lines are documentation for manual use only.

## 5. Interview questions

**Q: What is the difference between continuous integration, delivery and deployment?**
CI: every change is built and tested automatically on a clean machine. Continuous delivery: every change that passes is releasable with one decision. Continuous deployment: it is deployed with no decision. Here staging is continuous deployment (every commit on main) and production is delivery with an approval.

**Q: Why do the unit tests and the integration tests run in separate jobs?**
Feedback speed and cost. Unit tests need nothing and finish in about a minute; integration tests start real databases and take minutes. Separate jobs let a typo fail fast, and the integration tests can run in parallel, one machine per service. They are separated with a JUnit tag, and I proved the two sets add up to the full suite.

**Q: How do Testcontainers work in a pipeline?**
The test code starts containers through the Docker API, so the runner must have Docker (GitHub's Ubuntu machines do). Each job gets a fresh machine, containers die with it, and one machine per service avoids memory and port fights.

**Q: What is "build once, deploy many" and how did you do it?**
The image is built once for a commit and tagged `sha-<commit>`. Staging runs exactly that image. A release does not rebuild: it adds the name `1.4.0` to the same digest, and production runs that. I verified against a real registry that the two tags have the same digest. A rebuild would be an untested artifact.

**Q: Why not deploy `latest`?**
It moves, so you cannot say what is running, two clusters can run different images under the same name, and a rollback has nothing to go back to. Commit-tagged images are immutable names. A policy test fails the build if a workflow mentions it.

**Q: How do you protect production?**
A different trigger (a version tag, from main only), a GitHub Environment with required reviewers and a tag restriction, its own secrets that other jobs cannot read, a cluster identity limited to one namespace, an automatic rollback, and a smoke test.

**Q: How do secrets get to the deployment safely?**
They live in the environment, not the repository; the job that declares the environment gets them as environment variables, never written in a command line (they could leak through the process list or logs); the script reads them through files; GitHub masks them in logs but masking is a safety net. Better still is OIDC, where no long-lived credential exists.

**Q: What does the pipeline's cluster credential allow?**
Only what the manifests contain, in one namespace: apply and roll back workloads, write two secrets, read pods and events. Not delete, not exec, not logs, not other namespaces, not namespaces or permissions. I tested it by running the deployment as that ServiceAccount, and with `kubectl auth can-i` for each denied action.

**Q: What happens when a deployment fails?**
The script waits for each rollout; on a failure it prints the pod state and events, runs `rollout undo` on every Deployment and fails the job. Because the rolling update never takes the old pods away before the new ones are Ready, the site keeps serving. I tested this on a cluster with a good and a non-existent image.

**Q: Why a single `ci-ok` job?**
Branch protection requires named checks; a list of them must be edited whenever a job is added or renamed, and a job that is skipped counts as passing. One final job that depends on all the others and fails unless they all succeeded is the one name to require.

**Q: Tell me about something that went wrong.**
The deployment script worked when I ran it as an administrator and failed when I ran it as the pipeline's real identity: it asked the cluster whether the namespace existed with a cluster-wide permission that identity does not have, so it concluded the namespace was missing. Testing with the actual least-privilege identity found it; the fix is a namespaced probe. A second one: the Maven wrapper had no executable bit because it was committed from Windows, which would have failed the first run on Linux.

**Q: What would you add next?**
Build the jars once and reuse them in the images; scan before pushing; deploy by digest; a secret manager; OIDC instead of a stored token; database migrations as their own safe step; canary releases with automatic rollback on metrics (using the Prometheus alerts from Phase 12); and notifications.
