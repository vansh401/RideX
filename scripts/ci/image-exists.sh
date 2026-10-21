#!/usr/bin/env bash
# Exit code 0 if the image (with its tag) is in the registry, 1 if not.
#
#   scripts/ci/image-exists.sh ghcr.io/my-org/ridex-core:sha-1a2b3c4d5e6f
#
# Used so that a re-run of the pipeline does not rebuild an image that already exists, and so that a release tag can reuse the image
# built for the same commit.
set -uo pipefail
ref="${1:?usage: image-exists.sh <image:tag>}"
docker buildx imagetools inspect "$ref" > /dev/null 2>&1
