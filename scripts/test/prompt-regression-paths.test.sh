#!/bin/bash
# Tests for scripts/prompt-regression-paths.sh (docs/engineering/engineering-gaps-plan.md, Phase 5b, B4/B5).
#
# The script decides whether a PR spends money on the real-Claude suites, so every direction is pinned: a prompt
# path must switch its suite on (or the regression goes unwatched) and an unrelated path must leave both off (or
# every PR asks the owner for an approval click).

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS="$(cd "$HERE/.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

M='backend/src/main/java/com/gregochr/goldenhour'
T='backend/src/test/java/com/gregochr/goldenhour'
R='backend/src/test/resources'

# run_paths "path1 path2 ..." [pom-diff-text]  ->  "sky=X ask=Y"
run_paths() {
    local files="$1" pom="${2-__none__}" out
    # shellcheck disable=SC2086 # word splitting of the path list is the point
    printf '%s\n' $files > "$WORK/changed.txt"
    if [ "$pom" = "__none__" ]; then
        out="$(bash "$SCRIPTS/prompt-regression-paths.sh" "$WORK/changed.txt" 2>&1)"
    else
        printf '%s\n' "$pom" > "$WORK/pom.diff"
        out="$(bash "$SCRIPTS/prompt-regression-paths.sh" "$WORK/changed.txt" "$WORK/pom.diff" 2>&1)"
    fi
    printf '%s' "$out" | tr '\n' ' ' | sed 's/ $//'
}

echo "-- sky only"
assert_eq "evaluation service" "sky=true ask=false" "$(run_paths "$M/service/evaluation/ClaudeEvaluationStrategy.java")"
assert_eq "model package" "sky=true ask=false" "$(run_paths "$M/model/AtmosphericData.java")"
assert_eq "eval package" "sky=true ask=false" "$(run_paths "$M/eval/SkyRatingEvalFixtures.java")"
assert_eq "main eval resources" "sky=true ask=false" "$(run_paths "backend/src/main/resources/eval/x.json")"
assert_eq "ValleyMistCalculator" "sky=true ask=false" "$(run_paths "$M/service/ValleyMistCalculator.java")"
assert_eq "WoodlandVerdictEvaluator" "sky=true ask=false" "$(run_paths "$M/service/WoodlandVerdictEvaluator.java")"
assert_eq "ForecastStability enum" "sky=true ask=false" "$(run_paths "$M/entity/ForecastStability.java")"
assert_eq "TestAtmosphericData" "sky=true ask=false" "$(run_paths "$T/service/TestAtmosphericData.java")"
assert_eq "test eval package" "sky=true ask=false" "$(run_paths "$T/eval/SkyRatingRegressionTest.java")"
assert_eq "sky regression test class" "sky=true ask=false" "$(run_paths "$T/service/evaluation/BestBetAuroraPromptRegressionTest.java")"
assert_eq "token count test" "sky=true ask=false" "$(run_paths "$T/service/evaluation/SystemPromptTokenCountTest.java")"
assert_eq "test fixture under resources/eval" "sky=true ask=false" "$(run_paths "$R/eval/fixtures/day1.json")"
assert_eq "top-level prompt snapshot txt" "sky=true ask=false" "$(run_paths "$R/prompt-regression/system-prompt.txt")"

echo "-- ask only"
assert_eq "ask service" "sky=false ask=true" "$(run_paths "$M/service/ask/AskService.java")"
assert_eq "ask service, nested" "sky=false ask=true" "$(run_paths "$M/service/ask/tools/NightTool.java")"
assert_eq "DriveTimeResolver" "sky=false ask=true" "$(run_paths "$M/service/DriveTimeResolver.java")"
assert_eq "PlaceNames" "sky=false ask=true" "$(run_paths "$M/util/PlaceNames.java")"
assert_eq "ask test" "sky=false ask=true" "$(run_paths "$T/service/ask/AskThreadRegressionTest.java")"
assert_eq "prompt-regression/ask/ resources" "sky=false ask=true" "$(run_paths "$R/prompt-regression/ask/questions.json")"

echo "-- shared files set both"
assert_eq "AnthropicApiClient" "sky=true ask=true" "$(run_paths "$M/service/evaluation/AnthropicApiClient.java")"
assert_eq "ModelRequestSupport" "sky=true ask=true" "$(run_paths "$M/service/evaluation/ModelRequestSupport.java")"
assert_eq "PromptUtils" "sky=true ask=true" "$(run_paths "$M/service/evaluation/PromptUtils.java")"
assert_eq "RatingValidator" "sky=true ask=true" "$(run_paths "$M/service/evaluation/RatingValidator.java")"
assert_eq "EvaluationModel" "sky=true ask=true" "$(run_paths "$M/entity/EvaluationModel.java")"
assert_eq "ModelSelectionService" "sky=true ask=true" "$(run_paths "$M/service/ModelSelectionService.java")"
assert_eq "AppConfig" "sky=true ask=true" "$(run_paths "$M/config/AppConfig.java")"
assert_eq "DayLabels" "sky=true ask=true" "$(run_paths "$M/util/DayLabels.java")"
assert_eq "a sky file plus an ask file" "sky=true ask=true" "$(run_paths "$M/model/AtmosphericData.java $M/service/ask/AskService.java")"

echo "-- pom.xml"
assert_eq "pom with an anthropic version change" "sky=true ask=true" \
    "$(run_paths "backend/pom.xml" "-        <anthropic-sdk.version>3.4.0</anthropic-sdk.version>
+        <anthropic-sdk.version>3.5.0</anthropic-sdk.version>")"
assert_eq "pom with a prompt-regression profile change" "sky=true ask=true" \
    "$(run_paths "backend/pom.xml" "+            <id>prompt-regression</id>")"
assert_eq "pom with a surefire change" "sky=true ask=true" \
    "$(run_paths "backend/pom.xml" "+ <artifactId>maven-surefire-plugin</artifactId>")"
assert_eq "pom with an unrelated change" "sky=false ask=false" \
    "$(run_paths "backend/pom.xml" "-        <postgresql.version>42.7.13</postgresql.version>
+        <postgresql.version>42.7.14</postgresql.version>")"
assert_eq "pom changed, no diff supplied (fail-safe)" "sky=true ask=true" "$(run_paths "backend/pom.xml")"

echo "-- neither"
assert_eq "frontend only" "sky=false ask=false" "$(run_paths "frontend/src/App.jsx frontend/package.json")"
assert_eq "docs only" "sky=false ask=false" "$(run_paths "docs/engineering/x-plan.md CHANGELOG.md changelog.d/20261108-x.md")"
assert_eq "unrelated backend service" "sky=false ask=false" "$(run_paths "$M/service/LocationService.java")"
assert_eq "workflow file itself" "sky=false ask=false" "$(run_paths ".github/workflows/prompt-regression.yml")"
assert_eq "no changed files" "sky=false ask=false" "$(run_paths "")"

echo "-- unclassified prompt-regression resource runs both"
assert_eq "non-txt top-level resource" "sky=true ask=true" "$(run_paths "$R/prompt-regression/notes.json")"

echo "-- bad input"
bash "$SCRIPTS/prompt-regression-paths.sh" "$WORK/does-not-exist" >/dev/null 2>&1
assert_eq "missing changed-files input exits 2" "2" "$?"
bash "$SCRIPTS/prompt-regression-paths.sh" >/dev/null 2>&1
assert_eq "no arguments exits 2" "2" "$?"

finish_tests
