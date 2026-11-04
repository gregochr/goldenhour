"""Unit tests for zap_gate.py. Run: python3 -m unittest discover -s .github/scripts -p 'test_*.py'"""
import json
import os
import tempfile
import unittest

import zap_gate

GOOD_LOG = "2026-11-02T11:45:39Z 2026-11-02 11:45:39,086 Number of Imported URLs: 180\n"
RULE_LOG = (
    "2026-11-02T11:47:10Z PASS: Directory Browsing [0]\n"
    "2026-11-02T11:47:10Z WARN-NEW: Buffer Overflow [30001] x 22 \n"
    "2026-11-02T11:47:10Z FAIL-NEW: 0\tFAIL-INPROG: 0\tWARN-NEW: 1\tPASS: 1\n"
)
EXCEPTIONS = {
    "backend": [
        {"pluginid": "30001", "level": "warn", "reason": "r-warn"},
        {"pluginid": "100001", "uri_regex": "^http://b/stream", "level": "ignore", "reason": "r-sse"},
    ],
    "frontend": [],
}
KNOWN = "# comment\n0\n30001\n"


def alert(pluginid, risk, uris=("http://b/x",), name="Some alert"):
    """Build a ZAP-shaped alert."""
    return {"pluginid": pluginid, "alertRef": pluginid, "name": name, "riskcode": str(risk),
            "instances": [{"uri": u} for u in uris]}


def report(*alerts):
    """Wrap alerts in a ZAP-shaped report."""
    return {"site": [{"@name": "http://b", "alerts": list(alerts)}]}


class GateTest(unittest.TestCase):
    """Exercise the gate end to end through run()."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def path(self, name):
        return os.path.join(self.tmp.name, name)

    def write(self, name, content, as_json=False):
        with open(self.path(name), "w", encoding="utf-8") as handle:
            handle.write(json.dumps(content) if as_json else content)
        return self.path(name)

    def gate(self, backend=None, frontend=None, backend_rc="0", frontend_rc="0", backend_log=GOOD_LOG,
             frontend_log=RULE_LOG, minimum=150, write_backend=True, write_frontend=True):
        """Run the gate with the given reports; returns (exit_code, summary)."""
        exceptions = self.write("exceptions.json", EXCEPTIONS, True)
        known = self.write("known.txt", KNOWN)
        argv = ["--exceptions", exceptions, "--known-rules", known, "--summary", self.path("summary.md"),
                "--min-imported-urls", str(minimum),
                "--rc", "backend=" + backend_rc, "--rc", "frontend=" + frontend_rc]
        if write_backend:
            argv += ["--report", "backend=" + self.write("b.json", backend or report(), True)]
        else:
            argv += ["--report", "backend=" + self.path("absent.json")]
        if write_frontend:
            argv += ["--report", "frontend=" + self.write("f.json", frontend or report(), True)]
        argv += ["--log", "backend=" + self.write("b.log", backend_log),
                 "--log", "frontend=" + self.write("f.log", frontend_log)]
        args = self.parse(argv)
        return zap_gate.run(args)

    @staticmethod
    def parse(argv):
        import argparse
        parser = argparse.ArgumentParser()
        parser.add_argument("--exceptions")
        parser.add_argument("--report", action="append", default=[])
        parser.add_argument("--log", action="append", default=[])
        parser.add_argument("--rc", action="append", default=[])
        parser.add_argument("--min-imported-urls", type=int)
        parser.add_argument("--known-rules")
        parser.add_argument("--summary")
        return parser.parse_args(argv)

    def test_clean_pass(self):
        code, summary = self.gate()
        self.assertEqual(code, 0)
        self.assertIn("ZAP gate: PASS", summary)

    def test_unexcepted_low_alert_fails(self):
        code, summary = self.gate(backend=report(alert("99999", 1, name="Brand new")))
        self.assertEqual(code, 1)
        self.assertIn("Brand new", summary)
        self.assertIn("http://b/x", summary)

    def test_warn_exception_passes(self):
        code, summary = self.gate(backend=report(alert("30001", 2)))
        self.assertEqual(code, 0)
        self.assertIn("r-warn", summary)

    def test_uri_regex_partial_cover_fails(self):
        partial = report(alert("100001", 1, uris=("http://b/stream/1", "http://b/other")))
        code, summary = self.gate(backend=partial)
        self.assertEqual(code, 1)
        self.assertIn("1 uncovered", summary)

    def test_uri_regex_full_cover_passes(self):
        code, _ = self.gate(backend=report(alert("100001", 1, uris=("http://b/stream/1", "http://b/stream/2"))))
        self.assertEqual(code, 0)

    def test_exception_is_per_target(self):
        code, _ = self.gate(frontend=report(alert("30001", 1)))
        self.assertEqual(code, 1)

    def test_scanner_rc_3_fails_as_scanner_failure(self):
        code, summary = self.gate(backend_rc="3")
        self.assertEqual(code, 1)
        self.assertIn("Scanner failures", summary)

    def test_empty_rc_fails(self):
        code, summary = self.gate(frontend_rc="")
        self.assertEqual(code, 1)
        self.assertIn("did not run", summary)

    def test_missing_report_fails(self):
        code, summary = self.gate(write_backend=False)
        self.assertEqual(code, 1)
        self.assertIn("report missing or unparsable", summary)

    def test_unparsable_report_fails(self):
        exceptions = self.write("exceptions.json", EXCEPTIONS, True)
        bad = self.write("b.json", "{not json")
        res = zap_gate.evaluate_target("backend", bad, EXCEPTIONS["backend"])
        self.assertIsNotNone(res["error"])

    def test_missing_imported_urls_line_fails(self):
        code, summary = self.gate(backend_log="nothing useful\n")
        self.assertEqual(code, 1)
        self.assertIn("no 'Number of Imported URLs' line", summary)

    def test_imported_urls_below_floor_fails(self):
        code, summary = self.gate(backend_log="Number of Imported URLs: 149\n")
        self.assertEqual(code, 1)
        self.assertIn("below the minimum", summary)

    def test_informational_alert_ignored(self):
        code, _ = self.gate(backend=report(alert("77777", 0)))
        self.assertEqual(code, 0)

    def test_drift_listed_but_does_not_fail(self):
        log = GOOD_LOG + "2026-11-02T11:47:10Z PASS: A new rule [424242]\n"
        code, summary = self.gate(backend_log=log)
        self.assertEqual(code, 0)
        self.assertIn("424242", summary)

    def test_rule_ids_parser_ignores_summary_line(self):
        self.assertEqual(zap_gate.rule_ids(RULE_LOG), {"0", "30001"})


if __name__ == "__main__":
    unittest.main()
