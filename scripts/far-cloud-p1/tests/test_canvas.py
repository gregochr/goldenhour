"""Tests for canvas.py (weak-canvas sizing) on synthetic rows. Rows come from test_premise.mk."""
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
import canvas  # noqa: E402
import premise  # noqa: E402
from test_premise import mk  # noqa: E402

# observer 0/0, cone low 7 / high 91: the Angel's 13 October shape.
WEAK = dict(mid_cloud=0, high_cloud=0, solar_low_cloud=7, solar_high_cloud=91,
            antisolar_mid_cloud=0, antisolar_high_cloud=0)


def pop(rows):
    return canvas.population(rows)


class DefinitionTest(unittest.TestCase):
    def test_weak_canvas_boundaries(self):
        self.assertTrue(canvas.weak_canvas(mk(**WEAK)))
        self.assertTrue(canvas.weak_canvas(mk(**dict(WEAK, mid_cloud=19, high_cloud=19, solar_low_cloud=19,
                                                     solar_high_cloud=60))))
        self.assertFalse(canvas.weak_canvas(mk(**dict(WEAK, mid_cloud=20))))
        self.assertFalse(canvas.weak_canvas(mk(**dict(WEAK, high_cloud=20))))
        self.assertFalse(canvas.weak_canvas(mk(**dict(WEAK, solar_low_cloud=20))))
        self.assertFalse(canvas.weak_canvas(mk(**dict(WEAK, solar_high_cloud=59))))

    def test_null_input_is_untestable_not_a_hit(self):
        r = mk(**dict(WEAK, solar_high_cloud=None))
        self.assertFalse(canvas.weak_testable(r))
        self.assertFalse(canvas.weak_canvas(r))

    def test_canopy_overhead(self):
        self.assertTrue(canvas.canopy_overhead(mk(mid_cloud=60, high_cloud=0)))
        self.assertTrue(canvas.canopy_overhead(mk(mid_cloud=0, high_cloud=60)))
        self.assertFalse(canvas.canopy_overhead(mk(mid_cloud=59, high_cloud=59)))
        self.assertFalse(canvas.canopy_overhead(mk(mid_cloud=None, high_cloud=90)))


class Era5ChecksTest(unittest.TestCase):
    def test_all_three_agree(self):
        r = mk(cv_observer_mid_cloud=5.0, cv_observer_high_cloud=10.0, cv_horizon_high_cloud=80.0,
               cv_horizon_low_cloud=3.0)
        self.assertEqual(canvas.era5_checks(r), {'overhead_clear': True, 'cone_high': True,
                                                 'cone_low_clear': True, 'all': True})

    def test_overhead_disagrees(self):
        r = mk(cv_observer_mid_cloud=100.0, cv_observer_high_cloud=84.0, cv_horizon_high_cloud=80.0,
               cv_horizon_low_cloud=3.0)
        c = canvas.era5_checks(r)
        self.assertFalse(c['overhead_clear'])
        self.assertFalse(c['all'])

    def test_no_verification_row_is_unknown(self):
        c = canvas.era5_checks(mk(cv_id=None))
        self.assertEqual(set(c.values()), {None})

    def test_missing_value_with_a_known_failure_is_false_else_unknown(self):
        r = mk(cv_observer_mid_cloud=None, cv_observer_high_cloud=5.0, cv_horizon_high_cloud=10.0,
               cv_horizon_low_cloud=3.0)
        self.assertIsNone(canvas.era5_checks(r)['overhead_clear'])
        self.assertFalse(canvas.era5_checks(r)['all'])  # cone high < 60 already fails
        r2 = mk(cv_observer_mid_cloud=None, cv_observer_high_cloud=5.0, cv_horizon_high_cloud=80.0,
                cv_horizon_low_cloud=3.0)
        self.assertIsNone(canvas.era5_checks(r2)['all'])

    def test_one_known_value_over_the_limit_makes_overhead_false(self):
        r = mk(cv_observer_mid_cloud=None, cv_observer_high_cloud=50.0, cv_horizon_high_cloud=80.0,
               cv_horizon_low_cloud=3.0)
        self.assertFalse(canvas.era5_checks(r)['overhead_clear'])


class PopulationTest(unittest.TestCase):
    def test_selection_matches_premise(self):
        rows = [
            mk(location_id=1, **WEAK),                                        # go, weak canvas
            mk(location_id=2, **dict(WEAK, mid_cloud=70)),                    # go, canopy
            mk(location_id=3, rating=3, **WEAK),                              # not a go
            mk(location_id=4, days_ahead=2, **WEAK),                          # T+2: out
            mk(location_id=5, batch_state=None, **WEAK),                      # synchronous: out
            mk(location_id=6, loc_types='WILDLIFE', **WEAK),                  # not a sky location: out
            mk(location_id=7, triage_reason='LOW_VISIBILITY', rating=None, **WEAK),  # triaged: not prompted
        ]
        p = pop(rows)
        self.assertEqual(sorted(s['key'][0] for s in p['gos']), [1, 2])
        self.assertEqual(sorted(s['key'][0] for s in p['rated']), [1, 2, 3])

    def test_latest_run_decides(self):
        rows = [mk(location_id=1, forecast_run_at=datetime(2026, 10, 13, 8), **WEAK),
                mk(location_id=1, forecast_run_at=datetime(2026, 10, 13, 12), rating=2, **WEAK)]
        self.assertEqual(pop(rows)['gos'], [])

    def test_training_case_included_and_flagged(self):
        rows = [mk(loc_name='Angel of the North', **WEAK)]
        p = pop(rows)
        self.assertEqual(len(p['gos']), 1)
        self.assertEqual(p['gos'][0]['training'], 'Angel of the North')


class ReportTest(unittest.TestCase):
    def rows(self):
        return [
            mk(location_id=1, loc_name='Angel of the North', cv_observer_mid_cloud=16.0, cv_observer_high_cloud=8.0,
               cv_horizon_high_cloud=90.0, cv_horizon_low_cloud=2.0, **WEAK),
            mk(location_id=2, evaluation_model='HAIKU', **WEAK),
            mk(location_id=3, **dict(WEAK, mid_cloud=100, high_cloud=100)),
            mk(location_id=4, rating=2, mid_cloud=99, high_cloud=100, solar_low_cloud=43, low_cloud=99,
               cv_observer_low_cloud=2.0, cv_horizon_low_cloud=3.0, solar_high_cloud=100, solar_mid_cloud=100),
            mk(location_id=5, rating=2, mid_cloud=10, high_cloud=10, solar_low_cloud=10, solar_high_cloud=10),
        ]

    def test_counts_by_model(self):
        p = pop(self.rows())
        g = {(r[0], r[1]): r for r in canvas.by_model_event(p['gos'])}
        self.assertEqual(g[('SONNET_55', 'all')][2], 2)      # locations 1 and 3
        self.assertIn('1 (50.0%)', g[('SONNET_55', 'all')][4])
        self.assertIn('1 (100.0%)', g[('HAIKU', 'all')][4])
        self.assertEqual(g[('all', 'all')][2], 3)

    def test_by_date_lists_only_hit_dates(self):
        p = pop(self.rows() + [mk(location_id=9, target_date='2026-10-14', rating=4, mid_cloud=90)])
        self.assertEqual([r[0] for r in canvas.by_date(p['gos'])], ['2026-10-13'])

    def test_mirror_class_and_low_deck(self):
        p = pop(self.rows())
        m = canvas.mirror(p['rated'])
        self.assertEqual(sorted(s['key'][0] for s in m['canopy']), [3, 4])   # the go and the rated-2 slot
        self.assertEqual([s['key'][0] for s in m['missed']], [4])
        row = canvas.low_deck_rows(m['missed'])[0]
        self.assertEqual(row[1], 1)
        self.assertIn('1 (100.0%)', row[4])                  # forecast deck 99, ERA5 clear 2
        self.assertIn('0 (0.0%)', row[5])

    def test_reverse_direction_and_disjoint_contrast(self):
        rows = [mk(location_id=1, rating=2, mid_cloud=90, low_cloud=5, cv_observer_low_cloud=80.0),
                mk(location_id=2, rating=4, mid_cloud=90, low_cloud=95, cv_observer_low_cloud=3.0)]
        m = canvas.mirror(pop(rows)['rated'])
        self.assertEqual([s['key'][0] for s in m['missed']], [1])
        self.assertEqual([s['key'][0] for s in m['higher']], [2])
        self.assertIn('1 (100.0%)', canvas.low_deck_rows(m['missed'])[0][5])   # ERA5 deck, forecast clear
        self.assertIn('1 (100.0%)', canvas.low_deck_rows(m['higher'])[0][4])   # forecast deck, ERA5 clear

    def test_era5_view_cells_by_model(self):
        p = pop(ReportTest().rows())
        hits = [s for s in p['gos'] if canvas.weak_canvas(s['row'])]
        rows = {r[0]: r for r in canvas.era5_view(hits)}
        # columns: test, HAIKU, SONNET_55, all. The Angel row (SONNET_55) has a cirrus cone in ERA5; the
        # HAIKU row keeps mk's defaults (cone high 5), so it fails the cone-high test and the conjunction.
        counts = lambda key: [c.split(' (')[0] for c in rows[key][1:]]  # noqa: E731
        self.assertEqual(counts('all three'), ['0 of 1', '1 of 1', '1 of 2'])
        self.assertEqual(counts('cone high >= 60 (horizon cirrus)'), ['0 of 1', '1 of 1', '1 of 2'])
        self.assertIn(' W[', rows['all three'][3])
        self.assertIn(' B[', rows['all three'][3])

    def test_date_block_interval_beside_wilson_everywhere_a_rate_is_printed(self):
        p = pop(self.rows())
        g = {(r[0], r[1]): r for r in canvas.by_model_event(p['gos'])}
        self.assertIn(' W[', g[('SONNET_55', 'all')][4])
        self.assertIn(' B[', g[('SONNET_55', 'all')][4])
        m = canvas.mirror(p['rated'])
        for cell in canvas.low_deck_rows(m['missed'])[0][4:]:
            self.assertIn(' B[', cell)
        text, _ = canvas.render(self.rows())
        self.assertIn('5-day [', text)
        self.assertIn('3-day [', text)
        self.assertIn('7-day [', text)

    def test_headline_reports_strict_beside_broad(self):
        gos = pop(self.rows())['gos']
        rows = canvas.headline_rows(gos)
        self.assertEqual([r[0].split(' (')[0] for r in rows],
                         ['weak canvas', 'strict Angel shape', 'weak canvas'])
        # three gos (locations 1 to 3); 1 and 2 are weak canvases and, with a clear antisolar side, strict ones
        broad, strict = rows[0], rows[1]
        self.assertEqual((broad[1], broad[2]), (2, 3))
        self.assertEqual((strict[1], strict[2]), (2, 3))
        text, _ = canvas.render(self.rows())
        self.assertIn('strict Angel shape (antisolar mid/high < 20 too)', text)

    def test_strict_is_a_subset_of_broad_when_the_antisolar_side_is_not_clear(self):
        rows = [mk(location_id=1, **WEAK), mk(location_id=2, **dict(WEAK, antisolar_mid_cloud=60))]
        r = canvas.headline_rows(pop(rows)['gos'])
        self.assertEqual((r[0][1], r[1][1]), (2, 1))

    def test_sensitivity_without_one_date(self):
        other = dict(forecast_run_at=datetime(2026, 10, 15, 10), solar_event_time=datetime(2026, 10, 15, 17, 10),
                     target_date='2026-10-15')
        rows = [mk(location_id=1, **WEAK), mk(location_id=2, **dict(WEAK, **other)),
                mk(location_id=3, **dict(WEAK, **other))]
        line = canvas.sensitivity_line(pop(rows)['gos'])
        self.assertIn('(2 of the gos are dated 2026-10-15)', line)
        self.assertIn('broad: all dates 3 (100.0%)', line)
        self.assertIn('without 2026-10-15 1 (100.0%)', line)

    def test_render_without_ratings_names_the_second_export(self):
        text, hits = canvas.render(self.rows())
        self.assertEqual(len(hits), 2)
        self.assertIn('export_outcome_ratings.sql', text)
        self.assertIn('Angel of the North', text)
        self.assertIn('Sizing, not a decision', text)

    def test_render_with_ratings_joins_by_slot(self):
        ratings = {(1, '2026-10-13', 'SUNSET'): [{'rating': '2', 'seen': 'FLAT', 'forecast_rating': '4'}],
                   (1, '2026-10-13', 'SUNRISE'): [{'rating': '5'}]}   # other event: must not join
        text, _ = canvas.render(self.rows(), ratings)
        self.assertIn('| 2 | FLAT | 4 |', text)
        self.assertIn('1 with at least one reader rating (1 gos have none', text)


class IoTest(unittest.TestCase):
    def test_load_ratings_requires_columns(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, 'r.csv')
            with open(p, 'w', newline='') as f:
                csv.writer(f).writerows([['location_id', 'event_date'], ['1', '2026-10-15']])
            with self.assertRaises(ValueError):
                canvas.load_ratings(p)

    def test_load_ratings_keeps_not_seen_and_several_readers(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, 'r.csv')
            with open(p, 'w', newline='') as f:
                csv.writer(f).writerows([['location_id', 'event_date', 'event_type', 'rating'],
                                         ['1', '2026-10-15', 'SUNSET', '4'], ['1', '2026-10-15', 'SUNSET', '']])
            self.assertEqual(len(canvas.load_ratings(p)[(1, '2026-10-15', 'SUNSET')]), 2)

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
                self.assertEqual(canvas.main([src, '--out', out]), 0)
            with open(os.path.join(out, 'canvas.md')) as f:
                self.assertIn('Weak-canvas sizing', f.read())
            with open(os.path.join(out, 'canvas_weak_gos.csv'), newline='') as f:
                got = list(csv.DictReader(f))
            self.assertEqual(sorted(g['location_id'] for g in got), ['1', '2'])
            self.assertEqual({g['training'] for g in got}, {'', 'Angel of the North'})

    def test_main_reports_a_bad_header(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, 'bad.csv')
            with open(p, 'w') as f:
                f.write('a,b\n1,2\n')
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(canvas.main([p, '--out', d]), 2)


if __name__ == '__main__':
    unittest.main()
