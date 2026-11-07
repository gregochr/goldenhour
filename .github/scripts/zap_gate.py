#!/usr/bin/env python3
"""Pass/fail gate for the weekly ZAP scan.

ZAP itself cannot be made to fail usefully: passing a config file (-c) switches the API scan to a
different, much heavier policy, and -I suppresses the WARN exit code. So the scan always runs with
-I and no -c, and this script decides the result from the JSON reports.

Rules:
  * a scan whose exit code is not 0 is a SCANNER failure (distinct from findings);
  * a missing or unparsable report fails;
  * any alert with risk >= Low that is not covered by an explicit exception fails (default FAIL);
  * the backend log must show an imported-URL count at or above a floor (coverage);
  * every rule ID in a target's known-rules list must appear in that target's log (a rule that did not
    run cannot alert); rule IDs in the log but not in the list are reported as drift (never fails).

Standard library only.
"""
import argparse
import json
import re
import sys

RISK_NAMES = {0: "Informational", 1: "Low", 2: "Medium", 3: "High"}
IMPORTED_RE = re.compile(r"Number of Imported URLs:\s*(\d+)")
RULE_LINE_RE = re.compile(
    r"^(?:\S+Z\s+)?(?:PASS|WARN-NEW|FAIL-NEW|WARN-INPROG|FAIL-INPROG|INFO|IGNORE):\s.*\[(\d+)\](?:\s+x\s+\d+)?\s*$"
)
FIRST_URIS = 3


def parse_pairs(items, what):
    """Turn ['name=value', ...] into a dict, rejecting malformed items."""
    out = {}
    for item in items or []:
        if "=" not in item:
            raise SystemExit("bad %s argument (expected name=value): %r" % (what, item))
        name, value = item.split("=", 1)
        out[name] = value
    return out


def load_exceptions(path):
    """Read the exceptions file; returns {target: [entry, ...]}."""
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    for target, entries in data.items():
        for entry in entries:
            if "pluginid" not in entry or entry.get("level") not in ("warn", "ignore"):
                raise ValueError("invalid exception entry for %s: %r" % (target, entry))
            if "uri_regex" in entry:
                re.compile(entry["uri_regex"])
    return data


def load_alerts(path):
    """Flatten every site's alerts from a ZAP JSON report."""
    with open(path, encoding="utf-8") as handle:
        report = json.load(handle)
    alerts = []
    for site in report["site"]:
        alerts.extend(site.get("alerts", []))
    return alerts


def entry_matches_alert(entry, alert):
    """Whether an exception entry targets this alert (rule, and sub-alert if given)."""
    if str(entry["pluginid"]) != str(alert.get("pluginid")):
        return False
    if "alertRef" in entry and str(entry["alertRef"]) != str(alert.get("alertRef")):
        return False
    return True


def classify(alert, entries):
    """Return (covered, levels, uncovered_uris, reasons) for an alert against a target's entries."""
    candidates = [e for e in entries if entry_matches_alert(e, alert)]
    uris = [inst.get("uri", "") for inst in alert.get("instances", [])]
    if not uris:
        # No instance detail: only a whole-rule entry can cover it.
        whole = [e for e in candidates if "uri_regex" not in e]
        return bool(whole), sorted({e["level"] for e in whole}), [], sorted({e["reason"] for e in whole})
    uncovered = []
    levels = set()
    reasons = []
    for uri in uris:
        hit = [e for e in candidates if "uri_regex" not in e or re.search(e["uri_regex"], uri)]
        if not hit:
            uncovered.append(uri)
        for e in hit:
            levels.add(e["level"])
            if e["reason"] not in reasons:
                reasons.append(e["reason"])
    return not uncovered, sorted(levels), uncovered, reasons


def alert_summary(alert):
    """Small dict used by the summary writer."""
    uris = [inst.get("uri", "") for inst in alert.get("instances", [])]
    return {
        "pluginid": str(alert.get("pluginid")),
        "alertRef": str(alert["alertRef"]) if alert.get("alertRef") else "",
        "name": alert.get("name", ""),
        "risk": int(alert.get("riskcode", 0)),
        "count": len(uris),
        "uris": uris[:FIRST_URIS],
    }


def evaluate_target(target, report_path, entries):
    """Evaluate one target's report. Returns a result dict."""
    result = {"target": target, "error": None, "counts": {}, "failing": [], "excepted": []}
    try:
        alerts = load_alerts(report_path)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        result["error"] = "report missing or unparsable (%s): %s" % (report_path, type(exc).__name__)
        return result
    for alert in alerts:
        try:
            risk = int(alert.get("riskcode", 0))
        except (TypeError, ValueError):
            risk = 3  # unreadable risk is treated as the worst case
            alert = dict(alert, riskcode="3")
        result["counts"][risk] = result["counts"].get(risk, 0) + 1
        if risk < 1:
            continue
        summary = alert_summary(alert)
        covered, levels, uncovered, reasons = classify(alert, entries)
        if covered:
            summary["levels"] = levels
            summary["reasons"] = reasons
            result["excepted"].append(summary)
        else:
            summary["uncovered"] = len(uncovered) if uncovered else summary["count"]
            result["failing"].append(summary)
    return result


def read_log(path):
    """Return log text, or None if unreadable."""
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return None


def imported_urls(text):
    """Largest 'Number of Imported URLs' value in the log, or None."""
    values = [int(m.group(1)) for m in IMPORTED_RE.finditer(text or "")]
    return max(values) if values else None


def rule_ids(text):
    """Rule IDs from PASS/WARN/FAIL lines of a scan log."""
    ids = set()
    for line in (text or "").splitlines():
        match = RULE_LINE_RE.match(line.rstrip())
        if match:
            ids.add(match.group(1))
    return ids


def load_known(path):
    """Known rule IDs, one per line, '#' comments allowed."""
    known = set()
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.split("#", 1)[0].strip()
            if line:
                known.add(line)
    return known


def ref_label(a):
    """'90004' or '90004 / 90004-2' when the alert carries a sub-alert reference."""
    if a["alertRef"] and a["alertRef"] != a["pluginid"]:
        return "%s / %s" % (a["pluginid"], a["alertRef"])
    return a["pluginid"]


def render(results, rcs, scanner_failures, coverage, drift, failures, missing_rules):
    """Build the Markdown summary."""
    lines = ["## ZAP gate: %s" % ("FAIL" if failures else "PASS"), ""]
    if failures:
        lines.append("### Failures")
        lines.extend("- " + msg for msg in failures)
        lines.append("")
    if scanner_failures:
        lines.append("### Scanner failures (not findings)")
        lines.extend("- " + msg for msg in scanner_failures)
        lines.append("")
    lines.append("### Scan exit codes")
    for target in sorted(rcs):
        lines.append("- %s: rc=%s" % (target, rcs[target] if rcs[target] != "" else "(empty: scan did not run)"))
    lines.append("")
    lines.append("### Coverage")
    lines.append("- backend imported URLs: %s (minimum %s)" % (coverage["imported"], coverage["minimum"]))
    if coverage["error"]:
        lines.append("- FAIL: " + coverage["error"])
    for target in sorted(missing_rules):
        for rule in sorted(missing_rules[target], key=int):
            lines.append("- FAIL: %s rule %s did not run" % (target, rule))
    lines.append("")
    for res in results:
        lines.append("### %s" % res["target"])
        if res["error"]:
            lines.append("- FAIL: " + res["error"])
            lines.append("")
            continue
        counts = ", ".join("%s: %d" % (RISK_NAMES[r], res["counts"].get(r, 0)) for r in (3, 2, 1, 0))
        lines.append("Alerts by risk: " + counts)
        lines.append("")
        if res["failing"]:
            lines.append("Failing alerts (not covered by an exception):")
            for a in res["failing"]:
                lines.append("- [%s] %s | risk %s | %d instance(s), %d uncovered" % (
                    ref_label(a), a["name"], RISK_NAMES.get(a["risk"], a["risk"]), a["count"], a["uncovered"]))
                for uri in a["uris"]:
                    lines.append("  - `%s`" % uri)
            lines.append("")
        if res["excepted"]:
            lines.append("Excepted alerts:")
            for a in res["excepted"]:
                lines.append("- [%s] %s | risk %s | %d instance(s) | %s | %s" % (
                    ref_label(a), a["name"], RISK_NAMES.get(a["risk"], a["risk"]), a["count"],
                    "/".join(a["levels"]) or "-", "; ".join(a["reasons"])))
            lines.append("")
    lines.append("### Rule drift (informational, never fails the run)")
    if drift:
        for target in sorted(drift):
            if drift[target]:
                lines.append("- %s: %s" % (target, ", ".join(sorted(drift[target], key=int))))
        if not any(drift.values()):
            lines.append("- none")
    else:
        lines.append("- none")
    lines.append("")
    return "\n".join(lines)


def run(args):
    """Run the gate; returns (exit_code, summary_text)."""
    exceptions = load_exceptions(args.exceptions)
    reports = parse_pairs(args.report, "--report")
    logs = parse_pairs(args.log, "--log")
    rcs = parse_pairs(args.rc, "--rc")
    failures = []
    scanner_failures = []

    for target in sorted(set(reports) | set(rcs)):
        rc = rcs.get(target, "")
        if rc != "0":
            scanner_failures.append("%s scan exit code %r (expected 0; empty means it did not run)" % (target, rc))
    failures.extend(scanner_failures)
    rcs.setdefault("backend", "")
    rcs.setdefault("frontend", "")

    results = []
    for target in sorted(reports):
        res = evaluate_target(target, reports[target], exceptions.get(target, []))
        results.append(res)
        if res["error"]:
            failures.append("%s: %s" % (target, res["error"]))
        elif res["failing"]:
            failures.append("%s: %d alert(s) not covered by an exception" % (target, len(res["failing"])))

    backend_text = read_log(logs["backend"]) if "backend" in logs else None
    imported = imported_urls(backend_text)
    coverage = {"imported": imported if imported is not None else "missing", "minimum": args.min_imported_urls,
                "error": None}
    if backend_text is None:
        coverage["error"] = "backend log missing, cannot confirm coverage"
    elif imported is None:
        coverage["error"] = "no 'Number of Imported URLs' line in the backend log"
    elif imported < args.min_imported_urls:
        coverage["error"] = "imported %d URLs, below the minimum of %d" % (imported, args.min_imported_urls)
    if coverage["error"]:
        failures.append("coverage: " + coverage["error"])

    known_files = parse_pairs(args.known_rules, "--known-rules")
    drift = {}
    missing_rules = {}
    for target in sorted(set(reports) | set(logs) | set(known_files)):
        text = read_log(logs[target]) if target in logs else None
        if text is None:
            if target != "backend":  # the backend's missing log is already reported above
                failures.append("coverage: %s log missing, cannot confirm coverage" % target)
            continue
        if target not in known_files:
            continue
        known = load_known(known_files[target])
        seen = rule_ids(text)
        drift[target] = seen - known
        missing_rules[target] = known - seen
        for rule in sorted(missing_rules[target], key=int):
            failures.append("coverage: %s rule %s did not run" % (target, rule))

    summary = render(results, rcs, scanner_failures, coverage, drift, failures, missing_rules)
    return (1 if failures else 0), summary


def main(argv=None):
    """CLI entry point."""
    parser = argparse.ArgumentParser(description="Gate the weekly ZAP scan on its JSON reports.")
    parser.add_argument("--exceptions", required=True)
    parser.add_argument("--report", action="append", default=[], help="target=path to the JSON report")
    parser.add_argument("--log", action="append", default=[], help="target=path to the scan log")
    parser.add_argument("--rc", action="append", default=[], help="target=exit code of the scan")
    parser.add_argument("--min-imported-urls", type=int, default=150)
    parser.add_argument("--known-rules", action="append", default=[], help="target=path to the known-rules list")
    parser.add_argument("--summary", required=True)
    args = parser.parse_args(argv)
    code, summary = run(args)
    with open(args.summary, "w", encoding="utf-8") as handle:
        handle.write(summary)
    print(summary)
    return code


if __name__ == "__main__":
    sys.exit(main())
