#!/bin/bash
# Structural tests for .github/workflows/real-api-smoke.yml (docs/engineering/ci-spend-plan.md, C4).
#
# The daily smoke now records its token usage into an artifact that production prices on Cost trends. The steps and
# names that feed that, and the ones that must stay as they were (the timeout, concurrency, the inconclusive flag, the
# two notifiers, the failure-only surefire upload), are pinned here as structure. Steps are matched by exact name.

# shellcheck disable=SC2016
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
source "${HERE}/helpers.sh"

WF="$ROOT/.github/workflows/real-api-smoke.yml"
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

jobs = doc["jobs"]
job = jobs["smoke"]
steps = job["steps"]
names = [st.get("name", "") for st in steps]
print("yaml=ok")
print("name=%s" % doc["name"])
print("jobs=%s" % ",".join(sorted(jobs)))
print("job_name=%s" % job.get("name"))
print("timeout=%s" % job.get("timeout-minutes"))
print("cancel=%s" % (doc.get("concurrency") or {}).get("cancel-in-progress"))
print("group=%s" % (doc.get("concurrency") or {}).get("group"))
print("env=%s" % ",".join("%s=%s" % (k, v) for k, v in sorted((job.get("env") or {}).items())))
print("step_names=%s" % "|".join(names))
checkout = names.index("Checkout")
for key, label in (("start", "Record start time"), ("write", "Write usage"), ("upload", "Upload usage"),
                   ("maven", "Run real-API E2E test"), ("inconclusive", "Flag inconclusive run"),
                   ("surefire", "Upload surefire reports on failure")):
    count = names.count(label)
    print("%s_count=%d" % (key, count))
    if count == 1:
        i = names.index(label)
        st = steps[i]
        print("%s_after_checkout=%s" % (key, i > checkout))
        print("%s_before_maven=%s" % (key, i < names.index("Run real-API E2E test")))
        print("%s_if=%s" % (key, st.get("if")))
        print("%s_wd=%s" % (key, st.get("working-directory")))
        print("%s_run=%s" % (key, " ".join(str(st.get("run", "")).split())))
        w = st.get("with") or {}
        print("%s_artifact=%s" % (key, w.get("name")))
        print("%s_path=%s" % (key, w.get("path")))
        print("%s_retention=%s" % (key, w.get("retention-days")))
        print("%s_missing=%s" % (key, w.get("if-no-files-found")))
print("notify_failure_if=%s" % jobs["notify-failure"].get("if"))
print("notify_failure_needs=%s" % jobs["notify-failure"].get("needs"))
print("notify_failure_name=%s" % jobs["notify-failure"]["with"].get("workflow-name"))
print("notify_inconclusive_if=%s" % " ".join(str(jobs["notify-inconclusive"].get("if")).split()))
print("notify_inconclusive_needs=%s" % jobs["notify-inconclusive"].get("needs"))
print("notify_inconclusive_name=%s" % jobs["notify-inconclusive"]["with"].get("workflow-name"))
PY
)"

fact() { printf '%s\n' "$FACTS" | sed -n "s/^$1=//p" | head -1; }

if [ "$(fact yaml)" = "missing" ]; then
    echo "  note PyYAML is not installed: structural checks skipped, text checks still run"
else
    assert_eq "YAML parses" "ok" "$(fact yaml)"
    assert_eq "workflow name" "Real API Smoke" "$(fact name)"
    assert_eq "jobs" "notify-failure,notify-inconclusive,smoke" "$(fact jobs)"
    assert_eq "job name is pinned (the poller matches it)" "Real API completion smoke" "$(fact job_name)"
    assert_eq "timeout is unchanged" "320" "$(fact timeout)"
    assert_eq "concurrency does not cancel" "False" "$(fact cancel)"
    assert_eq "concurrency group is unchanged" "real-api-smoke" "$(fact group)"
    assert_eq "job env is the usage dir and the SHA" \
        'HEAD_SHA=${{ github.sha }},PROMPT_REGRESSION_USAGE_DIR=${{ github.workspace }}/backend/target/prompt-regression-usage' \
        "$(fact env)"

    assert_eq "the paid step keeps its exact name (the poller matches it)" "1" "$(fact maven_count)"
    assert_eq "exactly one Record start time" "1" "$(fact start_count)"
    assert_eq "Record start time runs from the repository root" "." "$(fact start_wd)"
    assert_eq "Record start time is after checkout" "True" "$(fact start_after_checkout)"
    assert_eq "Record start time is before the paid step" "True" "$(fact start_before_maven)"

    assert_eq "exactly one Write usage" "1" "$(fact write_count)"
    assert_eq "Write usage runs always()" "always()" "$(fact write_if)"
    assert_eq "Write usage runs from the repository root" "." "$(fact write_wd)"
    assert_eq "Write usage runs the smoke suite into the summary" \
        'python3 -I scripts/prompt-regression-usage.py --suite smoke >> "$GITHUB_STEP_SUMMARY"' "$(fact write_run)"

    assert_eq "exactly one Upload usage" "1" "$(fact upload_count)"
    assert_eq "Upload usage runs always()" "always()" "$(fact upload_if)"
    assert_eq "usage artifact is named per attempt" 'prompt-regression-usage-smoke-${{ github.run_attempt }}' "$(fact upload_artifact)"
    assert_eq "usage artifact is the one JSON file" "backend/target/prompt-regression-usage-smoke.json" "$(fact upload_path)"
    assert_eq "usage artifact is kept 90 days" "90" "$(fact upload_retention)"
    assert_eq "usage artifact is optional" "ignore" "$(fact upload_missing)"

    assert_eq "the inconclusive flag still runs always()" "always()" "$(fact inconclusive_if)"
    assert_eq "the surefire upload stays failure-only" "failure()" "$(fact surefire_if)"
    assert_eq "failure notifier condition is unchanged" "failure()" "$(fact notify_failure_if)"
    assert_eq "failure notifier needs the job" "smoke" "$(fact notify_failure_needs)"
    assert_eq "failure notifier name is unchanged" "Real API Smoke" "$(fact notify_failure_name)"
    assert_eq "inconclusive notifier condition is unchanged" \
        "success() && needs.smoke.outputs.inconclusive == 'true'" "$(fact notify_inconclusive_if)"
    assert_eq "inconclusive notifier needs the job" "smoke" "$(fact notify_inconclusive_needs)"
    assert_eq "inconclusive notifier name is unchanged" \
        "Real API Smoke (inconclusive — response parsing not verified)" "$(fact notify_inconclusive_name)"
fi

echo "-- text invariants"
assert_file_has "the paid step is the one test class" "$WF" "./mvnw test -Dtest=ForecastBatchPipelineRealApiE2ETest -e"
assert_file_has "the job keeps the scheduled cron" "$WF" "cron: '0 6 * * *'"
assert_file_lacks "no second secret is introduced" "$WF" "PROMPT_REGRESSION_ANTHROPIC_API_KEY"

echo "-- actionlint"
if command -v actionlint >/dev/null 2>&1; then
    if actionlint "$WF"; then ok "actionlint is clean"; else bad "actionlint found problems"; fi
else
    echo "  note actionlint is not installed here: not run"
fi

finish_tests
