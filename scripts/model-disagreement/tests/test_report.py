"""md_size end to end on the synthetic fixture: export CSV + a cache filled by the real sample and fetch
scripts through the fake provider. No network anywhere."""
import contextlib
import csv
import io
import os
import subprocess
import sys
import tempfile
import unittest

import fixture_tools as ft
import helpers as h
import md_common as mc
import md_size
from test_fetch import subset_csv

FIXTURE = os.path.join(h.HERE, "fixtures", "export_small.csv")
SCRIPT_DIR = os.path.normpath(os.path.join(h.HERE, ".."))


def run_size(csv_path, cache, out, *extra):
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        code = md_size.main([csv_path, "--cache", cache, "--out", out, "--draws", "300", *extra])
    return code, buf.getvalue()


class FullFixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.cache = os.path.join(cls.tmp.name, "cache")
        cls.work = os.path.join(cls.tmp.name, "work")
        with contextlib.redirect_stdout(io.StringIO()):
            cls.list, cls.fake = ft.populate_cache(FIXTURE, cls.cache, cls.work)
        cls.out = os.path.join(cls.tmp.name, "rep")
        cls.wilson_args = []
        orig = mc.stats.wilson

        def spy(*a, **kw):
            cls.wilson_args.append(a)
            return orig(*a, **kw)
        mc.stats.wilson = spy
        try:
            cls.code, cls.stdout = run_size(FIXTURE, cls.cache, cls.out, "--list", cls.list, "--observer")
        finally:
            mc.stats.wilson = orig
        with open(os.path.join(cls.out, "report.md")) as f:
            cls.report = f.read()

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_report_has_every_section_and_a_decision(self):
        self.assertEqual(self.code, 0)
        for s in ("## 1. Units and population", "## 2. Alignment control (read first)", "## 3. Decision",
                  "## 4. Lead bands", "## 5. Descriptive tables", "## 6. Baselines", "## 7. Secondary",
                  "## 8. Worked examples", "## 9. Notes"):
            self.assertIn(s, self.report)
        self.assertRegex(self.report, r"\*\*Outcome: (PASS|FAIL|INCONCLUSIVE|NO PASS POSSIBLE)\.\*\*")
        for n in range(1, 8):
            self.assertIn("| %d " % n, self.report)
        self.assertIn("DECISION on P'", self.stdout)

    def test_dedup_counts_before_and_after(self):
        rows, units = mc.dedup_units(mc.load_rows(FIXTURE))[1].rows, mc.dedup_units(mc.load_rows(FIXTURE))[1].units
        self.assertEqual((rows, units), (962, 242))
        self.assertIn("Export rows 962", self.report)
        self.assertIn("units after deduplication 242", self.report)

    def test_the_latest_pre_event_run_is_the_unit_not_the_stale_or_late_ones(self):
        with open(os.path.join(self.out, "units.csv")) as f:
            ids = {int(r["evaluation_id"]) for r in csv.DictReader(f)}
        rows = {int(r["evaluation_id"]): r for r in mc.load_rows(FIXTURE)}
        for i in ids:
            self.assertEqual(rows[i]["solar_mid_cloud"], "0")        # never the 40/70/80 decoy runs
            self.assertEqual(rows[i]["batch_state"], "")

    def test_moved_location_and_cases_are_kept_out_of_p(self):
        self.assertIn("Moved-location guard dropped 1", self.report)
        with open(os.path.join(self.out, "units.csv")) as f:
            locs = {r["location_id"] for r in csv.DictReader(f)}
        self.assertNotIn("9", locs)                                   # Angel of the North, trigger and training

    def test_trigger_is_shown_as_a_worked_example_only(self):
        sec = self.report.split("## 8. Worked examples")[1].split("## 9.")[0]
        self.assertIn("31 Oct 2026 Angel of the North sunset (trigger)", sec)
        self.assertIn("13 Oct 2026 Angel of the North sunset (training)", sec)
        self.assertIn("not verified yet", sec)

    def test_every_wilson_call_got_integers(self):
        self.assertTrue(self.wilson_args)
        for a in self.wilson_args:
            self.assertTrue(all(type(x) is int for x in a[:2]), a)

    def test_observer_secondary_uses_the_fetched_observer_points(self):
        self.assertIn("S2 at the observer point", self.report)

    def test_no_pipe_breaks_a_table_cell(self):
        for line in self.report.splitlines():
            if line.startswith("| 1 precision"):
                self.assertIn("P(Y\\|S2)", line)

    def test_a_cold_cache_abstains_instead_of_reading_zero_and_never_fetches(self):
        with tempfile.TemporaryDirectory() as d:
            code, _ = run_size(FIXTURE, os.path.join(d, "empty-cache"), os.path.join(d, "o"))
            self.assertEqual(code, 0)
            with open(os.path.join(d, "o", "report.md")) as f:
                text = f.read()
            self.assertIn("0 have all three voters", text)
            self.assertIn("INCONCLUSIVE", text)
            self.assertIn("do not vote and never decide", text)

    def test_no_network_function_raises(self):
        with self.assertRaises(AssertionError):
            md_size.no_network("https://example.invalid")


class AbstentionTests(unittest.TestCase):
    def test_missing_model_and_null_cone_high_mean_not_voting(self):
        with tempfile.TemporaryDirectory() as d:
            csv_path = os.path.join(d, "export.csv")
            subset_csv(csv_path, 3)
            prov = ft.Provider(csv_path, observer=False)
            day = ft.slot_dates()[0].isoformat()
            prov.missing_model[("1", day, "SUNSET")] = mc.ICON
            prov.null_high.add(("2", day, "SUNSET"))
            with contextlib.redirect_stdout(io.StringIO()):
                lst, _ = ft.populate_cache(csv_path, os.path.join(d, "c"), os.path.join(d, "w"), observer=False,
                                           provider=prov)
            units, _ = mc.dedup_units(mc.load_rows(csv_path))
            pop = mc.select_population(units)
            plan = mc.plan_requests(pop.members)
            client = mc.om.Client(os.path.join(d, "c"), 0, fetch=md_size.no_network, dry_run=True)
            recs = {r.u.location_id + r.u.date + r.u.ttype: r for r in md_size.gather(client, pop.members, plan)}
            gone = recs["1" + day + "SUNSET"]
            self.assertIsNone(gone.M[mc.ICON])
            self.assertIsNone(gone.s2)                                # two of three could still agree: no verdict
            self.assertIsNotNone(gone.M[mc.GFS])
            nullhigh = recs["2" + day + "SUNSET"]
            self.assertTrue(all(nullhigh.M[m] is None for m in mc.MODELS))      # a null high anywhere: nobody votes
            self.assertIsNone(nullhigh.s2)
            normal = recs["3" + day + "SUNSET"]
            self.assertIsNotNone(normal.s2)


class CommandLineTests(unittest.TestCase):
    def test_every_script_starts_with_help(self):
        for name in ("md_probe.py", "md_sample.py", "md_fetch.py", "md_size.py"):
            r = subprocess.run([sys.executable, os.path.join(SCRIPT_DIR, name), "--help"], capture_output=True,
                               text=True, cwd=tempfile.gettempdir())
            self.assertEqual(r.returncode, 0, (name, r.stderr))
            self.assertIn("usage:", r.stdout)

    def test_size_without_a_list_uses_all_of_p(self):
        with tempfile.TemporaryDirectory() as d:
            code, _ = run_size(FIXTURE, os.path.join(d, "c"), os.path.join(d, "o"))
            self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
