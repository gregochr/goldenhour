#!/bin/bash
# Tests for scripts/prompt-regression-usage.py (docs/engineering/ci-spend-plan.md, C1).
#
# The usage artifact is what production later prices into Cost trends, so a script that double-counts a shard,
# reads a half-written one, or lets anything but counts and identifiers into the file is worse than none. The
# script takes no path arguments: it derives every path from its own location. Each case therefore builds a
# temporary tree (scripts/prompt-regression-usage.py copied in, backend/target/... fixtures beside it) and runs the
# copy under `python3 -I`, exactly as the workflow runs it.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS="$(cd "$HERE/.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# new_tree NAME: a repo-shaped tree with the script copied in and empty shard and report directories
new_tree() {
    mkdir -p "$WORK/$1/scripts" "$WORK/$1/backend/target/prompt-regression-usage" "$WORK/$1/backend/target/surefire-reports"
    cp "$SCRIPTS/prompt-regression-usage.py" "$WORK/$1/scripts/"
    echo "$WORK/$1"
}

# usage TREE SUITE: runs the tree's copy with a fixed environment, from an unrelated directory; stdout is the summary
usage() {
    (cd "$WORK" && env -u GITHUB_STEP_SUMMARY GITHUB_RUN_ID=123456789 GITHUB_RUN_ATTEMPT=2 HEAD_SHA=abc123 \
        PR_NUMBER=1912 STARTED_AT=2026-11-27T10:00:00Z \
        python3 -I "$1/scripts/prompt-regression-usage.py" --suite "$2")
}

# jget FILE PYEXPR: evaluates PYEXPR with the parsed document as d and prints the result
jget() {
    python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$1" "$2"
}

SHARD_A='{"models":[{"model":"claude-sonnet-5-5","calls":3,"erroredCalls":1,"input":100,"output":20,"cacheRead":50,"cacheWrite":10,"cacheWrite1h":4}]}'
SHARD_B='{"models":[{"model":"claude-sonnet-5-5","calls":2,"erroredCalls":0,"input":5,"output":6,"cacheRead":7,"cacheWrite":8,"cacheWrite1h":0},{"model":"claude-haiku-4-5","calls":1,"erroredCalls":0,"input":1,"output":2,"cacheRead":3,"cacheWrite":4,"cacheWrite1h":0}]}'

REPORT='<testsuite name="com.x.SkyRatingRegressionTest" tests="10" errors="1" skipped="0" failures="2"><testcase name="a"/></testsuite>'
REPORT2='<testsuites><testsuite name="com.x.Other" tests="5" errors="0" skipped="0" failures="0"/></testsuites>'

# ── sums across shards, ignoring .tmp, counts from surefire ──────────────────────────────────────────────────
T="$(new_tree sums)"
S="$T/backend/target/prompt-regression-usage"
R="$T/backend/target/surefire-reports"
printf '%s' "$SHARD_A" > "$S/shard-1-aaa.json"
printf '%s' "$SHARD_B" > "$S/shard-2-bbb.json"
printf '%s' '{"models":[{"model":"claude-sonnet-5-5","calls":999,"erroredCalls":0,"input":999,"output":0,"cacheRead":0,"cacheWrite":0,"cacheWrite1h":0}]}' > "$S/shard-3-ccc.json.tmp"
printf '%s' "$REPORT" > "$R/TEST-com.x.SkyRatingRegressionTest.xml"
printf '%s' "$REPORT2" > "$R/TEST-com.x.Other.xml"
OUT="$T/backend/target/prompt-regression-usage-sky.json"
echo "-- sums shards"
assert_eq "exit status is 0" "0" "$(usage "$T" sky >/dev/null 2>&1; echo $?)"
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

# ── the output name follows the suite ────────────────────────────────────────────────────────────────────────
usage "$T" ask >/dev/null 2>&1
echo "-- suite selects the output file"
assert_file_exists "ask writes the ask file" "$T/backend/target/prompt-regression-usage-ask.json"
assert_eq "and says ask" "ask" "$(jget "$T/backend/target/prompt-regression-usage-ask.json" "d['suite']")"

# ── a malformed shard is skipped with a warning ──────────────────────────────────────────────────────────────
T="$(new_tree bad)"
S="$T/backend/target/prompt-regression-usage"
printf '%s' "$SHARD_A" > "$S/shard-1-aaa.json"
printf '{"models":[{"model":' > "$S/shard-2-bbb.json"
printf '%s' '{"models":"nope"}' > "$S/shard-3-ccc.json"
printf '%s' '[1,2]' > "$S/shard-4-ddd.json"
OUT="$T/backend/target/prompt-regression-usage-sky.json"
ERR="$(usage "$T" sky 2>&1 >/dev/null)"
echo "-- malformed shards"
assert_contains "warns about the truncated shard" "$ERR" "shard-2-bbb.json"
assert_contains "warns about the wrong-shape shard" "$ERR" "shard-3-ccc.json"
assert_contains "warns about the non-object shard" "$ERR" "shard-4-ddd.json"
assert_eq "the good shard still counts" "3" "$(jget "$OUT" "d['models'][0]['calls']")"
assert_eq "only the good model" "1" "$(jget "$OUT" "len(d['models'])")"

# ── no shards, no reports ────────────────────────────────────────────────────────────────────────────────────
T="$(new_tree none)"
OUT="$T/backend/target/prompt-regression-usage-ask.json"
echo "-- nothing recorded"
assert_eq "exit status is 0" "0" "$(usage "$T" ask >/dev/null 2>&1; echo $?)"
assert_eq "models empty" "[]" "$(jget "$OUT" "d['models']")"
assert_eq "complete is false without a report" "False" "$(jget "$OUT" "d['complete']")"
assert_eq "cases zero" "0" "$(jget "$OUT" "d['cases']")"
assert_eq "suite is passed through" "ask" "$(jget "$OUT" "d['suite']")"

# ── missing directories entirely ─────────────────────────────────────────────────────────────────────────────
T="$WORK/bare"
mkdir -p "$T/scripts"
cp "$SCRIPTS/prompt-regression-usage.py" "$T/scripts/"
assert_eq "no backend/target at all still exits 0" "0" "$(usage "$T" sky >/dev/null 2>&1; echo $?)"
assert_eq "and still writes a document" "[]" "$(jget "$T/backend/target/prompt-regression-usage-sky.json" "d['models']")"

# ── an unreadable report does not count as complete ──────────────────────────────────────────────────────────
T="$(new_tree garbled)"
printf '<testsuite name="x" tests="1"' > "$T/backend/target/surefire-reports/TEST-com.x.Broken.xml"
usage "$T" sky >/dev/null 2>&1
assert_eq "garbled report is not complete" "False" "$(jget "$T/backend/target/prompt-regression-usage-sky.json" "d['complete']")"

# ── a manual run has no PR; the environment may be empty ─────────────────────────────────────────────────────
T="$(new_tree manual)"
(cd "$WORK" && env -u GITHUB_STEP_SUMMARY GITHUB_RUN_ID= GITHUB_RUN_ATTEMPT= HEAD_SHA=def PR_NUMBER= STARTED_AT= \
    python3 -I "$T/scripts/prompt-regression-usage.py" --suite sky >/dev/null)
OUT="$T/backend/target/prompt-regression-usage-sky.json"
echo "-- manual run"
assert_eq "pr is null" "None" "$(jget "$OUT" "d['pr']")"
assert_eq "runId is null" "None" "$(jget "$OUT" "d['runId']")"
assert_eq "headSha kept" "def" "$(jget "$OUT" "d['headSha']")"

# ── summary: one line of totals on stdout; the script never touches the job summary file ──────────────────────
T="$(new_tree summary)"
printf '%s' "$SHARD_A" > "$T/backend/target/prompt-regression-usage/shard-1-aaa.json"
usage "$T" sky > "$WORK/stdout.txt" 2>/dev/null
echo "-- summary line"
assert_eq "one line on stdout" "1" "$(wc -l < "$WORK/stdout.txt" | tr -d ' ')"
assert_file_has "stdout has the totals" "$WORK/stdout.txt" "Prompt usage (sky): 3 calls (1 errored), 100 input, 20 output, 50 cache read, 10 cache write"
echo "existing" > "$WORK/step-summary.md"
(cd "$WORK" && GITHUB_STEP_SUMMARY="$WORK/step-summary.md" python3 -I "$T/scripts/prompt-regression-usage.py" --suite sky >/dev/null)
assert_eq "an env-named summary file is left alone" "existing" "$(cat "$WORK/step-summary.md")"
assert_file_lacks "the script does not reference GITHUB_STEP_SUMMARY" "$SCRIPTS/prompt-regression-usage.py" "GITHUB_STEP_SUMMARY"

# ── no path arguments, and the suite is validated ────────────────────────────────────────────────────────────
T="$(new_tree args)"
echo "-- arguments"
for arg in --out --shards --reports; do
    assert_eq "$arg is not accepted" "2" "$(cd "$WORK" && python3 -I "$T/scripts/prompt-regression-usage.py" --suite sky "$arg" "$WORK/elsewhere.json" >/dev/null 2>&1; echo $?)"
done
assert_file_missing "nothing was written outside the tree" "$WORK/elsewhere.json"
assert_eq "a bad --suite is rejected" "2" "$(usage "$T" evil >/dev/null 2>&1; echo $?)"
assert_eq "a path-shaped --suite is rejected" "2" "$(usage "$T" ../../evil >/dev/null 2>&1; echo $?)"
assert_file_missing "a bad --suite writes nothing" "$T/backend/target/prompt-regression-usage-evil.json"
assert_file_missing "and no file leaves the tree" "$WORK/evil.json"

finish_tests
