# Setting up GitHub for the pipeline (one time)

The workflow files in `.github/` are only half of the pipeline. The other half lives in **GitHub's settings** and cannot be committed as a file: the repository, the two environments with their secrets and approvals, and branch protection. This page is the checklist. **None of these steps was executed while building Phase 13** (there is no GitHub repository attached to this project); the workflow files, the scripts and the Kubernetes credential below were verified locally, see [phase-13-review.md](phase-13-review.md).

Commands marked `gh` use the GitHub CLI (`gh auth login` first). Everything can also be done in the web page (Settings).

## 1. The repository

```
gh repo create <your-user-or-org>/ridex --private --source . --remote origin
git push -u origin main
```

Images are published to `ghcr.io/<owner>/ridex-<service>`, with the owner in lower case. The first time the pipeline pushes, GitHub creates the packages as **private**; for a private repository keep them private and let the cluster pull with the pull secret that `deploy.sh` creates (below).

## 2. The cluster side: a namespace and a limited credential for each environment

Do this as a cluster administrator, once per environment. (The pipeline cannot create namespaces or change permissions, by design.)

```
kubectl apply -f infrastructure/k8s/overlays/staging/namespace.yaml
sed 's/__NAMESPACE__/ridex-staging/g' infrastructure/k8s/ci/deployer.yaml | kubectl apply -f -
```

Repeat with `prod` and `ridex-prod` for production. This creates a ServiceAccount `ci-deployer` whose Role covers only the kinds of objects RideX contains, in that one namespace: no delete, no exec, no listing of other secrets, nothing outside the namespace (verified, see the review).

Create the kubeconfig the pipeline will use, from a token of that ServiceAccount:

```
TOKEN=$(kubectl create token ci-deployer -n ridex-staging --duration=8760h)   # a year; the API server may cap it, then rotate
SERVER=$(kubectl config view --minify --raw -o jsonpath='{.clusters[0].cluster.server}')
kubectl config view --minify --raw -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' | base64 -d > ca.crt
kubectl config set-cluster ridex --server="$SERVER" --certificate-authority=ca.crt --embed-certs=true --kubeconfig=ci-staging.kubeconfig
kubectl config set-credentials ci-deployer --token="$TOKEN" --kubeconfig=ci-staging.kubeconfig
kubectl config set-context ci --cluster=ridex --user=ci-deployer --namespace=ridex-staging --kubeconfig=ci-staging.kubeconfig
kubectl config use-context ci --kubeconfig=ci-staging.kubeconfig
base64 -w0 ci-staging.kubeconfig        # the value of the KUBE_CONFIG secret below
```

Delete `ca.crt` and the kubeconfig file afterwards: they must live only in the GitHub secret. The cluster must be reachable from GitHub's runners; for a private cluster use a self-hosted runner inside the network, or a cloud OIDC login (section 6).

## 3. The two GitHub Environments

```
gh api -X PUT repos/<owner>/ridex/environments/staging
gh api -X PUT repos/<owner>/ridex/environments/production
```

Then, in **Settings > Environments**:

| Setting | `staging` | `production` |
|---|---|---|
| Required reviewers | none (deploys by itself) | **1 or 2 people** (not the person who tags the release, if you have a team: tick "Prevent self-review") |
| Wait timer | none | optional, for example 5 minutes (time to cancel) |
| Deployment branches and tags | `main` | **Selected tags: `v*`** (production can only deploy from a version tag) |

Secrets and variables of each environment (**the names are the same, the values differ**):

```
gh secret set KUBE_CONFIG    --env staging    --body "$(base64 -w0 ci-staging.kubeconfig)"
gh secret set DB_PASSWORD    --env staging    # prompts for the value; use a long random one, DIFFERENT from production
gh secret set SERVICE_TOKEN  --env staging    # random; shared by the core and the pricing service
gh variable set STAGING_URL  --env staging    --body "https://staging.ridex.example.com"

gh secret set KUBE_CONFIG    --env production --body "$(base64 -w0 ci-prod.kubeconfig)"
gh secret set DB_PASSWORD    --env production
gh secret set SERVICE_TOKEN  --env production
gh variable set PRODUCTION_URL --env production --body "https://ridex.example.com"
```

Generate the random values on your machine, for example `openssl rand -base64 32`; never reuse a development password.

No repository-level secrets are needed: `GITHUB_TOKEN`, used to push images, is created by GitHub for every run.

## 4. Branch protection for `main`

**Settings > Branches > Add rule** (or a *ruleset*) for `main`:

- Require a pull request before merging (at least 1 approval once there is a team).
- Require status checks to pass: select **`CI passed`** (the `ci-ok` job). One required name is enough because that job fails if any other job did not succeed.
- Require branches to be up to date before merging.
- Block force pushes and deletion.

Also under **Settings > Actions > General**: "Workflow permissions" = *Read repository contents* (the files already declare what more a job needs), and "Require approval for first-time contributors" for pull requests from forks.

## 5. Your first run

1. Open a pull request: `CI` runs (about 6 minutes on GitHub's machines). Fix anything red.
2. Merge it: `CD` runs: CI again, then the images are built and pushed (`sha-<commit>`), then **staging** is deployed and smoke-tested.
3. Look at staging. When you are happy, release:
   ```
   git tag v1.0.0
   git push origin v1.0.0
   ```
   `CD` runs for the tag: the images get the name `1.0.0` (nothing is rebuilt), and the **production** job waits for the reviewer. After approval it deploys `1.0.0` and smoke-tests.
4. Something wrong in production: re-run the previous tag's production deployment (the images are still there), or `kubectl rollout undo deployment/<name> -n ridex-prod`.

## 6. Better than a stored kubeconfig: OpenID Connect

A kubeconfig token in a secret is a long-lived credential: if it leaks, it works until someone notices. With **OIDC** the workflow asks GitHub for a short-lived identity token (`permissions: id-token: write` on the deployment job, and a login action of your cloud provider), and the cloud trusts it only for **this repository, this environment** (and for production only the tag ref). The cluster credential then exists for minutes. For a cloud cluster (EKS, GKE, AKS) this is the recommended setup; the rest of the pipeline does not change, only the "Connect to the cluster" step.

## 7. If something does not work

| Symptom | Likely cause |
|---|---|
| `Permission denied` running `mvnw` | the file lost its executable bit; `git update-index --chmod=+x backend/mvnw` (the workflows use `bash mvnw`, so this only affects local use) |
| `denied: permission_denied: write_package` when pushing | the `images` job lost `packages: write`, or the organisation forbids the token from publishing packages |
| `ImagePullBackOff` in the cluster | the packages are private and the pull secret is missing: `deploy.sh` creates `registry-pull` from the run's token, but that token expires; use a read-only personal token or a robot account stored as `PULL_TOKEN` for long-term pulls |
| the production job never starts | it is waiting for approval (Actions tab > the run > Review deployments), or the tag does not match `v<digits>.<digits>.<digits>` |
| `namespace ridex-... does not exist` | step 2 was skipped |
| `Forbidden` from kubectl in the deploy job | the Role in `infrastructure/k8s/ci/deployer.yaml` does not cover a new kind of object that was added to the manifests; add it (and the policy test will tell you what it forbids) |
| the smoke test fails right after a successful rollout | the ingress, DNS or the TLS certificate is not ready yet; it retries for 2.5 minutes by default (`SMOKE_ATTEMPTS`, `SMOKE_PAUSE`) |
