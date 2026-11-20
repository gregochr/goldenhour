#!/bin/bash
# Tests for scripts/mvn-retry.sh: a transient transfer failure is retried, a real failure is not.
# A fake build command prints a scripted log and exits with a scripted code, counting its runs.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS="$(cd "$HERE/.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export WORK

# The fake build: run N prints $WORK/out.N (else out.last) and exits with the Nth line of $WORK/rc (else the last).
cat > "$WORK/fake-mvn" <<'FAKE'
#!/bin/bash
n=$(( $(cat "$WORK/count" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$WORK/count"
cat "$WORK/out.$n" 2>/dev/null || cat "$WORK/out.last"
code="$(sed -n "${n}p" "$WORK/rc")"
[ -n "$code" ] || code="$(tail -n1 "$WORK/rc")"
exit "$code"
FAKE
cat > "$WORK/fake-sleep" <<'FAKE'
#!/bin/bash
echo "$1" >> "$WORK/sleeps"
FAKE
chmod +x "$WORK/fake-mvn" "$WORK/fake-sleep"

TRANSFER_429='[ERROR] Could not transfer artifact org.apache.maven.surefire:surefire-junit-platform:pom:3.5.6 from/to central (https://repo.maven.apache.org/maven2): status code: 429, reason phrase: Too Many Requests (429)'

reset_case() {
    rm -f "$WORK"/count "$WORK"/out.*
    : > "$WORK/sleeps"
}

# run_case RC...: the exit code of each run. Sets OUT, RC, RUNS, SLEEPS.
run_case() {
    printf '%s\n' "$@" > "$WORK/rc"
    OUT="$(MVN_RETRY_SLEEP="$WORK/fake-sleep" MVN_RETRY_DELAY=7 bash "$SCRIPTS/mvn-retry.sh" "$WORK/fake-mvn" 2>&1)"
    RC=$?
    RUNS="$(cat "$WORK/count")"
    SLEEPS="$(tr '\n' ' ' < "$WORK/sleeps" 2>/dev/null)"
}

reset_case
echo "$TRANSFER_429" > "$WORK/out.1"
echo "BUILD SUCCESS" > "$WORK/out.2"
run_case 1 0
assert_eq "429 then success: exits 0" "0" "$RC"
assert_eq "429 then success: ran twice" "2" "$RUNS"
assert_eq "429 then success: backed off 7s once" "7 " "$SLEEPS"

reset_case
echo "$TRANSFER_429" > "$WORK/out.last"
run_case 1
assert_eq "persistent 429: gives up with Maven's code" "1" "$RC"
assert_eq "persistent 429: three attempts" "3" "$RUNS"
assert_eq "persistent 429: linear backoff" "7 14 " "$SLEEPS"
assert_contains "persistent 429: says it stopped" "$OUT" "not retrying further"

reset_case
printf '%s\n%s\n' "[ERROR] COMPILATION ERROR :" "$TRANSFER_429" > "$WORK/out.last"
run_case 1
assert_eq "compile error: not retried" "1" "$RUNS"
assert_eq "compile error: Maven's code kept" "1" "$RC"

reset_case
printf '%s\n%s\n' "[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0" "$TRANSFER_429" > "$WORK/out.last"
run_case 1
assert_eq "test failure: not retried" "1" "$RUNS"

reset_case
echo "[ERROR] Could not transfer artifact a:b:pom:1 from/to central: status code: 404, reason phrase: Not Found" > "$WORK/out.last"
run_case 1
assert_eq "404 transfer failure: not retried" "1" "$RUNS"

reset_case
echo "[ERROR] BUILD FAILURE: something else" > "$WORK/out.last"
run_case 3
assert_eq "unrelated failure: not retried" "1" "$RUNS"
assert_eq "unrelated failure: exit code passed through" "3" "$RC"

reset_case
echo "BUILD SUCCESS" > "$WORK/out.last"
run_case 0
assert_eq "success: one run" "1" "$RUNS"
assert_eq "success: exit 0" "0" "$RC"

reset_case
echo "$TRANSFER_429" > "$WORK/out.last"
printf '1\n' > "$WORK/rc"
MVN_RETRY_ATTEMPTS=2 MVN_RETRY_SLEEP="$WORK/fake-sleep" bash "$SCRIPTS/mvn-retry.sh" "$WORK/fake-mvn" > /dev/null 2>&1
assert_eq "MVN_RETRY_ATTEMPTS is honoured" "2" "$(cat "$WORK/count")"

bash "$SCRIPTS/mvn-retry.sh" > /dev/null 2>&1
assert_eq "no command: usage error 2" "2" "$?"

finish_tests
