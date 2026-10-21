#!/usr/bin/env bash
# Deploys one build to one environment, waits for it to be healthy, and rolls back if it is not.
#
#   REGISTRY=ghcr.io/my-org scripts/ci/deploy.sh <staging|prod> <image-tag>
#
# Environment variables
#   REGISTRY       required   where the images are (owner part only; images are <REGISTRY>/ridex-<service>:<tag>)
#   KUBECONFIG     required   points at a kubeconfig that may deploy to the environment's namespace (the pipeline writes it from a secret)
#   DB_PASSWORD, SERVICE_TOKEN   optional   when both are set the Secret "ridex-secrets" is created or updated from them
#   PULL_USER, PULL_TOKEN        optional   a registry login for PRIVATE images: creates the pull secret "registry-pull" and attaches it
#                                           to the service account the pods use
#   ROLLOUT_TIMEOUT   seconds to wait for each workload (default 300)
#   RENDER_SCRIPT  path of another renderer: only for testing the deployment logic itself (see scripts/ci/selftest.sh)
#   DRY_RUN=1      only ask the API server to validate the manifests (kubectl apply --dry-run=server); changes nothing
#
# What it does, in order: render (render.sh) -> check the namespace exists (an administrator creates it once) -> secrets -> apply -> wait for every Deployment and
# StatefulSet -> on failure, show what is wrong and undo the rollout of every Deployment, then exit 1.
set -euo pipefail

env="${1:?usage: deploy.sh <staging|prod> <image-tag>}"
tag="${2:?image tag missing}"
registry="${REGISTRY:?REGISTRY is not set (for example ghcr.io/my-org)}"
timeout="${ROLLOUT_TIMEOUT:-300}"
dry_run="${DRY_RUN:-0}"
namespace="ridex-$env"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# "Does the namespace exist?" asked in a way a credential limited to ONE namespace is allowed to ask (reading Namespace objects is a cluster-wide
# permission it does not have): every existing namespace has a ServiceAccount called "default".
namespace_exists() { kubectl get serviceaccount default -n "$namespace" > /dev/null 2>&1; }

work="$(mktemp -d)"
chmod 700 "$work"
trap 'rm -rf "$work"' EXIT

echo "==> Rendering the $env manifests for ${registry}/ridex-*:${tag}"
bash "${RENDER_SCRIPT:-$here/render.sh}" "$env" "$registry" "$tag" > "$work/all.yaml"

# Safety net: nothing that points at a placeholder or a local image may reach a cluster
if grep -E "image: .*(example-org|:dev$|:latest$)" "$work/all.yaml"; then
  echo "deploy.sh: the rendered manifests still contain a placeholder or local image (lines above)" >&2
  exit 1
fi
images="$(grep -c "image: ${registry,,}/ridex-" "$work/all.yaml" || true)"
echo "    $images workloads use the new images"

# Namespaces are cluster-scoped. The pipeline's credential is limited to ONE namespace (infrastructure/k8s/ci/deployer.yaml), so it
# does not manage namespaces: an administrator creates each one once. Drop the Namespace object from what is applied.
awk 'BEGIN { RS = "\n---\n"; ORS = "\n---\n" } !/(^|\n)kind: Namespace(\n|$)/ { print }' "$work/all.yaml" > "$work/applied.yaml"
mv "$work/applied.yaml" "$work/all.yaml"

if [[ "$dry_run" == "1" ]]; then
  # The API server can only dry-run objects into a namespace that EXISTS. Once the first real deployment created it, every later dry
  # run is checked by the server (schema, admission rules, Pod Security); before that, only the client-side check is possible.
  if namespace_exists; then
    echo "==> DRY RUN: asking the API server to validate (nothing is changed)"
    kubectl apply --dry-run=server -f "$work/all.yaml" > "$work/dry.txt" || { cat "$work/dry.txt"; exit 1; }
    echo "    $(wc -l < "$work/dry.txt") objects validated by the API server"
  else
    echo "==> DRY RUN: namespace $namespace does not exist yet, so only a client-side check is possible"
    kubectl apply --dry-run=client -f "$work/all.yaml" > "$work/dry.txt" || { cat "$work/dry.txt"; exit 1; }
    echo "    $(wc -l < "$work/dry.txt") objects parsed"
  fi
  exit 0
fi

echo "==> Checking that namespace $namespace exists"
namespace_exists || {
  echo "deploy.sh: namespace $namespace does not exist. Create it once, as an administrator:" >&2
  echo "           kubectl apply -f infrastructure/k8s/overlays/$env/namespace.yaml" >&2
  exit 1
}

if [[ -n "${DB_PASSWORD:-}" && -n "${SERVICE_TOKEN:-}" ]]; then
  echo "==> Creating or updating the Secret ridex-secrets (values are read from files, never put on a command line)"
  umask 077
  printf '%s' "$DB_PASSWORD" > "$work/DB_PASSWORD"
  printf '%s' "$SERVICE_TOKEN" > "$work/SERVICE_TOKEN"
  kubectl create secret generic ridex-secrets -n "$namespace" \
    --from-file=DB_PASSWORD="$work/DB_PASSWORD" --from-file=SERVICE_TOKEN="$work/SERVICE_TOKEN" \
    --dry-run=client -o yaml | kubectl apply -f - > /dev/null
  rm -f "$work/DB_PASSWORD" "$work/SERVICE_TOKEN"
else
  kubectl get secret ridex-secrets -n "$namespace" > /dev/null 2>&1 \
    || { echo "deploy.sh: the Secret ridex-secrets does not exist in $namespace and DB_PASSWORD / SERVICE_TOKEN were not provided" >&2; exit 1; }
  echo "==> Using the Secret ridex-secrets that already exists"
fi

if [[ -n "${PULL_USER:-}" && -n "${PULL_TOKEN:-}" ]]; then
  echo "==> Creating the registry pull secret and attaching it to the service account"
  server="${registry%%/*}"
  kubectl create secret docker-registry registry-pull -n "$namespace" --docker-server="$server" \
    --docker-username="$PULL_USER" --docker-password="$PULL_TOKEN" --dry-run=client -o yaml | kubectl apply -f - > /dev/null
  # the service account is part of the manifests; apply them first so it exists, then patch
  kubectl apply -f "$work/all.yaml" > /dev/null
  kubectl patch serviceaccount ridex -n "$namespace" -p '{"imagePullSecrets":[{"name":"registry-pull"}]}' > /dev/null
fi

echo "==> Applying"
kubectl apply -f "$work/all.yaml"

deployments="$(kubectl get deployments -n "$namespace" -o name)"
statefulsets="$(kubectl get statefulsets -n "$namespace" -o name)"

failed=0
for workload in $statefulsets $deployments; do
  echo "==> Waiting for $workload (up to ${timeout}s)"
  if ! kubectl rollout status "$workload" -n "$namespace" --timeout="${timeout}s"; then
    failed=1
    break
  fi
done

if [[ "$failed" -ne 0 ]]; then
  echo "::error::Rollout did not finish in $namespace. State of the pods:" >&2
  kubectl get pods -n "$namespace" -o wide >&2 || true
  kubectl get events -n "$namespace" --sort-by=.lastTimestamp 2>/dev/null | tail -n 15 >&2 || true
  echo "==> Rolling back every Deployment to its previous revision" >&2
  for d in $deployments; do
    kubectl rollout undo "$d" -n "$namespace" >&2 || true
  done
  exit 1
fi

echo "==> Deployed ${registry}/ridex-*:${tag} to $namespace"
kubectl get deployments -n "$namespace"
