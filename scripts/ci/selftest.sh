#!/usr/bin/env bash
# Tests the part of deploy.sh that is hard to trust without trying it: a deployment that does NOT become healthy is rolled back.
#
#   scripts/ci/selftest.sh            (needs kubectl pointing at a throw-away cluster, for example the kind cluster of Phase 10)
#
# It deploys a tiny stand-in application (a "pause" container) into the namespace ridex-staging through the REAL deploy.sh, using a
# test renderer instead of the real manifests:
#   1. a good version  -> the rollout finishes, deploy.sh succeeds
#   2. a bad version (an image that does not exist) -> the rollout never finishes, deploy.sh must exit with an error AND the Deployment
#      must be back on the good version
# The namespace is deleted at the end. NEVER run this against a cluster where ridex-staging is real.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ns=ridex-staging
if kubectl get namespace "$ns" > /dev/null 2>&1; then
  echo "selftest.sh: namespace $ns already exists; refusing to touch it" >&2
  exit 2
fi

fake="$(mktemp)"
cleanup() { kubectl delete namespace "$ns" --wait=false > /dev/null 2>&1; rm -f "$fake"; }
trap cleanup EXIT
kubectl create namespace "$ns" > /dev/null || { echo "selftest.sh: cannot create the namespace" >&2; exit 2; }   # an administrator creates namespaces; deploy.sh does not
cat > "$fake" <<'RENDERER'
#!/usr/bin/env bash
# stand-in for render.sh: arguments are env, registry, tag; the "image tag" decides which image the Deployment uses
cat <<YAML
apiVersion: v1
kind: Namespace
metadata: {name: ridex-staging}
---
apiVersion: apps/v1
kind: Deployment
metadata: {name: standin, namespace: ridex-staging}
spec:
  replicas: 1
  selector: {matchLabels: {app: standin}}
  template:
    metadata: {labels: {app: standin}}
    spec:
      containers:
        - {name: pause, image: "registry.k8s.io/pause:$3", imagePullPolicy: IfNotPresent}
YAML
RENDERER

failures=0
check() { if [[ "$2" == "ok" ]]; then echo "  PASS  $1"; else echo "  FAIL  $1"; failures=$((failures + 1)); fi; }

export REGISTRY=ghcr.io/selftest DB_PASSWORD=selftest-not-a-real-password SERVICE_TOKEN=selftest-not-a-real-token ROLLOUT_TIMEOUT=30
export RENDER_SCRIPT="$fake"

echo "1. a good version is deployed"
bash "$here/deploy.sh" staging 3.10 > /tmp/selftest-good.log 2>&1
[[ $? -eq 0 ]] && check "deploy.sh succeeds when the rollout finishes" ok || { check "deploy.sh succeeds when the rollout finishes" no; tail -n 15 /tmp/selftest-good.log; }
good="$(kubectl get deployment standin -n "$ns" -o jsonpath='{.spec.template.spec.containers[0].image}')"
[[ "$good" == "registry.k8s.io/pause:3.10" ]] && check "the good image is running ($good)" ok || check "the good image is running (got '$good')" no
secret="$(kubectl get secret ridex-secrets -n "$ns" -o jsonpath='{.data.DB_PASSWORD}' 2> /dev/null | base64 -d 2> /dev/null)"
[[ "$secret" == "selftest-not-a-real-password" ]] && check "the secret was created from the environment variables" ok || check "the secret was created" no

echo "2. a bad version (the image does not exist) is rolled back"
bash "$here/deploy.sh" staging nope > /tmp/selftest-bad.log 2>&1
code=$?
[[ $code -ne 0 ]] && check "deploy.sh fails when the rollout does not finish (exit $code)" ok || check "deploy.sh fails when the rollout does not finish" no
grep -q "Rolling back" /tmp/selftest-bad.log && check "it announces the rollback" ok || check "it announces the rollback" no
sleep 5
after="$(kubectl get deployment standin -n "$ns" -o jsonpath='{.spec.template.spec.containers[0].image}')"
[[ "$after" == "registry.k8s.io/pause:3.10" ]] && check "the Deployment is back on the good image ($after)" ok || check "the Deployment is back on the good image (got '$after')" no
ready="$(kubectl get deployment standin -n "$ns" -o jsonpath='{.status.readyReplicas}')"
[[ "$ready" == "1" ]] && check "the good pod kept serving the whole time (readyReplicas=$ready)" ok || check "readyReplicas is $ready" no

echo
if [[ "$failures" -eq 0 ]]; then echo "SELFTEST PASSED"; else echo "$failures CHECK(S) FAILED"; exit 1; fi
