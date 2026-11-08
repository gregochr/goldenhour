#!/bin/bash
# Tests for scripts/prompt-regression-summary.py (docs/engineering/engineering-gaps-plan.md, Phase 5b, B9).
#
# The summary is what the owner reads before deciding whether a prompt change is safe, so a parser that reports a
# red run as green, or chokes on a parameterised test name, is worse than no summary. Fixture surefire reports are
# written here and fed through `python3 -I`, exactly as the workflow runs it.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS="$(cd "$HERE/.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# summarise DIR [extra args...]: stdout of the script (GITHUB_STEP_SUMMARY unset)
summarise() {
    local dir="$1"
    shift
    env -u GITHUB_STEP_SUMMARY python3 -I "$SCRIPTS/prompt-regression-summary.py" --reports "$dir" --title "T" "$@"
}

new_dir() {
    mkdir -p "$WORK/$1"
    echo "$WORK/$1"
}

# ── all passing ──────────────────────────────────────────────────────────────────────────────────────────────
D="$(new_dir pass)"
cat > "$D/TEST-com.x.SkyRatingRegressionTest.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.gregochr.goldenhour.eval.SkyRatingRegressionTest" tests="10" errors="0" skipped="0" failures="0">
  <testcase name="run" classname="com.x.SkyRatingRegressionTest"/>
</testsuite>
EOF
cat > "$D/TEST-com.x.SystemPromptTokenCountTest.xml" <<'EOF'
<testsuite name="com.gregochr.goldenhour.service.evaluation.SystemPromptTokenCountTest" tests="3" errors="0" skipped="0" failures="0"/>
EOF
OUT="$(summarise "$D")"
echo "-- all passing"
assert_contains "has the title" "$OUT" "### T"
assert_contains "sky row passes with 10" "$OUT" "| SkyRatingRegressionTest | :white_check_mark: passed | 10 | 0 | 0 | 0 |"
assert_contains "token row passes with 3" "$OUT" "| SystemPromptTokenCountTest | :white_check_mark: passed | 3 | 0 | 0 | 0 |"
assert_contains "totals" "$OUT" "13 tests, 0 failed, 0 errors, 0 skipped"
assert_not_contains "no red" "$OUT" ":x:"
assert_not_contains "no failing-tests block" "$OUT" "Failing tests"

# ── a failure and an error ───────────────────────────────────────────────────────────────────────────────────
D="$(new_dir fail)"
cat > "$D/TEST-com.x.SkyRatingRegressionTest.xml" <<'EOF'
<testsuite name="com.x.SkyRatingRegressionTest" tests="10" errors="1" skipped="0" failures="2">
  <testcase name="steelrigg" classname="com.x.SkyRatingRegressionTest">
    <failure message="rating 5 above band 2-3" type="java.lang.AssertionError">trace</failure>
  </testcase>
  <testcase name="hauxley" classname="com.x.SkyRatingRegressionTest">
    <failure type="java.lang.AssertionError">fiery 80 above 60 (no message attr)</failure>
  </testcase>
  <testcase name="bamburgh" classname="com.x.SkyRatingRegressionTest">
    <error message="boom" type="java.io.IOException"/>
  </testcase>
</testsuite>
EOF
OUT="$(summarise "$D")"
echo "-- a failure"
assert_contains "row is failed with 7 passed" "$OUT" "| SkyRatingRegressionTest | :x: failed | 7 | 2 | 1 | 0 |"
assert_contains "totals count failures" "$OUT" "10 tests, 2 failed, 1 errors, 0 skipped"
assert_contains "lists the failing case" "$OUT" "SkyRatingRegressionTest / steelrigg: rating 5 above band 2-3"
assert_contains "falls back to the element text" "$OUT" "hauxley: fiery 80 above 60 (no message attr)"
assert_contains "lists the error case" "$OUT" "bamburgh: boom"

# ── errors alone (no assertion failure) still fail the class ─────────────────────────────────────────────────
D="$(new_dir erroronly)"
cat > "$D/TEST-com.x.BestBetAuroraPromptRegressionTest.xml" <<'EOF'
<testsuite name="com.x.BestBetAuroraPromptRegressionTest" tests="3" errors="1" skipped="0" failures="0"/>
EOF
OUT="$(summarise "$D")"
echo "-- errors only"
assert_contains "an error-only class is failed" "$OUT" "| BestBetAuroraPromptRegressionTest | :x: failed | 2 | 0 | 1 | 0 |"

# ── advisory class: failure shown amber, excluded from the totals ────────────────────────────────────────────
D="$(new_dir advisory)"
cat > "$D/TEST-com.x.ValleyMistPromptRegressionTest.xml" <<'EOF'
<testsuite name="com.x.ValleyMistPromptRegressionTest" tests="4" errors="0" skipped="0" failures="1"/>
EOF
cat > "$D/TEST-com.x.BestBetAuroraPromptRegressionTest.xml" <<'EOF'
<testsuite name="com.x.BestBetAuroraPromptRegressionTest" tests="2" errors="0" skipped="0" failures="0"/>
EOF
OUT="$(summarise "$D" --advisory ValleyMistPromptRegressionTest)"
echo "-- advisory"
assert_contains "advisory failure is a warning" "$OUT" "| ValleyMistPromptRegressionTest | :warning: failed (advisory) | 3 | 1 | 0 | 0 |"
assert_contains "totals leave it out" "$OUT" "2 tests, 0 failed, 0 errors, 0 skipped"
OUT="$(summarise "$D")"
assert_contains "without --advisory it is red" "$OUT" "| ValleyMistPromptRegressionTest | :x: failed |"

# ── parameterised names with brackets, pipes, entities ───────────────────────────────────────────────────────
D="$(new_dir param)"
cat > "$D/TEST-com.x.SkyRatingRegressionTest.xml" <<'EOF'
<testsuite name="com.x.SkyRatingRegressionTest" tests="2" errors="0" skipped="0" failures="1">
  <testcase name="run(Fixture)[3] Steel Rigg | &lt;golden&gt; &amp; `fiery`" classname="com.x.SkyRatingRegressionTest">
    <failure message="expected [4] but was &lt;2&gt; &amp; more | cells" type="x"/>
  </testcase>
  <testcase name="run(Fixture)[4]" classname="com.x.SkyRatingRegressionTest"/>
</testsuite>
EOF
OUT="$(summarise "$D")"
echo "-- parameterised names"
assert_contains "brackets are escaped" "$OUT" "run(Fixture)&#91;3&#93; Steel Rigg &#124; &lt;golden&gt; &amp; &#96;fiery&#96;"
assert_contains "message entities decoded then re-escaped" "$OUT" "expected &#91;4&#93; but was &lt;2&gt; &amp; more &#124; cells"
assert_not_contains "no raw html tag leaks" "$OUT" "<golden>"
assert_contains "table row intact" "$OUT" "| SkyRatingRegressionTest | :x: failed | 1 | 1 | 0 | 0 |"

# ── zero reports ─────────────────────────────────────────────────────────────────────────────────────────────
D="$(new_dir empty)"
OUT="$(summarise "$D")"
echo "-- zero reports"
assert_contains "says nothing ran" "$OUT" "No test reports were produced"
assert_contains "is red" "$OUT" ":x:"
assert_not_contains "no table" "$OUT" "| Class |"
OUT="$(summarise "$WORK/never-created")"
assert_contains "missing directory is the same" "$OUT" "No test reports were produced"
env -u GITHUB_STEP_SUMMARY python3 -I "$SCRIPTS/prompt-regression-summary.py" --reports "$D" --title T >/dev/null
assert_eq "always exits 0" "0" "$?"

# ── Ask with skipped > 0 ─────────────────────────────────────────────────────────────────────────────────────
D="$(new_dir ask)"
cat > "$D/TEST-com.x.AskPromptRegressionTest.xml" <<'EOF'
<testsuite name="com.x.AskPromptRegressionTest" tests="20" errors="0" skipped="5" failures="0"/>
EOF
cat > "$D/TEST-com.x.AskThreadRegressionTest.xml" <<'EOF'
<testsuite name="com.x.AskThreadRegressionTest" tests="6" errors="0" skipped="0" failures="0"/>
EOF
OUT="$(summarise "$D" --skipped-red)"
echo "-- ask skipped"
assert_contains "skipped Ask class is red" "$OUT" "| AskPromptRegressionTest | :x: failed | 15 | 0 | 0 | 5 |"
assert_contains "clean Ask class stays green" "$OUT" "| AskThreadRegressionTest | :white_check_mark: passed | 6 | 0 | 0 | 0 |"
assert_contains "explains the red" "$OUT" "a skipped paid test checked nothing"
OUT="$(summarise "$D")"
assert_contains "without --skipped-red a partial skip is not failed" "$OUT" "| AskPromptRegressionTest | :white_check_mark: passed | 15 | 0 | 0 | 5 |"

# ── an all-skipped class is amber even without --skipped-red ─────────────────────────────────────────────────
D="$(new_dir allskipped)"
cat > "$D/TEST-com.x.AskNightRegressionTest.xml" <<'EOF'
<testsuite name="com.x.AskNightRegressionTest" tests="4" errors="0" skipped="4" failures="0"/>
EOF
OUT="$(summarise "$D")"
echo "-- all skipped"
assert_contains "all skipped warns" "$OUT" "| AskNightRegressionTest | :warning: all skipped | 0 | 0 | 0 | 4 |"

# ── an unreadable report ─────────────────────────────────────────────────────────────────────────────────────
D="$(new_dir garbled)"
printf '<testsuite name="x" tests="1"' > "$D/TEST-com.x.BrokenTest.xml"
cat > "$D/TEST-com.x.GoodTest.xml" <<'EOF'
<testsuite name="com.x.GoodTest" tests="1" errors="0" skipped="0" failures="0"/>
EOF
OUT="$(summarise "$D")"
echo "-- unreadable report"
assert_contains "broken report is red" "$OUT" ":x: "
assert_contains "broken report is named" "$OUT" "TEST-com.x.BrokenTest.xml"
assert_contains "the good class still reports" "$OUT" "| GoodTest | :white_check_mark: passed | 1 |"

# ── writes to GITHUB_STEP_SUMMARY, appending ─────────────────────────────────────────────────────────────────
D="$(new_dir pass)"
echo "existing" > "$WORK/step-summary.md"
GITHUB_STEP_SUMMARY="$WORK/step-summary.md" python3 -I "$SCRIPTS/prompt-regression-summary.py" --reports "$D" --title "Appended" > "$WORK/stdout.txt"
echo "-- step summary"
assert_file_has "keeps what was there" "$WORK/step-summary.md" "existing"
assert_file_has "appends the table" "$WORK/step-summary.md" "### Appended"
assert_eq "nothing on stdout" "0" "$(wc -c < "$WORK/stdout.txt" | tr -d ' ')"

finish_tests
