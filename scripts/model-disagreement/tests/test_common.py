"""Units, population, model values and the S2 vote (plan sections 2 and 4)."""
import os
import sys
import unittest
from datetime import datetime

import helpers as h
import md_common as mc


class DedupTests(unittest.TestCase):
    def test_latest_pre_event_run_per_location_date_type_and_lead(self):
        rows = [
            h.raw_row(1, run="2026-03-02T02:00:00"),
            h.raw_row(2, run="2026-03-02T10:00:00"),                 # the latest before the event: wins
            h.raw_row(3, run="2026-03-02T19:00:00"),                 # after the event (18:10): never
            h.raw_row(4, run="2026-03-02T12:00:00", state="PENDING"),  # batch row not scored: never
            h.raw_row(5, run="2026-03-02T11:00:00", state="SCORED"),  # SCORED is allowed and is later than 2
            h.raw_row(6, run="2026-03-02T09:00:00", loc="2"),        # another location: its own unit
            h.raw_row(7, run="2026-03-02T01:00:00", ttype="SUNRISE", event="2026-03-02T06:10:00"),
            h.raw_row(8, run="2026-03-01T09:00:00", days=1),         # same slot, another lead: its own unit
        ]
        rows[1]["triage_reason"] = "HEAVY_RAIN"                      # triaged rows are kept
        units, counts = mc.dedup_units(rows)
        by_key = {(u.location_id, u.date, u.ttype, u.days_ahead): u.eval_id for u in units}
        self.assertEqual(by_key[("1", "2026-03-02", "SUNSET", 0)], 5)
        self.assertEqual(by_key[("2", "2026-03-02", "SUNSET", 0)], 6)
        self.assertEqual(by_key[("1", "2026-03-02", "SUNRISE", 0)], 7)
        self.assertEqual(by_key[("1", "2026-03-02", "SUNSET", 1)], 8)
        self.assertEqual(counts.rows, 8)
        self.assertEqual(counts.eligible, 6)       # rows 3 (post-event) and 4 (PENDING) are not eligible
        self.assertEqual(counts.units, 4)
        self.assertEqual(len(units), 4)

    def test_triaged_row_is_kept_when_latest(self):
        r = h.raw_row(1)
        r["triage_reason"] = "HEAVY_RAIN"
        units, _ = mc.dedup_units([r, h.raw_row(2, run="2026-03-02T05:00:00")])
        self.assertEqual([u.eval_id for u in units], [1])

    def test_tie_on_run_time_goes_to_the_higher_evaluation_id(self):
        units, _ = mc.dedup_units([h.raw_row(9), h.raw_row(4)])
        self.assertEqual([u.eval_id for u in units], [9])

    def test_latest_run_wins_even_without_verification(self):
        """Falling back to an earlier verified run would change the lead: the unit simply leaves P."""
        latest = h.raw_row(2, run="2026-03-02T12:00:00", cv_id="", cv_horizon_mid_cloud="")
        units, _ = mc.dedup_units([h.raw_row(1, run="2026-03-02T02:00:00"), latest])
        self.assertEqual(units[0].eval_id, 2)
        self.assertEqual(mc.select_population(units).members, [])

    def test_rows_without_event_or_run_time_cannot_be_units(self):
        units, counts = mc.dedup_units([h.raw_row(1, event=""), h.raw_row(2, run="")])
        self.assertEqual((len(units), counts.rows, counts.eligible), (0, 2, 0))

    def test_missing_required_column_is_named(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "x.csv")
            with open(p, "w") as f:
                f.write("evaluation_id,location_id\n1,2\n")
            with self.assertRaisesRegex(ValueError, "target_date"):
                mc.load_rows(p)


class ConversionTests(unittest.TestCase):
    def test_azimuth_is_truncated_to_whole_degrees(self):
        u = mc.row_to_unit(h.raw_row(1))
        self.assertEqual(u.az, 255)
        self.assertIsInstance(u.az, int)

    def test_event_time_parse_and_fetch_date_is_the_events_utc_date(self):
        self.assertEqual(mc.parse_event("2026-10-31T16:36:12.5Z"), datetime(2026, 10, 31, 16, 36, 12))
        u = mc.row_to_unit(h.raw_row(1, date="2026-03-02", event="2026-03-03T00:20:00", run="2026-03-02T10:00:00"))
        self.assertEqual(u.date, "2026-03-02")
        self.assertEqual(u.event_date, "2026-03-03")      # an event after UTC midnight is fetched on the next date

    def test_slot_uses_the_production_rule_with_api_time_strings(self):
        u = mc.row_to_unit(h.raw_row(1, event="2026-03-02T18:10:00"))
        p = h.point(1, 1, hour="2026-03-02T17:00")
        for hh in ("2026-03-02T18:00", "2026-03-02T19:00"):
            p.hours[hh] = {}
        self.assertEqual(mc.slot_hour(u, p), "2026-03-02T18:00")      # sunset: latest slot at or before
        u2 = mc.row_to_unit(h.raw_row(2, ttype="SUNRISE", event="2026-03-02T17:10:00"))
        self.assertEqual(mc.slot_hour(u2, p), "2026-03-02T18:00")     # sunrise: earliest slot at or after


class ModelValueTests(unittest.TestCase):
    def test_floor_mean_per_layer_then_max(self):
        # mid (10, 10, 11) -> 31 // 3 = 10; high (0, 0, 91) -> 91 // 3 = 30; max = 30
        self.assertEqual(mc.model_value(h.cone([10, 10, 11], [0, 0, 91]), h.HOUR), 30)
        # a mean is taken per layer BEFORE the max: max-then-mean would give (10 + 10 + 91) // 3 = 37
        self.assertEqual(mc.model_value(h.cone([10, 10, 0], [0, 0, 91]), h.HOUR), 30)

    def test_thirty_thirty_twenty_nine_floors_to_twenty_nine_and_does_not_vote_cloud(self):
        m = mc.model_value(h.cone([30, 30, 29], [0, 0, 0]), h.HOUR)
        self.assertEqual(m, 29)
        self.assertLess(m, mc.THRESH)

    def test_missing_cone_point_or_layer_is_not_voting_never_zero(self):
        self.assertIsNone(mc.model_value(h.cone([50, 50, None], [0, 0, 0]), h.HOUR))     # null mid
        self.assertIsNone(mc.model_value(h.cone([50, 50, 50], [0, None, 0]), h.HOUR))    # null high
        self.assertIsNone(mc.model_value(h.cone([50, 50], [0, 0]), h.HOUR))              # two points only
        self.assertIsNone(mc.model_value([h.point(50, 0), None, h.point(50, 0)], h.HOUR))  # an absent point
        self.assertIsNone(mc.model_value(h.cone([50, 50, 50], [0, 0, 0], hour="2026-03-02T17:00"), h.HOUR))

    def test_all_zero_cone_is_a_real_zero_vote(self):
        self.assertEqual(mc.model_value(h.cone([0, 0, 0], [0, 0, 0]), h.HOUR), 0)

    def test_pressure_value_and_observer_value(self):
        pts = [h.point(0, 0, extra={"cloud_cover_700hPa": a, "cloud_cover_500hPa": b, "cloud_cover_300hPa": c})
               for a, b, c in ((0, 40, 10), (0, 40, 10), (0, 41, 10))]
        self.assertEqual(mc.pressure_value(pts, h.HOUR), 40)       # 500 hPa mean 121 // 3
        self.assertIsNone(mc.pressure_value([h.point(0, 0)] * 3, h.HOUR))
        self.assertEqual(mc.observer_value(h.point(35, 10), h.HOUR), 35)
        self.assertIsNone(mc.observer_value(h.point(35, None), h.HOUR))


class VoteTests(unittest.TestCase):
    def M(self, icon, gfs, mf, ecmwf=0):
        return {mc.ICON: icon, mc.GFS: gfs, mc.MF: mf, mc.ECMWF: ecmwf}

    def test_two_of_three_with_all_voting(self):
        self.assertTrue(mc.votes(self.M(30, 30, 0)))
        self.assertTrue(mc.votes(self.M(0, 30, 99)))
        self.assertFalse(mc.votes(self.M(30, 29, 0)))
        self.assertFalse(mc.votes(self.M(0, 0, 0)))

    def test_needs_all_three_voters(self):
        self.assertIsNone(mc.votes(self.M(90, 90, None)))        # two cloud votes, third abstains: no S2 verdict
        self.assertIsNone(mc.votes({mc.ICON: 90, mc.GFS: 90}))   # a missing key is an abstention too

    def test_ecmwf_never_enters_s2(self):
        self.assertFalse(mc.votes(self.M(90, 0, 0, ecmwf=99)))   # ECMWF agreeing does not make S2
        self.assertIsNone(mc.votes(self.M(90, 90, None, ecmwf=99)))   # and cannot stand in for a missing voter
        self.assertNotIn(mc.ECMWF, mc.VOTERS)
        self.assertTrue(mc.votes(self.M(90, 0, 0, ecmwf=99), mc.VOTERS + (mc.ECMWF,), 2))   # S2+E only

    def test_models_are_requested_in_a_fixed_order(self):
        self.assertEqual(mc.MODELS[0], mc.UKMO)
        self.assertEqual(len(set(mc.MODELS)), 6)


class PopulationTests(unittest.TestCase):
    def units(self, **changes):
        base = dict(cv_id="1", cv_horizon_mid_cloud=40, cv_horizon_high_cloud=10, cv_horizon_low_cloud=20)
        out = []
        for i, ch in enumerate(changes.get("variants", [{}]), start=1):
            row = h.raw_row(i, loc=str(i), **dict(base, **ch))
            out.append(row)
        return mc.dedup_units(out)[0]

    def test_filters_and_funnel(self):
        units = self.units(variants=[
            {},                                           # in P
            {"mid": 1},                                   # stored mid not zero
            {"high": 4},                                  # stored high not zero
            {"mid": ""},                                  # stored mid null: not a zero
            {"low": 60},                                  # blocked horizon
            {"low": ""},                                  # stored low null
            {"cv_id": "", "cv_horizon_mid_cloud": ""},    # no ERA5
            {"cv_horizon_high_cloud": ""},                # ERA5 incomplete
            {"days": 1},                                  # not T+0
        ])
        pop = mc.select_population(units)
        self.assertEqual([u.location_id for u in pop.members], ["1"])
        self.assertEqual(pop.funnel[0], ("units after deduplication", 9))
        self.assertEqual(pop.funnel[-1][1], 1)

    def test_moved_location_guard(self):
        u = mc.row_to_unit(h.raw_row(1))
        clat, clon = mc.cone_centre(u)
        for dlat, dlon, expect in ((0.0, 0.0, False), (0.009, -0.009, False), (0.011, 0.0, True), (0.0, 0.5, True)):
            u.s_lat, u.s_lon = clat + dlat, clon + dlon
            self.assertEqual(mc.moved(u), expect, (dlat, dlon))
        pop = mc.select_population([u])
        self.assertEqual(pop.moved, 1)
        self.assertEqual(pop.members, [])
        u.s_lat = u.s_lon = None
        self.assertFalse(mc.moved(u))                     # unknown sample point cannot disagree

    def test_trigger_and_training_cases_are_set_aside_as_worked_examples(self):
        rows = [h.raw_row(1, loc="64", date="2026-10-31", event="2026-10-31T16:36:00", run="2026-10-31T08:00:00",
                          loc_name="Angel of the North"),
                h.raw_row(2, loc="64", date="2026-10-13", event="2026-10-13T16:50:00", run="2026-10-13T08:00:00",
                          loc_name="Angel of the North"),
                h.raw_row(3, loc="64", date="2026-10-12", event="2026-10-12T16:50:00", run="2026-10-12T08:00:00",
                          loc_name="Angel of the North"),
                h.raw_row(4, loc="65", date="2026-10-04", ttype="SUNRISE", event="2026-10-04T06:20:00",
                          run="2026-10-03T20:00:00", loc_name="Low Hauxley")]
        units, _ = mc.dedup_units(rows)
        pop = mc.select_population(units)
        self.assertEqual(sorted(u.eval_id for u in pop.worked), [1, 2, 4])
        self.assertEqual([u.eval_id for u in pop.members], [3])      # an ordinary Angel date stays in P
        self.assertIn("trigger", [u for u in pop.worked if u.eval_id == 1][0].case)

    def test_y_definition(self):
        u = h.mk_unit(1)
        u.h_mid, u.h_high, u.h_low = 29, 30, 59
        self.assertTrue(mc.era5_y(u))
        u.h_high = 29
        self.assertFalse(mc.era5_y(u))
        u.h_mid, u.h_low = 30, 60
        self.assertFalse(mc.era5_y(u))                    # blocked horizon
        u.h_low, u.h_mid, u.h_high = 59, 31, None
        self.assertIsNone(mc.era5_y(u))                   # high missing, mid alone is not the full Y
        self.assertTrue(mc.era5_y(u, mid_only=True))


class PlanTests(unittest.TestCase):
    def test_requests_are_deduplicated_by_date_and_grid_cell_and_observer_never_moves_cone_cells(self):
        a = h.mk_unit(1, "2026-03-02")
        b = h.mk_unit(2, "2026-03-02", loc="2")      # same coordinates: shares every cell with unit 1
        c = h.mk_unit(3, "2026-03-03")
        plan = mc.plan_requests([a, b, c])
        self.assertEqual(sorted(plan), ["2026-03-02", "2026-03-03"])
        self.assertEqual(len(plan["2026-03-02"]), 3)
        with_obs = mc.plan_requests([a, b, c], observer=True)
        for d, cells in plan.items():
            for k, coord in cells.items():
                self.assertEqual(with_obs[d][k], coord)
        self.assertGreater(len(with_obs["2026-03-02"]), 3)

    def test_planned_calls_is_one_per_date_model_coordinate(self):
        plan = mc.plan_requests([h.mk_unit(1)])
        self.assertEqual(mc.planned_calls(plan), 3 * len(mc.MODELS))

    def test_p1_directory_is_appended_not_inserted_first(self):
        self.assertIn(mc.P1_DIR, sys.path)
        self.assertNotEqual(sys.path[0], mc.P1_DIR)
        self.assertLess(sys.path.index(os.path.dirname(os.path.abspath(mc.__file__))), sys.path.index(mc.P1_DIR))
        for name in ("probe", "size"):                    # P1's non-unique names are never imported
            self.assertNotIn(name, sys.modules)


class WilsonTests(unittest.TestCase):
    def test_wilson_only_takes_integers(self):
        self.assertIsNotNone(mc.safe_wilson(3, 10))
        for k, n in ((3.0, 10), (3, 10.0), (0.5 * 6, 6), (True, 3)):
            with self.assertRaises(TypeError):
                mc.safe_wilson(k, n)
        self.assertIsNone(mc.safe_wilson(0, 0))


if __name__ == "__main__":
    unittest.main()
