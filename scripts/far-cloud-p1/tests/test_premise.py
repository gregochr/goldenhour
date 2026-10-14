"""Tests for premise.py on synthetic rows (plan section 4 definitions and section 2 Part B)."""
import contextlib
import io
import itertools
import os
import sys
import tempfile
import unittest
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
import premise  # noqa: E402
import stats  # noqa: E402

_ids = itertools.count(1)


def mk(**kw):
    """A parsed row with sensible defaults; values may be given already typed (None = NULL)."""
    base = {c: None for c in premise.COLUMNS}
    base.update({
        'location_id': 1, 'target_date': '2026-10-13', 'target_type': 'SUNSET',
        'forecast_run_at': datetime(2026, 10, 13, 10, 0, 0), 'solar_event_time': datetime(2026, 10, 13, 17, 10, 0),
        'days_ahead': 0, 'evaluation_model': 'SONNET_55', 'batch_state': 'SCORED', 'rating': 4,
        'loc_name': 'Somewhere', 'loc_lat': 54.9, 'loc_lon': -1.6, 'loc_region_id': 1, 'region_name': 'North East',
        'loc_types': 'LANDSCAPE', 'cv_id': 1,
        'solar_low_cloud': 7, 'solar_mid_cloud': 24, 'solar_high_cloud': 91, 'mid_cloud': 0, 'high_cloud': 0,
        'antisolar_mid_cloud': 0, 'antisolar_high_cloud': 0,
        'cv_horizon_low_cloud': 10.0, 'cv_horizon_low_min': 5.0, 'cv_horizon_mid_cloud': 5.0,
        'cv_horizon_high_cloud': 5.0, 'cv_far_low_cloud': 10.0, 'cv_observer_mid_cloud': 5.0,
        'cv_observer_high_cloud': 5.0,
    })
    base.update(kw)
    base['evaluation_id'] = kw.get('evaluation_id', next(_ids))
    return base


def run_at(h, m=0):
    return datetime(2026, 10, 13, h, m, 0)


def slots_of(rows):
    s, _ = premise.build_slots(rows)
    return s


class LatestSelectionTest(unittest.TestCase):
    def test_latest_ignores_pending_abandoned_and_post_event(self):
        rows = [mk(forecast_run_at=run_at(8), rating=3),
                mk(forecast_run_at=run_at(12), rating=4),
                mk(forecast_run_at=run_at(15), rating=2, batch_state='PENDING'),
                mk(forecast_run_at=run_at(16), rating=1, batch_state='ABANDONED'),
                mk(forecast_run_at=run_at(18), rating=2)]  # after the 17:10 event
        s = slots_of(rows)
        self.assertEqual(len(s), 1)
        self.assertEqual(s[0]['row']['rating'], 4)
        self.assertTrue(s[0]['go'])
        self.assertEqual(s[0]['engine'], 'batch')

    def test_only_post_event_rows_means_no_slot(self):
        s, none = premise.build_slots([mk(forecast_run_at=run_at(18))])
        self.assertEqual((len(s), none), (0, 1))

    def test_later_triage_row_means_not_a_go(self):
        rows = [mk(forecast_run_at=run_at(8), rating=4),
                mk(forecast_run_at=run_at(12), rating=None, triage_reason='HIGH_CLOUD', batch_state=None)]
        s = slots_of(rows)[0]
        self.assertFalse(s['go'])
        self.assertEqual(s['engine'], 'triage')

    def test_sync_engine_classified(self):
        s = slots_of([mk(batch_state=None)])[0]
        self.assertEqual(s['engine'], 'sync')

    def test_previous_rated_run_skips_triaged_and_null(self):
        rows = [mk(forecast_run_at=run_at(6), rating=3),
                mk(forecast_run_at=run_at(8), rating=None, triage_reason='X', batch_state=None),
                mk(forecast_run_at=run_at(9), rating=None),
                mk(forecast_run_at=run_at(12), rating=4)]
        s = slots_of(rows)[0]
        self.assertEqual(s['prev']['rating'], 3)
        self.assertEqual(s['raised'], 'same')

    def test_raised_changed_model_none_and_not_raised(self):
        changed = slots_of([mk(forecast_run_at=run_at(6), rating=3, evaluation_model='HAIKU'),
                            mk(forecast_run_at=run_at(12), rating=4)])[0]
        self.assertEqual(changed['raised'], 'changed')
        noprev = slots_of([mk(forecast_run_at=run_at(12), rating=4)])[0]
        self.assertEqual(noprev['raised'], 'noprev')
        steady = slots_of([mk(forecast_run_at=run_at(6), rating=4), mk(forecast_run_at=run_at(12), rating=5)])[0]
        self.assertIsNone(steady['raised'])


class PremiseTest(unittest.TestCase):
    def test_flags_each_premise_separately(self):
        r = mk(cv_horizon_low_cloud=75.0, cv_far_low_cloud=20.0)
        self.assertEqual(premise.premises(r), {'low': True, 'far': False, 'canvas': True})

    def test_canvas_false_when_any_ge_20(self):
        r = mk(cv_observer_high_cloud=20.0)
        self.assertFalse(premise.premises(r)['canvas'])

    def test_no_cv_row_all_unknown(self):
        r = mk(cv_id=None)
        self.assertEqual(premise.premises(r), {'low': None, 'far': None, 'canvas': None})

    def test_null_values_are_unknown_for_that_premise_only(self):
        r = mk(cv_horizon_low_cloud=None, cv_observer_mid_cloud=None)
        p = premise.premises(r)
        self.assertIsNone(p['low'])
        self.assertIsNone(p['canvas'])
        self.assertFalse(p['far'])

    def test_era5_canvas_cost_flag(self):
        self.assertTrue(premise.era5_canvas(mk(cv_horizon_high_cloud=30.0, cv_observer_mid_cloud=None)))
        self.assertFalse(premise.era5_canvas(mk()))
        self.assertIsNone(premise.era5_canvas(mk(cv_observer_mid_cloud=None)))


class AngelShapeTest(unittest.TestCase):
    def test_shape(self):
        self.assertTrue(premise.angel_shape(mk()))

    def test_near_canvas_breaks_it(self):
        self.assertFalse(premise.angel_shape(mk(antisolar_high_cloud=20)))
        self.assertFalse(premise.angel_shape(mk(mid_cloud=25)))

    def test_cone_thresholds(self):
        self.assertFalse(premise.angel_shape(mk(solar_low_cloud=20)))
        self.assertFalse(premise.angel_shape(mk(solar_high_cloud=59)))
        self.assertTrue(premise.angel_shape(mk(solar_high_cloud=60, solar_low_cloud=19)))

    def test_null_input_is_not_the_shape(self):
        self.assertFalse(premise.angel_shape(mk(antisolar_mid_cloud=None)))

    def test_capped(self):
        self.assertTrue(premise.already_capped(mk(solar_trend_building=True, upwind_current_low_cloud=60.0)))
        self.assertFalse(premise.already_capped(mk(solar_trend_building=True, upwind_current_low_cloud=59.0)))
        self.assertFalse(premise.already_capped(mk(solar_trend_building=False, upwind_current_low_cloud=90.0)))
        self.assertFalse(premise.already_capped(mk()))


class FilterTest(unittest.TestCase):
    def test_sky_location_filter(self):
        self.assertTrue(premise.is_sky_location(None))
        self.assertTrue(premise.is_sky_location('LANDSCAPE'))
        self.assertTrue(premise.is_sky_location('SEASCAPE,WOODLAND'))
        self.assertTrue(premise.is_sky_location('WATERFALL,WILDLIFE'))
        self.assertFalse(premise.is_sky_location('WILDLIFE'))
        self.assertFalse(premise.is_sky_location('WOODLAND'))
        self.assertFalse(premise.is_sky_location('BLUEBELL,WOODLAND'))

    def test_wildlife_and_woodland_excluded_from_pool(self):
        rows = [mk(location_id=1, loc_types='LANDSCAPE'), mk(location_id=2, loc_types='WILDLIFE'),
                mk(location_id=3, loc_types='WOODLAND')]
        a = premise.analyse(rows, draws=20)
        self.assertEqual([s['row']['location_id'] for s in a['gos']], [1])

    def test_training_cases_excluded_but_listed(self):
        rows = [mk(location_id=1, loc_name='Angel of the North'),
                mk(location_id=2, loc_name='Low Hauxley', target_type='SUNRISE', target_date='2026-10-04',
                   solar_event_time=datetime(2026, 10, 4, 6, 20), forecast_run_at=datetime(2026, 10, 4, 1, 0)),
                mk(location_id=3, loc_name='Copt Hill', target_date='2026-03-11',
                   solar_event_time=datetime(2026, 3, 11, 17, 40), forecast_run_at=datetime(2026, 3, 11, 9, 0)),
                mk(location_id=4, loc_name='Angel of the North', target_date='2026-10-12',
                   solar_event_time=datetime(2026, 10, 12, 17, 12), forecast_run_at=datetime(2026, 10, 12, 9, 0)),
                mk(location_id=5)]
        a = premise.analyse(rows, draws=20)
        labels = sorted(s['training'] for s in a['slots'] if s['training'])
        self.assertEqual(labels, ['Angel of the North', 'Copt Hill 2026-03-11', 'Low Hauxley'])
        # the Angel on another date, and the ordinary location, are pooled
        self.assertEqual(sorted(s['row']['location_id'] for s in a['gos']), [4, 5])
        text = premise.render(a)
        self.assertIn('Training cases', text)
        self.assertIn('Low Hauxley', text)


class SunBandTest(unittest.TestCase):
    def test_angel_sunset_slot_is_in_lowest_band(self):
        # Durham-ish, 13 Oct 2026, sunset ~17:10 UTC, hourly slot 17:00: sun well under 2 degrees
        alt, band = premise.sun_band(mk(loc_lat=54.91, loc_lon=-1.59))
        self.assertGreaterEqual(alt, 0)
        self.assertEqual(band, '0-2')

    def test_sunrise_slot_after_event(self):
        r = mk(target_type='SUNRISE', solar_event_time=datetime(2026, 10, 13, 6, 5, 0))
        alt, band = premise.sun_band(r)
        self.assertIn(band, premise.BANDS)
        self.assertIsNotNone(alt)

    def test_wrong_time_is_other(self):
        r = mk(solar_event_time=datetime(2026, 10, 13, 12, 0, 0))  # noon "sunset": sun high
        self.assertEqual(premise.sun_band(r)[1], '>=9')

    def test_missing_coordinates(self):
        self.assertEqual(premise.sun_band(mk(loc_lat=None))[1], 'unknown')


def _dated(i, **kw):
    d = datetime(2026, 9, 1) + timedelta(days=i)
    ds = d.date().isoformat()
    ev = d.replace(hour=17, minute=10)
    kw.setdefault('forecast_run_at', ev - timedelta(hours=5))
    return mk(target_date=ds, solar_event_time=ev, **kw)


class PipelineTest(unittest.TestCase):
    def _population(self):
        """40 days x 2 locations: Angel-shape gos mostly P-low, ordinary gos rarely."""
        rows = []
        for i in range(40):
            rows.append(_dated(i, location_id=1, cv_horizon_low_cloud=80.0 if i % 4 else 10.0))
            rows.append(_dated(i, location_id=2, solar_low_cloud=30, cv_horizon_low_cloud=80.0 if i % 20 == 0 else 5.0))
        return rows

    def test_primary_test_pass_with_enough_units(self):
        a = premise.analyse(self._population(), draws=300)
        t = a['tests_loc']['R1 (Angel shape / canvas)']
        self.assertEqual((t['n1'], t['n2']), (40, 40))
        self.assertGreaterEqual(t['k1'], 10)
        self.assertEqual(t['verdict'], 'pass')
        self.assertGreater(t['newcombe'][1], 0)
        self.assertIsNotNone(t['p_holm'])
        text = premise.render(a)
        self.assertIn('**pass**', text)

    def test_small_sample_is_inconclusive(self):
        rows = [_dated(i, location_id=1, cv_horizon_low_cloud=80.0) for i in range(8)]
        rows += [_dated(i, location_id=2, solar_low_cloud=30, cv_horizon_low_cloud=5.0) for i in range(8)]
        a = premise.analyse(rows, draws=50)
        for t in a['tests_loc'].values():
            self.assertEqual(t['verdict'], 'inconclusive')

    def test_r2_only_same_model_raises(self):
        rows = [_dated(0, location_id=1, forecast_run_at=datetime(2026, 9, 1, 6, 0), rating=3),
                _dated(0, location_id=1, forecast_run_at=datetime(2026, 9, 1, 12, 0), rating=4),
                _dated(1, location_id=1, forecast_run_at=datetime(2026, 9, 2, 6, 0), rating=3, evaluation_model='HAIKU'),
                _dated(1, location_id=1, forecast_run_at=datetime(2026, 9, 2, 12, 0), rating=4)]
        a = premise.analyse(rows, draws=20)
        raised = {s['date']: s['raised'] for s in a['gos']}
        self.assertEqual(raised, {'2026-09-01': 'same', '2026-09-02': 'changed'})
        r2 = a['tests_loc']['R2 (raised, same model)']
        self.assertEqual(r2['n1'], 1)

    def test_cost_side(self):
        flagged = slots_of([mk(location_id=1, cv_horizon_high_cloud=40.0), mk(location_id=2),
                            mk(location_id=3, cv_id=None, cv_horizon_mid_cloud=None)])
        t, kn, unk, share = premise.cost_side(flagged)
        self.assertEqual((t, kn, unk), (1, 2, 1))
        self.assertAlmostEqual(share, 0.5)

    def test_synchronous_rows_not_pooled(self):
        a = premise.analyse([mk(location_id=1, batch_state=None), mk(location_id=2)], draws=20)
        self.assertEqual(len(a['gos']), 1)
        self.assertEqual(len(a['sync']), 1)

    def test_region_event_needs_five_locations(self):
        rows = [mk(location_id=i) for i in range(1, 5)]
        a = premise.analyse(rows, draws=20)
        self.assertEqual(premise.region_event_units(a['pooled'], lambda s: s['angel']), [])
        rows.append(mk(location_id=5, cv_horizon_low_cloud=90.0))
        a = premise.analyse(rows, draws=20)
        units = premise.region_event_units(a['pooled'], lambda s: s['angel'])
        self.assertEqual(len(units), 1)
        self.assertTrue(units[0]['flag'])
        self.assertFalse(units[0]['out'])  # 1 of 5 is P-low

    def test_region_event_flagged_if_any_go_flagged(self):
        # finding 5: one flagged go of five used to fail the half-of-slots rule only when unflagged
        rows = [mk(location_id=i) for i in range(1, 6)]
        a = premise.analyse(rows, draws=20)
        units = premise.region_event_units(a['pooled'], lambda s: s['row']['location_id'] == 3)
        self.assertEqual(len(units), 1)
        self.assertTrue(units[0]['flag'])
        none = premise.region_event_units(a['pooled'], lambda s: False)
        self.assertFalse(none[0]['flag'])

    def test_few_distinct_dates_is_inconclusive(self):
        # finding 2: 40 flagged units on 4 dates (10 locations per date) cannot pass
        rows = []
        for i in range(4):
            for loc in range(10):
                rows.append(_dated(i, location_id=100 + loc, cv_horizon_low_cloud=85.0))
        for i in range(40):
            rows.append(_dated(i, location_id=2, solar_low_cloud=30, cv_horizon_low_cloud=5.0))
        a = premise.analyse(rows, draws=100)
        t = a['tests_loc']['R1 (Angel shape / canvas)']
        self.assertLess(t['dates1'], stats.MIN_FLAGGED_DATES + 1)
        self.assertEqual(t['verdict'], 'inconclusive')
        self.assertIn('distinct dates', t['reason'])
        text = premise.render(a)
        self.assertIn('dates)', text)

    def test_shape_null_exclusion_count_reported(self):
        rows = [mk(location_id=1), mk(location_id=2, antisolar_mid_cloud=None, antisolar_high_cloud=None)]
        text = premise.render(premise.analyse(rows, draws=20))
        self.assertIn('excluded from the shape test because an input was NULL (pre-directional capture): 1 of 2', text)

    def test_report_states_disabled_kept(self):
        text = premise.render(premise.analyse([mk()], draws=20))
        self.assertIn('Disabled locations are kept for history', text)


class AngelLineTest(unittest.TestCase):
    def _text(self, **kw):
        rows = [mk(location_id=1, loc_name='Angel of the North', **kw), mk(location_id=2)]
        return premise.render(premise.analyse(rows, draws=20))

    def test_warning_when_angel_passes_everything(self):
        text = self._text(cv_observer_mid_cloud=30.0)
        self.assertIn(premise.ANGEL_WARNING, text)
        self.assertTrue(text.split('\n', 3)[2].startswith('**Angel sanity check:**'))

    def test_warning_when_unverified(self):
        self.assertIn(premise.ANGEL_WARNING, self._text(cv_id=None))

    def test_far_or_canvas_flag_alone_does_not_silence_warning(self):
        # finding 1: P-far feeds no decision and P-canvas must not judge R1
        self.assertIn(premise.ANGEL_WARNING, self._text(cv_far_low_cloud=85.0))
        self.assertIn(premise.ANGEL_WARNING, self._text(cv_observer_mid_cloud=30.0))
        self.assertIn(premise.ANGEL_WARNING, self._text(cv_far_low_cloud=85.0, cv_observer_mid_cloud=30.0))

    def test_angel_line_labels_each_premise_and_unknown(self):
        text = self._text(cv_far_low_cloud=85.0, cv_horizon_low_cloud=None)
        line = [l for l in text.split('\n') if l.startswith('**Angel sanity check:**')][0]
        self.assertIn('P-low (the only premise that counts) unknown (not yet verified)', line)
        self.assertIn('P-far (diagnostic only, feeds no decision) FLAGS', line)
        self.assertIn('P-canvas (upper bound, does not judge R1) FLAGS', line)
        unverified = self._text(cv_id=None)
        self.assertIn('unknown (not yet verified)', unverified)

    def test_no_warning_when_a_premise_flags_it(self):
        text = self._text(cv_horizon_low_cloud=85.0)
        self.assertNotIn(premise.ANGEL_WARNING, text)

    def test_warning_when_angel_missing(self):
        text = premise.render(premise.analyse([mk()], draws=20))
        self.assertIn(premise.ANGEL_WARNING, text)

    def test_commit_from_env(self):
        os.environ['P1_COMMIT'] = 'abc1234'
        try:
            self.assertIn('abc1234', premise.render(premise.analyse([mk()], draws=20)))
        finally:
            del os.environ['P1_COMMIT']


class SunBandSplitTest(unittest.TestCase):
    def test_below_horizon_and_high_are_separate_bands(self):
        night = mk(solar_event_time=datetime(2026, 10, 13, 22, 0, 0))
        self.assertEqual(premise.sun_band(night)[1], '<0')
        noon = mk(solar_event_time=datetime(2026, 10, 13, 12, 0, 0))
        self.assertEqual(premise.sun_band(noon)[1], '>=9')
        self.assertNotIn('other', premise.BANDS)


class HeaderCompatTest(unittest.TestCase):
    def _write(self, tmp, header, rows):
        import csv
        p = os.path.join(tmp, 'x.csv')
        with open(p, 'w', newline='', encoding='utf-8') as f:
            w = csv.writer(f)
            w.writerow(header)
            w.writerows(rows)
        return p

    def test_superset_and_reordered_header_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            cols = list(reversed(premise.COLUMNS)) + ['loc_tide_types']
            row = {c: '' for c in cols}
            row.update({'evaluation_id': '7', 'location_id': '3', 'loc_name': 'A', 'rating': '4',
                        'loc_tide_types': 'HIGH'})
            p = self._write(tmp, cols, [[row[c] for c in cols]])
            rows = premise.load_rows(p)
            self.assertEqual((rows[0]['evaluation_id'], rows[0]['loc_name'], rows[0]['rating']), (7, 'A', 4))
            self.assertNotIn('loc_tide_types', rows[0])

    def test_missing_required_column_is_clear(self):
        with tempfile.TemporaryDirectory() as tmp:
            cols = [c for c in premise.COLUMNS if c != 'rating']
            p = self._write(tmp, cols, [])
            with self.assertRaises(ValueError) as cm:
                premise.load_rows(p)
            self.assertIn('rating', str(cm.exception))
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                self.assertEqual(premise.main([p, '--out', tmp]), 2)
            self.assertIn('rating', err.getvalue())


class FixtureCliTest(unittest.TestCase):
    def test_fixture_loads_and_cli_writes_outputs(self):
        path = os.path.join(HERE, 'fixtures', 'forecasts_small.csv')
        rows = premise.load_rows(path)
        self.assertGreater(len(rows), 10)
        self.assertIsInstance(rows[0]['forecast_run_at'], datetime)
        self.assertIsNone(rows[0]['triage_message'])
        with tempfile.TemporaryDirectory() as tmp:
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(premise.main([path, '--out', tmp, '--draws', '50']), 0)
            with open(os.path.join(tmp, 'part_b.md'), encoding='utf-8') as f:
                report = f.read()
            self.assertIn('Primary tests', report)
            self.assertTrue(os.path.exists(os.path.join(tmp, 'slots.csv')))
            self.assertTrue(os.path.exists(os.path.join(tmp, 'region_events.csv')))
        a = premise.analyse(rows, draws=20)
        by_loc = {s['row']['loc_name']: s for s in a['slots']}
        self.assertEqual(by_loc['Fixture Latest']['row']['rating'], 4)
        self.assertEqual(by_loc['Fixture Raised']['raised'], 'same')
        self.assertTrue(by_loc['Fixture Angel Shape']['angel'])
        self.assertIsNone(by_loc['Fixture NoCV']['pr']['low'])
        self.assertTrue(by_loc['Angel of the North']['training'])
        self.assertFalse(by_loc['Fixture Hide']['sky'])


if __name__ == '__main__':
    unittest.main()


class CostVerdictTest(unittest.TestCase):
    def test_unknown_canvases_count_against_the_rule(self):
        self.assertEqual(premise.cost_verdict(1, 10, 90), 'inconclusive (90 unknown)')
        self.assertEqual(premise.cost_verdict(1, 10, 0), 'ok')
        self.assertEqual(premise.cost_verdict(1, 10, 1), 'ok')        # worst case 2/11 = 18%
        self.assertEqual(premise.cost_verdict(5, 10, 3), 'EXCEEDS')   # known share alone 50%
        self.assertEqual(premise.cost_verdict(0, 0, 0), 'n/a')
        self.assertEqual(premise.cost_verdict(0, 0, 4), 'inconclusive (4 unknown)')
