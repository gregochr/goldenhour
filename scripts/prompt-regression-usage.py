"""Token-usage artifact of a paid prompt-regression run (docs/engineering/ci-spend-plan.md, C1).

    python3 -I scripts/prompt-regression-usage.py --suite sky|ask --shards DIR --reports DIR --out FILE

Sums every shard-*.json under --shards (one per recording JVM, written by PromptUsageRecorder), counts cases and
failures from the surefire TEST-*.xml under --reports, and writes one small JSON file (schema 1) that the workflow
uploads and production later prices. It holds token counts and run identifiers only: no prompt text, model output,
keys or headers.

Environment: GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT, HEAD_SHA, PR_NUMBER (empty on a manual run), STARTED_AT.
Prints one totals line to stdout; the workflow appends it to the job summary.

* *.tmp files are ignored (a shard being replaced); a malformed shard is skipped with a warning.
* "complete" is false when no surefire report exists (the build failed before any test ran).

Always exits 0: the test step owns the job's verdict.
"""

import argparse
import datetime
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

FIELDS = ("calls", "erroredCalls", "input", "output", "cacheRead", "cacheWrite", "cacheWrite1h")


def confined(path):
    """The resolved absolute path, or None (with an error on stderr) when it is outside the working directory.

    The workflow runs the script from the repository root with paths under backend/target; anything that resolves
    elsewhere (an absolute path, `..`, a symlink out) is refused, so the script can only read and write inside the
    checkout.
    """
    root = os.path.realpath(os.getcwd())
    resolved = os.path.realpath(path)
    try:
        inside = os.path.commonpath([resolved, root]) == root
    except ValueError:
        inside = False
    if not inside:
        sys.stderr.write("error: path is outside the working directory, refusing: %s\n" % os.path.basename(path))
        return None
    return resolved


def as_int(raw):
    """An integer, or None when absent or not a whole number."""
    raw = (raw or "").strip()
    try:
        return int(raw)
    except ValueError:
        return None


def count(node, attr):
    """An integer attribute, 0 when absent or malformed. Surefire may write 1,234 style numbers."""
    raw = (node.get(attr) or "0").replace(",", "")
    try:
        return int(raw)
    except ValueError:
        return 0


def non_negative(value):
    """A non-negative integer from a shard value, else 0. Booleans are not counts."""
    if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
        return value
    return 0


def read_shards(shards_dir):
    """Sum the shards: {model: {field: total}}. Malformed shards are skipped with a warning."""
    totals = {}
    for path in sorted(glob.glob(os.path.join(shards_dir, "shard-*.json"))):
        try:
            with open(path, encoding="utf-8") as handle:
                doc = json.load(handle)
            rows = doc["models"]
            if not isinstance(rows, list):
                raise ValueError("models is not a list")
            parsed = []
            for row in rows:
                model = row["model"]
                if not isinstance(model, str) or not model:
                    raise ValueError("model is not a string")
                parsed.append((model, {f: non_negative(row.get(f)) for f in FIELDS}))
        except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
            sys.stderr.write("warning: skipping malformed shard %s (%s)\n" % (os.path.basename(path),
                                                                              type(exc).__name__))
            continue
        for model, fields in parsed:
            slot = totals.setdefault(model, {f: 0 for f in FIELDS})
            for field, value in fields.items():
                slot[field] += value
    return totals


def read_reports(reports_dir):
    """(cases, failed, any_report): tests, failures plus errors, and whether a readable report exists."""
    cases = failed = 0
    found = False
    for path in sorted(glob.glob(os.path.join(reports_dir, "TEST-*.xml"))):
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            continue
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        if not suites:
            continue
        found = True
        for suite in suites:
            cases += count(suite, "tests")
            failed += count(suite, "failures") + count(suite, "errors")
    return cases, failed, found


def utc_now():
    """The current UTC time as ISO-8601 with a Z suffix."""
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def build(args, env):
    """The schema-1 document as a dict."""
    totals = read_shards(args.shards)
    cases, failed, found = read_reports(args.reports)
    models = [dict([("model", model)] + [(f, totals[model][f]) for f in FIELDS]) for model in sorted(totals)]
    return {
        "schema": 1,
        "suite": args.suite,
        "runId": as_int(env.get("GITHUB_RUN_ID")),
        "runAttempt": as_int(env.get("GITHUB_RUN_ATTEMPT")),
        "headSha": env.get("HEAD_SHA") or None,
        "pr": as_int(env.get("PR_NUMBER")),
        "startedAt": env.get("STARTED_AT") or None,
        "finishedAt": utc_now(),
        "complete": found,
        "cases": cases,
        "failed": failed,
        "models": models,
    }


def summary_line(doc):
    """One markdown line of token totals."""
    keys = ("calls", "erroredCalls", "input", "output", "cacheRead", "cacheWrite")
    sums = {k: sum(m[k] for m in doc["models"]) for k in keys}
    return ("Prompt usage (%s): %d calls (%d errored), %d input, %d output, %d cache read, %d cache write "
            "tokens across %d model(s).\n" % (doc["suite"], sums["calls"], sums["erroredCalls"], sums["input"],
                                              sums["output"], sums["cacheRead"], sums["cacheWrite"],
                                              len(doc["models"])))


def main(argv):
    """Entry point."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", required=True, choices=("sky", "ask"))
    parser.add_argument("--shards", required=True)
    parser.add_argument("--reports", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    args.shards = confined(args.shards)
    args.reports = confined(args.reports)
    out = confined(args.out)
    if args.shards is None or args.reports is None or out is None:
        return 0
    doc = build(args, os.environ)
    out_dir = os.path.dirname(out)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(out, "w", encoding="utf-8") as handle:
        json.dump(doc, handle, indent=2)
        handle.write("\n")
    sys.stdout.write(summary_line(doc))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as exc:  # pylint: disable=broad-except
        # The test step owns the verdict; a reporting failure must never turn a job red.
        sys.stderr.write("warning: usage artifact not written (%s)\n" % type(exc).__name__)
        sys.exit(0)
