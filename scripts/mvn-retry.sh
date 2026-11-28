#!/bin/bash
# ============================================================================
# mvn-retry.sh COMMAND [ARGS...]  (e.g. scripts/mvn-retry.sh ./mvnw clean verify --batch-mode)
#
# Runs a Maven command and re-runs it, with backoff, ONLY when it failed because a repository
# transfer failed transiently (Maven Central HTTP 429 / 5xx, a timeout or a connection reset).
# Maven's own wagon retry gives up after four quick attempts, which is not enough when Central is
# rate-limiting a shared GitHub runner IP (PR 1890: surefire-junit-platform:pom 429).
#
# A real failure is never re-run: a build that shows a compilation error or a test failure, or
# fails with no transfer error, exits at once with Maven's own code and output.
#
# Environment:
#   MVN_RETRY_ATTEMPTS  maximum runs, default 3
#   MVN_RETRY_DELAY     seconds of backoff per attempt (attempt N waits N x this), default 20
#   MVN_RETRY_SLEEP     the sleep command, default "sleep" (the tests stub it)
# ============================================================================

set -uo pipefail

if [ "$#" -eq 0 ]; then
    echo "usage: mvn-retry.sh COMMAND [ARGS...]" >&2
    exit 2
fi

ATTEMPTS="${MVN_RETRY_ATTEMPTS:-3}"
DELAY="${MVN_RETRY_DELAY:-20}"
SLEEPER="${MVN_RETRY_SLEEP:-sleep}"

LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

# A transfer problem Maven could not get past: the artifact-transfer message (or a descriptor read
# or resolution that failed on one) together with a rate-limit, server-error, timeout or reset cause.
TRANSFER_RE='Could not transfer (artifact|metadata)|Failed to read artifact descriptor|Could not resolve dependencies|could not be resolved'
CAUSE_RE='status code: (429|5[0-9][0-9])|HTTP Status: (429|5[0-9][0-9])|Too Many Requests|Read timed out|Connect timed out|Connection reset|Premature end of Content-Length|Remote host terminated the handshake|Connection refused|Temporary failure in name resolution'
# Anything that means the code, not the network, is at fault.
REAL_FAILURE_RE='COMPILATION ERROR|There are test failures|Tests run:.*(Failures|Errors): [1-9]|Coverage checks have not been met|You have [1-9][0-9]* Checkstyle violation|BugInstance size is [1-9]'

is_transient() {
    if grep -Eqi -- "$REAL_FAILURE_RE" "$LOG"; then
        return 1
    fi
    # Only [ERROR] lines count: a "[WARNING] Could not transfer metadata ... Connection reset" from a
    # transfer that later succeeded must not qualify a later real failure (jacoco, checkstyle ...).
    local errors
    errors="$(grep -E -- '^\[ERROR\]' "$LOG")"
    grep -Eqi -- "$TRANSFER_RE" <<< "$errors" && grep -Eqi -- "$CAUSE_RE" <<< "$errors"
}

attempt=1
while true; do
    : > "$LOG"
    "$@" 2>&1 | tee "$LOG"
    rc="${PIPESTATUS[0]}"
    if [ "$rc" -eq 0 ]; then
        exit 0
    fi
    if [ "$attempt" -ge "$ATTEMPTS" ] || ! is_transient; then
        if [ "$attempt" -gt 1 ]; then
            echo "::error::Maven failed (exit ${rc}) after ${attempt} attempts; not retrying further."
        fi
        exit "$rc"
    fi
    wait_s=$((attempt * DELAY))
    echo "::warning::Maven attempt ${attempt}/${ATTEMPTS} failed on a transient repository transfer error; retrying in ${wait_s}s."
    "$SLEEPER" "$wait_s"
    attempt=$((attempt + 1))
done
