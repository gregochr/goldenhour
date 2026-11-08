#!/bin/bash
# The two manifest field readers must be identical (docs/engineering/engineering-gaps-plan.md, Phase 4, R3).
#
# scripts/rollback.sh, the manual Plan B, keeps its OWN one-line reader so it never depends on the host's
# working copy; scripts/lib/release-manifest.sh is the copy auto-rollback.sh sources. They are the same
# line by hand, so this fails CI the moment either is edited alone.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS="$(cd "$HERE/.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

reader_of() { grep -E '^field\(\) ' "$1"; }

A="$(reader_of "$SCRIPTS/rollback.sh")"
B="$(reader_of "$SCRIPTS/lib/release-manifest.sh")"

assert_eq "rollback.sh has exactly one field() reader" 1 "$(printf '%s\n' "$A" | grep -c .)"
assert_eq "the helper has exactly one field() reader" 1 "$(printf '%s\n' "$B" | grep -c .)"
assert_eq "the two readers are byte-identical" "$A" "$B"

# And they behave the same on a manifest with and without the key.
M="$(mktemp)"
trap 'rm -f "$M"' EXIT
printf '{\n  "rollback_to": {\n    "backend": "ghcr.io/x@sha256:aa",\n    "schema_version": "7"\n  }\n}\n' > "$M"
MANIFEST="$M"
# shellcheck source=scripts/lib/release-manifest.sh
source "$SCRIPTS/lib/release-manifest.sh"
assert_eq "reads a present key" "ghcr.io/x@sha256:aa" "$(field backend)"
assert_eq "a missing key is empty, and does not kill a set -e caller" "" "$(set -e; set -o pipefail; field frontend; echo)"

finish_tests
