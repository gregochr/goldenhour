#!/bin/bash
# Tests for scripts/prompt-regression-usage.py (docs/engineering/ci-spend-plan.md, C1).
#
# The usage artifact is what production later prices into Cost trends, so a script that double-counts a shard,
# reads a half-written one, or lets anything but counts and identifiers into the file is worse than none. Fixture
# shards and surefire reports are written here and fed through `python3 -I`, exactly as the workflow runs it.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS="$(cd "$HERE/.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# usage SUITE SHARDS REPORTS OUT: runs the script with a fixed environment
usage() {
    env -u GITHUB_STEP_SUMMARY GITHUB_RUN_ID=123456789 GITHUB_RUN_ATTEMPT=2 HEAD_SHA=abc123 PR_NUMBER=1912 \
        STARTED_AT=2026-11-27T10:00:00Z \
        python3 -I "$SCRIPTS/prompt-regression-usage.py" --suite "$1" --shards "$2" --reports "$3" --out "$4"
}

# jget FILE PYEXPR: evaluates PYEXPR with the parsed document as d and prints the result
jget() {
    python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$1" "$2"
}

new_dir() {
    mkdir -p "$WORK/$1"
    echo "$WORK/$1"
}

SHARD_A='{"models":[{"model":"claude-sonnet-5-5","calls":3,"erroredCalls":1,"input":100,"output":20,"cacheRead":50,"cacheWrite":10,"cacheWrite1h":4}]}'
SHARD_B='{"models":[{"model":"claude-sonnet-5-5","calls":2,"erroredCalls":0,"input":5,"output":6,"cacheRead":7,"cacheWrite":8,"cacheWrite1h":0},{"model":"claude-haiku-4-5","calls":1,"erroredCalls":0,"input":1,"output":2,"cacheRead":3,"cacheWrite":4,"cacheWrite1h":0}]}'

REPORT='<testsuite name="com.x.SkyRatingRegressionTest" tests="10" errors="1" skipped="0" failures="2"><testcase name="a"/></testsuite>'
REPORT2='<testsuites><testsuite name="com.x.Other" tests="5" errors="0" skipped="0" failures="0"/></testsuites>'

# ── sums across shards, ignoring .tmp, counts from surefire ──────────────────────────────────────────────────
S="$(new_dir shards)"
R="$(new_dir reports)"
printf '%s' "$SHARD_A" > "$S/shard-1-aaa.json"
printf '%s' "$SHARD_B" > "$S/shard-2-bbb.json"
printf '%s' '{"models":[{"model":"claude-sonnet-5-5","calls":999,"erroredCalls":0,"input":999,"output":0,"cacheRead":0,"cacheWrite":0,"cacheWrite1h":0}]}' > "$S/shard-3-ccc.json.tmp"
printf '%s' "$REPORT" > "$R/TEST-com.x.SkyRatingRegressionTest.xml"
printf '%s' "$REPORT2" > "$R/TEST-com.x.Other.xml"
OUT="$WORK/out/usage.json"
usage sky "$S" "$R" "$OUT"
echo "-- sums shards"
assert_eq "exit status is 0" "0" "$(usage sky "$S" "$R" "$OUT" >/dev/null 2>&1; echo $?)"
assert_eq "sonnet calls summed, .tmp ignored" "5" "$(jget "$OUT" "[m for m in d['models'] if m['model']=='claude-sonnet-5-5'][0]['calls']")"
assert_eq "sonnet erroredCalls" "1" "$(jget "$OUT" "[m for m in d['models'] if m['model']=='claude-sonnet-5-5'][0]['erroredCalls']")"
assert_eq "sonnet input" "105" "$(jget "$OUT" "[m for m in d['models'] if m['model']=='claude-sonnet-5-5'][0]['input']")"
assert_eq "sonnet output" "26" "$(jget "$OUT" "[m for m in d['models'] if m['model']=='claude-sonnet-5-5'][0]['output']")"
assert_eq "sonnet cacheRead" "57" "$(jget "$OUT" "[m for m in d['models'] if m['model']=='claude-sonnet-5-5'][0]['cacheRead']")"
assert_eq "sonnet cacheWrite" "18" "$(jget "$OUT" "[m for m in d['models'] if m['model']=='claude-sonnet-5-5'][0]['cacheWrite']")"
assert_eq "sonnet cacheWrite1h" "4" "$(jget "$OUT" "[m for m in d['models'] if m['model']=='claude-sonnet-5-5'][0]['cacheWrite1h']")"
assert_eq "models sorted, haiku first" "['claude-haiku-4-5', 'claude-sonnet-5-5']" "$(jget "$OUT" "[m['model'] for m in d['models']]")"
echo "-- surefire counts"
assert_eq "cases summed over both reports" "15" "$(jget "$OUT" "d['cases']")"
assert_eq "failed is failures plus errors" "3" "$(jget "$OUT" "d['failed']")"
assert_eq "complete" "True" "$(jget "$OUT" "d['complete']")"
echo "-- identifiers"
assert_eq "suite" "sky" "$(jget "$OUT" "d['suite']")"
assert_eq "schema" "1" "$(jget "$OUT" "d['schema']")"
assert_eq "runId type" "int" "$(jget "$OUT" "type(d['runId']).__name__")"
assert_eq "runAttempt" "2" "$(jget "$OUT" "d['runAttempt']")"
assert_eq "pr is an integer" "1912" "$(jget "$OUT" "d['pr']")"
assert_eq "headSha" "abc123" "$(jget "$OUT" "d['headSha']")"
assert_eq "startedAt" "2026-11-27T10:00:00Z" "$(jget "$OUT" "d['startedAt']")"
assert_contains "finishedAt is UTC Z" "$(jget "$OUT" "d['finishedAt']")" "Z"
echo "-- exact keys: counts and identifiers only"
assert_eq "top-level keys" "['cases', 'complete', 'failed', 'finishedAt', 'headSha', 'models', 'pr', 'runAttempt', 'runId', 'schema', 'startedAt', 'suite']" "$(jget "$OUT" "sorted(d)")"
assert_eq "model keys" "['cacheRead', 'cacheWrite', 'cacheWrite1h', 'calls', 'erroredCalls', 'input', 'model', 'output']" "$(jget "$OUT" "sorted(d['models'][0])")"

# ── a malformed shard is skipped with a warning ──────────────────────────────────────────────────────────────
S="$(new_dir shards-bad)"
printf '%s' "$SHARD_A" > "$S/shard-1-aaa.json"
printf '{"models":[{"model":' > "$S/shard-2-bbb.json"
printf '%s' '{"models":"nope"}' > "$S/shard-3-ccc.json"
printf '%s' '[1,2]' > "$S/shard-4-ddd.json"
OUT="$WORK/out/bad.json"
ERR="$(usage sky "$S" "$R" "$OUT" 2>&1 >/dev/null)"
echo "-- malformed shards"
assert_contains "warns about the truncated shard" "$ERR" "shard-2-bbb.json"
assert_contains "warns about the wrong-shape shard" "$ERR" "shard-3-ccc.json"
assert_contains "warns about the non-object shard" "$ERR" "shard-4-ddd.json"
assert_eq "the good shard still counts" "3" "$(jget "$OUT" "d['models'][0]['calls']")"
assert_eq "only the good model" "1" "$(jget "$OUT" "len(d['models'])")"

# ── no shards, no reports ────────────────────────────────────────────────────────────────────────────────────
S="$(new_dir shards-none)"
R0="$(new_dir reports-none)"
OUT="$WORK/out/none.json"
echo "-- nothing recorded"
assert_eq "exit status is 0" "0" "$(usage ask "$S" "$R0" "$OUT" >/dev/null 2>&1; echo $?)"
assert_eq "models empty" "[]" "$(jget "$OUT" "d['models']")"
assert_eq "complete is false without a report" "False" "$(jget "$OUT" "d['complete']")"
assert_eq "cases zero" "0" "$(jget "$OUT" "d['cases']")"
assert_eq "suite is passed through" "ask" "$(jget "$OUT" "d['suite']")"

# ── missing directories entirely ─────────────────────────────────────────────────────────────────────────────
OUT="$WORK/out/missing.json"
assert_eq "missing shards and reports dirs still exit 0" "0" "$(usage sky "$WORK/nope1" "$WORK/nope2" "$OUT" >/dev/null 2>&1; echo $?)"
assert_eq "and still write a document" "[]" "$(jget "$OUT" "d['models']")"

# ── an unreadable report does not count as complete ──────────────────────────────────────────────────────────
R1="$(new_dir reports-garbled)"
printf '<testsuite name="x" tests="1"' > "$R1/TEST-com.x.Broken.xml"
OUT="$WORK/out/garbled.json"
usage sky "$S" "$R1" "$OUT" >/dev/null 2>&1
assert_eq "garbled report is not complete" "False" "$(jget "$OUT" "d['complete']")"

# ── a manual run has no PR; the environment may be empty ─────────────────────────────────────────────────────
OUT="$WORK/out/manual.json"
env -u GITHUB_STEP_SUMMARY GITHUB_RUN_ID= GITHUB_RUN_ATTEMPT= HEAD_SHA=def PR_NUMBER= STARTED_AT= \
    python3 -I "$SCRIPTS/prompt-regression-usage.py" --suite sky --shards "$S" --reports "$R" --out "$OUT"
echo "-- manual run"
assert_eq "pr is null" "None" "$(jget "$OUT" "d['pr']")"
assert_eq "runId is null" "None" "$(jget "$OUT" "d['runId']")"
assert_eq "headSha kept" "def" "$(jget "$OUT" "d['headSha']")"

# ── step summary: appended, one line of totals ───────────────────────────────────────────────────────────────
S="$(new_dir shards-sum)"
printf '%s' "$SHARD_A" > "$S/shard-1-aaa.json"
echo "existing" > "$WORK/step-summary.md"
GITHUB_STEP_SUMMARY="$WORK/step-summary.md" python3 -I "$SCRIPTS/prompt-regression-usage.py" \
    --suite sky --shards "$S" --reports "$R" --out "$WORK/out/sum.json" > "$WORK/stdout.txt"
echo "-- step summary"
assert_file_has "keeps what was there" "$WORK/step-summary.md" "existing"
assert_file_has "appends the totals" "$WORK/step-summary.md" "Prompt usage (sky): 3 calls (1 errored), 100 input, 20 output, 50 cache read, 10 cache write"
assert_eq "one line appended" "2" "$(wc -l < "$WORK/step-summary.md" | tr -d ' ')"
assert_eq "nothing on stdout" "0" "$(wc -c < "$WORK/stdout.txt" | tr -d ' ')"

finish_tests
