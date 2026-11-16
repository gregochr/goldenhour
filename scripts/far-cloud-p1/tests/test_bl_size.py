"""Tests for bl_size.py (broken-low Phase A sizing). No network. Rows come from test_premise.mk."""
import contextlib
import csv
import io
import itertools
import os
import sys
import tempfile
import unittest
from datetime import date, datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
import bl_size  # noqa: E402
import premise  # noqa: E402
from test_premise import mk  # noqa: E402

FIX = os.path.join(HERE, 'fixtures')
DAY0 = date(2026, 11, 1)
_eid = itertools.count(50000)

# A clean, testable broken-low slot. Override one field to cross one boundary.
BL = dict(solar_low_cloud=25, solar_mid_cloud=0, solar_high_cloud=0, mid_cloud=0, high_cloud=0,
          antisolar_mid_cloud=0, antisolar_high_cloud=0, far_solar_low_cloud=10, low_cloud=10,
          solar_trend_building=False, loc_types='LANDSCAPE', sky_rating=3, rating=3, summary='Some light.',
          cv_horizon_low_cloud=20.0, cv_horizon_mid_cloud=5.0, cv_horizon_high_cloud=5.0)


def ds(i):
    return (DAY0 + timedelta(days=i)).isoformat()


def slot_row(i, loc=1, name='Alpha', event='SUNSET', **kw):
    """One batch run at 10:00 on day i with the event at 16:20 (a T+0 slot)."""
    d = DAY0 + timedelta(days=i)
    base = dict(BL, evaluation_id=next(_eid), location_id=loc, loc_name=name, target_date=d.isoformat(),
                target_type=event, forecast_run_at=datetime(d.year, d.month, d.day, 10, 0),
                solar_event_time=datetime(d.year, d.month, d.day, 16, 20))
    base.update(kw)
    return mk(**base)


def kind(**kw):
    return bl_size.kind_of(mk(**dict(BL, **kw)))[0]


def rating(loc, i, outcome, fr=3, admin=True, seen='COLOUR', event='SUNSET'):
    valid = outcome is not None and seen != 'NOT_SEEN'
    return {'key': (loc, ds(i), event), 'date': ds(i), 'rating': outcome, 'seen': seen, 'forecast_rating': fr,
            'by_admin': admin, 'valid': valid}


def scenario(n_bl, n_cl, bl_sky=3, bl_summary='Some light.', bl_out=4, cl_out=2, n_other=0):
    """n_bl BL evenings and n_cl CL evenings, alternating days from DAY0, one location, all rated by the admin
    at forecast star 3. Returns (rows, ratings)."""
    rows, ratings = [], []
    i = 0
    for k in range(max(n_bl, n_cl)):
        if k < n_bl:
            rows.append(slot_row(i, sky_rating=bl_sky, rating=bl_sky, summary=bl_summary))
            ratings.append(rating(1, i, bl_out))
            i += 1
        if k < n_cl:
            rows.append(slot_row(i, solar_low_cloud=5, sky_rating=3, summary='Plain.'))
            ratings.append(rating(1, i, cl_out))
            i += 1
    for k in range(n_other):
        rows.append(slot_row(i, solar_low_cloud=60))
        i += 1
    return rows, ratings


def run_gate(rows, ratings=None, start=None, draws=300):
    a = bl_size.analyse(rows, ratings, start, draws)
    return a['gate']


class UnitSelectionTest(unittest.TestCase):
    def test_latest_eligible_run_is_the_unit(self):
        d = DAY0
        early = slot_row(0, forecast_run_at=datetime(d.year, d.month, d.day, 6, 0), solar_low_cloud=5)
        late = slot_row(0, forecast_run_at=datetime(d.year, d.month, d.day, 12, 0))
        pending = slot_row(0, forecast_run_at=datetime(d.year, d.month, d.day, 14, 0), batch_state='PENDING',
                           solar_low_cloud=5)
        after = slot_row(0, forecast_run_at=datetime(d.year, d.month, d.day, 17, 0), solar_low_cloud=5)
        u = bl_size.build_units([early, late, pending, after])
        self.assertEqual(len(u['base']), 1)
        self.assertEqual(u['base'][0]['row']['evaluation_id'], late['evaluation_id'])
        self.assertEqual(u['base'][0]['kind'], 'BL')

    def test_population_filters(self):
        rows = [slot_row(0), slot_row(1, batch_state=None), slot_row(2, days_ahead=2),
                slot_row(3, loc_types='AURORA'), slot_row(4, sky_rating=None),
                slot_row(5, triage_reason='HIGH_CLOUD', batch_state='SCORED')]
        u = bl_size.build_units(rows)
        self.assertEqual([s['date'] for s in u['base']], [ds(0)])

    def test_t_plus_one_is_kept(self):
        u = bl_size.build_units([slot_row(0, days_ahead=1)])
        self.assertEqual(len(u['bl']), 1)

    def test_locations_filter(self):
        rows = [slot_row(0, loc=1), slot_row(1, loc=2, name='Beta')]
        u = bl_size.build_units(rows, {2})
        self.assertEqual([s['row']['location_id'] for s in u['base']], [2])


class A1BoundaryTest(unittest.TestCase):
    def test_cone_low_band_is_20_to_39_inclusive(self):
        self.assertEqual(kind(solar_low_cloud=20), 'BL')
        self.assertEqual(kind(solar_low_cloud=39), 'BL')
        self.assertEqual(bl_size.kind_of(mk(**dict(BL, solar_low_cloud=29)))[1], '20-29')
        self.assertEqual(bl_size.kind_of(mk(**dict(BL, solar_low_cloud=30)))[1], '30-39')

    def test_sensitivity_bands_are_not_bl(self):
        for low in (15, 19, 40, 45):
            self.assertEqual(kind(solar_low_cloud=low), 'SENS', low)
        for low in (14, 46, 70):
            self.assertEqual(kind(solar_low_cloud=low), 'OTHER', low)

    def test_canvas_must_be_under_10_on_both_sides(self):
        for col in ('solar_mid_cloud', 'solar_high_cloud', 'antisolar_mid_cloud', 'antisolar_high_cloud'):
            self.assertEqual(kind(**{col: 9}), 'BL', col)
            self.assertEqual(kind(**{col: 10}), 'OTHER', col)

    def test_far_low_under_30(self):
        self.assertEqual(kind(far_solar_low_cloud=29), 'BL')
        self.assertEqual(kind(far_solar_low_cloud=30), 'OTHER')

    def test_observer_low_under_60(self):
        self.assertEqual(kind(low_cloud=59), 'BL')
        self.assertEqual(kind(low_cloud=60), 'OTHER')

    def test_building_trend_excluded_null_is_not_building(self):
        self.assertEqual(kind(solar_trend_building=True), 'OTHER')
        self.assertEqual(kind(solar_trend_building=None), 'BL')

    def test_null_input_is_untestable(self):
        for col in bl_size.REQUIRED_SLOT_INPUTS:
            self.assertEqual(kind(**{col: None}), 'UNTESTABLE', col)

    def test_control_is_clear_cone_with_the_same_filters(self):
        self.assertEqual(kind(solar_low_cloud=9), 'CL')
        self.assertEqual(kind(solar_low_cloud=0), 'CL')
        self.assertEqual(kind(solar_low_cloud=10), 'OTHER')
        self.assertEqual(kind(solar_low_cloud=5, solar_mid_cloud=10), 'OTHER')
        self.assertEqual(kind(solar_low_cloud=5, antisolar_high_cloud=10), 'OTHER')
        self.assertEqual(kind(solar_low_cloud=5, far_solar_low_cloud=30), 'OTHER')
        self.assertEqual(kind(solar_low_cloud=5, low_cloud=60), 'OTHER')
        self.assertEqual(kind(solar_low_cloud=5, solar_trend_building=True), 'OTHER')

    def test_era5_consistent_and_heterogeneous(self):
        r = mk(**dict(BL, cv_horizon_low_min=10.0, cv_horizon_low_max=29.0))
        self.assertTrue(bl_size.era5_consistent(r))
        self.assertFalse(bl_size.heterogeneous(r))
        self.assertTrue(bl_size.heterogeneous(mk(**dict(BL, cv_horizon_low_min=10.0, cv_horizon_low_max=30.0))))
        self.assertTrue(bl_size.era5_consistent(mk(**dict(BL, cv_horizon_low_cloud=10.0))))
        self.assertTrue(bl_size.era5_consistent(mk(**dict(BL, cv_horizon_low_cloud=50.0))))
        self.assertFalse(bl_size.era5_consistent(mk(**dict(BL, cv_horizon_low_cloud=9.0))))
        self.assertFalse(bl_size.era5_consistent(mk(**dict(BL, cv_horizon_low_cloud=51.0))))
        self.assertFalse(bl_size.era5_consistent(mk(**dict(BL, cv_horizon_mid_cloud=20.0))))
        self.assertFalse(bl_size.era5_consistent(mk(**dict(BL, cv_horizon_high_cloud=20.0))))
        self.assertIsNone(bl_size.era5_consistent(mk(**dict(BL, cv_id=None))))


class SensitivityNeverDecidesTest(unittest.TestCase):
    def test_sensitivity_slots_change_no_decision_number(self):
        rows, _ = scenario(10, 10)
        base = run_gate(rows)
        extra = [slot_row(40 + i, solar_low_cloud=17, sky_rating=1, rating=1, summary='No canvas') for i in range(30)]
        extra += [slot_row(80 + i, solar_low_cloud=42, sky_rating=1, rating=1, summary='No canvas') for i in range(30)]
        with_sens = run_gate(rows + extra)
        self.assertEqual(base['e1'], with_sens['e1'])
        self.assertEqual(base['e2'], with_sens['e2'])
        self.assertEqual(base['freq']['bl_evenings'], with_sens['freq']['bl_evenings'])
        u = bl_size.build_units(rows + extra)
        self.assertEqual(len(u['sens']), 60)
        self.assertEqual(len(u['bl']), 10)


class RatingRulesTest(unittest.TestCase):
    def write(self, lines):
        d = tempfile.mkdtemp()
        p = os.path.join(d, 'r.csv')
        with open(p, 'w', newline='') as f:
            f.write('location_id,event_date,event_type,rating,seen,forecast_rating,by_admin\n')
            f.write('\n'.join(lines) + '\n')
        return p

    def test_not_seen_and_empty_rows_are_invalid(self):
        p = self.write(['1,2026-11-01,SUNSET,,NOT_SEEN,3,t', '1,2026-11-02,SUNSET,,,3,t',
                        '1,2026-11-03,SUNSET,4,COLOUR,3,t', '1,2026-11-04,SUNSET,4,,3,f'])
        rs = bl_size.load_ratings(p)
        self.assertEqual([r['valid'] for r in rs], [False, False, True, True])
        self.assertEqual([r['by_admin'] for r in rs], [True, True, True, False])
        self.assertEqual(len(bl_size.ratings_by_slot(rs)), 2)

    def test_missing_by_admin_column_is_an_error(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, 'r.csv')
        with open(p, 'w') as f:
            f.write('location_id,event_date,event_type,rating,seen,forecast_rating\n1,2026-11-01,SUNSET,4,COLOUR,3\n')
        with self.assertRaises(ValueError):
            bl_size.load_ratings(p)

    def test_several_ratings_are_averaged_within_the_slot(self):
        u = bl_size.build_units([slot_row(0)])
        s = u['bl'][0]
        by = bl_size.ratings_by_slot([rating(1, 0, 2), rating(1, 0, 4, admin=False), rating(1, 0, 5, admin=False)])
        self.assertAlmostEqual(bl_size.slot_outcome(s, by, False)[0], 11 / 3)
        self.assertEqual(bl_size.slot_outcome(s, by, True)[0], 2)

    def test_admin_decides_other_readers_are_reported_only(self):
        rows, ratings = scenario(10, 10, bl_sky=2, bl_out=4, cl_out=2)
        # every reader who is not the admin says the opposite; they must not move the decision
        others = [rating(1, i, 1 if i % 2 == 0 else 5, admin=False) for i in range(20)]
        a = bl_size.analyse(rows, ratings + others, DAY0, 300)
        self.assertEqual(a['gate']['outcome'], 'proceed')
        b = bl_size.analyse(rows, others, DAY0, 300)
        self.assertEqual(b['gate']['outcome'], 'waiting')   # no admin ratings: nothing can decide
        us_all = bl_size.matched_units(a['units']['bl'], a['units']['cl'], a['by_slot'], False)
        us_adm = bl_size.matched_units(a['units']['bl'], a['units']['cl'], a['by_slot'], True)
        self.assertNotEqual([u[4] for u in us_all], [u[4] for u in us_adm])

    def test_historic_ratings_never_decide(self):
        rows, ratings = scenario(10, 10, bl_sky=2)
        g = run_gate(rows, ratings, DAY0 + timedelta(days=100))   # protocol starts after every rating
        self.assertEqual(g['outcome'], 'waiting')
        self.assertEqual(run_gate(rows, ratings, None)['outcome'], 'waiting')   # no start, no protocol evidence

    def test_protocol_start_boundary_is_inclusive(self):
        rows, ratings = scenario(10, 10, bl_sky=2)
        a = bl_size.analyse(rows, ratings, DAY0, 300)
        self.assertEqual(a['protocol']['bl_evenings'] + a['protocol']['cl_evenings'], 20)
        b = bl_size.analyse(rows, ratings, DAY0 + timedelta(days=1), 300)
        self.assertEqual(b['protocol']['bl_evenings'] + b['protocol']['cl_evenings'], 19)


class E1E2Test(unittest.TestCase):
    def test_e1_share_of_bl_slots_forecast_1_or_2(self):
        rows = [slot_row(i, sky_rating=s, rating=s) for i, s in enumerate([1, 2, 3, 4, 5])]
        u = bl_size.build_units(rows)
        self.assertEqual(bl_size.e1_counts(u['bl']), (2, 5))

    def test_e2_matcher_phrases_case_insensitive(self):
        for p in bl_size.PHRASES:
            self.assertEqual(bl_size.e2_phrase('There is ' + p.upper() + ' here.'), p)
            self.assertEqual(bl_size.e2_phrase(p.title()), p)
        self.assertEqual(bl_size.PHRASES, ('no canvas', 'nothing to catch', 'no cloud to catch',
                                           'no mid or high cloud', 'clear sky'))
        self.assertIsNone(bl_size.e2_phrase('Broken low cloud with a good gap.'))
        self.assertIsNone(bl_size.e2_phrase(None))

    def test_e2_rate_over_bl_slots_with_a_summary(self):
        rows = [slot_row(0, summary='No canvas tonight'), slot_row(1, summary='Nothing to catch the light'),
                slot_row(2, summary='A modest sky'), slot_row(3, summary=''), slot_row(4, summary=None)]
        u = bl_size.build_units(rows)
        self.assertEqual(bl_size.e2_counts(u['bl']), (2, 3))

    def test_twenty_examples_are_listed(self):
        rows = [slot_row(i % 28, loc=1 + i // 28, name='L%d' % (i // 28), summary='Clear sky %d' % i)
                for i in range(40)]
        u = bl_size.build_units(rows)
        g = bl_size.gate(u, None, False, None)
        text = bl_size.render(u, g, None, None, 10, None, None)
        self.assertEqual(text.count('[MATCH "clear sky"]'), 20)


class EveningCountTest(unittest.TestCase):
    def test_counts_are_distinct_date_event_pairs(self):
        rows = [slot_row(0, loc=1), slot_row(0, loc=2, name='Beta'), slot_row(0, loc=3, name='Gamma'),
                slot_row(1, loc=1), slot_row(1, loc=1, event='SUNRISE'), slot_row(2, solar_low_cloud=60)]
        u = bl_size.build_units(rows)
        f = bl_size.frequency(u)
        self.assertEqual(len(u['bl']), 5)
        self.assertEqual(f['bl_evenings'], 3)
        self.assertEqual(f['evenings'], 4)
        self.assertAlmostEqual(f['share'], 0.75)


class MatchedComparisonTest(unittest.TestCase):
    def test_matched_on_forecast_star_not_pooled(self):
        # BL slots were forecast 2 and CL slots 3: nothing matches; a stratum needs both arms.
        rows = [slot_row(0, sky_rating=2), slot_row(1, solar_low_cloud=5)]
        by = bl_size.ratings_by_slot([rating(1, 0, 5, fr=2), rating(1, 1, 1, fr=3)])
        u = bl_size.build_units(rows)
        us = bl_size.matched_units(u['bl'], u['cl'], by, True)
        self.assertIsNone(bl_size.evaluate_matched(us, 50)['point'])
        by2 = bl_size.ratings_by_slot([rating(1, 0, 5, fr=3), rating(1, 1, 1, fr=3)])
        us2 = bl_size.matched_units(u['bl'], u['cl'], by2, True)
        self.assertEqual(bl_size.evaluate_matched(us2, 50)['point'], 4)

    def test_strata_are_weighted_and_only_1_to_3_count(self):
        units = [('2026-11-01', 'SUNSET', 'BL', 1, 3.0), ('2026-11-02', 'SUNSET', 'CL', 1, 1.0),    # diff +2, weight 0.5
                 ('2026-11-03', 'SUNSET', 'BL', 2, 4.0), ('2026-11-04', 'SUNSET', 'BL', 2, 4.0),
                 ('2026-11-05', 'SUNSET', 'CL', 2, 2.0), ('2026-11-06', 'SUNSET', 'CL', 2, 2.0)]    # diff +2, weight 1
        ev = bl_size.evaluate_matched(units, 50)
        self.assertAlmostEqual(ev['point'], 2.0)
        by = bl_size.ratings_by_slot([rating(1, 0, 5, fr=4), rating(1, 1, 1, fr=5)])
        u = bl_size.build_units([slot_row(0), slot_row(1, solar_low_cloud=5)])
        self.assertEqual(bl_size.matched_units(u['bl'], u['cl'], by, True), [])

    def test_forecast_star_is_rounded_mean_of_the_slot_ratings(self):
        u = bl_size.build_units([slot_row(0)])
        by = bl_size.ratings_by_slot([rating(1, 0, 3, fr=2), rating(1, 0, 3, fr=3, admin=False)])
        self.assertEqual(bl_size.slot_outcome(u['bl'][0], by, False)[1], 3)   # 2.5 rounds up
        self.assertEqual(bl_size.slot_outcome(u['bl'][0], by, True)[1], 2)

    def test_evening_is_the_unit_not_the_slot(self):
        # three BL locations on one evening are one unit: their mean
        rows = [slot_row(0, loc=i, name='L%d' % i) for i in (1, 2, 3)] + [slot_row(1, solar_low_cloud=5)]
        by = bl_size.ratings_by_slot([rating(1, 0, 5), rating(2, 0, 5), rating(3, 0, 2), rating(1, 1, 1)])
        u = bl_size.build_units(rows)
        us = bl_size.matched_units(u['bl'], u['cl'], by, True)
        self.assertEqual(sorted(x[2:] for x in us), [('BL', 3, 4.0), ('CL', 3, 1.0)])

    def test_bootstrap_lower_bound_positive_for_a_clear_difference(self):
        rows, ratings = scenario(12, 12, bl_out=4, cl_out=2)
        by = bl_size.ratings_by_slot(ratings)
        u = bl_size.build_units(rows)
        ev = bl_size.evaluate_matched(bl_size.matched_units(u['bl'], u['cl'], by, True), 400)
        self.assertEqual(ev['bl_evenings'], 12)
        self.assertEqual(ev['cl_evenings'], 12)
        self.assertGreater(ev['lo'], 0)
        self.assertAlmostEqual(ev['point'], 2.0)

    def test_bootstrap_lower_bound_not_positive_for_no_difference(self):
        rows, ratings = scenario(12, 12, bl_out=3, cl_out=3)
        by = bl_size.ratings_by_slot(ratings)
        u = bl_size.build_units(rows)
        ev = bl_size.evaluate_matched(bl_size.matched_units(u['bl'], u['cl'], by, True), 400)
        self.assertLessEqual(ev['lo'], 0)


def staged(spec, bl_sky=2):
    """spec: list of (class, forecast star, outcome), one evening per entry on consecutive days. Returns
    (rows, ratings), every rating by the admin."""
    rows, ratings = [], []
    for i, (cls, fr, out) in enumerate(spec):
        low = 25 if cls == 'BL' else 5
        rows.append(slot_row(i, solar_low_cloud=low, sky_rating=bl_sky, rating=bl_sky, summary='Plain.'))
        ratings.append(rating(1, i, out, fr=fr))
    return rows, ratings


class MatchedStrataGateTest(unittest.TestCase):
    def test_unmatched_strata_cannot_meet_the_evening_gate(self):
        # 7 BL only in stratum 1, 7 CL only in stratum 3, one of each in stratum 2
        spec = [('BL', 1, 5)] * 7 + [('CL', 3, 1)] * 7 + [('BL', 2, 5), ('CL', 2, 1)]
        rows, ratings = staged(spec)
        a = bl_size.analyse(rows, ratings, DAY0, 300)
        self.assertEqual((a['protocol']['bl_evenings'], a['protocol']['cl_evenings']), (1, 1))
        self.assertEqual(a['protocol']['matched_strata'], [2])
        self.assertEqual(a['gate']['outcome'], 'waiting')

    def test_counts_are_matched_evenings_only_and_outcome_follows_the_bound(self):
        spec = []
        for k in range(5):
            spec += [('BL', 2, 4), ('CL', 2, 2), ('BL', 3, 4), ('CL', 3, 2)]     # 10 + 10 across two shared strata
        spec += [('BL', 1, 5)] * 3 + [('CL', 3, 2)] * 0                         # unmatched extras
        rows, ratings = staged(spec)
        a = bl_size.analyse(rows, ratings, DAY0, 400)
        p = a['protocol']
        self.assertEqual((p['bl_evenings'], p['cl_evenings']), (10, 10))
        self.assertEqual(p['matched_strata'], [2, 3])
        self.assertEqual(a['gate']['outcome'], 'proceed')
        flat = [('BL', 2, 3), ('CL', 2, 3), ('BL', 3, 3), ('CL', 3, 3)] * 5 + [('BL', 1, 5)] * 3
        rows, ratings = staged(flat)
        self.assertEqual(bl_size.analyse(rows, ratings, DAY0, 400)['gate']['outcome'], 'stop')

    def test_unmatched_stratum_does_not_enter_the_estimate(self):
        base = [('BL', 2, 4), ('CL', 2, 2)] * 6
        r1, g1 = staged(base)
        r2, g2 = staged(base + [('BL', 1, 5), ('BL', 1, 5)])
        e1 = bl_size.evaluate_matched(bl_size.matched_units(*[bl_size.build_units(r1)[k] for k in ('bl', 'cl')],
                                                           bl_size.ratings_by_slot(g1), True), 200)
        e2 = bl_size.evaluate_matched(bl_size.matched_units(*[bl_size.build_units(r2)[k] for k in ('bl', 'cl')],
                                                           bl_size.ratings_by_slot(g2), True), 200)
        self.assertEqual(e1['point'], e2['point'])
        self.assertEqual((e1['lo'], e1['hi']), (e2['lo'], e2['hi']))
        self.assertFalse([t for t in e2['strata'] if t['stratum'] == 1][0]['matched'])


class GateOutcomeTest(unittest.TestCase):
    def test_proceed_via_e1(self):
        rows, ratings = scenario(10, 10, bl_sky=2, bl_summary='A modest low deck.')
        g = run_gate(rows, ratings, DAY0)
        self.assertEqual((g['outcome'], g['via']), ('proceed', ['E1']))

    def test_proceed_via_e2(self):
        rows, ratings = scenario(10, 10, bl_sky=3, bl_summary='There is no canvas to speak of.')
        g = run_gate(rows, ratings, DAY0)
        self.assertEqual((g['outcome'], g['via']), ('proceed', ['E2']))

    def test_stop_on_criterion_1(self):
        rows, ratings = scenario(10, 10, bl_sky=3, bl_summary='A modest low deck.')
        g = run_gate(rows, ratings, DAY0)
        self.assertEqual(g['outcome'], 'stop')
        self.assertFalse(g['c1'])
        self.assertTrue(g['c2'])
        self.assertIn('criterion 1', g['reason'])

    def test_stop_on_criterion_2(self):
        rows, ratings = scenario(1, 10, bl_sky=1, bl_summary='No canvas', n_other=300)
        g = run_gate(rows, ratings, DAY0)
        self.assertEqual(g['outcome'], 'stop')
        self.assertTrue(g['c1'])
        self.assertFalse(g['c2'])
        self.assertIn('criterion 2', g['reason'])

    def test_stop_on_criterion_3_when_no_outcome_difference(self):
        rows, ratings = scenario(12, 12, bl_sky=2, bl_out=3, cl_out=3)
        g = run_gate(rows, ratings, DAY0, draws=400)
        self.assertEqual(g['outcome'], 'stop')
        self.assertIn('criterion 3', g['reason'])

    def test_waiting_when_fewer_than_8_evenings_in_an_arm(self):
        rows, ratings = scenario(7, 12, bl_sky=2)
        g = run_gate(rows, ratings, DAY0)
        self.assertEqual(g['outcome'], 'waiting')
        rows, ratings = scenario(12, 7, bl_sky=2)
        self.assertEqual(run_gate(rows, ratings, DAY0)['outcome'], 'waiting')
        rows, ratings = scenario(8, 8, bl_sky=2)
        self.assertEqual(run_gate(rows, ratings, DAY0)['outcome'], 'proceed')

    def test_waiting_without_ratings_or_protocol_start(self):
        rows, _ = scenario(10, 10, bl_sky=2)
        self.assertEqual(run_gate(rows)['outcome'], 'waiting')
        self.assertIn('no ratings file', run_gate(rows)['reason'])

    def test_criteria_1_and_2_stop_even_without_ratings(self):
        rows, _ = scenario(10, 10, bl_sky=4, bl_summary='ok')
        self.assertEqual(run_gate(rows)['outcome'], 'stop')

    def test_no_bl_slots_stops(self):
        rows = [slot_row(i, solar_low_cloud=5) for i in range(10)]
        g = run_gate(rows)
        self.assertEqual(g['outcome'], 'stop')

    def test_decision_block_text(self):
        rows, ratings = scenario(10, 10, bl_sky=2)
        text = '\n'.join(bl_size.decision_block(run_gate(rows, ratings, DAY0)))
        self.assertIn('OUTCOME: proceed', text)
        rows, _ = scenario(10, 10, bl_sky=2)
        self.assertIn('OUTCOME: waiting for protocol ratings', '\n'.join(bl_size.decision_block(run_gate(rows))))


class ExclusionTest(unittest.TestCase):
    def angel(self, d, **kw):
        dt = datetime.strptime(d, '%Y-%m-%d')
        base = dict(BL, evaluation_id=next(_eid), location_id=9, loc_name='Angel of the North', target_date=d,
                    target_type='SUNSET', forecast_run_at=dt.replace(hour=12), solar_event_time=dt.replace(hour=16),
                    sky_rating=1, rating=1, summary='No canvas')
        base.update(kw)
        return mk(**base)

    def test_31_oct_and_training_cases_are_excluded_from_every_count(self):
        rows = [self.angel('2026-10-31'), self.angel('2026-10-13'),
                mk(**dict(BL, evaluation_id=next(_eid), location_id=8, loc_name='Low Hauxley',
                          target_date='2026-10-04', target_type='SUNRISE',
                          forecast_run_at=datetime(2026, 10, 4, 3, 0), solar_event_time=datetime(2026, 10, 4, 6, 40)))]
        rows += [slot_row(i) for i in range(3)]
        u = bl_size.build_units(rows)
        self.assertEqual(len(u['bl']), 3)
        self.assertIsNotNone(u['excluded_31'])
        self.assertEqual(len(u['excluded_training']), 2)
        g = bl_size.gate(u, None, False, None)
        self.assertEqual(g['e1'], (0, 3))     # the 1-star Angel rows would have made it 2/5
        self.assertEqual(g['e2'], (0, 3))
        self.assertEqual(g['freq']['evenings'], 3)

    def test_a_different_location_on_31_oct_is_not_excluded(self):
        rows = [self.angel('2026-10-31', loc_name='Somewhere Else', location_id=10)]
        self.assertEqual(len(bl_size.build_units(rows)['bl']), 1)

    def test_31_oct_run_choice_is_printed_and_may_be_later_than_137844(self):
        rows = [self.angel('2026-10-31', evaluation_id=137844, forecast_run_at=datetime(2026, 10, 31, 12, 38)),
                self.angel('2026-10-31', evaluation_id=137901, forecast_run_at=datetime(2026, 10, 31, 14, 10))]
        text = '\n'.join(bl_size.case_31_oct_lines(bl_size.build_units(rows)))
        self.assertIn('evaluation 137901', text)
        self.assertIn('as expected', text)
        early = [self.angel('2026-10-31', evaluation_id=137000)]
        self.assertIn('EARLIER', '\n'.join(bl_size.case_31_oct_lines(bl_size.build_units(early))))
        self.assertIn('No Angel', '\n'.join(bl_size.case_31_oct_lines(bl_size.build_units([slot_row(0)]))))


class CliTest(unittest.TestCase):
    def run_cli(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = bl_size.main(list(args))
        return rc, out.getvalue()

    def test_end_to_end_on_fixtures(self):
        d = tempfile.mkdtemp()
        fc = os.path.join(FIX, 'bl_forecasts.csv')
        rc, out = self.run_cli(fc, '--out', d)
        self.assertEqual(rc, 0)
        self.assertTrue(os.path.exists(os.path.join(d, 'bl.md')))
        self.assertIn('OUTCOME: waiting for protocol ratings', out)
        self.assertIn('evaluation 137901', out)
        rc, out = self.run_cli(fc, '--ratings', os.path.join(FIX, 'bl_ratings.csv'), '--out', d)
        self.assertEqual(rc, 0)
        self.assertIn('OUTCOME: waiting for protocol ratings', out)
        self.assertIn('no --protocol-start', out)
        rc, out = self.run_cli(fc, '--ratings', os.path.join(FIX, 'bl_ratings.csv'), '--protocol-start',
                               '2026-11-08', '--out', d, '--draws', '300')
        self.assertEqual(rc, 0)
        self.assertIn('OUTCOME: proceed', out)
        with open(os.path.join(d, 'bl.md'), encoding='utf-8') as f:
            text = f.read()
        self.assertIn('## 8. The A5 gate', text)
        with open(os.path.join(d, 'bl_slots.csv'), newline='') as f:
            rows = list(csv.DictReader(f))
        self.assertEqual(sorted({r['kind'] for r in rows}), ['BL', 'CL'])
        self.assertEqual(sum(1 for r in rows if r['kind'] == 'BL'), 12)
        self.assertTrue(all(r['location'] != 'Angel of the North' for r in rows))

    def test_fixture_forecasts_parse_with_premise(self):
        rows = premise.load_rows(os.path.join(FIX, 'bl_forecasts.csv'))
        self.assertEqual(len(rows), 51)

    def test_bad_input_exits_2(self):
        d = tempfile.mkdtemp()
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            rc = bl_size.main([os.path.join(FIX, 'bl_forecasts.csv'), '--protocol-start', 'yesterday', '--out', d])
        self.assertEqual(rc, 2)
        p = os.path.join(d, 'bad.csv')
        with open(p, 'w') as f:
            f.write('location_id,event_date\n1,2026-11-01\n')
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            rc = bl_size.main([os.path.join(FIX, 'bl_forecasts.csv'), '--ratings', p, '--out', d])
        self.assertEqual(rc, 2)


if __name__ == '__main__':
    unittest.main()
