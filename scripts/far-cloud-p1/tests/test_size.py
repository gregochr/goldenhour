import json
import math
import os
import re
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
import openmeteo as om  # noqa: E402
import size  # noqa: E402
from om_fake import FakeOM  # noqa: E402

_ID = [0]


def row(loc, region, day, ttype="SUNSET", run="12:00:00", lead=0, low=5, far_high=None, far_mid=0,
        types="LANDSCAPE", triage="", state="", **kw):
    _ID[0] += 1
    ev = "17:10:00" if ttype == "SUNSET" else "07:10:00"
    r = {k: "" for k in ("cv_id cv_far_mid_cloud cv_far_high_cloud far_solar_low_cloud "
                         "far_solar_cloud_600hpa far_solar_cloud_400hpa").split()}
    r.update({"evaluation_id": str(_ID[0]), "location_id": str(loc), "target_date": day,
              "target_type": ttype, "forecast_run_at": f"{day}T{run}", "solar_event_time": f"{day}T{ev}",
              "azimuth_deg": "255" if ttype == "SUNSET" else "100", "days_ahead": str(lead),
              "batch_state": state, "triage_reason": triage, "rating": "4", "solar_low_cloud": str(low),
              "far_solar_mid_cloud": str(far_mid), "far_solar_high_cloud": "" if far_high is None else str(far_high),
              "far_solar_cloud_500hpa": "0", "loc_lat": str(54.0 + loc * 0.01), "loc_lon": "-1.5",
              "loc_region_id": str(region), "loc_types": types})
    r.update({k: str(v) for k, v in kw.items()})
    return r


def day_of(url):
    return int(re.search(r"start_date=\d{4}-\d{2}-(\d{2})", url).group(1))


def provider(offset=0):
    """High cloud at any point is 80 on even days of the month, 10 on odd; everything else 0.
    Cone-low (previous runs) can be offset to make the fetched forecast disagree with stored."""
    def p(var, la, lo, hour, url):
        if var.startswith("cloud_cover_high"):
            return 80 if day_of(url) % 2 == 0 else 10
        if var.startswith("cloud_cover_low"):
            return 5 + offset if "previous-runs" in url else 0
        return 0
    return p


def dataset(n_days=30, regions=2, locs=4):
    rows = []
    for d in range(1, n_days + 1):
        day = f"2026-09-{d:02d}"
        fh = 80 if d % 2 == 0 else 10
        for rg in range(1, regions + 1):
            for l in range(locs):
                rows.append(row(rg * 100 + l, rg, day, far_high=fh))
    return rows


class SelectionTest(unittest.TestCase):
    def test_latest_prompted_rules(self):
        d = "2026-09-10"
        rows = [
            row(1, 1, d, run="06:00:00", low=50),                 # superseded by the 12:00 run
            row(1, 1, d, run="12:00:00", low=5),                  # the one
            row(1, 1, d, run="18:00:00", low=99),                 # after the 17:10 event: ignored
            row(2, 1, d, run="12:00:00", state="ABANDONED"),      # ignored state
            row(2, 1, d, run="09:00:00", low=7),                  # so this is the latest valid
            row(3, 1, d, run="12:00:00", triage="HIGH_CLOUD"),    # latest is triaged: dropped
            row(4, 1, d, types="WILDLIFE"),                       # not a sky location
            row(5, 1, d, lead=2),                                 # T+2: out of scope
            row(6, 1, d, types="WILDLIFE,LANDSCAPE", state="SCORED"),
        ]
        got = size.latest_prompted(rows)
        self.assertEqual(sorted((r["location_id"], r["solar_low_cloud"]) for r in got),
                         [("1", "5"), ("2", "7"), ("6", "5")])

    def test_cone_gate_needs_half_the_locations(self):
        d = "2026-09-10"
        rows = ([row(i, 1, d, low=5) for i in range(2)] + [row(i, 1, d, low=40) for i in range(2, 4)] +
                [row(i, 2, d, low=5) for i in range(20, 21)] + [row(i, 2, d, low=40) for i in range(10, 13)])
        ev = {e["region"]: e for e in size.region_events(size.latest_prompted(rows))}
        self.assertTrue(ev["1"]["gated"])       # 2 of 4
        self.assertFalse(ev["2"]["gated"])      # 1 of 4

    def test_pick_locations_deterministic_and_bounded(self):
        rows = [row(i, 1, "2026-09-10", low=5) for i in range(8)]
        e = size.region_events(size.latest_prompted(rows))[0]
        a, b = size.pick_locations(e, 1), size.pick_locations(e, 1)
        self.assertEqual([r["location_id"] for r in a], [r["location_id"] for r in b])
        self.assertEqual(len(a), 3)
        self.assertTrue(set(r["location_id"] for r in a) <= set(r["location_id"] for r in e["firing"]))
        few = size.region_events(size.latest_prompted(rows[:2]))[0]
        self.assertEqual(len(size.pick_locations(few, 1)), 2)

    def test_stratify_weights_and_determinism(self):
        ev = size.region_events(size.latest_prompted(dataset(30, 2, 3)))
        self.assertEqual(len(ev), 60)
        s1, s2 = size.stratify(ev, 20, 7), size.stratify(ev, 20, 7)
        self.assertEqual([e["key"] for e in s1], [e["key"] for e in s2])
        self.assertLessEqual(len(s1), 40)
        self.assertAlmostEqual(sum(e["weight"] for e in s1), 60, places=6)
        self.assertTrue(all(e["weight"] == 1.0 for e in size.stratify(ev, 100, 7)))


class StatsTest(unittest.TestCase):
    def test_wilson(self):
        lo, hi = size.wilson(5, 10)
        self.assertAlmostEqual(lo, 0.2366, places=3)
        self.assertAlmostEqual(hi, 0.7634, places=3)
        self.assertTrue(math.isnan(size.wilson(0, 0)[0]))
        self.assertEqual(size.wilson(0, 10)[0], 0.0)

    def test_any_ge_three_valued(self):
        self.assertTrue(size.any_ge([(None, 60), (70, 60)]))
        self.assertFalse(size.any_ge([(10, 60), (20, 50)]))
        self.assertIsNone(size.any_ge([(10, 60), (None, 50)]))
        self.assertIsNone(size.any_ge([(None, 60)]))

    def test_h_statistics_on_tiny_dataset(self):
        evs = []
        # 10 dates: flagged&observed x4, flagged only x1, observed only x1, neither x4 -> per event
        pattern = [(True, True)] * 4 + [(True, False)] + [(False, True)] + [(False, False)] * 4
        for i, (f, o) in enumerate(pattern):
            evs.append({"date": f"2026-09-{i + 1:02d}", "weight": 1.0, "type": "SUNSET",
                        "out": {"H1": (f, o)}})
        evs.append({"date": "2026-09-30", "weight": 1.0, "type": "SUNSET", "out": {"H1": None}})
        s = size.hypothesis_stats(evs, "H1", total_prompted=100)
        self.assertEqual(s["n_events"], 10)
        self.assertAlmostEqual(s["precision"], 0.8)
        self.assertAlmostEqual(s["recall"], 0.8)
        self.assertAlmostEqual(s["base"], 0.5)
        self.assertAlmostEqual(s["lift"], 1.6)
        self.assertAlmostEqual(s["failure_pop"], 5 / 100)          # 5 observed / ALL prompted
        self.assertLess(s["prec_ci"][0], 0.8 < s["prec_ci"][1] and 0.8)
        self.assertEqual(s["n_dates"], 10)
        again = size.hypothesis_stats(evs, "H1", total_prompted=100)
        self.assertEqual(s["lift_ci"], again["lift_ci"])             # fixed seed

    def test_weights_scale_estimates(self):
        evs = [{"date": "2026-09-01", "weight": 3.0, "out": {"H1": (True, True)}},
               {"date": "2026-09-02", "weight": 1.0, "out": {"H1": (True, False)}}]
        s = size.hypothesis_stats(evs, "H1", total_prompted=4)
        self.assertAlmostEqual(s["precision"], 3 / 4)
        self.assertAlmostEqual(s["failure_pop"], 3 / 4)

    def test_checklist_fails_on_small_counts(self):
        evs = [{"date": f"2026-09-{i + 1:02d}", "weight": 1.0, "out": {"H1": (True, True)}} for i in range(5)]
        s = size.hypothesis_stats(evs, "H1", 1000)
        items = size.checklist(s)
        self.assertFalse(items[0][1])


class EndToEndTest(unittest.TestCase):
    def run_size(self, rows, fake, **kw):
        c = om.Client(tempfile.mkdtemp(), fetch=fake, pause_s=0, dry_run=kw.pop("dry_run", False))
        return size.run(rows, c, **kw), c

    def test_h1_perfect_on_synthetic_world(self):
        fake = FakeOM(provider())
        info, c = self.run_size(dataset(), fake)
        self.assertEqual(info["n_prompted"], 60)
        self.assertEqual(info["n_gated"], 60)
        self.assertEqual(info["n_locs"], 180)               # 3 of 4 per region-event
        s = info["stats"][("H1", "pooled")]
        self.assertEqual(s["n_events"], 60)
        self.assertEqual(s["n_dates"], 30)
        self.assertAlmostEqual(s["precision"], 1.0)
        self.assertAlmostEqual(s["recall"], 1.0)
        self.assertAlmostEqual(s["base"], 0.5)
        self.assertAlmostEqual(s["lift"], 2.0)
        self.assertEqual(info["stats"][("H1", "SUNRISE")]["n_events"], 0)   # empty slice is safe
        self.assertEqual(info["stats"][("H1", "SUNSET")]["n_events"], 60)
        self.assertAlmostEqual(info["stats"][("H1|H2", "pooled")]["lift"], 2.0)
        md = size.render(info)
        self.assertIn("Decision checklist", md)
        self.assertIn("| H1 | SUNRISE |", md)
        for u in fake.urls:
            self.assertNotIn("past_days", u)
            self.assertNotIn("_previous_day0", u)
        arch = [u for u in fake.urls if "archive-api" in u]
        self.assertTrue(arch and all("models=era5" in u for u in arch))
        self.assertTrue(any("cloud_cover_low_previous_day0" not in u for u in fake.urls))

    def test_fetches_only_what_is_missing_and_dedupes(self):
        fake = FakeOM(provider())
        info, c = self.run_size(dataset(2, 1, 4), fake)
        # stored far mid/high + 500hPa present -> no historical forecast, no prev-run 226
        self.assertFalse([u for u in fake.urls if "historical-forecast" in u])
        # cv far mid/high absent -> ERA5 at 226 IS fetched
        self.assertTrue([u for u in fake.urls if "archive-api" in u])
        # nothing refetched: all identical URLs are served from cache
        self.assertEqual(len(fake.urls), len(set(fake.urls)))

    def test_missing_stored_fields_trigger_fetch(self):
        rows = dataset(2, 1, 4)
        for r in rows:
            r["far_solar_high_cloud"] = ""
            r["far_solar_cloud_500hpa"] = ""
        fake = FakeOM(provider())
        info, c = self.run_size(rows, fake)
        self.assertTrue([u for u in fake.urls if "historical-forecast" in u])
        self.assertTrue(info["stats"][("H5", "pooled")]["n_events"] > 0)

    def test_deterministic_and_plan_makes_no_calls(self):
        fake = FakeOM(provider())
        info, c = self.run_size(dataset(5, 2, 4), fake, dry_run=True, plan_only=True)
        self.assertEqual(fake.urls, [])
        self.assertGreater(c.projected_weight, 0)
        f2 = FakeOM(provider())
        c2 = om.Client(tempfile.mkdtemp(), fetch=f2, pause_s=0)
        size.run(dataset(5, 2, 4), c2)
        c3 = om.Client(tempfile.mkdtemp(), fetch=FakeOM(provider()), pause_s=0)
        size.run(dataset(5, 2, 4), c3)
        self.assertEqual(sorted(f2.urls), sorted(c3.fetch.urls))

    def test_pilot_reliable_and_unreliable(self):
        rows = dataset(10, 2, 4)
        info, _ = self.run_size(rows, FakeOM(provider(0)), pilot=30)
        rep = info["pilot_rep"]
        self.assertEqual(rep["_verdict"], "reliable")
        self.assertEqual(rep["cone low (mean of 3)"]["median_abs_diff"], 0)
        self.assertEqual(rep["cone low (mean of 3)"]["within10"], 1.0)
        self.assertTrue(info["fc_fetched"])
        self.assertIn("Calibration pilot", size.render(info))
        info2, _ = self.run_size(rows, FakeOM(provider(40)), pilot=30)
        self.assertEqual(info2["pilot_rep"]["_verdict"], "UNRELIABLE")
        self.assertFalse(info2["fc_fetched"])
        self.assertLessEqual(info2["n_locs"], 30)

    def test_cli_plan_mode(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, "f.csv")
        import csv
        rows = dataset(3, 1, 4)
        with open(p, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0]))
            w.writeheader()
            w.writerows(rows)
        fake = FakeOM(provider())
        c = om.Client(tempfile.mkdtemp(), fetch=fake, pause_s=0, dry_run=True)
        self.assertEqual(size.main([p, "--plan", "--out", d], client=c), 0)
        self.assertEqual(fake.urls, [])
        c2 = om.Client(tempfile.mkdtemp(), fetch=fake, pause_s=0)
        self.assertEqual(size.main([p, "--out", d], client=c2), 0)
        self.assertTrue(os.path.exists(os.path.join(d, "part_c.md")))

    def test_rate_limit_stops_run(self):
        c = om.Client(tempfile.mkdtemp(), fetch=lambda u: (200, '{"error": true, "reason": "Daily API request limit exceeded"}'),
                      pause_s=0)
        d = tempfile.mkdtemp()
        p = os.path.join(d, "f.csv")
        import csv
        rows = dataset(2, 1, 4)
        with open(p, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0]))
            w.writeheader()
            w.writerows(rows)
        self.assertEqual(size.main([p, "--out", d], client=c), 2)
        self.assertEqual(c.live_requests, 1)


if __name__ == "__main__":
    unittest.main()
