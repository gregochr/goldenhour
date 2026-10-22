"""Tests for lowdeck.py (over-forecast low deck) on synthetic rows. Rows come from test_premise.mk."""
import contextlib
import csv
import io
import os
import sys
import tempfile
import unittest
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
import lowdeck  # noqa: E402
import premise  # noqa: E402
from test_premise import mk, run_at  # noqa: E402

OBS = ('low_cloud', 'cv_observer_low_cloud')
CONE = ('solar_low_cloud', 'cv_horizon_low_cloud')


def row(f_obs=None, e_obs=None, f_cone=None, e_cone=None, **kw):
    return mk(**dict(dict(low_cloud=f_obs, cv_observer_low_cloud=e_obs, solar_low_cloud=f_cone,
                          cv_horizon_low_cloud=e_cone), **kw))


def slots(rows):
    return lowdeck.population(rows)['primary']


class CellTest(unittest.TestCase):
    def test_boundaries(self):
        c = lambda f, e: lowdeck.cell(row(f_obs=f, e_obs=e), *OBS)  # noqa: E731
        self.assertEqual(c(60, 19), 'phantom')
        self.assertEqual(c(59, 19), 'middle')      # forecast just under a deck
        self.assertEqual(c(60, 20), 'middle')      # ERA5 just above clear
        self.assertEqual(c(19, 60), 'missed')
        self.assertEqual(c(20, 60), 'middle')
        self.assertEqual(c(19, 59), 'middle')
        self.assertEqual(c(60, 60), 'both_high')
        self.assertEqual(c(19, 19), 'both_low')
        self.assertEqual(c(40, 40), 'middle')

    def test_cone_uses_its_own_columns(self):
        r = row(f_obs=100, e_obs=0, f_cone=0, e_cone=100)
        self.assertEqual(lowdeck.cell(r, *OBS), 'phantom')
        self.assertEqual(lowdeck.cell(r, *CONE), 'missed')

    def test_untestable_when_a_value_or_the_verification_row_is_missing(self):
        self.assertIsNone(lowdeck.cell(row(f_obs=None, e_obs=0), *OBS))
        self.assertIsNone(lowdeck.cell(row(f_obs=90, e_obs=None), *OBS))
        self.assertIsNone(lowdeck.cell(row(f_obs=90, e_obs=0, cv_id=None), *OBS))


class CanopyTest(unittest.TestCase):
    def test_canopy(self):
        mk_ = lambda m, h: mk(cv_observer_mid_cloud=m, cv_observer_high_cloud=h)  # noqa: E731
        self.assertTrue(lowdeck.era5_canopy(mk_(60, 0)))
        self.assertTrue(lowdeck.era5_canopy(mk_(0, 60)))
        self.assertFalse(lowdeck.era5_canopy(mk_(59, 59)))
        self.assertTrue(lowdeck.era5_canopy(mk_(None, 90)))     # one known value is enough
        self.assertIsNone(lowdeck.era5_canopy(mk_(None, 10)))   # cannot say
        self.assertIsNone(lowdeck.era5_canopy(mk_(None, None)))


class OutcomeTest(unittest.TestCase):
    def test_categories(self):
        o = lambda **kw: lowdeck.outcome(mk(**dict(dict(triage_reason=None, rating=None), **kw)))  # noqa: E731
        self.assertEqual(o(triage_reason='HIGH_CLOUD', triage_message='Solar horizon low cloud 100% — sun blocked'),
                         'triaged on low cloud')
        self.assertEqual(o(triage_reason='HIGH_CLOUD', triage_message='Low cloud cover 90% — sun blocked'),
                         'triaged on low cloud')
        # HIGH_CLOUD with no message, or one that does not name low cloud, is its own category, never low-cloud triage
        self.assertEqual(o(triage_reason='HIGH_CLOUD'), 'triaged, HIGH_CLOUD without a low-cloud message')
        self.assertEqual(o(triage_reason='HIGH_CLOUD', triage_message='something about mid cloud'),
                         'triaged, HIGH_CLOUD without a low-cloud message')
        self.assertEqual(o(triage_reason='PRECIPITATION', triage_message='low cloud'), 'triaged, other reason')
        self.assertEqual(o(rating=1), 'rated 1-2')
        self.assertEqual(o(rating=2), 'rated 1-2')
        self.assertEqual(o(rating=3), 'rated 3')
        self.assertEqual(o(rating=4), 'rated 4-5')
        self.assertEqual(o(rating=5), 'rated 4-5')
        self.assertEqual(o(), 'unrated, not triaged')

    def test_poor_or_blocked(self):
        p = lambda **kw: lowdeck.poor_or_blocked(mk(**dict(dict(triage_reason=None, rating=None), **kw)))  # noqa
        self.assertTrue(p(rating=3))
        self.assertTrue(p(triage_reason='HIGH_CLOUD', triage_message='Low cloud cover 90% — sun blocked'))
        self.assertFalse(p(triage_reason='HIGH_CLOUD'))             # no message: not folded into low-cloud triage
        self.assertFalse(p(rating=4))
        self.assertFalse(p(triage_reason='LOW_VISIBILITY'))
        self.assertFalse(p())


class PopulationTest(unittest.TestCase):
    def test_latest_run_per_slot_wins(self):
        rows = [row(f_obs=0, e_obs=0, location_id=1, forecast_run_at=run_at(8)),
                row(f_obs=90, e_obs=0, location_id=1, forecast_run_at=run_at(12))]
        s = slots(rows)
        self.assertEqual(len(s), 1)
        self.assertEqual(lowdeck.cell(s[0]['row'], *OBS), 'phantom')

    def test_filters(self):
        rows = [row(f_obs=90, e_obs=0, location_id=1),                                 # kept
                row(f_obs=90, e_obs=0, location_id=2, cv_id=None),                     # no ERA5 row
                row(f_obs=90, e_obs=0, location_id=3, loc_types='WOODLAND'),           # not a sky location
                row(f_obs=90, e_obs=0, location_id=4, days_ahead=2),                   # T+2: descriptive only
                row(f_obs=90, e_obs=0, location_id=5, days_ahead=None)]                # unknown lead
        p = lowdeck.population(rows)
        self.assertEqual([s['row']['location_id'] for s in p['primary']], [1])
        self.assertEqual(sorted(s['row']['location_id'] for s in p['verified']), [1, 4, 5])

    def test_triaged_slots_are_kept(self):
        rows = [row(f_obs=100, e_obs=0, rating=None, triage_reason='HIGH_CLOUD', batch_state=None,
                    triage_message='Solar horizon low cloud 100% — sun blocked')]
        s = slots(rows)
        self.assertEqual(len(s), 1)
        self.assertEqual(lowdeck.outcome(s[0]['row']), 'triaged on low cloud')


class MeasureTest(unittest.TestCase):
    def rows(self):
        return [row(f_obs=90, e_obs=0, location_id=1, rating=2),     # phantom, error +90
                row(f_obs=80, e_obs=10, location_id=2, rating=4),    # phantom, error +70
                row(f_obs=80, e_obs=70, location_id=3, rating=2),    # both high, error +10
                row(f_obs=40, e_obs=0, location_id=4, rating=3),     # middle
                row(f_obs=0, e_obs=90, location_id=5, rating=4),     # missed, error -90
                row(f_obs=70, e_obs=40, location_id=6, rating=3)]    # forecast deck, ERA5 middle: error +30

    def test_cells_and_forecast_decks(self):
        c = lowdeck.cells_of(slots(self.rows()), *OBS)
        self.assertEqual((c['n'], c['phantom'], c['both_high'], c['middle'], c['missed'], c['fc_deck']),
                         (6, 2, 1, 2, 1, 4))

    def test_signed_error_and_mae(self):
        me, mae = lowdeck.errors(slots(self.rows()), *OBS)
        self.assertAlmostEqual(me, (90 + 70 + 10 + 40 - 90 + 30) / 6)
        self.assertAlmostEqual(mae, (90 + 70 + 10 + 40 + 90 + 30) / 6)

    def test_errors_empty(self):
        self.assertEqual(lowdeck.errors([], *OBS), (None, None))

    def test_split_row_reports_both_denominators(self):
        r = lowdeck.split_row('all', slots(self.rows()), *OBS)
        self.assertEqual(r[1], 6)
        self.assertIn('2 (33.3%)', r[2])        # phantom of testable
        self.assertEqual(r[3], 4)               # forecast decks: phantom x2, both high, ERA5 middle
        self.assertIn('2 (50.0%)', r[4])        # phantom of forecast decks
        self.assertIn('1 (16.7%)', r[5])        # missed
        self.assertEqual(r[6], '+25.0')

    def test_contingency_grid_totals(self):
        text = '\n'.join(lowdeck.contingency(slots(self.rows()), *OBS))
        self.assertIn('| forecast >= 60 | 2 | 1 | 1 |', text)
        self.assertIn('| forecast 20-59 | 1 | 0 | 0 |', text)
        self.assertIn('| forecast < 20 | 0 | 0 | 1 |', text)

    def test_wilson_interval_printed(self):
        self.assertEqual(lowdeck.rate(0, 0), '-')
        self.assertEqual(lowdeck.rate(2, 5), '40.0% [11.8%, 76.9%]')

    def test_date_block_interval_sits_beside_every_wilson(self):
        units = slots(self.rows())
        cell = lowdeck.bshare(units, lambda u: lowdeck.cell(u['row'], *OBS) == 'phantom')
        self.assertTrue(cell.startswith('2 (33.3%) W[') and ' B[' in cell, cell)
        self.assertEqual(lowdeck.bshare([], lambda u: True), '-')
        self.assertIn(' B[', lowdeck.brate(units, lambda u: True))
        for c in lowdeck.split_row('all', units, *OBS)[2:6:2]:
            self.assertIn(' B[', c)
        for line in lowdeck.contingency(units, *OBS)[2:7]:
            self.assertIn(' B[', line)


class ReportTest(unittest.TestCase):
    def rows(self):
        canopy = dict(cv_observer_mid_cloud=90.0, cv_observer_high_cloud=80.0)
        clear = dict(cv_observer_mid_cloud=0.0, cv_observer_high_cloud=0.0)
        return [
            row(f_obs=100, e_obs=0, f_cone=90, e_cone=0, location_id=1, loc_name='Alpha', rating=2, **canopy),
            row(f_obs=100, e_obs=0, f_cone=80, e_cone=40, location_id=2, loc_name='Bravo', rating=None,
                batch_state=None, triage_reason='HIGH_CLOUD', triage_message='Solar horizon low cloud 80%',
                **canopy),
            row(f_obs=70, e_obs=10, f_cone=70, e_cone=10, location_id=3, loc_name='Charlie', rating=4,
                evaluation_model='HAIKU', **clear),
            row(f_obs=5, e_obs=5, f_cone=5, e_cone=5, location_id=4, loc_name='Delta', rating=5, **canopy),
        ]

    def test_render_sections_and_numbers(self):
        text, phantom = lowdeck.render(self.rows())
        for h in ('## Definitions', '## 1. Forecast against ERA5', '## 2 and 3.', '## 4. What the rating did',
                  '## 5. Phantom deck and a real canopy', '## 6. The 20 largest', 'Measurement, not a decision',
                  'reanalysis, not an observation'):
            self.assertIn(h, text)
        self.assertEqual(sorted(u['row']['loc_name'] for u in phantom), ['Alpha', 'Bravo', 'Charlie'])
        self.assertIn('Phantom-deck slots with an ERA5 canopy overhead: **2** = triaged on low cloud **1** + '
                      'triaged, HIGH_CLOUD without a low-cloud message **0** + triaged, other reason **0** + '
                      'rated 1-2 **1** + rated 3 **0** + rated 4-5 **0** + unrated, not triaged **0**.', text)

    def test_worst_is_ordered_by_gap_then_newest_date(self):
        rows = [row(f_obs=70, e_obs=0, location_id=1, loc_name='A'),
                row(f_obs=100, e_obs=10, location_id=2, loc_name='B'),
                row(f_obs=100, e_obs=0, location_id=3, loc_name='C'),
                row(f_obs=100, e_obs=0, location_id=4, loc_name='D', target_date='2026-10-12')]
        w = lowdeck.worst(slots(rows), *OBS)
        self.assertEqual([u['row']['loc_name'] for u in w], ['C', 'D', 'B', 'A'])

    def test_top_ties_counts_slots_sharing_the_largest_gap(self):
        rows = [row(f_obs=100, e_obs=0, location_id=1), row(f_obs=100, e_obs=0, location_id=2),
                row(f_obs=90, e_obs=0, location_id=3)]
        self.assertEqual(lowdeck.top_ties(slots(rows), *OBS), (100, 2))
        self.assertEqual(lowdeck.top_ties([], *OBS), (0, 0))

    def test_worst_is_capped(self):
        rows = [row(f_obs=100, e_obs=0, location_id=i, loc_name='L%02d' % i) for i in range(1, 30)]
        self.assertEqual(len(lowdeck.worst(slots(rows), *OBS)), lowdeck.WORST_N)

    def test_days_ahead_split_includes_later_leads_but_other_splits_do_not(self):
        rows = self.rows() + [row(f_obs=100, e_obs=0, location_id=9, loc_name='Echo', days_ahead=2, rating=2)]
        text, _ = lowdeck.render(rows)
        self.assertIn('| T+2 | 1 |', text)
        self.assertEqual(text.count('| T+2 |'), 2)          # observer and cone days_ahead tables only
        self.assertNotIn('Echo', text.split('## 6.')[1])    # not in the largest-phantom list (T+0/T+1 only)

    def test_report_states_its_caveats_and_no_verdict(self):
        text, _ = lowdeck.render(self.rows())
        self.assertIn('Wilson intervals (W) treat slots as independent', text)
        self.assertIn('date-block bootstrap', text)
        self.assertIn('Training cases', text)
        self.assertIn('share the largest gap', text)
        for word in ('should', 'recommend', 'we propose', 'conclude'):
            self.assertNotIn(word, text.lower().replace('recommends nothing', ''))

    def test_headline_table_has_all_three_block_lengths_and_a_sensitivity_line(self):
        text, _ = lowdeck.render(self.rows())
        self.assertIn('## Headline rates with date-block intervals', text)
        head = text.split('## Headline rates')[1].split('## 1.')[0]
        for tag in ('5-day [', '3-day [', '7-day ['):
            self.assertIn(tag, head)
        self.assertIn('observer phantom deck, of testable', head)
        self.assertIn('Sensitivity (0 of the T+0/T+1 slots are dated 2026-10-15)', head)

    def test_sensitivity_removes_the_named_date(self):
        rows = self.rows() + [row(f_obs=100, e_obs=0, location_id=9, loc_name='Echo', rating=2,
                                  target_date='2026-10-15',
                                  forecast_run_at=datetime(2026, 10, 15, 10), solar_event_time=datetime(2026, 10, 15, 17))]
        line = lowdeck.sensitivity_line(slots(rows))
        self.assertIn('(1 of the T+0/T+1 slots are dated 2026-10-15)', line)
        self.assertIn('all dates 4 (', line.split('cone')[0])
        self.assertIn('without 2026-10-15 3 (', line.split('cone')[0])

    def test_outcome_categories_partition_the_slots(self):
        us = slots(self.rows())
        cnt = lowdeck.outcome_counts(us)
        self.assertEqual(sum(cnt.values()), len(us))
        self.assertEqual(set(cnt), set(lowdeck.OUTCOMES))
        text, _ = lowdeck.render(self.rows())
        self.assertIn('HIGH_CLOUD triage check (T+0/T+1 slots): 1 slots with reason HIGH_CLOUD; message names low '
                      'cloud 1, message missing 0, message present but not naming low cloud 0', text)

    def test_high_cloud_message_check_counts_missing_and_other_messages(self):
        mk_ = lambda **kw: {'row': mk(**dict(dict(triage_reason='HIGH_CLOUD'), **kw))}  # noqa: E731
        units = [mk_(triage_message='Low cloud cover 90% — sun blocked'), mk_(triage_message=None),
                 mk_(triage_message='mid cloud'), {'row': mk(triage_reason='PRECIPITATION')}]
        self.assertEqual(lowdeck.high_cloud_message_check(units), (3, 1, 1, 1))
        # the report headline lists every category, so the HIGH_CLOUD oddities are visible, not folded
        cnt = lowdeck.outcome_counts(units)
        self.assertEqual((cnt['triaged on low cloud'], cnt['triaged, HIGH_CLOUD without a low-cloud message'],
                          cnt['triaged, other reason']), (1, 2, 1))

    def test_empty_population_renders(self):
        text, phantom = lowdeck.render([])
        self.assertEqual(phantom, [])
        self.assertIn('Over-forecast low deck', text)


class IoTest(unittest.TestCase):
    def test_main_writes_report_and_csv(self):
        rows = ReportTest().rows()
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, 'in.csv')
            with open(src, 'w', newline='') as f:
                w = csv.writer(f)
                w.writerow(premise.COLUMNS)
                for r in rows:
                    w.writerow(['' if r[c] is None else (r[c].isoformat() if isinstance(r[c], datetime) else r[c])
                                for c in premise.COLUMNS])
            out = os.path.join(d, 'out')
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(lowdeck.main([src, '--out', out]), 0)
            with open(os.path.join(out, 'lowdeck.md')) as f:
                self.assertIn('Over-forecast low deck', f.read())
            with open(os.path.join(out, 'lowdeck_phantom.csv'), newline='') as f:
                got = list(csv.DictReader(f))
            self.assertEqual(sorted(g['location'] for g in got), ['Alpha', 'Bravo', 'Charlie'])
            by = {g['location']: g for g in got}
            self.assertEqual(by['Bravo']['outcome'], 'triaged on low cloud')
            self.assertEqual((by['Alpha']['obs_phantom'], by['Bravo']['cone_phantom']), ('1', '0'))

    def test_main_reports_a_bad_header(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, 'bad.csv')
            with open(p, 'w') as f:
                f.write('a,b\n1,2\n')
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(lowdeck.main([p, '--out', d]), 2)


if __name__ == '__main__':
    unittest.main()
