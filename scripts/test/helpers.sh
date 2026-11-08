#!/bin/bash
# ============================================================================
# Sourced by scripts/test/*.test.sh: a few assertion helpers and nothing else.
#
# These tests exist because nothing else executes scripts/*.sh: CI lints them
# (ShellCheck) but until the post-deploy smoke and automatic rollback landed no
# test ran them, so a shell defect shipped straight to production. They run with
# stubs for docker, git, psql and friends on PATH, so they need no Docker, no
# network and no database. Run them all with scripts/test/run-all.sh.
# ============================================================================

T_PASS=0
T_FAIL=0

ok() {
    T_PASS=$((T_PASS + 1))
    printf '  ok   %s\n' "$1"
}

bad() {
    T_FAIL=$((T_FAIL + 1))
    printf '  FAIL %s\n' "$1"
}

# assert_eq DESCRIPTION EXPECTED ACTUAL
assert_eq() {
    if [ "$2" = "$3" ]; then
        ok "$1"
    else
        bad "$1 (expected '$2', got '$3')"
    fi
}

# assert_contains DESCRIPTION HAYSTACK NEEDLE (fixed string)
assert_contains() {
    if printf '%s' "$2" | grep -qF -- "$3"; then
        ok "$1"
    else
        bad "$1 (no '$3' in the output)"
    fi
}

# assert_not_contains DESCRIPTION HAYSTACK NEEDLE (fixed string)
assert_not_contains() {
    if printf '%s' "$2" | grep -qF -- "$3"; then
        bad "$1 ('$3' is present)"
    else
        ok "$1"
    fi
}

# assert_file_has DESCRIPTION FILE NEEDLE (fixed string)
assert_file_has() {
    if [ -f "$2" ] && grep -qF -- "$3" "$2"; then
        ok "$1"
    else
        bad "$1 (no '$3' in $2)"
    fi
}

# assert_file_lacks DESCRIPTION FILE NEEDLE: passes when the file is absent too
assert_file_lacks() {
    if [ -f "$2" ] && grep -qF -- "$3" "$2"; then
        bad "$1 ('$3' is in $2)"
    else
        ok "$1"
    fi
}

assert_file_exists() {
    if [ -e "$2" ]; then ok "$1"; else bad "$1 (missing $2)"; fi
}

assert_file_missing() {
    if [ -e "$2" ]; then bad "$1 ($2 exists)"; else ok "$1"; fi
}

finish_tests() {
    echo ""
    echo "$(basename "$0"): ${T_PASS} passed, ${T_FAIL} failed"
    [ "$T_FAIL" -eq 0 ]
}
