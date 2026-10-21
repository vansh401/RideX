#!/usr/bin/env bash
# Renders the Kubernetes manifests of one environment with the images of ONE build, and prints the YAML.
#
#   scripts/ci/render.sh <staging|prod> <registry> <image-tag>  >  all.yaml
#   scripts/ci/render.sh staging ghcr.io/my-org sha-1a2b3c4d5e6f
#
# The overlays in infrastructure/k8s/overlays/ name placeholder images (ghcr.io/example-org/..., a made-up version). The pipeline must
# deploy the images IT just built, but must not edit files that are tracked in git. So: work on a temporary COPY of infrastructure/k8s,
# rewrite the image lines there, and let kustomize render the copy.
#
# Needs only kubectl (it has kustomize built in).
set -euo pipefail

env="${1:?usage: render.sh <staging|prod> <registry> <image-tag>}"
registry="${2:?registry missing, for example ghcr.io/my-org}"
tag="${3:?image tag missing, for example sha-1a2b3c4d5e6f}"

case "$env" in
  staging | prod) ;;
  *) echo "render.sh: environment must be staging or prod, not '$env'" >&2; exit 2 ;;
esac
# A tag is a version or "sha-<hex>": never "latest", never the local "dev" image
if [[ ! "$tag" =~ ^(sha-[0-9a-f]{7,40}|[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?)$ ]]; then
  echo "render.sh: refusing image tag '$tag' (expected sha-<hex> or a version like 1.2.3)" >&2
  exit 2
fi
registry="${registry,,}"   # registries want lower case; GitHub owner names may not be

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp -R "$here/infrastructure/k8s" "$work/k8s"
rm -rf "$work/k8s/overlays/dev/secrets"   # never needed, never copied

kustomization="$work/k8s/overlays/$env/kustomization.yaml"
# Every image line has the form  - {name: ridex/core, newName: ghcr.io/example-org/ridex-core, newTag: "1.0.0"}
sed -E -i "s#\{name: ridex/([a-z]+), newName: [^,]+, newTag: \"[^\"]*\"\}#{name: ridex/\1, newName: ${registry}/ridex-\1, newTag: \"${tag}\"}#" "$kustomization"

changed="$(grep -c "newName: ${registry}/ridex-" "$kustomization" || true)"
if [[ "$changed" -ne 7 ]]; then
  echo "render.sh: expected to rewrite 7 image lines in the $env overlay, rewrote $changed" >&2
  exit 1
fi

kubectl kustomize "$work/k8s/overlays/$env"
