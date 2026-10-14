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
            b = json.load(f)
        self.assertEqual((b["date"], b["used"], b["hour_used"]), ("2026-10-14", 2, 2))
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


def cache_files(c):
    return [f for f in os.listdir(c.cache_dir) if f != "budget.json"]


def body_with(series_by_var, lat=54.9, lon=-1.6):
    hours = [f"2026-09-01T{h:02d}:00" for h in range(24)]
    return json.dumps({"latitude": lat, "longitude": lon,
                       "hourly": dict({"time": hours}, **{v: [x] * 24 for v, x in series_by_var.items()})})


class CachePolicyTest(unittest.TestCase):
    def test_all_null_body_is_never_cached(self):
        calls = []

        def fetch(url):
            calls.append(url)
            return 200, body_with({"cloud_cover_low": None, "cloud_cover_mid": None})
        c = client(fetch)
        args = ("archive", (54.9, -1.6), ["cloud_cover_low", "cloud_cover_mid"], "2026-09-01", "2026-09-01")
        p = c.get_one(*args)                      # still returned to the caller
        self.assertEqual(p.not_served, {"cloud_cover_low", "cloud_cover_mid"})
        c.get_one(*args)
        self.assertEqual(len(calls), 2)           # refetched: nulls were not cached
        self.assertEqual(cache_files(c), [])

    def test_body_without_hourly_is_never_cached(self):
        c = client(lambda u: (200, json.dumps({"latitude": 1, "longitude": 2})))
        c.get_one("archive", (1, 2), ["cloud_cover_low"], "2026-09-01", "2026-09-01")
        self.assertEqual(cache_files(c), [])

    def test_partly_served_body_is_cached(self):
        c = client(lambda u: (200, body_with({"cloud_cover_low": 5, "cloud_cover_mid": None})))
        c.get_one("archive", (1, 2), ["cloud_cover_low", "cloud_cover_mid"], "2026-09-01", "2026-09-01")
        self.assertEqual(len(cache_files(c)), 1)

    def test_recent_archive_cached_with_24h_ttl_stored_in_entry(self):
        from datetime import datetime, timedelta, timezone
        now = [datetime(2026, 10, 14, 12, 0, tzinfo=timezone.utc)]
        calls = []

        def fetch(url):
            calls.append(url)
            return 200, body_with({"cloud_cover_low": 5})
        d = tempfile.mkdtemp()
        c = om.Client(d, fetch=fetch, pause_s=0, now=lambda: now[0])
        args = ("archive", (54.9, -1.6), ["cloud_cover_low"], "2026-10-10", "2026-10-10")  # 4 days old
        c.get_one(*args)
        c.get_one(*args)
        self.assertEqual(len(calls), 1)
        entry = json.load(open(os.path.join(d, cache_files(c)[0])))
        self.assertEqual(entry["expires"], (now[0] + timedelta(hours=24)).isoformat())
        now[0] += timedelta(hours=25)               # expired -> a miss
        c.get_one(*args)
        self.assertEqual(len(calls), 2)

    def test_old_archive_and_other_endpoints_have_no_ttl(self):
        from datetime import datetime, timedelta, timezone
        now = [datetime(2026, 10, 14, 12, 0, tzinfo=timezone.utc)]
        d = tempfile.mkdtemp()
        c = om.Client(d, fetch=lambda u: (200, body_with({"cloud_cover_low": 5})), pause_s=0,
                      now=lambda: now[0])
        c.get_one("archive", (1, 2), ["cloud_cover_low"], "2026-09-01", "2026-09-01")      # 43 days old
        c.get_one("previous_runs", (1, 2), ["cloud_cover_low"], "2026-10-13", "2026-10-13")  # recent, not archive
        for f in cache_files(c):
            self.assertIsNone(json.load(open(os.path.join(d, f)))["expires"])

    def test_corrupt_or_legacy_cache_files_are_misses(self):
        calls = []

        def fetch(url):
            calls.append(url)
            return 200, body_with({"cloud_cover_low": 5})
        c = client(fetch)
        args = ("archive", (1, 2), ["cloud_cover_low"], "2026-09-01", "2026-09-01")
        c.get_one(*args)
        path = os.path.join(c.cache_dir, cache_files(c)[0])
        for junk in ("{not json", "", json.dumps(body_with({"cloud_cover_low": 5})),   # legacy raw body
                     json.dumps({"v": 1, "expires": "garbage", "node": {}})):
            with open(path, "w") as f:
                f.write(junk)
            n = len(calls)
            c.get_one(*args)
            self.assertEqual(len(calls), n + 1, junk)

    def test_cache_writes_are_atomic(self):
        from unittest import mock
        c = client(FakeOM())
        with mock.patch.object(om.os, "replace", wraps=os.replace) as rep:
            c.get_one("archive", (1, 2), ["cloud_cover_low"], "2026-09-01", "2026-09-01")
        self.assertGreaterEqual(rep.call_count, 2)            # one cache entry + the budget file
        self.assertFalse([f for f in os.listdir(c.cache_dir) if f.endswith(".tmp")])
        with mock.patch.object(om.os, "replace", side_effect=OSError("boom")):
            with self.assertRaises(OSError):
                om.atomic_write(os.path.join(c.cache_dir, "x.json"), "{}")
        self.assertFalse([f for f in os.listdir(c.cache_dir) if f.endswith(".tmp")])
        self.assertFalse(os.path.exists(os.path.join(c.cache_dir, "x.json")))


class CacheGranularityTest(unittest.TestCase):
    def test_adding_one_coordinate_costs_one_request_not_a_reshuffle(self):
        fake = FakeOM()
        c = client(fake)
        coords = [(50.0 + i * 0.5, -2.0) for i in range(25)]
        c.get("archive", coords, om.CLOUD_VARS, "2026-09-01", "2026-09-01")
        self.assertEqual(len(fake.urls), 2)                      # 20 + 5
        extra = coords + [(50.25, -2.0)]                         # lands in the MIDDLE of the sort
        pts = c.get("archive", extra, om.CLOUD_VARS, "2026-09-01", "2026-09-01")
        self.assertEqual(len(fake.urls), 3)
        self.assertEqual(fake.urls[2].count("latitude=50.2500&") + fake.urls[2].count("latitude=50.2500,"), 1)
        self.assertEqual(len(fake.urls[2].split("latitude=")[1].split("&")[0].split(",")), 1)
        self.assertEqual(len(pts), 26)

    def test_values_follow_their_own_coordinate_across_chunks_and_cache(self):
        from om_fake import coord_value, default_provider
        fake = FakeOM()
        c = client(fake)
        coords = [(60.0 - i * 0.3, -1.0 + (i % 7) * 0.7) for i in range(45)]   # descending, 3 chunks
        for _ in range(2):                                                      # second pass: all cached
            pts = c.get("archive", coords, ["cloud_cover_low"], "2026-09-01", "2026-09-01")
            for (la, lo), p in zip(coords, pts):
                la, lo = om.round_coord(la, lo)
                self.assertEqual(p.value("2026-09-01T07:00", "cloud_cover_low"),
                                 default_provider("cloud_cover_low", la, lo, "2026-09-01T07:00", ""))
        self.assertEqual(len(fake.urls), 3)
        self.assertNotEqual(coord_value(54.0, -1.0, "2026-09-01T07:00"), coord_value(54.0, -1.1, "2026-09-01T07:00"))


class LimitTest(unittest.TestCase):
    def mk(self, **kw):
        from datetime import datetime, timezone
        d = tempfile.mkdtemp()
        sleeps = []
        kw.setdefault("now", lambda: datetime(2026, 10, 14, 10, 30, tzinfo=timezone.utc))
        fake = kw.pop("fetch", None) or FakeOM()
        c = om.Client(d, fetch=fake, pause_s=0, sleep=sleeps.append, **kw)
        return c, fake, sleeps

    def four(self, c, i):
        c.get("archive", [(50.0 + i, 1.0 + k) for k in range(4)], ["cloud_cover_low"], "2026-09-01", "2026-09-01")

    def test_hourly_cap_sleeps_to_next_hour_and_prints(self):
        import contextlib
        import io
        c, fake, sleeps = self.mk(hourly_cap=10)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            for i in range(3):
                self.four(c, i)
        self.assertEqual(sleeps, [30 * 60 + 2])                  # 10:30 -> 11:00 (+2 s margin)
        self.assertIn("hourly cap", buf.getvalue())
        self.assertEqual(len(fake.urls), 3)
        self.assertEqual(c.used_today(), 12)
        self.assertEqual(c.used_this_hour(), 4)                  # the counter restarted

    def test_hourly_cap_no_wait_stops_cleanly(self):
        c, fake, sleeps = self.mk(hourly_cap=10, wait_for_hour=False)
        self.four(c, 0)
        self.four(c, 1)
        with self.assertRaises(om.HourlyBudgetExhausted):
            self.four(c, 2)
        self.assertTrue(issubclass(om.HourlyBudgetExhausted, om.BudgetExhausted))
        self.assertEqual((len(fake.urls), sleeps), (2, []))
        self.assertEqual(c.used_today(), 8)

    def test_hour_counter_persisted_with_daily_one(self):
        c, _, _ = self.mk()
        self.four(c, 0)
        b = json.load(open(os.path.join(c.cache_dir, "budget.json")))
        self.assertEqual((b["used"], b["hour_used"], b["hour"]), (4, 4, "2026-10-14T10"))
        c2 = om.Client(c.cache_dir, fetch=FakeOM(), pause_s=0, now=c._now)    # a new process
        self.assertEqual(c2.used_this_hour(), 4)

    def test_default_caps_are_4500_per_hour_and_500_per_minute(self):
        self.assertEqual((om.HOURLY_CAP, om.MINUTE_CAP), (4500, 500))

    def test_minute_cap_waits(self):
        c, fake, sleeps = self.mk(minute_cap=10, mono=lambda: 1000.0)
        for i in range(3):
            self.four(c, i)
        self.assertEqual(sleeps, [60.0])

    def test_rate_limited_rejection_is_not_charged(self):
        c, _, _ = self.mk(fetch=lambda u: (200, fixture("om_error_limit.json")))
        with self.assertRaises(om.RateLimited):
            self.four(c, 0)
        self.assertEqual((c.used_today(), c.used_this_hour(), c.weight_spent), (0, 0, 0))
        c2, _, _ = self.mk(fetch=lambda u: (429, '{"reason": "slow down"}'))
        with self.assertRaises(om.RateLimited):
            self.four(c2, 0)
        self.assertEqual((c2.used_today(), c2.weight_spent), (0, 0))
        c3, _, _ = self.mk(fetch=lambda u: (429, "<html>429</html>"))
        with self.assertRaises(om.RateLimited):
            self.four(c3, 0)
        self.assertEqual(c3.used_today(), 0)

    def test_network_failure_is_not_charged_and_is_clear(self):
        import urllib.error
        import socket
        from unittest import mock
        for exc in (urllib.error.URLError("dns down"), socket.timeout("timed out"), ConnectionResetError("reset")):
            with mock.patch.object(om.urllib.request, "urlopen", side_effect=exc):
                c, _, _ = self.mk(fetch=om.default_fetch)
                with self.assertRaises(om.NetworkError) as cm:
                    self.four(c, 0)
            self.assertIn("network error", str(cm.exception))
            self.assertEqual((c.used_today(), c.weight_spent), (0, 0))

    def test_corrupt_budget_file_fails_closed(self):
        d = tempfile.mkdtemp()
        for junk in ("{broken", "", "[]", json.dumps({"date": "2026-10-14"}),
                     json.dumps({"date": "2026-10-14", "used": "lots"})):
            with open(os.path.join(d, "budget.json"), "w") as f:
                f.write(junk)
            calls = []
            with self.assertRaises(om.BudgetFileCorrupt) as cm:
                om.Client(d, fetch=lambda u: calls.append(u) or (200, "{}"), pause_s=0)
            self.assertIn("Refusing", str(cm.exception))
            self.assertEqual(calls, [])
            self.assertEqual(open(os.path.join(d, "budget.json")).read(), junk)    # not reset


class ProbeShapesTest(unittest.TestCase):
    class Coop(FakeOM):
        def __call__(self, url):
            if "previous-runs" in url and "700hPa" in url or "_previous_day0" in url:
                return 400, fixture("om_error_variable.json")
            return super().__call__(url)

    def names(self, res, prefix):
        return [r for r in res if r[1].startswith(prefix)]

    def test_new_shapes_pass_and_hit_the_intended_requests(self):
        from datetime import date
        fake = self.Coop(lambda v, la, lo, h, u: 10)
        c = client(fake)
        res = probe.run(c, date(2027, 3, 1))
        for pre in ("(i)", "(ii)", "(iii)"):
            self.assertEqual([r[0] for r in self.names(res, pre)], ["PASS"], pre)
        prev = [u for u in fake.urls if "previous-runs" in u and "start_date=2027-01-30" in u and "models=best_match" in u
                and "latitude=54.9140&" in u]
        self.assertTrue(any("cloud_cover_low_previous_day1,cloud_cover_mid_previous_day1,cloud_cover_high_previous_day1" in u
                            for u in prev))
        self.assertTrue(any("hourly=cloud_cover_low,cloud_cover_mid,cloud_cover_high&" in u for u in prev))
        arch20 = [u for u in fake.urls if "archive-api" in u and "start_date=2027-02-09" in u
                  and len(u.split("latitude=")[1].split("&")[0].split(",")) == 20]
        self.assertEqual(len(arch20), 1)
        self.assertIn("models=era5", arch20[0])
        self.assertLess(c.weight_spent, 60)

    def test_dates_are_relative_except_the_angel_check(self):
        from datetime import date
        fake = self.Coop(lambda v, la, lo, h, u: 10)
        probe.run(client(fake), date(2027, 3, 1))
        for u in fake.urls:
            if "historical-forecast" in u and "cloud_cover_850hPa" in u:
                self.assertIn("start_date=2026-10-13", u)          # (e): explicit Angel hour
            else:
                self.assertNotIn("2026-10-1", u)

    def test_new_shapes_fail_on_unserved_variable_and_broken_order(self):
        from datetime import date

        class NullMid(self.Coop):
            def __call__(self, url):
                st, body = super().__call__(url)
                if "previous-runs" in url and st == 200:
                    obj = json.loads(body)
                    if "cloud_cover_mid_previous_day1" in obj.get("hourly", {}):
                        obj["hourly"]["cloud_cover_mid_previous_day1"] = [None] * 24
                    body = json.dumps(obj)
                return st, body
        res = probe.run(client(NullMid(lambda v, la, lo, h, u: 10)), date(2027, 3, 1))
        self.assertEqual([r[0] for r in self.names(res, "(i)")], ["FAIL"])
        self.assertEqual([r[0] for r in self.names(res, "(ii)")], ["PASS"])

        class Reversed(self.Coop):
            def __call__(self, url):
                st, body = super().__call__(url)
                if "archive-api" in url and len(url.split("latitude=")[1].split("&")[0].split(",")) == 20:
                    body = json.dumps(list(reversed(json.loads(body))))
                return st, body
        res = probe.run(client(Reversed(lambda v, la, lo, h, u: 10)), date(2027, 3, 1))
        self.assertEqual([r[0] for r in self.names(res, "(iii)")], ["FAIL"])


if __name__ == "__main__":
    unittest.main()
