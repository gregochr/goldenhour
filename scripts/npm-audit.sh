#!/bin/bash
# ============================================================================
# Frontend dependency audit for CI.
#
# ---------------------------------------------------------------------------
# WHY THIS IS NOT JUST `npm audit --audit-level=high`
#
# On 2026-09-04 the frontend job went red on a PR that touched no dependency
# file, with this:
#
#   npm notice This endpoint is being retired. Use the bulk advisory endpoint
#              instead.
#   npm warn audit 400 Bad Request - POST .../security/audits/quick
#     { message: 'Invalid package tree, run npm install to rebuild your
#                 package-lock.json' }
#   npm error audit endpoint returned an error
#
# It passed on re-run with an identical tree, so the tree was never invalid.
#
# The message is misleading in a way worth writing down, because the obvious
# reading of it produces the wrong fix. npm does NOT choose the quick endpoint.
# `@npmcli/arborist` 8.x (bundled with npm 10, which Node 22 ships) tries the
# BULK endpoint first and falls back to the quick one when that throws:
#
#   try   { fetch('/-/npm/v1/security/advisories/bulk', ...) }
#   catch { log.silly('audit', 'bulk request failed', ...)      <-- SILLY ONLY
#           fetch('/-/npm/v1/security/audits/quick', ...) }
#
# The bulk failure is logged at `silly`, which CI never prints. So the error
# on screen came from a fallback nobody chose, about an endpoint nobody asked
# for, while the actual failure — bulk being briefly unavailable — was
# invisible. "Switch to the bulk endpoint" was already the behaviour.
#
# Two changes follow from that, and they fix different halves:
#
#   1. Run the audit under npm 11, which DELETED the quick fallback (grep it:
#      `audits/quick` appears nowhere in npm 11). A registry problem then names
#      the endpoint that actually failed instead of a fallback's 400. This is
#      the diagnosability half.
#
#   2. Retry, but ONLY on transport/endpoint errors. This is the half that
#      stops registry weather reding a build. A real advisory must still fail
#      on the first attempt — retrying a vulnerability finding would be a gate
#      that gets quieter the more it is asked, which is the opposite of a gate.
#
# npm 11 is fetched per-run rather than installed globally so that `npm ci`,
# the lint, the tests and the build all keep running under the runner's own
# npm. This repo has a recorded sensitivity to npm 11 writing lockfile
# metadata (the `libc` field on optional rollup binaries) that the runner's
# npm does not, and the smallest change that fixes the audit is the one that
# does not touch how anything else is installed.
# ============================================================================

set -uo pipefail

readonly AUDIT_DIR="${1:-frontend}"
readonly AUDIT_LEVEL="${2:-high}"
readonly NPM_SPEC="npm@11"
readonly MAX_ATTEMPTS=3

cd "$AUDIT_DIR" || {
    echo "npm-audit: no such directory: $AUDIT_DIR" >&2
    exit 2
}

# Substrings that mean "we could not get an answer", as opposed to "the answer
# was: you have a vulnerability". Kept deliberately narrow: anything not listed
# here fails the build immediately, so a new failure mode is loud by default
# rather than silently retried into a timeout.
is_transport_error() {
    case "$1" in
        *"audit endpoint returned an error"*) return 0 ;;
        *"Invalid package tree"*)             return 0 ;;
        *ETIMEDOUT*|*ENOTFOUND*|*ECONNRESET*|*ECONNREFUSED*|*EAI_AGAIN*) return 0 ;;
        *"socket hang up"*|*"network timeout"*|*"request to "*"failed"*)  return 0 ;;
        *"502 Bad Gateway"*|*"503 Service Unavailable"*|*"504 Gateway"*)  return 0 ;;
        *"429 Too Many Requests"*)            return 0 ;;
        *) return 1 ;;
    esac
}

# ⚠️ STATE WHICH npm ACTUALLY RAN, rather than trusting that `npx npm@11` did what it looks like.
# The first CI run of this script still printed the registry's "this endpoint is being retired"
# notice, which npm 11 should never provoke — so either npx resolved something else, or the notice
# rides a response npm 11 does ask for. Either way the log has to answer it without a re-run.
audit_npm_version=$(npx --yes "$NPM_SPEC" --version 2>/dev/null || echo "unknown")
echo "npm-audit: auditing with npm ${audit_npm_version} (runner default: $(npm --version 2>/dev/null || echo unknown))"

attempt=1
while [ "$attempt" -le "$MAX_ATTEMPTS" ]; do
    # `--yes` so a cold npx cache does not sit waiting on a prompt no one can
    # answer. stderr is folded into stdout because npm puts the interesting
    # part (the endpoint, the status code) on stderr and the finding summary on
    # stdout, and the classifier below has to see both.
    # `--fetch-timeout` because npm's default is five minutes, and the failure mode here is a
    # HANG rather than a refusal — the 2026-09-04 red build spent 5m05s waiting before erroring.
    # Three attempts at that default would be a sixteen-minute step. Ninety seconds is well beyond
    # a healthy bulk request (measured in single-digit seconds) and turns the bad path from
    # sixteen minutes into about five.
    if output=$(npx --yes "$NPM_SPEC" audit --audit-level="$AUDIT_LEVEL" --fetch-timeout=90000 2>&1); then
        printf '%s\n' "$output"
        exit 0
    fi

    printf '%s\n' "$output"

    if ! is_transport_error "$output"; then
        # A real finding, or a genuinely broken tree. Fail now: this is the
        # gate doing its job, and repeating it would only delay the same answer.
        echo "npm-audit: audit reported findings — failing without retry." >&2
        exit 1
    fi

    if [ "$attempt" -lt "$MAX_ATTEMPTS" ]; then
        backoff=$(( attempt * 15 ))
        echo "::warning::npm audit could not reach the registry (attempt ${attempt}/${MAX_ATTEMPTS}); retrying in ${backoff}s"
        sleep "$backoff"
    fi

    attempt=$(( attempt + 1 ))
done

echo "::error::npm audit could not reach the registry after ${MAX_ATTEMPTS} attempts." >&2
echo "npm-audit: this is a registry availability failure, NOT a clean audit — the build fails" >&2
echo "npm-audit: deliberately rather than reporting a pass it never actually obtained." >&2
exit 1
