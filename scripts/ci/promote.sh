#!/usr/bin/env bash
# Gives an image that was already built and tested a new tag, WITHOUT rebuilding it.
#
#   scripts/ci/promote.sh <image-without-tag> <existing-tag> <new-tag>
#   scripts/ci/promote.sh ghcr.io/my-org/ridex-core sha-1a2b3c4d5e6f 1.4.0
#
# This is "build once, deploy many": what production runs is byte for byte the image that passed the tests and ran in staging. A tag is
# only a name pointing at an image digest; adding a name copies nothing. Rebuilding for the release would produce a DIFFERENT image
# (new timestamps, possibly new dependency versions) that nobody has tested.
#
# Exits 1 if the existing tag is not in the registry, or if the new tag does not end up pointing at the same digest.
set -euo pipefail

image="${1:?usage: promote.sh <image> <existing-tag> <new-tag>}"
from="${2:?existing tag missing}"
to="${3:?new tag missing}"

digest_of() {
  docker buildx imagetools inspect "$1" --format '{{json .Manifest.Digest}}' | tr -d '"'
}

before="$(digest_of "$image:$from")" || { echo "promote.sh: $image:$from is not in the registry" >&2; exit 1; }
docker buildx imagetools create --tag "$image:$to" "$image:$from" > /dev/null
after="$(digest_of "$image:$to")"

if [[ "$before" != "$after" ]]; then
  echo "promote.sh: $image:$to points at $after, not at $before" >&2
  exit 1
fi
echo "$image:$to -> $after (same image as $from)"
