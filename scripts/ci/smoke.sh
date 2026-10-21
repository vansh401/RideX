#!/usr/bin/env bash
# A short check that a deployment really serves traffic, run by the pipeline after the rollout is complete.
#
#   scripts/ci/smoke.sh https://staging.ridex.example.com
#   SMOKE_HOST=ridex.localhost scripts/ci/smoke.sh http://127.0.0.1:8088     (an address that needs a Host header)
#
# "The pods are Ready" does not prove the site works for a user: the ingress, the website, the gateway and the core must all answer.
# It retries for a while because the ingress controller may need a moment to learn about new pods.
set -uo pipefail

base="${1:?usage: smoke.sh <base-url>}"
base="${base%/}"
attempts="${SMOKE_ATTEMPTS:-30}"
pause="${SMOKE_PAUSE:-5}"
host_header=()
[[ -n "${SMOKE_HOST:-}" ]] && host_header=(-H "Host: ${SMOKE_HOST}")

failures=0
fail() { echo "  FAIL  $1"; failures=$((failures + 1)); }
pass() { echo "  PASS  $1"; }

# status of a request, retrying until it is the expected one (or we run out of attempts)
expect_status() {
  local description="$1" expected="$2" method="$3" path="$4" status="000"
  for ((i = 1; i <= attempts; i++)); do
    status="$(curl -s -o /dev/null -m 10 -w '%{http_code}' -X "$method" "${host_header[@]}" "$base$path" || true)"
    [[ "$status" == "$expected" ]] && { pass "$description"; return; }
    sleep "$pause"
  done
  fail "$description (expected $expected, last answer $status)"
}

echo "Smoke test of $base"
expect_status "the website answers (GET /)" 200 GET /
expect_status "the API is up (GET /api/health)" 200 GET /api/health
expect_status "a protected path needs a token (GET /api/rides gives 401 from the gateway)" 401 GET /api/rides

body="$(curl -s -m 10 "${host_header[@]}" "$base/" || true)"
if grep -q '<div id="root"' <<< "$body"; then pass "the website is the single-page app"; else fail "GET / does not return the app"; fi

internal="$(curl -s -o /dev/null -m 10 -w '%{http_code}' -X POST "${host_header[@]}" "$base/internal/quotes" || true)"
if [[ "$internal" == "404" || "$internal" == "405" || "$internal" == "401" || "$internal" == "403" ]]; then pass "internal paths are not reachable from outside ($internal)"; else fail "POST /internal/quotes answered $internal"; fi

echo
if [[ "$failures" -eq 0 ]]; then echo "SMOKE TEST PASSED"; else echo "$failures CHECK(S) FAILED"; exit 1; fi
