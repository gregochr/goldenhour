import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
import openmeteo as om  # noqa: E402
import probe  # noqa: E402
from om_fake import FakeOM  # noqa: E402

FIX = os.path.join(HERE, "fixtures")


def fixture(name):
    with open(os.path.join(FIX, name)) as f:
        return f.read()


def client(fetch, **kw):
    d = tempfile.mkdtemp()
    kw.setdefault("pause_s", 0)
    return om.Client(d, fetch=fetch, **kw)


class WeightTest(unittest.TestCase):
    def test_formula(self):
        self.assertEqual(om.weight(1, 1, 1), 1)
        self.assertEqual(om.weight(1, 10, 14), 1)
        self.assertEqual(om.weight(1, 11, 14), 2)
        self.assertEqual(om.weight(1, 10, 15), 2)
        self.assertEqual(om.weight(20, 3, 1), 20)
        self.assertEqual(om.weight(5, 12, 30), 5 * 2 * 3)

    def test_prev_var(self):
        self.assertEqual(om.prev_var("cloud_cover_low", 0), "cloud_cover_low")
        self.assertEqual(om.prev_var("cloud_cover_low", 1), "cloud_cover_low_previous_day1")
        with self.assertRaises(ValueError):
            om.prev_var("x", -1)


class UrlTest(unittest.TestCase):
    def test_shape(self):
        u = om.build_url("previous_runs", [(54.91234567, -1.5), (50.0, 0.0)], ["cloud_cover_low"],
                         "2026-10-13", "2026-10-13", models="ukmo_seamless")
        self.assertTrue(u.startswith("https://previous-runs-api.open-meteo.com/v1/forecast?"))
        self.assertIn("latitude=54.9123,50.0000", u)
        self.assertIn("timezone=UTC", u)
        self.assertIn("start_date=2026-10-13", u)
        self.assertIn("models=ukmo_seamless", u)
        self.assertNotIn("past_days", u)

    def test_archive_always_era5(self):
        u = om.build_url("archive", [(1, 2)], ["cloud_cover_mid"], "2026-10-01", "2026-10-01")
        self.assertIn("models=era5", u)
        u2 = om.build_url("archive", [(1, 2)], ["cloud_cover_mid"], "2026-10-01", "2026-10-01", models="")
        self.assertNotIn("models=", u2)

    def test_max_coords(self):
        with self.assertRaises(ValueError):
            om.build_url("forecast", [(i, 0) for i in range(21)], ["a"], "2026-01-01", "2026-01-01")

    def test_hosts(self):
        self.assertIn("archive-api.open-meteo.com/v1/archive", om.HOSTS["archive"])
        self.assertIn("historical-forecast-api.open-meteo.com/v1/forecast", om.HOSTS["historical_forecast"])


class ParseTest(unittest.TestCase):
    def test_object_one_coordinate(self):
        pts = om.parse_body(fixture("om_single.json"))
        self.assertEqual(len(pts), 1)
        self.assertEqual(pts[0].value("2026-10-13T01:00", "cloud_cover_low"), 10)
        self.assertIsNone(pts[0].value("2026-10-13T02:00", "cloud_cover_low"))

    def test_array_several(self):
        pts = om.parse_body(fixture("om_array.json"))
        self.assertEqual([p.value("2026-10-13T00:00", "cloud_cover_low") for p in pts], [1, 4])
        self.assertEqual(pts[1].latitude, 55.5)

    def test_all_null_flagged_but_zero_is_not(self):
        p = om.parse_body(fixture("om_all_null.json"))[0]
        self.assertIn("cloud_cover_700hPa_previous_day1", p.not_served)
        self.assertNotIn("cloud_cover_low", p.not_served)   # all zeros: a real clear sky
        self.assertTrue(p.served("cloud_cover_low"))
        self.assertFalse(p.served("cloud_cover_700hPa_previous_day1"))

    def test_error_body(self):
        with self.assertRaises(om.OpenMeteoError):
            om.parse_body(fixture("om_error_limit.json"))


class ClientTest(unittest.TestCase):
    def test_cache_hit_costs_nothing_and_errors_never_cached(self):
        calls = []
        def fetch(url):
            calls.append(url)
            return 200, fixture("om_single.json")
        c = client(fetch)
        a = c.get_one("archive", (54.9, -1.6), ["cloud_cover_low"], "2026-10-13", "2026-10-13")
        b = c.get_one("archive", (54.9, -1.6), ["cloud_cover_low"], "2026-10-13", "2026-10-13")
        self.assertEqual(len(calls), 1)
        self.assertEqual(c.cache_hits, 1)
        self.assertEqual(c.used_today(), 1)
        self.assertEqual(a.hours, b.hours)

    def test_error_body_never_cached_and_limit_stops(self):
        c = client(lambda u: (200, fixture("om_error_limit.json")))
        with self.assertRaises(om.RateLimited):
            c.get_one("forecast", (1, 2), ["cloud_cover_low"], "2026-10-13", "2026-10-13")
        self.assertEqual([f for f in os.listdir(c.cache_dir) if f != "budget.json"], [])
        # non-limit error: also not cached, raised as OpenMeteoError
        c2 = client(lambda u: (400, fixture("om_error_variable.json")))
        with self.assertRaises(om.OpenMeteoError):
            c2.get_one("previous_runs", (1, 2), ["x"], "2026-10-13", "2026-10-13")
        self.assertEqual([f for f in os.listdir(c2.cache_dir) if f != "budget.json"], [])

    def test_non_200_not_cached(self):
        c = client(lambda u: (500, fixture("om_single.json")))
        with self.assertRaises(om.OpenMeteoError):
            c.get_one("forecast", (1, 2), ["cloud_cover_low"], "2026-10-13", "2026-10-13")
        self.assertEqual([f for f in os.listdir(c.cache_dir) if f != "budget.json"], [])

    def test_budget_stops_cleanly_and_persists_by_date(self):
        fake = FakeOM()
        d = tempfile.mkdtemp()
        today = ["2026-10-14"]
        c = om.Client(d, budget=2, fetch=fake, pause_s=0, today=lambda: today[0])
        c.get_one("archive", (1, 2), ["cloud_cover_low"], "2026-10-01", "2026-10-01")
        c.get_one("archive", (3, 4), ["cloud_cover_low"], "2026-10-01", "2026-10-01")
        with self.assertRaises(om.BudgetExhausted):
            c.get_one("archive", (5, 6), ["cloud_cover_low"], "2026-10-01", "2026-10-01")
        self.assertEqual(len(fake.urls), 2)
        with open(os.path.join(d, "budget.json")) as f:
            self.assertEqual(json.load(f), {"date": "2026-10-14", "used": 2})
        today[0] = "2026-10-15"   # a new UTC day resets the counter
        c.get_one("archive", (5, 6), ["cloud_cover_low"], "2026-10-01", "2026-10-01")
        self.assertEqual(c.used_today(), 1)

    def test_dry_run_counts_only(self):
        fake = FakeOM()
        c = client(fake, dry_run=True)
        pts = c.get("archive", [(i, 0) for i in range(25)], om.CLOUD_VARS, "2026-10-01", "2026-10-01")
        self.assertEqual(len(pts), 25)
        self.assertEqual(fake.urls, [])
        self.assertEqual(c.projected_requests, 2)         # 20 + 5
        self.assertEqual(c.projected_weight, 25)
        self.assertEqual(c.used_today(), 0)

    def test_chunks_sorts_and_preserves_input_order(self):
        fake = FakeOM(lambda v, la, lo, h, u: la)
        c = client(fake)
        coords = [(60.0 - i, 0.0) for i in range(25)]    # descending
        pts = c.get("forecast", coords, ["cloud_cover_low"], "2026-10-01", "2026-10-01")
        self.assertEqual(len(fake.urls), 2)
        for (la, _), p in zip(coords, pts):
            self.assertEqual(p.value("2026-10-01T00:00", "cloud_cover_low"), la)
        for u in fake.urls:   # latitudes ascending within each request
            lats = [float(x) for x in u.split("latitude=")[1].split("&")[0].split(",")]
            self.assertEqual(lats, sorted(lats))

    def test_pause_between_live_requests(self):
        sleeps = []
        d = tempfile.mkdtemp()
        c = om.Client(d, fetch=FakeOM(), pause_s=3, sleep=sleeps.append)
        c.get_one("archive", (1, 2), ["cloud_cover_low"], "2026-10-01", "2026-10-01")
        c.get_one("archive", (3, 4), ["cloud_cover_low"], "2026-10-01", "2026-10-01")
        self.assertEqual(sleeps, [3])    # none before the first, 3 s before the second

    def test_urls_never_contain_past_days_or_previous_day0(self):
        fake = FakeOM()
        c = client(fake)
        c.get_one("previous_runs", (1, 2), [om.prev_var("cloud_cover_low", 0), om.prev_var("cloud_cover_low", 1)],
                  "2026-10-01", "2026-10-02", models="best_match")
        c.get_one("archive", (1, 2), om.CLOUD_VARS, "2026-10-01", "2026-10-01")
        for u in fake.urls:
            self.assertNotIn("past_days", u)
            self.assertNotIn("_previous_day0", u)
        self.assertIn("models=era5", fake.urls[1])
        self.assertIn("cloud_cover_low_previous_day1", fake.urls[0])


class ProbeTest(unittest.TestCase):
    def test_probe_passes_against_a_cooperative_fake_and_fails_on_errors(self):
        from datetime import date

        def provider(v, la, lo, h, u):
            if "previous_runs" in u and "700hPa" in u:
                return None
            return 10
        class Fake(FakeOM):
            def __call__(self, url):
                if "previous-runs" in url and "700hPa" in url or "_previous_day0" in url:
                    return 400, fixture("om_error_variable.json")
                return super().__call__(url)
        c = client(Fake(provider))
        res = probe.run(c, date(2026, 10, 14))
        fails = [r for r in res if r[0] == "FAIL"]
        self.assertEqual(fails, [], fails)
        self.assertLess(c.weight_spent, 60)
        c2 = client(lambda u: (400, fixture("om_error_variable.json")))
        res2 = probe.run(c2, date(2026, 10, 14))
        self.assertTrue(any(r[0] == "FAIL" for r in res2))
        self.assertEqual(probe.main(["--cache", tempfile.mkdtemp()], client=c2), 1)


if __name__ == "__main__":
    unittest.main()
