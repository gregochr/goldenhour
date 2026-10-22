"""Tests for canopyreplay.py (replay of rated forecast-deck-under-canopy analogues) on synthetic rows."""
import contextlib
import csv
import io
import os
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
import canopyreplay  # noqa: E402
import premise  # noqa: E402
from test_premise import mk  # noqa: E402


def row(eid, low=90, mid=80, high=0, **kw):
    return mk(**dict(dict(evaluation_id=eid, location_id=eid, low_cloud=low, mid_cloud=mid, high_cloud=high,
                          solar_low_cloud=50, cv_observer_low_cloud=0.0, cv_horizon_low_cloud=10.0,
                          cv_observer_mid_cloud=0.0, cv_observer_high_cloud=0.0), **kw))


def raw(r):
    """The raw-string forecast row replay_set reads (only what select needs)."""
    return {'evaluation_id': str(r['evaluation_id']), 'location_id': str(r['location_id']),
            'loc_name': r['loc_name'], 'target_date': r['target_date'], 'target_type': r['target_type'],
            'evaluation_model': r['evaluation_model'], 'rating': str(r['rating'] or ''),
            'sky_rating': str(r['rating'] or ''), 'loc_tide_types': ''}


def prompt(eid):
    return {'evaluation_id': str(eid), 'user_message': 'message %s' % eid}


class SelectTest(unittest.TestCase):
    def pick(self, rows, prompts=None):
        prompts = prompts if prompts is not None else [prompt(r['evaluation_id']) for r in rows]
        return [r['evaluation_id'] for r in canopyreplay.select(rows, [raw(r) for r in rows], prompts)]

    def test_selects_rated_forecast_deck_under_a_forecast_canopy_only(self):
        rows = [row(1, rating=2), row(2, rating=4),                                   # selected
                row(3, mid=10, high=10, rating=3),                                    # no forecast canopy
                row(4, low=59, rating=3),                                             # no forecast deck
                row(5, rating=None, batch_state=None, triage_reason='HIGH_CLOUD',
                    triage_message='Solar horizon low cloud 90% — sun blocked'),      # triaged: no prompt, not rated
                row(6, days_ahead=2, rating=3),                                       # not T+0/T+1
                row(7, mid=None, high=10, rating=3)]                                  # unknown canopy
        self.assertEqual(sorted(self.pick(rows)), ['1', '2'])

    def test_needs_a_stored_prompt(self):
        rows = [row(1, rating=2), row(2, rating=4)]
        self.assertEqual(self.pick(rows, [prompt(2)]), ['2'])

    def test_selection_ignores_era5(self):
        # the same forecast, ERA5 saying obstructed, clear, or absent: all selected (the rule keys on forecast only)
        rows = [row(1, rating=2, cv_observer_low_cloud=95.0), row(2, rating=2, cv_observer_low_cloud=0.0),
                row(3, rating=2, cv_id=None)]
        self.assertEqual(sorted(self.pick(rows)), ['1', '2', '3'])

    def test_latest_run_per_slot_decides(self):
        early = row(1, rating=2, forecast_run_at=datetime(2026, 10, 13, 8))
        late = row(2, rating=None, batch_state=None, triage_reason='HIGH_CLOUD', location_id=1,
                   triage_message='Low cloud cover 90% — sun blocked', forecast_run_at=datetime(2026, 10, 13, 12))
        self.assertEqual(self.pick([early, late]), [])

    def test_rows_carry_prompt_and_basis(self):
        r = row(1, rating=3)
        out = canopyreplay.select([r], [dict(raw(r), sky_rating='4')], [prompt(1)])
        self.assertEqual((out[0]['user_message'], out[0]['basis'], out[0]['basis_rating']), ('message 1', 'sky_rating', 4))


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.rows = [row(1, rating=4, cv_observer_low_cloud=5.0, cv_horizon_low_cloud=5.0),       # phantom, canopy-free
                     row(2, rating=2, cv_observer_low_cloud=90.0, cv_horizon_low_cloud=85.0),     # obstructed
                     row(3, rating=3, cv_observer_low_cloud=40.0, cv_horizon_low_cloud=30.0),     # middle
                     row(4, rating=3, cv_id=None)]                                                # no ERA5
        self.typed = {str(r['evaluation_id']): r for r in self.rows}
        self.manifest = [{'evaluation_id': str(r['evaluation_id']), 'label': str(r['evaluation_id']),
                          'group': 'canopy', 'training': '0', 'model': 'HAIKU',
                          'basis_rating': str(r['rating'])} for r in self.rows]
        self.replay = {'1': 4.0, '2': 3.0, '3': 2.0, '4': None}

    def text(self):
        return canopyreplay.render(self.manifest, self.replay, self.typed)

    def test_go_rate_by_era5_band(self):
        sec = self.text().split('## 1.')[1].split('## 2.')[0]
        self.assertIn('| ERA5 < 20 (phantom) | 1 | 1/1 (100.0%)', sec)           # stored 4, replay 4
        self.assertIn('| ERA5 >= 60 (obstructed) | 1 | 0/1 (0.0%)', sec)
        self.assertIn('| ERA5 20-59 | 1 | 0/1 (0.0%)', sec)
        self.assertNotIn('ERA5 unknown', sec)                                      # the unreplayed slot is not counted

    def test_cone_table_and_population_and_caveats(self):
        t = self.text()
        self.assertIn('## 2. Go rate by the ERA5 CONE low band', t)
        self.assertIn('Analogues in the manifest 4 (stored by HAIKU 4); replayed on SONNET_55 with a rating 3', t)
        self.assertIn('Not replayed (every attempt failed or absent): 1.', t)
        self.assertIn('their forecast cone low cloud is at most 80', t)
        self.assertIn('not about a released slot', t)
        self.assertIn('reanalysis, not an observation', t)
        for word in ('should', 'recommend ', 'we propose', 'conclude'):
            self.assertNotIn(word, t.lower())

    def test_training_and_other_groups_are_excluded(self):
        manifest = self.manifest + [dict(self.manifest[0], evaluation_id='1', group='canopy', training='1'),
                                    dict(self.manifest[1], group='go')]
        t = canopyreplay.render(manifest, self.replay, self.typed)
        self.assertIn('Analogues in the manifest 4 ', t)

    def test_phantom_analogues_are_listed(self):
        sec = self.text().split('## 5.')[1]
        self.assertIn('ERA5 observer low < 20: 1 analogues', sec)
        self.assertIn('| 1 | Somewhere | 2026-10-13 | SUNSET | 90 | 50 | 5 | 5 |', sec)

    def test_replay_medians_use_the_model_and_production_prompt_and_skip_failures(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, 'runs.csv')
            with open(p, 'w', newline='') as f:
                w = csv.writer(f)
                w.writerow(['label', 'model', 'system_prompt', 'rating'])
                w.writerows([['1', 'SONNET_55', 'production', '4'], ['1', 'SONNET_55', 'production', '2'],
                             ['2', 'SONNET_55', 'production', ''], ['3', 'SONNET_55', 'override', '5'],
                             ['4', 'HAIKU', 'production', '5']])
            got = canopyreplay.replay_medians(p, 'SONNET_55')
        self.assertEqual(got, {'1': 3.0, '2': None})


class IoTest(unittest.TestCase):
    def test_select_writes_prompts_and_manifest(self):
        rows = [row(1, rating=2), row(2, rating=4)]
        with tempfile.TemporaryDirectory() as d:
            fc = os.path.join(d, 'forecasts.csv')
            with open(fc, 'w', newline='') as f:
                w = csv.writer(f)
                w.writerow(premise.COLUMNS + ['loc_tide_types'])
                for r in rows:
                    w.writerow(['' if r[c] is None else (r[c].isoformat() if isinstance(r[c], datetime) else r[c])
                                for c in premise.COLUMNS] + [''])
            pr = os.path.join(d, 'prompts.csv')
            with open(pr, 'w', newline='') as f:
                w = csv.writer(f)
                w.writerow(['evaluation_id', 'user_message'])
                w.writerows([[1, 'first'], [2, 'second']])
            out = os.path.join(d, 'set')
            with contextlib.redirect_stdout(io.StringIO()) as buf:
                self.assertEqual(canopyreplay.main(['select', '--prompts', pr, '--forecasts', fc, '--out', out]), 0)
            self.assertIn('analogues with a stored prompt: 2', buf.getvalue())
            self.assertIn('COST ESTIMATE', buf.getvalue())
            self.assertEqual(sorted(p.name for p in Path(out).glob('*.txt')), ['1.inland.txt', '2.inland.txt'])
            with open(os.path.join(out, 'manifest.csv'), newline='') as f:
                m = list(csv.DictReader(f))
            # the synthetic rows carry no sky_rating, so the basis falls back to the combined rating
            self.assertEqual([(x['evaluation_id'], x['group'], x['basis']) for x in m],
                             [('1', 'canopy', 'rating'), ('2', 'canopy', 'rating')])

    def test_select_requires_the_tide_column(self):
        with tempfile.TemporaryDirectory() as d:
            fc = os.path.join(d, 'f.csv')
            with open(fc, 'w', newline='') as f:
                csv.writer(f).writerows([premise.COLUMNS, ['' for _ in premise.COLUMNS]])
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(canopyreplay.main(['select', '--prompts', fc, '--forecasts', fc, '--out', d]), 2)


if __name__ == '__main__':
    unittest.main()
