#!/bin/bash
# Tests for scripts/npm-audit.sh and its temporary allowlist (scripts/npm-audit-allowlist.txt).
# A fake `npx` stands in for npm 11: `--version`, the plain audit (exit code and text from the
# environment) and `audit --json` (a fixture file).

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS="$(cd "$HERE/.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

CASES=()
cleanup_cases() {
    local d
    for d in "${CASES[@]}"; do rm -rf "$d"; done
}
trap cleanup_cases EXIT
ORIGINAL_PATH="$PATH"

GOOD='https://github.com/advisories/GHSA-fc5q-39xc-vjpf'
OTHER='https://github.com/advisories/GHSA-aaaa-bbbb-cccc'

# new_case PLAIN_EXIT PLAIN_TEXT JSON_FIXTURE
new_case() {
    CASE="$(mktemp -d)"
    CASES+=("$CASE")
    mkdir -p "$CASE/bin" "$CASE/proj"
    printf '%s' "$3" > "$CASE/report.json"
    : > "$CASE/calls.log"
    cat > "$CASE/bin/npx" <<'EOF'
#!/bin/bash
echo "npx $*" >> "$CALL_LOG"
case "$*" in
    *--version*) echo "11.0.0" ;;
    *"audit --json"*) cat "$REPORT"; exit 1 ;;
    *"audit --audit-level"*) echo "$PLAIN_TEXT"; exit "$PLAIN_EXIT" ;;
esac
exit 0
EOF
    chmod +x "$CASE/bin/npx"
    printf '%s\n' "GHSA-fc5q-39xc-vjpf | expires 2026-12-17 | test reason" > "$CASE/allow.txt"
    export CALL_LOG="$CASE/calls.log" REPORT="$CASE/report.json" PLAIN_EXIT="$1" PLAIN_TEXT="$2"
    export NPM_AUDIT_ALLOWLIST="$CASE/allow.txt" NPM_AUDIT_TODAY="2026-11-17" NPM_AUDIT_BACKOFF_UNIT=0
    export PATH="$CASE/bin:$ORIGINAL_PATH"
}

run() {
    OUT=$(bash "$SCRIPTS/npm-audit.sh" "$CASE/proj" high 2>&1)
    RC=$?
}

# picomatch (the allowlisted advisory) plus a dependent that lists it only by name
report_for() {
    printf '{"vulnerabilities":{"picomatch":{"severity":"high","via":[{"source":1,"url":"%s"}]},"vite":{"severity":"high","via":["picomatch"]}}}' "$1"
}
# the same report with an unrelated, non-allowlisted advisory added
report_with_extra() {
    printf '{"vulnerabilities":{"picomatch":{"severity":"high","via":[{"source":1,"url":"%s"}]},"vite":{"severity":"high","via":["picomatch"]},"lodash":{"severity":"high","via":[{"source":2,"url":"%s"}]}}}' "$GOOD" "$OTHER"
}

echo "no findings passes"
new_case 0 "found 0 vulnerabilities" '{}'
run
assert_eq "exit 0" 0 "$RC"

echo "allowlisted advisory only passes with a warning"
new_case 1 "2 high severity vulnerabilities" "$(report_for "$GOOD")"
run
assert_eq "exit 0" 0 "$RC"
assert_contains "loud warning" "$OUT" "::warning::npm audit is TOLERATING GHSA-fc5q-39xc-vjpf until 2026-12-17"

echo "a second, non-allowlisted advisory still fails"
new_case 1 "3 high severity vulnerabilities" "$(report_with_extra)"
run
assert_eq "exit 1" 1 "$RC"
assert_contains "names the culprit" "$OUT" "lodash"
assert_not_contains "no tolerance warning" "$OUT" "TOLERATING"

echo "a dependent of a non-allowlisted advisory fails"
new_case 1 "high" "$(report_for "$OTHER")"
run
assert_eq "exit 1" 1 "$RC"

echo "an expired entry is ignored"
new_case 1 "high" "$(report_for "$GOOD")"
export NPM_AUDIT_TODAY="2026-12-18"
run
assert_eq "exit 1" 1 "$RC"
assert_contains "says expired" "$OUT" "EXPIRED on 2026-12-17"

echo "expiry day itself is still valid"
new_case 1 "high" "$(report_for "$GOOD")"
export NPM_AUDIT_TODAY="2026-12-17"
run
assert_eq "exit 0" 0 "$RC"

echo "a failure with no parseable vulnerabilities never passes"
new_case 1 "high" '{"error":{"code":"EAUDITNOLOCK"}}'
run
assert_eq "exit 1" 1 "$RC"

echo "transport errors retry and fail, never allowlist-pass"
new_case 1 "npm error audit endpoint returned an error" "$(report_for "$GOOD")"
run
assert_eq "exit 1" 1 "$RC"
assert_eq "three plain attempts" 3 "$(grep -c 'audit --audit-level' "$CALL_LOG")"
assert_eq "json never requested" 0 "$(grep -c 'audit --json' "$CALL_LOG")"
assert_contains "registry failure message" "$OUT" "registry availability failure"

finish_tests
