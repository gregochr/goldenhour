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


def load_size_rows(p):
    return size.load_rows(p)
from om_fake import FakeOM  # noqa: E402

_ID = [0]


def row(loc, region, day, ttype="SUNSET", run="12:00:00", lead=0, low=5, far_high=None, far_mid=0,
        types="LANDSCAPE", triage="", state="", **kw):
    _ID[0] += 1
    ev = "17:10:00" if ttype == "SUNSET" else "07:10:00"
    r = {k: "" for k in "cv_id cv_far_mid_cloud cv_far_high_cloud far_solar_low_cloud".split()}
    r.update({"far_solar_cloud_600hpa": "0", "far_solar_cloud_400hpa": "0"})
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
            return 5 + offset if "historical-forecast" in url else 0
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
        # 30 dates: flagged&observed x12, flagged only x3, observed only x3, neither x12
        pattern = ([(True, True)] * 4 + [(True, False)] + [(False, True)] + [(False, False)] * 4) * 3
        for i, (f, o) in enumerate(pattern):
            evs.append({"date": f"2026-09-{i + 1:02d}", "weight": 1.0, "type": "SUNSET",
                        "out": {"H1": (f, o)}})
        evs.append({"date": "2026-10-01", "weight": 1.0, "type": "SUNSET", "out": {"H1": None}})
        s = size.hypothesis_stats(evs, "H1", total_prompted=100)
        self.assertEqual(s["n_events"], 30)
        self.assertAlmostEqual(s["precision"], 0.8)
        self.assertAlmostEqual(s["recall"], 0.8)
        self.assertAlmostEqual(s["base"], 0.5)
        self.assertAlmostEqual(s["lift"], 1.6)
        # observed rate among evaluable (0.5) x total gated weight (31, incl. the unevaluable) / 100
        self.assertAlmostEqual(s["failure_pop"], 0.5 * 31 / 100)
        self.assertLess(s["prec_ci"][0], 0.8 < s["prec_ci"][1] and 0.8)
        self.assertEqual(s["n_dates"], 30)
        again = size.hypothesis_stats(evs, "H1", total_prompted=100)
        self.assertEqual(s["lift_ci"], again["lift_ci"])             # fixed seed

    def test_weights_scale_estimates(self):
        evs = [{"date": "2026-09-01", "weight": 3.0, "out": {"H1": (True, True)}},
               {"date": "2026-09-02", "weight": 1.0, "out": {"H1": (True, False)}}]
        s = size.hypothesis_stats(evs, "H1", total_prompted=4)
        self.assertAlmostEqual(s["precision"], 3 / 4)
        self.assertTrue(math.isnan(s["failure_pop"]))   # 2 evaluable events: not evaluable

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
        # stored far mid/high + 500hPa present -> no pressure-level request; the layer arm (day 0,
        # Historical Forecast) is fetched only for the 165 and 320 km points H3 and H4 need
        hist = [x for x in fake.urls if "historical-forecast" in x]
        self.assertTrue(hist)
        self.assertFalse([x for x in hist if "hPa" in x])
        self.assertFalse([x for x in fake.urls if "previous-runs" in x or "_previous_day" in x])
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
        self.assertEqual(size.main([p, "--out", d, "--yes"], client=c2), 0)
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
        self.assertEqual(size.main([p, "--out", d, "--yes"], client=c), 2)
        self.assertEqual(c.live_requests, 1)


def write_csv(rows):
    import csv
    d = tempfile.mkdtemp()
    p = os.path.join(d, "f.csv")
    with open(p, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    return d, p


def ev(date, fl, ob, h="H1", w=1.0):
    return {"date": date, "weight": w, "type": "SUNSET", "out": {h: (fl, ob)}}


class GateAndEstimateTest(unittest.TestCase):
    def test_count_gate_counts_flagged_events_and_their_dates_unweighted(self):
        # 100 evaluable events over 30 dates, but only 10 flagged: the gate must FAIL
        evs = [ev(f"2026-09-{i % 30 + 1:02d}", i < 10, i % 2 == 0, w=3.0) for i in range(100)]
        st = size.hypothesis_stats(evs, "H1", 1000)
        self.assertEqual((st["n_events"], st["n_flagged_events"], st["n_flagged_dates"]), (100, 10, 10))
        self.assertFalse(size.checklist(st)[0][1])
        # 90 flagged over 30 dates: passes (unweighted, despite weight 3)
        evs = [ev(f"2026-09-{i % 30 + 1:02d}", i < 90, i % 2 == 0, w=3.0) for i in range(100)]
        st = size.hypothesis_stats(evs, "H1", 1000)
        self.assertEqual((st["n_flagged_events"], st["n_flagged_dates"]), (90, 30))
        self.assertTrue(size.checklist(st)[0][1])
        # 90 flagged on only 20 dates: fails the dates half
        evs = [ev(f"2026-09-{i % 20 + 1:02d}", True, i % 2 == 0) for i in range(90)]
        self.assertFalse(size.checklist(size.hypothesis_stats(evs, "H1", 1000))[0][1])

    def test_failure_population_scales_observed_rate_by_total_gated_weight(self):
        # 40 evaluable (half observed) + 60 not evaluable: gated weight 100, 200 prompted
        evs = [ev(f"2026-09-{i % 30 + 1:02d}", True, i % 2 == 0) for i in range(40)]
        evs += [{"date": "2026-10-01", "weight": 1.0, "type": "SUNSET", "out": {"H1": None}} for _ in range(60)]
        st = size.hypothesis_stats(evs, "H1", 200)
        self.assertAlmostEqual(st["failure_pop"], 0.5 * 100 / 200)       # the old figure was 20/200 = 0.1
        # weighted: observed share is weighted, scaled by total sampled weight
        evs = [ev("2026-09-01", True, True, w=4.0)] * 15 + [ev("2026-09-02", True, False, w=1.0)] * 15
        st = size.hypothesis_stats(evs, "H1", 1000)
        self.assertAlmostEqual(st["failure_pop"], (60 / 75) * 75 / 1000)

    def test_failure_population_not_evaluable_below_30_events(self):
        evs = [ev(f"2026-09-{i + 1:02d}", True, True) for i in range(29)]
        st = size.hypothesis_stats(evs, "H1", 100)
        self.assertTrue(math.isnan(st["failure_pop"]))
        self.assertIsNone(size.checklist(st)[2][1])
        self.assertIn("not evaluable", size.checklist(st)[2][0])
        st30 = size.hypothesis_stats(evs + [ev("2026-09-30", True, True)], "H1", 100)
        self.assertFalse(math.isnan(st30["failure_pop"]))

    def test_h5_rule_compares_lower_bound_to_union_upper_bound(self):
        h5 = {"h": "H5", "n_events": 50, "lift_ci": (2.2, 3.0)}
        un = {"h": "H1|H2", "n_events": 50, "lift_ci": (1.2, 2.1)}
        base = size.hypothesis_stats([ev(f"2026-09-{i % 30 + 1:02d}", True, True) for i in range(90)], "H5", 1000)
        base.update(h="H5")
        self.assertTrue(size.checklist(dict(base, **h5), (h5, un))[3][1])
        h5b = dict(h5, lift_ci=(2.0, 3.0))                   # lower bound 2.0 < union UPPER 2.1: fail
        self.assertFalse(size.checklist(dict(base, **h5b), (h5b, un))[3][1])
        # old rule compared against the union POINT lift: 2.0 > 1.5 would have passed
        un2 = dict(un, lift=1.5)
        self.assertFalse(size.checklist(dict(base, **h5b), (h5b, un2))[3][1])

    def test_h5_and_union_are_computed_on_the_same_events(self):
        rows = dataset(10, 2, 4)
        for r in rows:
            if r["loc_region_id"] != "1":     # H5 evaluable (600/500/400 all stored) only in region 1
                r["far_solar_cloud_600hpa"] = ""
                r["far_solar_cloud_400hpa"] = ""
        base = provider()

        def no_levels(var, la, lo, hour, url):  # the fetched pressure levels are not served
            return None if "hPa" in var else base(var, la, lo, hour, url)
        info, _ = EndToEndTest().run_size(rows, FakeOM(no_levels))
        h5, un = info["h5_pair"]
        self.assertGreater(h5["n_events"], 0)
        self.assertEqual(h5["n_events"], un["n_events"])
        self.assertLess(h5["n_events"], info["stats"][("H1|H2", "pooled")]["n_events"])

    def test_too_few_evaluable_events_is_not_evaluable_never_does_not_meet(self):
        few = size.hypothesis_stats([ev(f"2026-09-{i + 1:02d}", True, False, h="H3") for i in range(10)], "H3", 500)
        self.assertIn("not evaluable", size.verdict(few, size.checklist(few)))
        self.assertNotIn("NOT meet", size.verdict(few, size.checklist(few)))
        enough = size.hypothesis_stats([ev(f"2026-09-{i % 30 + 1:02d}", True, False, h="H3") for i in range(40)], "H3", 500)
        self.assertEqual(size.verdict(enough, size.checklist(enough)), "does NOT meet the bar")

    def test_report_says_not_evaluable_for_hypotheses_with_no_forecast_side(self):
        info, _ = EndToEndTest().run_size(dataset(10, 2, 4), FakeOM(provider()), fc_reliable=False)
        md = size.render(info)
        h3 = md.split("### H3")[1].split("### H4")[0]
        self.assertIn("verdict: not evaluable", h3)
        self.assertNotIn("does NOT meet", h3)


class BootstrapTest(unittest.TestCase):
    def test_calendar_days_include_empty_days(self):
        days = size.calendar_days(["2026-09-10", "2026-09-01", "2026-09-04"])
        self.assertEqual((len(days), days[0], days[-1]), (10, "2026-09-01", "2026-09-10"))
        self.assertEqual(size.calendar_days([]), [])

    def test_blocks_are_non_circular_uniform_over_valid_starts_and_original_length(self):
        import random
        rng = random.Random(1)
        seen = set()
        for _ in range(500):
            blocks = size.draw_blocks(12, 5, rng)
            self.assertEqual(sum(ln for _, ln in blocks), 12)             # original length, last trimmed
            self.assertEqual([ln for _, ln in blocks], [5, 5, 2])
            for st, ln in blocks:
                self.assertTrue(0 <= st <= 12 - 5)                         # never wraps past the end
                seen.add(st)
        self.assertEqual(seen, set(range(0, 8)))                           # every valid start reachable
        self.assertEqual(size.draw_blocks(3, 5, rng)[0][1], 3)              # block clipped to the series

    def test_gaps_in_the_calendar_count_as_days(self):
        # events on day 1 and day 30 only: a 5-day block over the CALENDAR rarely holds an event
        pd = {"2026-09-01": (1.0, 1.0, 1.0, 1.0), "2026-09-30": (1.0, 1.0, 1.0, 1.0)}
        r = size.block_bootstrap(pd, block=5, draws=300, seed=3)
        # many draws contain no event at all (blocks of empty days) -> far fewer valid than draws
        self.assertLess(r["lift"][2], 300)

    def test_stats_report_block_sensitivity_and_recall_ci(self):
        evs = [ev(f"2026-09-{i % 30 + 1:02d}", i % 3 != 0, i % 2 == 0) for i in range(90)]
        st = size.hypothesis_stats(evs, "H1", 500)
        self.assertEqual(sorted(st["boot_by_block"]), [3, 5, 7])
        for b in (3, 5, 7):
            lo, hi = st["boot_by_block"][b]["recall"]
            self.assertTrue(0 <= lo <= hi <= 1, (b, lo, hi))
        self.assertEqual(st["recall_ci"], st["boot_by_block"][5]["recall"])
        self.assertEqual(st["lift_ci"], st["boot_by_block"][5]["lift"])
        info, _ = EndToEndTest().run_size(dataset(), FakeOM(provider()))
        md = size.render(info)
        self.assertIn("block 3 | block 5 | block 7", md)
        self.assertIn("recall (bootstrap CI)", md)


class GeometryRoundingTest(unittest.TestCase):
    def test_azimuth_uses_java_half_up_rounding(self):
        import geometry
        r = row(1, 1, "2026-09-10", azimuth_deg="102.5")
        c = size.Ctx(r)
        self.assertEqual(c.cone, geometry.cone_points(c.lat, c.lon, 103))   # python round(102.5) is 102
        self.assertNotEqual(c.cone, geometry.cone_points(c.lat, c.lon, 102))


class ForecastSideTest(unittest.TestCase):
    def test_collect_needs_without_forecast_requests_only_era5(self):
        rows = dataset(2, 1, 4)[:4]
        for r in rows:  # every pressure level stored, so only a pilot needs Historical Forecast
            r["far_solar_cloud_600hpa"] = r["far_solar_cloud_400hpa"] = "0"
        ctxs = [size.Ctx(r) for r in rows]
        for fc in (True, False):
            for pilot in (False, True):
                srcs = {k[0][0] for k in size.collect_needs(ctxs, pilot, fc=fc)}
                self.assertEqual(srcs, {"era5", "fc", "hist"} if (fc and pilot) else
                                 ({"era5", "fc"} if fc else {"era5"}))

    def test_run_with_unreliable_forecast_never_touches_forecast_hosts(self):
        fake = FakeOM(provider())
        rows = dataset(4, 1, 4)
        for r in rows:
            r["far_solar_high_cloud"] = ""
            r["far_solar_cloud_500hpa"] = ""
        c = om.Client(tempfile.mkdtemp(), fetch=fake, pause_s=0)
        info = size.run(rows, c, fc_reliable=False)
        self.assertTrue(fake.urls)
        self.assertFalse([u for u in fake.urls if "historical-forecast" in u])
        self.assertFalse(info["fc_fetched"])

    def test_fail_closed_only_an_explicit_reliable_allows_fetched_values(self):
        self.assertTrue(size.forecast_side_reliable("reliable"))
        for v in ("unknown", "UNRELIABLE", None, "", "Reliable", "reliable-ish"):
            self.assertFalse(size.forecast_side_reliable(v))

    def test_pilot_with_too_few_pairs_is_unknown_and_stored_only(self):
        info, _ = EndToEndTest().run_size(dataset(10, 2, 4), FakeOM(provider(0)), pilot=10)
        self.assertEqual(info["pilot_rep"]["_verdict"], "unknown")
        self.assertFalse(info["fc_fetched"])

    def _main(self, verdict, extra=()):
        d, p = write_csv(dataset(4, 1, 4))
        out = os.path.join(d, "out")
        os.makedirs(out)
        if verdict is not None:
            with open(os.path.join(out, "pilot.json"), "w") as f:
                json.dump({"_verdict": verdict}, f)
        fake = FakeOM(provider())
        c = om.Client(tempfile.mkdtemp(), fetch=fake, pause_s=0)
        import contextlib, io
        with contextlib.redirect_stdout(io.StringIO()):
            rc = size.main([p, "--out", out, "--yes", *extra], client=c)
        return rc, fake, out

    def test_cli_auto_reads_the_pilot_verdict_fail_closed(self):
        for verdict in (None, "unknown", "UNRELIABLE", "garbage"):
            rc, fake, out = self._main(verdict)
            self.assertEqual(rc, 0)
            self.assertFalse([u for u in fake.urls if "historical-forecast" in u], verdict)
            with open(os.path.join(out, "part_c.md")) as f:
                self.assertIn("STORED values only", f.read())
        rc, fake, out = self._main("reliable")
        self.assertTrue([u for u in fake.urls if "historical-forecast" in u])
        rc, fake, out = self._main(None, ["--forecast", "stored"])
        self.assertFalse([u for u in fake.urls if "historical-forecast" in u])


class ProjectionGateTest(unittest.TestCase):
    def test_live_run_prints_projection_before_first_call_and_refuses_without_yes(self):
        import contextlib, io
        d, p = write_csv(dataset(3, 1, 4))
        buf = io.StringIO()
        seen = []
        fake = FakeOM(provider())

        def fetch(url):
            seen.append(buf.getvalue())
            return fake(url)
        c = om.Client(tempfile.mkdtemp(), fetch=fetch, pause_s=0)
        with contextlib.redirect_stdout(buf):
            rc = size.main([p, "--out", d], client=c)
        out = buf.getvalue()
        self.assertEqual((rc, seen, c.live_requests), (3, [], 0))          # refused, nothing fetched
        self.assertIn("projection:", out)
        self.assertRegex(out, r"projection: \d+ weighted calls")
        self.assertIn("--yes", out)
        self.assertIn(p, out)                                               # the command to re-run
        with contextlib.redirect_stdout(buf):
            rc = size.main([p, "--out", d, "--yes"], client=c)
        self.assertEqual(rc, 0)
        self.assertTrue(seen and "projection:" in seen[0])                  # printed BEFORE the first call
        self.assertFalse(os.path.exists(os.path.join(d, "pilot.md")))

    def test_projection_equals_what_the_run_then_spends(self):
        import contextlib, io
        d, p = write_csv(dataset(3, 1, 4))
        c = om.Client(tempfile.mkdtemp(), fetch=FakeOM(provider()), pause_s=0)
        proj = size.projection_client(c)
        size.run(load_size_rows(p), proj, plan_only=True, fc_reliable=False)   # no pilot.json: stored-only
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(size.main([p, "--out", d, "--yes"], client=c), 0)
        self.assertEqual(proj.projected_weight, c.weight_spent)
        self.assertEqual(proj.projected_requests, c.live_requests)

    def test_fully_cached_run_needs_no_yes(self):
        import contextlib, io
        d, p = write_csv(dataset(3, 1, 4))
        cache = tempfile.mkdtemp()
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(size.main([p, "--out", d, "--yes"], client=om.Client(cache, fetch=FakeOM(provider()), pause_s=0)), 0)
            c2 = om.Client(cache, fetch=FakeOM(provider()), pause_s=0)
            self.assertEqual(size.main([p, "--out", d], client=c2), 0)
        self.assertEqual(c2.live_requests, 0)

    def test_pilot_writes_pilot_md_not_part_c(self):
        import contextlib, io
        d, p = write_csv(dataset(10, 2, 4))
        c = om.Client(tempfile.mkdtemp(), fetch=FakeOM(provider()), pause_s=0)
        with contextlib.redirect_stdout(io.StringIO()):
            rc = size.main([p, "--out", d, "--pilot", "30", "--yes"], client=c)
        self.assertEqual(rc, 0)
        self.assertTrue(os.path.exists(os.path.join(d, "pilot.md")))
        self.assertTrue(os.path.exists(os.path.join(d, "pilot.json")))
        self.assertFalse(os.path.exists(os.path.join(d, "part_c.md")))

    def test_corrupt_budget_file_refuses_the_cli(self):
        import contextlib, io
        d, p = write_csv(dataset(2, 1, 4))
        cache = tempfile.mkdtemp()
        with open(os.path.join(cache, "budget.json"), "w") as f:
            f.write("{oops")
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = size.main([p, "--out", d, "--yes", "--cache", cache])
        self.assertEqual(rc, 2)
        self.assertIn("REFUSING", buf.getvalue())

    def test_no_wait_flag_reaches_the_client_and_stops_cleanly(self):
        import contextlib, io
        d, p = write_csv(dataset(3, 1, 4))
        cache = tempfile.mkdtemp()
        c = om.Client(cache, fetch=FakeOM(provider()), pause_s=0, hourly_cap=2, wait_for_hour=False)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = size.main([p, "--out", d, "--yes", "--no-wait"], client=c)
        self.assertEqual(rc, 2)
        self.assertIn("BUDGET EXHAUSTED", buf.getvalue())


if __name__ == "__main__":
    unittest.main()


class CodexFindingsTest(unittest.TestCase):
    def test_missing_600_or_400_hpa_fetches_pressure_levels_even_with_500_stored(self):
        for gap in ("far_solar_cloud_600hpa", "far_solar_cloud_400hpa"):
            r = dataset(1, 1, 4)[0]
            r["far_solar_cloud_500hpa"] = "0"
            r["far_solar_cloud_600hpa"] = r["far_solar_cloud_400hpa"] = "0"
            r[gap] = ""
            srcs = {k[0][0] for k in size.collect_needs([size.Ctx(r)], False, fc=True)}
            self.assertIn("hist", srcs, gap)

    def test_training_cases_never_enter_the_population(self):
        rows = dataset(4, 1, 4)
        for r in rows:
            r["loc_name"] = "Angel of the North"
            r["target_date"] = "2026-10-13"
            r["target_type"] = "SUNSET"
        self.assertTrue(all(size.is_training(r) for r in rows))
        prompted = [r for r in size.latest_prompted(rows) if not size.is_training(r)]
        self.assertEqual(prompted, [])
