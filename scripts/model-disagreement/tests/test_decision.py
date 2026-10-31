"""The decision rule, the alignment control and P' on hand-built tables (plan sections 2 and 3)."""
import unittest

import helpers as h
import md_common as mc
import md_size

DRAWS = 400          # enough for stable bounds on these tables, fast enough for a unit test


def item(n, s2, y, **kw):
    d = {"n": n, "s2": s2, "y": y}
    d.update(kw)
    return d


def status(result, prefix):
    return [c.status for c in result["criteria"] if c.name.startswith(prefix)][0]


def decide(recs, pairs=None, gate=False):
    return md_size.decide("test", recs, recs, len(recs) if pairs is None else pairs, gate, draws=DRAWS)


# S2 precision 83%, not-S2 8% (separation about +75 pp), recall 71%, flag 20%; every comparator flag sits on a
# miss, so S2 beats UKMO and best_match.
PASSING = [item(100, True, True), item(20, True, False),
           item(40, False, True), item(388, False, False), item(40, False, False, uk=40, bm=40)]


class DecisionRuleTests(unittest.TestCase):
    def test_a_good_table_passes_every_criterion(self):
        r = decide(h.build(PASSING))
        self.assertEqual([c.status for c in r["criteria"]], ["pass"] * 7, [(c.name, c.bound) for c in r["criteria"]])
        self.assertEqual(r["outcome"], "pass")
        self.assertTrue(all(ok for _, _, ok in r["mins"]))

    def test_low_precision_fails_criterion_1(self):
        r = decide(h.build([item(30, True, True), item(70, True, False), item(20, False, True),
                            item(480, False, False)]))
        self.assertEqual(status(r, "1"), "fail")
        self.assertEqual(r["outcome"], "fail")

    def test_low_separation_fails_criterion_2(self):
        r = decide(h.build([item(60, True, True), item(40, True, False), item(240, False, True),
                            item(240, False, False)]))
        self.assertEqual(status(r, "2"), "fail")
        self.assertEqual(r["outcome"], "fail")

    def test_low_recall_fails_criterion_3(self):
        r = decide(h.build([item(36, True, True), item(4, True, False), item(200, False, True),
                            item(300, False, False)]))
        self.assertEqual(status(r, "3"), "fail")
        self.assertEqual(status(r, "2"), "pass")      # the failure is recall alone, not separation
        self.assertEqual(r["outcome"], "fail")

    def test_high_flag_rate_fails_criterion_4(self):
        r = decide(h.build([item(360, True, True), item(40, True, False), item(40, False, True),
                            item(160, False, False)]))
        self.assertEqual(status(r, "4"), "fail")
        self.assertEqual(r["outcome"], "fail")

    def test_s2_no_better_than_a_comparator_fails_criterion_5(self):
        # UKMO and best_match flag exactly the S2 units: the difference in precision is zero
        r = decide(h.build([item(100, True, True, uk=40, bm=40), item(20, True, False, uk=40, bm=40),
                            item(40, False, True), item(440, False, False)]))
        self.assertEqual(status(r, "5"), "fail")
        self.assertEqual(r["outcome"], "fail")

    def test_silent_comparators_leave_criterion_5_not_assessable_so_the_outcome_is_inconclusive(self):
        recs = h.build([item(100, True, True), item(20, True, False), item(40, False, True), item(440, False, False)])
        r = decide(recs)
        self.assertEqual(status(r, "5"), "inconclusive")
        self.assertEqual(r["outcome"], "inconclusive")
        self.assertIn("not assessable", r["why"])

    def test_season_alone_fails_criterion_6_even_though_the_pooled_separation_is_large(self):
        cloudy, clear = ("2026-03",), ("2026-04",)
        # March: S2 and Y both common but independent; April: both rare but independent
        recs = h.build([item(128, True, True, months=cloudy), item(32, True, False, months=cloudy),
                        item(32, False, True, months=cloudy), item(8, False, False, months=cloudy),
                        item(2, True, True, months=clear), item(18, True, False, months=clear),
                        item(18, False, True, months=clear), item(162, False, False, months=clear)])
        r = decide(recs)
        self.assertGreater(r["metrics"]["separation"], 0.4)       # pooled: looks great
        self.assertEqual(status(r, "6"), "fail")                  # within month: nothing
        self.assertEqual(r["outcome"], "fail")

    def test_one_event_below_half_fails_criterion_7(self):
        recs = h.build([item(12, True, True, ttype="SUNRISE"), item(28, True, False, ttype="SUNRISE"),
                        item(76, True, True, ttype="SUNSET"), item(4, True, False, ttype="SUNSET"),
                        item(20, False, True), item(460, False, False)])
        r = decide(recs)
        self.assertEqual(status(r, "7"), "fail")
        self.assertEqual(r["outcome"], "fail")

    def test_each_minimum_count_alone_makes_the_outcome_inconclusive(self):
        strong = dict(passing=h.build(PASSING))
        self.assertEqual(decide(**{"recs": strong["passing"]})["outcome"], "pass")
        cases = {
            "S2 < 30": h.build([item(20, True, True), item(4, True, False), item(40, False, True),
                                item(440, False, False)]),
            "not-S2 < 30": h.build([item(100, True, True), item(20, True, False), item(5, False, True),
                                    item(15, False, False)]),
            # 40 S2 units, all on 5 distinct dates
            "dates < 10": h.build([item(40, True, True, months=("2026-03",), day_mod=5), item(40, False, True),
                                   item(440, False, False)]),
        }
        for want, recs in cases.items():
            r = decide(recs)
            self.assertEqual(r["outcome"], "inconclusive", want)
            self.assertIn(want.split(" ")[0], r["why"], want)
            self.assertIn("minimum count", r["why"])
        r = decide(h.build(PASSING), pairs=19)
        self.assertEqual(r["outcome"], "inconclusive")
        self.assertIn("alignment pairs", r["why"])
        self.assertEqual(decide(h.build(PASSING), pairs=20)["outcome"], "pass")

    def test_inconclusive_is_not_fail(self):
        r = decide(h.build([item(20, True, True), item(4, True, False), item(40, False, True),
                            item(440, False, False)]))
        self.assertNotEqual(r["outcome"], "fail")

    def test_alignment_gate_caps_a_pass(self):
        r = decide(h.build(PASSING), gate=True)
        self.assertEqual(r["outcome"], "no pass possible")
        self.assertTrue(r["capped"])
        self.assertEqual(status(r, "1"), "pass")          # the criteria themselves are still shown honestly

    def test_sensitivity_bounds_for_three_and_seven_day_blocks_are_reported(self):
        r = decide(h.build(PASSING))
        c1 = [c for c in r["criteria"] if c.name.startswith("1")][0]
        self.assertIn("3d", c1.sens)
        self.assertIn("7d", c1.sens)

    def test_thresholds_are_the_pre_registered_ones(self):
        self.assertEqual((md_size.PRECISION_MIN, md_size.SEPARATION_MIN, md_size.RECALL_MIN, md_size.FLAG_MAX),
                         (0.50, 0.15, 0.25, 0.40))
        self.assertEqual((md_size.MIN_ARM_UNITS, md_size.MIN_S2_DATES, md_size.MIN_PAIRS), (30, 10, 20))
        self.assertEqual((md_size.DRAWS, md_size.DECISION_BLOCK, md_size.BLOCKS), (2000, 5, (3, 5, 7)))
        self.assertEqual(mc.THRESH, 30)


class SeparationTests(unittest.TestCase):
    def test_separation_is_against_not_s2_not_against_the_base_rate(self):
        # S2: 8/10 yes; not-S2: 2/90 yes. Base rate 10/100 = 10%; separation vs not-S2 is 0.8 - 2/90.
        m = md_size.metrics((100, 10, 8, 10))
        self.assertAlmostEqual(m["separation"], 0.8 - 2 / 90)
        self.assertAlmostEqual(m["precision"], 0.8)
        self.assertAlmostEqual(m["recall"], 0.8)
        self.assertAlmostEqual(m["flag"], 0.1)

    def test_empty_arm_gives_none(self):
        self.assertIsNone(md_size.metrics((10, 0, 0, 3))["precision"])
        self.assertIsNone(md_size.metrics((10, 10, 3, 3))["separation"])


class BootstrapTests(unittest.TestCase):
    def test_blocks_are_resampled_within_each_month(self):
        # a month of 10 days each (1,...) and a month of 5 days each (2,...): a resample that stays inside
        # its month always totals 10 * 1 + 5 * 2 = 20; one that mixed months would vary
        by_month = {"2026-03": {"2026-03-%02d" % d: (1, 0, 0, 0) for d in range(1, 11)},
                    "2026-04": {"2026-04-%02d" % d: (2, 0, 0, 0) for d in range(1, 6)}}
        totals = []
        boot = md_size.MonthBootstrap(by_month)
        res = boot.run({"n": lambda ms: float(sum(m[0] for m in ms))}, block_days=3, draws=200, seed=1)
        totals = set(res["n"][0])
        self.assertEqual(totals, {20.0})

    def test_empty_calendar_days_are_part_of_the_resample(self):
        by_month = {"2026-03": {"2026-03-01": (1, 0, 0, 0), "2026-03-10": (1, 0, 0, 0)}}
        boot = md_size.MonthBootstrap(by_month)
        self.assertEqual(boot.prep["2026-03"][0], 10)

    def test_same_seed_same_draws(self):
        by_month = {"2026-03": {"2026-03-%02d" % d: (1, d % 2, d % 2, d % 3 == 0) for d in range(1, 20)}}
        a = md_size.MonthBootstrap(by_month).run({"p": md_size.f_precision}, 5, 50, 7)
        b = md_size.MonthBootstrap(by_month).run({"p": md_size.f_precision}, 5, 50, 7)
        self.assertEqual(a, b)


class LeadFairTests(unittest.TestCase):
    def test_p_prime_needs_both_comparators_voting_and_below_ten(self):
        ok = h.mk_rec(1, True, True, uk=9, bm=9)
        self.assertTrue(ok.lead_fair)
        self.assertFalse(h.mk_rec(2, True, True, uk=10, bm=0).lead_fair)
        self.assertFalse(h.mk_rec(3, True, True, uk=0, bm=10).lead_fair)
        none = h.mk_rec(4, True, True)
        none.M[mc.BEST] = None
        self.assertFalse(none.lead_fair)         # an abstention cannot confirm the stored zero was reproduced


class AlignmentTests(unittest.TestCase):
    def recs(self, n, hi, ttype="SUNSET", start=1, lead=8.0):
        return [h.mk_rec(start + i, False, False, bm=45 if i < hi else 0, ttype=ttype, lead=lead,
                         day=1 + i % 28) for i in range(n)]

    def test_share_above_twenty_percent_fires_the_gate(self):
        a = md_size.alignment(self.recs(100, 25))
        self.assertEqual(a["pairs"], 100)
        self.assertAlmostEqual(a["pooled_share"], 0.25)
        self.assertTrue(a["gate"])
        self.assertIn("pooled", a["fired"])

    def test_share_at_twenty_percent_or_below_does_not(self):
        self.assertFalse(md_size.alignment(self.recs(100, 20))["gate"])
        self.assertFalse(md_size.alignment(self.recs(100, 5))["gate"])

    def test_fewer_than_twenty_pairs_cannot_fire_it(self):
        a = md_size.alignment(self.recs(15, 15))
        self.assertEqual(a["pairs"], 15)
        self.assertFalse(a["gate"])

    def test_one_event_can_fire_it_alone(self):
        recs = self.recs(40, 20, "SUNRISE") + self.recs(160, 8, "SUNSET", start=100)
        a = md_size.alignment(recs)
        self.assertLess(a["pooled_share"], 0.20)
        self.assertEqual(a["fired"], ["sunrise"])

    def test_lead_bands_are_reported_and_best_match_abstentions_are_not_pairs(self):
        recs = self.recs(30, 0, lead=3.0) + self.recs(30, 0, lead=9.0, start=100) + self.recs(30, 0, lead=15.0, start=200)
        recs[0].M[mc.BEST] = None
        a = md_size.alignment(recs)
        bands = {(r["event"], r["band"]): r["pairs"] for r in a["rows"]}
        self.assertEqual(bands[("ALL", "< 6 h")], 29)
        self.assertEqual(bands[("ALL", "6-12 h")], 30)
        self.assertEqual(bands[("ALL", "> 12 h")], 30)
        self.assertEqual(a["pairs"], 89)

    def test_median_delta_is_reported_beside_the_share(self):
        a = md_size.alignment(self.recs(30, 0))
        self.assertEqual(a["rows"][-1]["median_delta"], 0)


if __name__ == "__main__":
    unittest.main()
