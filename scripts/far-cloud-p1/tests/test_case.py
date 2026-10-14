import csv
import os
import sys
import tempfile
import unittest
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
import case  # noqa: E402
import geometry  # noqa: E402
import openmeteo as om  # noqa: E402
from om_fake import FakeOM  # noqa: E402

ANGEL = (54.914, -1.589, "2026-10-13", "SUNSET")


class HoursTest(unittest.TestCase):
    def test_angel_default_hours_16_17_18(self):
        ev = case.event_time(*ANGEL[:2], ANGEL[2], "SUNSET")
        self.assertEqual(ev.strftime("%H"), "17")
        self.assertEqual(case.case_hours(ev, ANGEL[2], "SUNSET"),
                         ["2026-10-13T16:00", "2026-10-13T17:00", "2026-10-13T18:00"])

    def test_sunrise_picks_slot_at_or_after(self):
        ev = case.event_time(54.9, -1.6, "2026-10-13", "SUNRISE")
        hrs = case.case_hours(ev, "2026-10-13", "SUNRISE")
        self.assertEqual(len(hrs), 3)
        self.assertGreaterEqual(datetime.strptime(hrs[1], "%Y-%m-%dT%H:%M"), ev.replace(microsecond=0, second=0, minute=0))

    def test_each_hour_has_own_azimuth_and_17_points(self):
        azs = []
        for h in ["2026-10-13T16:00", "2026-10-13T17:00", "2026-10-13T18:00"]:
            elev, az, rows = case.hour_transect(54.914, -1.589, h)
            azs.append(az)
            self.assertEqual([r[0] for r in rows], [i * 25.0 for i in range(17)])
            self.assertEqual(rows[0][1], (54.914, -1.589))
        self.assertEqual(azs, sorted(azs))           # sun moves round to the west
        self.assertGreater(azs[2] - azs[0], 10)
        self.assertLess(azs[0], 250)


class SunLineTest(unittest.TestCase):
    def test_height_formula(self):
        self.assertAlmostEqual(case.sun_line_height_km(0, 5), 0.1)
        # 113 km at 0.9 deg: 0.1 + 113*tan(.9deg) + 113^2/12742 = ~2.87 km (parent plan: ~2.8)
        self.assertAlmostEqual(case.sun_line_height_km(113, 0.9), 2.87, delta=0.05)

    def test_band_overlap(self):
        self.assertTrue(case.band_overlaps("low", 2.9))
        self.assertFalse(case.band_overlaps("low", 3.1))
        self.assertTrue(case.band_overlaps("mid", 3.1))
        self.assertTrue(case.band_overlaps("high", 12))
        self.assertFalse(case.band_overlaps("high", 7.9))
        self.assertFalse(case.band_overlaps("low", -0.5))

    def test_the_91_percent_deck_at_113km_is_not_on_the_sun_line(self):
        # high deck 91% at ~113 km, sun at 0.9 deg: line is ~2.9 km, high band is 8+ km: not flagged
        vals = {112.5: {"low": 7, "mid": 24, "high": 91}}
        self.assertEqual(case.flag_points(vals, 0.9), [])
        # a low deck 70% at the same place IS on the line
        fl = case.flag_points({112.5: {"low": 70, "mid": 0, "high": 0}}, 0.9)
        self.assertEqual([(d, l) for d, l, _, _ in fl], [(112.5, "low")])

    def test_far_high_deck_is_flagged_where_line_climbs(self):
        # 350 km at 0.9 deg: 0.1 + 5.5 + 9.6 = 15.2 km: inside the high band
        fl = case.flag_points({350: {"low": 0, "mid": 0, "high": 80}}, 0.9)
        self.assertEqual(len(fl), 1)
        self.assertEqual(fl[0][1], "high")

    def test_below_threshold_not_flagged(self):
        self.assertEqual(case.flag_points({350: {"high": 59}}, 0.9), [])


class BuildCaseTest(unittest.TestCase):
    def test_end_to_end_with_fake_api(self):
        def provider(v, la, lo, h, url):
            if "cloud_cover_high" == v and "archive" in url:
                return 91
            return 5
        fake = FakeOM(provider)
        c = om.Client(tempfile.mkdtemp(), fetch=fake, pause_s=0)
        cs = case.build_case(c, *ANGEL[:2], ANGEL[2], "SUNSET", ["best_match", "ukmo_seamless"])
        self.assertEqual(len(cs["hours"]), 3)
        for u in fake.urls:
            self.assertNotIn("past_days", u)
            self.assertNotIn("_previous_day0", u)
        archive = [u for u in fake.urls if "archive-api" in u]
        self.assertTrue(archive and all("models=era5" in u for u in archive))
        prev = [u for u in fake.urls if "previous-runs" in u]
        self.assertGreaterEqual(len(prev), 2)  # 20 coords per request, per model
        self.assertTrue(all("cloud_cover_low_previous_day1" in u for u in prev))
        hist = [u for u in fake.urls if "historical-forecast" in u]
        self.assertTrue(hist and "cloud_cover_850hPa" in hist[0])
        out = tempfile.mkdtemp()
        cp, mp = case.write_outputs(cs, out)
        self.assertTrue(cp.endswith("case_2026-10-13.csv"))
        with open(cp, newline="") as f:
            rows = list(csv.DictReader(f))
        sources = {r["source"] for r in rows}
        self.assertEqual(sources, {"era5", "prev:best_match:day0", "prev:best_match:day1",
                                   "prev:ukmo_seamless:day0", "prev:ukmo_seamless:day1", "hist:pressure"})
        # 3 hours x 17 distances x 6 sources
        self.assertEqual(len(rows), 3 * 17 * 6)
        era = [r for r in rows if r["source"] == "era5"]
        self.assertTrue(all(r["high"] == "91" and r["p850"] == "" for r in era))
        md = open(mp).read()
        self.assertIn("What was on the sun line", md)
        self.assertIn("15:30-17:20 UTC", md)
        self.assertIn("Meteosat", md)
        self.assertIn("17:00 UTC", md)

    def test_dedupes_by_grid_cell_across_hours(self):
        fake = FakeOM()
        c = om.Client(tempfile.mkdtemp(), fetch=fake, pause_s=0)
        cs = case.build_case(c, *ANGEL[:2], ANGEL[2], "SUNSET", ["best_match"])
        self.assertEqual(len(cs["keys"]), len(set(cs["keys"])))
        # observer cell is shared by all three hours -> fewer unique cells than 3*17
        self.assertLess(len(cs["keys"]), 3 * 17)

    def test_rejected_model_is_skipped_not_fatal(self):
        class F(FakeOM):
            def __call__(self, url):
                if "models=bad_model" in url:
                    return 400, '{"error": true, "reason": "unknown model"}'
                return super().__call__(url)
        c = om.Client(tempfile.mkdtemp(), fetch=F(), pause_s=0)
        cs = case.build_case(c, *ANGEL[:2], ANGEL[2], "SUNSET", ["bad_model", "best_match"])
        self.assertIn("prev:best_match:day1", cs["sources"])
        self.assertNotIn("prev:bad_model:day0", cs["sources"])
        self.assertTrue(cs["notes"])

    def test_cli_dry_run_writes_nothing(self):
        out = tempfile.mkdtemp()
        c = om.Client(tempfile.mkdtemp(), fetch=FakeOM(), pause_s=0, dry_run=True)
        self.assertEqual(case.main(["--out", out, "--dry-run"], client=c), 0)
        self.assertEqual(os.listdir(out), [])
        self.assertGreater(c.projected_weight, 0)


if __name__ == "__main__":
    unittest.main()
