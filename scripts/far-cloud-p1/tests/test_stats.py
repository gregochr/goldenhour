"""Tests for stats.py: known values, determinism, gates."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import stats  # noqa: E402


class IntervalTest(unittest.TestCase):
    def test_wilson_7_of_20(self):
        p, lo, hi = stats.wilson(7, 20)
        self.assertAlmostEqual(p, 0.35)
        self.assertAlmostEqual(lo, 0.181, places=3)
        self.assertAlmostEqual(hi, 0.567, places=3)

    def test_wilson_edges(self):
        self.assertIsNone(stats.wilson(0, 0))
        p, lo, hi = stats.wilson(0, 10)
        self.assertEqual(lo, 0.0)
        self.assertGreater(hi, 0.2)
        self.assertEqual(stats.wilson(10, 10)[2], 1.0)

    def test_newcombe_1998_table(self):
        d, lo, hi = stats.newcombe_diff(56, 70, 48, 80)
        self.assertAlmostEqual(d, 0.2, places=4)
        self.assertAlmostEqual(lo, 0.0524, places=3)
        self.assertAlmostEqual(hi, 0.3339, places=3)

    def test_newcombe_empty_arm(self):
        self.assertIsNone(stats.newcombe_diff(1, 0, 1, 5))

    def test_score_test(self):
        self.assertAlmostEqual(stats.two_prop_pvalue(5, 10, 5, 10), 1.0)
        self.assertLess(stats.two_prop_pvalue(40, 50, 10, 50), 1e-6)
        self.assertIsNone(stats.two_prop_pvalue(1, 0, 1, 5))
        self.assertEqual(stats.two_prop_pvalue(0, 10, 0, 10), 1.0)


class HolmTest(unittest.TestCase):
    def test_textbook(self):
        adj = stats.holm([0.01, 0.04, 0.03, 0.005])
        for got, want in zip(adj, [0.03, 0.06, 0.06, 0.02]):
            self.assertAlmostEqual(got, want)

    def test_cap_and_none(self):
        adj = stats.holm([0.6, None, 0.9])
        self.assertEqual(adj[1], None)
        self.assertEqual(adj[0], 1.0)
        self.assertEqual(adj[2], 1.0)


class BootstrapTest(unittest.TestCase):
    def setUp(self):
        # 30 dates, 4 units each; the 'event' occurs in the first 10 dates only (regime persistence)
        self.units = [{"date": "2026-09-%02d" % (d + 1), "ev": d < 10} for d in range(30) for _ in range(4)]
        self.stat = lambda us: sum(u["ev"] for u in us) / len(us) if us else None

    def test_deterministic_with_seed(self):
        a = stats.moving_block_bootstrap(self.units, self.stat, lambda u: u["date"], seed=7, draws=300)
        b = stats.moving_block_bootstrap(self.units, self.stat, lambda u: u["date"], seed=7, draws=300)
        c = stats.moving_block_bootstrap(self.units, self.stat, lambda u: u["date"], seed=8, draws=300)
        self.assertEqual(a, b)
        self.assertNotEqual(a, c)
        self.assertLessEqual(a[0], 1 / 3 + 0.05)
        self.assertGreaterEqual(a[1], 1 / 3 - 0.05)

    def test_blocks_widen_interval_relative_to_iid(self):
        w1 = stats.moving_block_bootstrap(self.units, self.stat, lambda u: u["date"], block_days=1, draws=600)
        w5 = stats.moving_block_bootstrap(self.units, self.stat, lambda u: u["date"], block_days=5, draws=600)
        self.assertGreater(w5[1] - w5[0], w1[1] - w1[0])

    def test_sensitivity_keys(self):
        s = stats.block_sensitivity(self.units, self.stat, lambda u: u["date"], draws=100)
        self.assertEqual(sorted(s), [3, 5, 7])

    def test_counts_matches_units_path(self):
        dc = {}
        for u in self.units:
            c = dc.setdefault(u["date"], [0, 0])
            c[0] += u["ev"]
            c[1] += 1
        dc = {k: tuple(v) for k, v in dc.items()}
        draws = stats.bootstrap_counts(dc, lambda t: t[0] / t[1], draws=200, seed=3)
        draws2 = stats.moving_block_bootstrap(self.units, self.stat, lambda u: u["date"], draws=200, seed=3,
                                              return_draws=True)
        self.assertEqual(draws, draws2)

    def test_empty_and_undefined(self):
        self.assertIsNone(stats.moving_block_bootstrap([], self.stat, lambda u: u["date"]))
        self.assertIsNone(stats.moving_block_bootstrap(self.units, lambda us: None, lambda u: u["date"], draws=5))

    def test_pvalue(self):
        self.assertAlmostEqual(stats.bootstrap_pvalue([1, 2, 3, -1]), 2 * 1 / 4)
        self.assertAlmostEqual(stats.bootstrap_pvalue([1.0] * 99), 1 / 100)
        self.assertIsNone(stats.bootstrap_pvalue([]))


class GateTest(unittest.TestCase):
    def test_inconclusive_small_arm(self):
        self.assertEqual(stats.verdict(29, 15, 100, 0.2, 0.001), "inconclusive")
        self.assertEqual(stats.verdict(40, 15, 29, 0.2, 0.001), "inconclusive")

    def test_inconclusive_few_events(self):
        self.assertEqual(stats.verdict(40, 9, 100, 0.2, 0.001), "inconclusive")

    def test_pass_fail(self):
        self.assertEqual(stats.verdict(30, 10, 30, 0.05, 0.01), "pass")
        self.assertEqual(stats.verdict(30, 10, 30, -0.05, 0.01), "fail")
        self.assertEqual(stats.verdict(30, 10, 30, 0.05, 0.2), "fail")
        self.assertEqual(stats.verdict(30, 10, 30, None, None), "inconclusive")


if __name__ == "__main__":
    unittest.main()
