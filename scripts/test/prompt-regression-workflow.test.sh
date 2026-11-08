#!/bin/bash
# Structural tests for .github/workflows/prompt-regression.yml (docs/engineering/engineering-gaps-plan.md, Phase 5b).
#
# Nothing can run the paid jobs in a test, so the properties that keep them safe are pinned as text and structure:
# the key only in the approval environment, no secrets in the change-detection job, the path logic called from the
# tested script, PromptReplayHarnessTest never run, and the summary and upload surviving a red test step.
# actionlint is run when it is installed (CI does not install it; the build recorded its result locally).

# The single-quoted `${{ ... }}` and `$VAR` strings below are literals compared against the workflow text.
# shellcheck disable=SC2016
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

WF="$ROOT/.github/workflows/prompt-regression.yml"

assert_file_exists "workflow exists" "$WF"

FACTS="$(python3 -I - "$WF" <<'PY'
import sys

try:
    import yaml
except ImportError:
    print("yaml=missing")
    sys.exit(0)

with open(sys.argv[1], encoding="utf-8") as handle:
    doc = yaml.safe_load(handle)

# PyYAML reads the bare key `on` as boolean True.
triggers = doc.get("on", doc.get(True)) or {}
jobs = doc["jobs"]
print("yaml=ok")
print("name=%s" % doc["name"])
print("triggers=%s" % ",".join(sorted(triggers)))
print("pr_types=%s" % ",".join(triggers["pull_request"]["types"]))
print("dispatch_inputs=%s" % ",".join(sorted(triggers["workflow_dispatch"]["inputs"])))
print("permissions=%s" % doc.get("permissions"))
print("jobs=%s" % ",".join(sorted(jobs)))
for name in ("sky", "ask"):
    job = jobs[name]
    print("%s_env=%s" % (name, job.get("environment")))
    print("%s_needs=%s" % (name, job.get("needs")))
    print("%s_key=%s" % (name, (job.get("env") or {}).get("ANTHROPIC_API_KEY")))
    print("%s_timeout=%s" % (name, job.get("timeout-minutes")))
    print("%s_cancel=%s" % (name, (job.get("concurrency") or {}).get("cancel-in-progress")))
    print("%s_group=%s" % (name, (job.get("concurrency") or {}).get("group")))
changes = jobs["changes"]
print("changes_env=%s" % changes.get("environment"))
print("changes_if=%s" % " ".join(str(changes.get("if", "")).split()))
checkouts = 0
safe = 0
for job in jobs.values():
    for step in job["steps"]:
        if str(step.get("uses", "")).startswith("actions/checkout@"):
            checkouts += 1
            if step.get("with", {}).get("persist-credentials") is False:
                safe += 1
print("checkouts=%d" % checkouts)
print("checkouts_without_credentials=%d" % safe)
print("changes_fetch_depth=%s" % changes["steps"][0]["with"].get("fetch-depth"))
for name in ("sky", "ask"):
    for step in jobs[name]["steps"]:
        label = step.get("name", "")
        if label.startswith("Summarise") or label.startswith("Upload"):
            print("%s_%s_if=%s" % (name, label.split()[0].lower(), step.get("if")))
        if label.startswith("ValleyMist"):
            print("valleymist_continue=%s" % step.get("continue-on-error"))
            print("valleymist_if=%s" % step.get("if"))
PY
)"

fact() { printf '%s\n' "$FACTS" | sed -n "s/^$1=//p" | head -1; }

if [ "$(fact yaml)" = "missing" ]; then
    echo "  note PyYAML is not installed: structural checks skipped, text checks still run"
else
    assert_eq "YAML parses" "ok" "$(fact yaml)"
    assert_eq "name" "Prompt regression (approval-gated)" "$(fact name)"
    assert_eq "triggers" "pull_request,workflow_dispatch" "$(fact triggers)"
    assert_eq "pull_request types" "opened,synchronize,reopened,ready_for_review" "$(fact pr_types)"
    assert_eq "dispatch inputs" "sky_runs,suite" "$(fact dispatch_inputs)"
    assert_eq "workflow-level permissions are read-only" "{'contents': 'read'}" "$(fact permissions)"
    assert_eq "jobs" "ask,changes,sky" "$(fact jobs)"
    assert_eq "changes job has no environment" "None" "$(fact changes_env)"
    assert_contains "changes job guard allows manual runs" "$(fact changes_if)" "github.event_name == 'workflow_dispatch'"
    assert_contains "changes job guard is same-repo only" "$(fact changes_if)" "github.event.pull_request.head.repo.full_name == github.repository"
    assert_contains "changes job guard excludes Dependabot" "$(fact changes_if)" "github.actor != 'dependabot[bot]'"
    assert_eq "changes job checks out the full history" "0" "$(fact changes_fetch_depth)"
    for job in sky ask; do
        assert_eq "$job runs in the approval environment" "prompt-regression" "$(fact ${job}_env)"
        assert_eq "$job needs changes" "changes" "$(fact ${job}_needs)"
        assert_eq "$job maps the separate key" '${{ secrets.PROMPT_REGRESSION_ANTHROPIC_API_KEY }}' "$(fact ${job}_key)"
        assert_eq "$job cancels stale runs" "True" "$(fact ${job}_cancel)"
        assert_contains "$job concurrency group uses the head ref" "$(fact ${job}_group)" "github.head_ref || github.ref"
        assert_eq "$job summary survives a red step" '${{ !cancelled() }}' "$(fact ${job}_summarise_if)"
        assert_eq "$job upload survives a red step" '${{ !cancelled() }}' "$(fact ${job}_upload_if)"
    done
    assert_eq "ask timeout" "75" "$(fact ask_timeout)"
    assert_contains "sky timeout is 60 in eval mode, else 30" "$(fact sky_timeout)" "inputs.sky_runs == '8' && 60 || 30"
    assert_eq "every checkout drops credentials" "$(fact checkouts)" "$(fact checkouts_without_credentials)"
    assert_eq "ValleyMist is advisory" "True" "$(fact valleymist_continue)"
    assert_contains "ValleyMist still runs after a red sky step" "$(fact valleymist_if)" "!cancelled()"
fi

echo "-- text invariants"
assert_file_has "calls the tested path script" "$WF" "scripts/prompt-regression-paths.sh"
assert_file_has "uses the three-dot merge-base diff" "$WF" '"$BASE_SHA"..."$HEAD_SHA"'
assert_file_has "runs the summary isolated" "$WF" "python3 -I scripts/prompt-regression-summary.py"
assert_file_has "sky runs the unified sky class" "$WF" "SkyRatingRegressionTest,BestBetAuroraPromptRegressionTest,SystemPromptTokenCountTest"
assert_file_has "ask runs the Ask classes" "$WF" "-Dtest='Ask*RegressionTest'"
assert_file_has "ask forks once" "$WF" "-DforkCount=1"
assert_file_has "sky model is Sonnet 5.5" "$WF" "SKY_MODEL: SONNET_55"
assert_file_has "SKY_RUNS defaults to 1" "$WF" "SKY_RUNS: \${{ inputs.sky_runs || '1' }}"
assert_file_has "ask summary shows skips red" "$WF" "--skipped-red"
assert_file_lacks "no Anthropic key as a repository secret" "$WF" "secrets.ANTHROPIC_API_KEY"
assert_file_lacks "never runs the replay harness in a -Dtest" "$WF" "-Dtest='PromptReplayHarnessTest"
assert_file_lacks "ValleyMist is not in a hard step" "$WF" "SystemPromptTokenCountTest,ValleyMist"
assert_file_lacks "no legacy REGRESSION_MODEL (B11)" "$WF" "REGRESSION_MODEL"
assert_file_lacks "does not run the retired SkyRatingEvalTest" "$WF" "SkyRatingEvalTest"
assert_eq "does not run the retired PromptRegressionTest" "" "$(grep -w 'PromptRegressionTest' "$WF" || true)"

# The only mention of PromptReplayHarnessTest allowed is a comment saying why it is absent.
HARNESS="$(grep -n 'PromptReplayHarnessTest' "$WF" | grep -v '^[0-9]*: *#' || true)"
assert_eq "PromptReplayHarnessTest appears only in comments" "" "$HARNESS"

# The change-detection job must never see a secret.
CHANGES_BLOCK="$(awk '/^  changes:/{f=1;next} /^  sky:/{f=0} f' "$WF")"
assert_not_contains "changes job references no secrets" "$CHANGES_BLOCK" "secrets."

echo "-- actionlint"
if command -v actionlint >/dev/null 2>&1; then
    if actionlint "$WF"; then ok "actionlint is clean"; else bad "actionlint found problems"; fi
else
    echo "  note actionlint is not installed here: not run"
fi

finish_tests
