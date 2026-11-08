"""Markdown summary of a prompt-regression run (docs/engineering/engineering-gaps-plan.md, Phase 5b, B9).

    python3 -I scripts/prompt-regression-summary.py --reports DIR --title TITLE [--skipped-red] [--advisory NAME ...]

Reads every TEST-*.xml under DIR with xml.etree, takes tests / failures / errors / skipped from each <testsuite>
root and prints one table row per class. Writes to $GITHUB_STEP_SUMMARY (appending) when it is set, else to stdout.

* Zero reports means the build failed before any test ran, and the summary says so.
* --skipped-red shows any skipped test as a failure. The Ask classes skip silently when a precondition is missing,
  and a skipped paid test proved nothing.
* --advisory NAME marks a class whose failure is reported but does not fail the job (ValleyMist, until the owner
  approves its assertions). NAME is matched as a substring of the class name.

Always exits 0: the test step owns the job's verdict, this script only reports it.
"""

import argparse
import glob
import html
import os
import sys
import xml.etree.ElementTree as ET

PASS = ":white_check_mark:"
FAIL = ":x:"
WARN = ":warning:"
MAX_FAILURES_LISTED = 20
MAX_MESSAGE_CHARS = 200


def md(text):
    """Escape text for a markdown table cell: entities, pipes and bracket/backtick syntax."""
    out = html.escape(str(text), quote=False)
    for ch, rep in (("|", "&#124;"), ("[", "&#91;"), ("]", "&#93;"), ("`", "&#96;")):
        out = out.replace(ch, rep)
    return " ".join(out.split())


def count(node, attr):
    """An integer attribute, 0 when absent or malformed. Surefire may write 1,234 style numbers."""
    raw = (node.get(attr) or "0").replace(",", "")
    try:
        return int(raw)
    except ValueError:
        return 0


def read_report(path):
    """Return a dict for one TEST-*.xml, or one with 'unreadable' set when it does not parse."""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        return {"name": os.path.basename(path), "unreadable": str(exc)}
    suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
    if not suites:
        return {"name": os.path.basename(path), "unreadable": "no <testsuite> element"}
    result = {"name": suites[0].get("name") or os.path.basename(path), "tests": 0, "failures": 0,
              "errors": 0, "skipped": 0, "failed_cases": []}
    for suite in suites:
        for key in ("tests", "failures", "errors", "skipped"):
            result[key] += count(suite, key)
    for case in root.iter("testcase"):
        problem = case.find("failure")
        if problem is None:
            problem = case.find("error")
        if problem is not None:
            message = problem.get("message") or (problem.text or "").strip()
            result["failed_cases"].append((case.get("name") or "?", message))
    return result


def status(report, skipped_red, advisory):
    """(emoji, label) for one class."""
    if "unreadable" in report:
        return FAIL, "unreadable report"
    bad = report["failures"] + report["errors"] > 0 or (skipped_red and report["skipped"] > 0)
    if bad:
        return (WARN, "failed (advisory)") if advisory else (FAIL, "failed")
    if report["tests"] > 0 and report["skipped"] == report["tests"]:
        return WARN, "all skipped"
    return PASS, "passed"


def render(reports_dir, title, skipped_red, advisory_names):
    """The whole markdown document as a string."""
    paths = sorted(glob.glob(os.path.join(reports_dir, "TEST-*.xml")))
    lines = ["### " + md(title), ""]
    if not paths:
        lines.append(FAIL + " **No test reports were produced.** The build failed before any test ran; "
                     "see the Maven step's log.")
        return "\n".join(lines) + "\n"

    reports = sorted((read_report(p) for p in paths), key=lambda r: r["name"])
    lines += ["| Class | Result | Passed | Failed | Errors | Skipped |", "|---|---|---:|---:|---:|---:|"]
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    listed = []
    for report in reports:
        short = report["name"].rsplit(".", 1)[-1]
        if "unreadable" in report:
            short = report["name"]
            lines.append("| %s | %s %s | - | - | - | - |" % (md(short), FAIL, md(report["unreadable"])))
            continue
        advisory = any(a in report["name"] for a in advisory_names)
        emoji, label = status(report, skipped_red, advisory)
        passed = max(report["tests"] - report["failures"] - report["errors"] - report["skipped"], 0)
        lines.append("| %s | %s %s | %d | %d | %d | %d |" % (
            md(short), emoji, label, passed, report["failures"], report["errors"], report["skipped"]))
        if not advisory:
            for key in totals:
                totals[key] += report[key]
        for case_name, message in report["failed_cases"]:
            listed.append((short, case_name, message))

    lines += ["", "Totals (advisory classes excluded): %d tests, %d failed, %d errors, %d skipped." % (
        totals["tests"], totals["failures"], totals["errors"], totals["skipped"])]
    if skipped_red and totals["skipped"] > 0:
        lines.append(FAIL + " Skipped tests are shown as failures here: a skipped paid test checked nothing.")
    if listed:
        lines += ["", "<details><summary>Failing tests</summary>", ""]
        for short, case_name, message in listed[:MAX_FAILURES_LISTED]:
            lines.append("- %s / %s: %s" % (md(short), md(case_name), md(message[:MAX_MESSAGE_CHARS])))
        if len(listed) > MAX_FAILURES_LISTED:
            lines.append("- ... and %d more" % (len(listed) - MAX_FAILURES_LISTED))
        lines += ["", "</details>"]
    return "\n".join(lines) + "\n"


def main(argv):
    """Entry point."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--skipped-red", action="store_true")
    parser.add_argument("--advisory", action="append", default=[])
    args = parser.parse_args(argv)
    text = render(args.reports, args.title, args.skipped_red, args.advisory)
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as handle:
            handle.write(text)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
