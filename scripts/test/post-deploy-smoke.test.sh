#!/bin/bash
# Shell-level tests for scripts/post-deploy-smoke.sh that need no server (the behavioural cases against a live
# stack and a stub site are in frontend/src/test/e2e/smoke/deploy-smoke.spec.js): how the script treats its own
# environment and its deadline, with a stub `curl` that only records how it was called.

# SC2016: the single-quoted bad password is meant literally.
# shellcheck disable=SC2016
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS="$(cd "$HERE/.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

BIN="$(mktemp -d)"
LOG="${BIN}/curl.log"
trap 'rm -rf "$BIN"' EXIT
ORIGINAL_PATH="$PATH"

# A curl that records its arguments and answers like a dead network (no body, exit 7).
cat > "$BIN/curl" <<'EOF'
#!/bin/bash
echo "curl $*" >> "$CURL_LOG"
printf '000'
exit 7
EOF
chmod +x "$BIN/curl"
export CURL_LOG="$LOG" APP_URL_BASE="http://127.0.0.1:9"

run_smoke() {
    OUT="$(bash "$SCRIPTS/post-deploy-smoke.sh" 2>&1)"
    RC=$?
}

echo "-- mktemp fails: inconclusive (3), not a crash with some other code"
printf '#!/bin/bash\nexit 1\n' > "$BIN/mktemp"
chmod +x "$BIN/mktemp"
export PATH="$BIN:$ORIGINAL_PATH"
run_smoke
assert_eq "exit 3" 3 "$RC"
assert_eq "no request was made without a working directory" "" "$(cat "$LOG" 2>/dev/null)"
rm -f "$BIN/mktemp"

echo "-- every request is --compressed and has a max-time of at least 10 s, even with the deadline close"
: > "$LOG"
SMOKE_DEADLINE_SECONDS=12 SMOKE_RETRY_SECONDS=0 run_smoke
assert_eq "exit 3 (no response)" 3 "$RC"
assert_file_has "curl was given --compressed" "$LOG" "--compressed"
assert_file_has "the first request got the full 20 s, not what was left of a 12 s deadline" "$LOG" "--max-time 20"
assert_file_lacks "no request was clamped below 10 s" "$LOG" "--max-time 1 "
assert_file_lacks "...nor to 2" "$LOG" "--max-time 2 "

echo "-- fewer than 10 s left: no new probe is started at all"
: > "$LOG"
SMOKE_DEADLINE_SECONDS=5 run_smoke
assert_eq "exit 3" 3 "$RC"
assert_contains "says why" "$OUT" "less than 10s left"
assert_eq "curl was never called" "" "$(cat "$LOG")"

echo "-- a password the SSH forwarding can mangle is refused before anything is called"
: > "$LOG"
SMOKE_DEPLOY_PASSWORD='bad$pw' run_smoke
assert_eq "exit 4" 4 "$RC"
assert_contains "names the cause" "$OUT" "characters the SSH forwarding can mangle"
assert_not_contains "never prints it" "$OUT" 'bad$pw'
assert_eq "curl was never called" "" "$(cat "$LOG")"

finish_tests
