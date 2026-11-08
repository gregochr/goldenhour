#!/bin/bash
# ============================================================================
# Sourced helper: read one string field from a pre-release manifest.
#
# Sourced by scripts/auto-rollback.sh via "$(dirname "$0")/lib/release-manifest.sh".
# deploy.yml extracts the tag's whole scripts/ directory into one temporary
# directory (`git archive "$RELEASE_VERSION" scripts | tar -x`), so this path
# exists on the host only because of that layout.
#
# scripts/rollback.sh deliberately keeps its OWN copy of this one-line reader and
# does not source this file: the manual Plan B must not depend on the host's
# working copy being current. scripts/test/manifest-field-parity.test.sh fails
# CI if the two lines ever drift apart, so the `field()` line below must stay
# byte-identical to the `field()` line in rollback.sh.
#
# Needs $MANIFEST set to the manifest path before field() is called.
#
# The `|| true` is load-bearing (see rollback.sh): without it a MISSING key makes
# grep exit 1, pipefail promotes that to the pipeline's status, and under
# `set -e` the caller dies silently before any explicit error is reachable.
# ============================================================================

# shellcheck disable=SC2154  # MANIFEST is assigned by the script that sources this file
field() { grep -o "\"$1\": *\"[^\"]*\"" "$MANIFEST" | head -1 | cut -d'"' -f4 || true; }
