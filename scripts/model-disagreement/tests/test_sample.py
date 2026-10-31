"""The sampler: proportional strata, the power table, and the stop before any write (plan section 4, T2)."""
import contextlib
import io
import os
import tempfile
import unittest
from collections import Counter

import helpers as h
import md_common as mc
import md_sample

FIXTURE = os.path.join(h.HERE, "fixtures", "export_small.csv")


def population():
    units = []
    i = 0
    for ttype, month, n in (("SUNRISE", "2026-03", 120), ("SUNRISE", "2026-04", 60), ("SUNSET", "2026-03", 90),
                            ("SUNSET", "2026-05", 30)):
        for k in range(n):
            i += 1
            units.append(h.mk_unit(i, "%s-%02d" % (month, 1 + k % 28), ttype, loc=str(i)))
    return units


class SampleTests(unittest.TestCase):
    def test_sample_proportions_match_month_shares_within_event(self):
        units = population()
        sample = md_sample.stratified_sample(units, 100, seed=3)
        self.assertEqual(len(sample), 100)
        pop = Counter((u.ttype, u.month) for u in units)
        got = Counter((u.ttype, u.month) for u, _ in sample)
        for k, n in pop.items():
            self.assertLessEqual(abs(got[k] - 100 * n / len(units)), 1.0, k)        # rounding only
        # shares within each event match too
        for ev in ("SUNRISE", "SUNSET"):
            tot_pop = sum(n for (e, _), n in pop.items() if e == ev)
            tot_got = sum(n for (e, _), n in got.items() if e == ev)
            for (e, m), n in pop.items():
                if e == ev:
                    self.assertAlmostEqual(got[(e, m)] / tot_got, n / tot_pop, delta=0.05)

    def test_weights_are_stratum_size_over_draws_and_nearly_equal(self):
        units = population()
        sample = md_sample.stratified_sample(units, 100, seed=3)
        for u, w in sample:
            k = (u.ttype, u.month)
            n_pop = sum(1 for x in units if (x.ttype, x.month) == k)
            n_got = sum(1 for x, _ in sample if (x.ttype, x.month) == k)
            self.assertAlmostEqual(w, n_pop / n_got)
        ws = [w for _, w in sample]
        self.assertLess(max(ws) / min(ws), 1.3)          # self-weighting up to rounding

    def test_fixed_seed_is_repeatable_and_independent_of_input_order(self):
        units = population()
        a = md_sample.stratified_sample(units, 80, seed=11)
        b = md_sample.stratified_sample(list(reversed(units)), 80, seed=11)
        c = md_sample.stratified_sample(units, 80, seed=12)
        self.assertEqual([u.eval_id for u, _ in a], [u.eval_id for u, _ in b])
        self.assertNotEqual([u.eval_id for u, _ in a], [u.eval_id for u, _ in c])

    def test_target_at_or_above_population_takes_everyone(self):
        units = population()
        self.assertEqual(len(md_sample.stratified_sample(units, 10 ** 6)), len(units))

    def test_allocation_never_exceeds_a_stratum(self):
        alloc = md_sample.allocate({("a", "1"): 3, ("a", "2"): 1000}, 500)
        self.assertEqual(sum(alloc.values()), 500)
        self.assertLessEqual(alloc[("a", "1")], 3)


class PowerTests(unittest.TestCase):
    def test_power_rises_with_true_precision_and_uses_integer_wilson(self):
        seen = []
        orig = mc.stats.wilson
        mc.stats.wilson = lambda k, n, *a, **kw: (seen.append((k, n)), orig(k, n, *a, **kw))[1]
        try:
            rows = md_sample.power_table(300, sims=300, seed=5)
        finally:
            mc.stats.wilson = orig
        self.assertEqual([r["true_precision"] for r in rows], [0.55, 0.65, 0.75])
        self.assertEqual(rows[0]["n_flagged"], 60)                 # 20% of 300
        p = [r["p_lower_bound_ge_50"] for r in rows]
        self.assertLess(p[0], p[1])
        self.assertLess(p[1], p[2])
        self.assertTrue(seen and all(type(k) is int and type(n) is int for k, n in seen))


class CommandTests(unittest.TestCase):
    def run_main(self, *args):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = md_sample.main(list(args))
        return code, buf.getvalue()

    def test_stops_without_go_prints_days_and_power_and_writes_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "out")
            code, text = self.run_main(FIXTURE, "--out", out, "--cache", os.path.join(d, "cache"), "--n", "60")
            self.assertEqual(code, 0)
            self.assertFalse(os.path.exists(out))
            self.assertIn("EXPECTED FETCH DAYS", text)
            self.assertIn("600 weighted calls per UTC day", text)
            self.assertIn("Power", text)
            self.assertIn("STOPPED", text)
            self.assertIn("Population funnel", text)

    def test_go_writes_the_fetch_list_with_sample_and_worked_units(self):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "out")
            code, text = self.run_main(FIXTURE, "--out", out, "--cache", os.path.join(d, "cache"), "--n", "60", "--go")
            self.assertEqual(code, 0)
            rows = md_sample.load_fetch_list(os.path.join(out, "fetch_list.csv"))
            roles = Counter(r["role"] for r in rows)
            self.assertEqual(roles["sample"], 60)
            self.assertEqual(roles["worked"], 2)             # the trigger and the 13 Oct training case
            self.assertEqual(set(md_sample.FETCH_COLUMNS), set(rows[0]))
            units = md_sample.units_from_fetch_rows(rows)
            self.assertTrue(all(isinstance(u.az, int) for u in units))

    def test_expected_days_follow_the_daily_budget(self):
        with tempfile.TemporaryDirectory() as d:
            args = (FIXTURE, "--cache", os.path.join(d, "cache"), "--n", "60")
            _, a = self.run_main(*args, "--daily-calls", "600")
            _, b = self.run_main(*args, "--daily-calls", "60")

            def days(t):
                return int([ln for ln in t.splitlines() if ln.startswith("EXPECTED")][0].split(":")[1].split()[0])
            self.assertGreater(days(b), days(a))

    def test_default_cache_is_p1s(self):
        self.assertEqual(os.path.basename(mc.DEFAULT_CACHE), "cache")
        self.assertEqual(os.path.basename(os.path.dirname(mc.DEFAULT_CACHE)), "far-cloud-p1")


if __name__ == "__main__":
    unittest.main()
