#!/bin/bash
# Decides which prompt-regression suites a change needs (docs/engineering/engineering-gaps-plan.md, Phase 5b).
#
#   scripts/prompt-regression-paths.sh CHANGED_FILES [POM_DIFF]
#
# CHANGED_FILES is a file holding one changed path per line (git diff --name-only). POM_DIFF is optional: the
# output of `git diff -U8 BASE...HEAD -- backend/pom.xml`. Prints exactly two lines, ready for $GITHUB_OUTPUT:
#
#   sky=true|false
#   ask=true|false
#
# Fail-safe direction: an unreadable input is an error (exit 2), and a changed pom.xml with no diff supplied
# counts as a prompt-affecting change, because running a paid suite once too often is cheaper than missing one.
# The globs are written out in full on purpose (B5): a reviewer should be able to read every path that spends money.

set -euo pipefail

CHANGED_FILE="${1:-}"
POM_DIFF_FILE="${2:-}"

if [ -z "$CHANGED_FILE" ] || [ ! -r "$CHANGED_FILE" ]; then
    echo "usage: $0 CHANGED_FILES [POM_DIFF]" >&2
    exit 2
fi
if [ -n "$POM_DIFF_FILE" ] && [ ! -r "$POM_DIFF_FILE" ]; then
    echo "$0: cannot read pom diff '$POM_DIFF_FILE'" >&2
    exit 2
fi

MAIN='backend/src/main/java/com/gregochr/goldenhour'
TEST='backend/src/test/java'
RES='backend/src/test/resources'

sky=false
ask=false

# Classify one path. In a case pattern * also matches '/', so MAIN/service/ask/* covers every depth.
classify() {
    case "$1" in
        # ── shared: both suites ────────────────────────────────────────────────────────────────────────────
        "$MAIN"/service/evaluation/AnthropicApiClient.java | \
        "$MAIN"/service/evaluation/ModelRequestSupport.java | \
        "$MAIN"/service/evaluation/PromptUtils.java | \
        "$MAIN"/service/evaluation/RatingValidator.java | \
        "$MAIN"/entity/EvaluationModel.java | \
        "$MAIN"/service/ModelSelectionService.java | \
        "$MAIN"/config/AppConfig.java | \
        "$MAIN"/util/DayLabels.java)
            sky=true
            ask=true
            ;;
        # ── ask ────────────────────────────────────────────────────────────────────────────────────────────
        "$MAIN"/service/ask/* | \
        "$MAIN"/service/DriveTimeResolver.java | \
        "$MAIN"/util/PlaceNames.java | \
        "$TEST"/*/service/ask/* | \
        "$RES"/prompt-regression/ask/*)
            ask=true
            ;;
        # ── sky ────────────────────────────────────────────────────────────────────────────────────────────
        "$MAIN"/service/evaluation/* | \
        "$MAIN"/model/* | \
        "$MAIN"/eval/* | \
        backend/src/main/resources/eval/* | \
        "$MAIN"/service/ValleyMistCalculator.java | \
        "$MAIN"/service/CoastalMistCalculator.java | \
        "$MAIN"/service/InversionScoreCalculator.java | \
        "$MAIN"/service/DirectionalSamplingGeometry.java | \
        "$MAIN"/service/WoodlandVerdictEvaluator.java | \
        "$MAIN"/entity/ForecastStability.java | \
        "$MAIN"/entity/TargetType.java | \
        "$MAIN"/entity/AlertLevel.java | \
        "$TEST"/*/TestAtmosphericData.java | \
        "$TEST"/*/eval/* | \
        "$TEST"/*/service/evaluation/*Regression*.java | \
        "$TEST"/*/service/evaluation/*Fixture*.java | \
        "$TEST"/*/service/evaluation/*PromptTrims*.java | \
        "$TEST"/*/service/evaluation/*TokenCount*.java | \
        "$RES"/eval/*)
            sky=true
            ;;
        # prompt-regression resources: ask/ is handled above and the top-level *.txt files are the sky prompt
        # snapshots. Anything else in there is unclassified, so it runs both suites.
        "$RES"/prompt-regression/*)
            case "${1#"$RES"/prompt-regression/}" in
                */*) sky=true; ask=true ;;
                *.txt) sky=true ;;
                *) sky=true; ask=true ;;
            esac
            ;;
        backend/pom.xml)
            if [ -z "$POM_DIFF_FILE" ] || grep -Eq 'anthropic|prompt-regression|surefire' "$POM_DIFF_FILE"; then
                sky=true
                ask=true
            fi
            ;;
        *) ;;
    esac
}

while IFS= read -r path || [ -n "$path" ]; do
    [ -n "$path" ] || continue
    classify "$path"
done < "$CHANGED_FILE"

echo "sky=${sky}"
echo "ask=${ask}"
