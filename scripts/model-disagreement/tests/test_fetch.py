"""md_fetch and md_probe against the model-switching fake: one single-model request per model, no network."""
import contextlib
import io
import itertools
import os
import tempfile
import unittest
from urllib.parse import parse_qs, urlsplit

import fixture_tools as ft
import helpers as h
import md_common as mc
import md_fetch
import md_probe
import md_sample
from model_fake import ModelOM


def client_for(fake, cache, budget=10 ** 6):
    return mc.om.Client(cache, budget, fetch=fake, pause_s=0, sleep=lambda s: None, wait_for_hour=False,
                        hourly_cap=10 ** 9, minute_cap=10 ** 9)


def subset_csv(path, n_dates=2):
    dates = {d.isoformat() for d in ft.slot_dates()[:n_dates]}
    ft.write_csv(path, [r for r in ft.build_rows() if r["target_date"] in dates])


class FakeSwitchesOnModels(unittest.TestCase):
    def test_the_fake_serves_different_values_per_model_and_refuses_a_models_list(self):
        fake = ModelOM(lambda model, var, la, lo, hr, url: len(model))
        import json
        base = "https://x/y?latitude=50.0&longitude=1.0&hourly=cloud_cover_mid&start_date=2026-03-02&end_date=2026-03-02"
        _, a = fake(base + "&models=gfs_seamless")
        _, b = fake(base + "&models=icon_seamless")
        self.assertNotEqual(json.loads(a)["hourly"]["cloud_cover_mid"][0], json.loads(b)["hourly"]["cloud_cover_mid"][0])
        for bad in ("&models=gfs_seamless,icon_seamless", ""):
            with self.assertRaises(AssertionError):
                fake(base + bad)


class FetchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.csv = os.path.join(cls.tmp.name, "export.csv")
        subset_csv(cls.csv)
        cls.out = os.path.join(cls.tmp.name, "out")
        with contextlib.redirect_stdout(io.StringIO()):
            md_sample.main([cls.csv, "--out", cls.out, "--cache", os.path.join(cls.tmp.name, "c0"), "--n", "1000",
                            "--go"])
        cls.list = os.path.join(cls.out, "fetch_list.csv")
        cls.units = md_sample.units_from_fetch_rows(md_sample.load_fetch_list(cls.list))

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def fetch(self, cache, *extra, fake=None, budget=10 ** 6):
        fake = fake or ModelOM(ft.Provider(self.csv))
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = md_fetch.main([self.list, "--cache", cache, "--budget", str(budget), "--pause", "0",
                                  *extra], client=client_for(fake, cache, budget))
        return code, fake, buf.getvalue()

    def test_one_single_model_request_per_model_grouped_by_date(self):
        with tempfile.TemporaryDirectory() as cache:
            code, fake, _ = self.fetch(cache)
            self.assertEqual(code, 0)
            self.assertTrue(fake.urls)
            for u in fake.urls:
                q = parse_qs(urlsplit(u).query)
                self.assertNotIn(",", q["models"][0])                       # never a models list
                self.assertEqual(q["start_date"], q["end_date"])           # one date per request
                self.assertEqual(q["hourly"][0].split(","), mc.HOURLY)     # fixed variable order
                self.assertEqual(q["timezone"], ["UTC"])
                self.assertTrue(u.startswith(mc.om.HOSTS["historical_forecast"]))
            self.assertEqual(set(fake.models_seen), set(mc.MODELS))
            # per date: models in the fixed order
            by_date = {}
            for u in fake.urls:
                q = parse_qs(urlsplit(u).query)
                by_date.setdefault(q["start_date"][0], []).append(q["models"][0])
            for date, ms in by_date.items():
                # a model's coordinates may span several 20-coordinate requests; the model order is fixed
                self.assertEqual([m for m, _ in itertools.groupby(ms)], list(mc.MODELS), date)

    def test_requests_are_deduplicated_by_date_and_grid_cell(self):
        plan = mc.plan_requests(self.units)
        with tempfile.TemporaryDirectory() as cache:
            _, fake, _ = self.fetch(cache)
            coords = sum(len(c) for c in plan.values())
            requested = sum(len(parse_qs(urlsplit(u).query)["latitude"][0].split(",")) for u in fake.urls)
            self.assertEqual(requested, coords * len(mc.MODELS))
            clone = md_sample.units_from_fetch_rows(md_sample.load_fetch_list(self.list))[0]
            clone.eval_id, clone.location_id = 10 ** 6, "clone"             # same place, same date: same cells
            self.assertEqual(sum(len(c) for c in mc.plan_requests(self.units + [clone]).values()), coords)
    def test_a_second_run_is_served_from_the_cache_and_costs_nothing(self):
        with tempfile.TemporaryDirectory() as cache:
            self.fetch(cache)
            code, fake, text = self.fetch(cache)
            self.assertEqual(code, 0)
            self.assertEqual(fake.urls, [])

    def test_observer_points_only_with_the_flag(self):
        with tempfile.TemporaryDirectory() as c1, tempfile.TemporaryDirectory() as c2:
            _, plain, _ = self.fetch(c1)
            _, withobs, _ = self.fetch(c2, "--observer")
            n = lambda f: sum(len(parse_qs(urlsplit(u).query)["latitude"][0].split(",")) for u in f.urls)
            self.assertGreater(n(withobs), n(plain))

    def test_budget_stop_is_clean_and_resumable(self):
        with tempfile.TemporaryDirectory() as cache:
            code, fake, text = self.fetch(cache, budget=40)
            self.assertEqual(code, 2)
            self.assertIn("BUDGET REACHED", text)
            n_first = len(fake.urls)
            self.assertGreater(n_first, 0)
            code2, fake2, _ = self.fetch(cache, budget=10 ** 6)
            self.assertEqual(code2, 0)
            all_coords = sum(len(c) for c in mc.plan_requests(self.units).values()) * len(mc.MODELS)
            got = lambda f: sum(len(parse_qs(urlsplit(u).query)["latitude"][0].split(",")) for u in f.urls)
            self.assertEqual(got(fake) + got(fake2), all_coords)             # nothing fetched twice

    def test_default_cache_dir_is_p1s(self):
        self.assertTrue(md_fetch.mc.DEFAULT_CACHE.endswith(os.path.join("far-cloud-p1", "cache")))


class ProbeTests(unittest.TestCase):
    def provider(self, drop=None):
        def f(model, var, la, lo, hr, url):
            if drop and (model, var) == drop:
                return None
            return {"ukmo_seamless": 0, "best_match": 0, "icon_seamless": 23, "gfs_seamless": 0,
                    "meteofrance_seamless": 100, "ecmwf_ifs025": 3}[model]
        return f

    def run_probe(self, fake, *extra):
        with tempfile.TemporaryDirectory() as cache:
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                code = md_probe.main(["--no-wait", "--pause", "0", *extra], client=client_for(fake, cache))
            return code, buf.getvalue()

    def test_one_single_model_request_per_model_and_a_value_table(self):
        fake = ModelOM(self.provider())
        code, text = self.run_probe(fake, "--no-single-runs")
        self.assertEqual(code, 0)
        self.assertEqual(sorted(fake.models_seen), sorted(mc.MODELS))
        self.assertEqual(len(fake.urls), len(mc.MODELS))
        self.assertIn("RESULT: OK", text)
        self.assertIn("NOT the real cost", text)
        self.assertIn("meteofrance_seamless", text)

    def test_an_unserved_layer_fails_the_probe(self):
        fake = ModelOM(self.provider(drop=("ukmo_seamless", "cloud_cover_mid")))
        code, text = self.run_probe(fake, "--no-single-runs")
        self.assertEqual(code, 1)
        self.assertIn("ukmo_seamless serves low/mid/high", text)

    def test_unserved_pressure_levels_are_informational(self):
        fake = ModelOM(self.provider(drop=("gfs_seamless", "cloud_cover_500hPa")))
        code, text = self.run_probe(fake, "--no-single-runs")
        self.assertEqual(code, 0)
        self.assertIn("S2-PL loses this model", text)

    def test_single_runs_probe_is_recorded_and_never_fails(self):
        fake = ModelOM(self.provider())
        code, text = self.run_probe(fake)
        self.assertEqual(code, 0)
        self.assertIn("Single Runs: icon_seamless", text)
        self.assertTrue(any("single-runs-api" in u for u in fake.urls))


if __name__ == "__main__":
    unittest.main()
