"""Tests for canopydeck.py (phantom deck under a forecast canopy) on synthetic rows. Rows come from test_premise.mk."""
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
import canopydeck  # noqa: E402
import lowdeck  # noqa: E402
import premise  # noqa: E402
from test_premise import mk  # noqa: E402

HC = 'Solar horizon low cloud 90% — sun blocked'


def row(f_low=None, e_low=None, mid=0, high=0, **kw):
    return mk(**dict(dict(low_cloud=f_low, cv_observer_low_cloud=e_low, mid_cloud=mid, high_cloud=high,
                          cv_observer_mid_cloud=0.0, cv_observer_high_cloud=0.0), **kw))


def triaged(**kw):
    return dict(dict(batch_state=None, triage_reason='HIGH_CLOUD', triage_message=HC, rating=None), **kw)


def prim(rows):
    return lowdeck.population(rows)['primary']


class DefinitionTest(unittest.TestCase):
    def test_forecast_canopy_boundaries_and_unknowns(self):
        c = lambda m, h: canopydeck.forecast_canopy(mk(mid_cloud=m, high_cloud=h))  # noqa: E731
        self.assertTrue(c(60, 0))
        self.assertTrue(c(0, 60))
        self.assertFalse(c(59, 59))
        self.assertTrue(c(None, 90))       # one known value is enough
        self.assertIsNone(c(None, 10))
        self.assertIsNone(c(None, None))

    def test_forecast_deck_is_observer_low_at_60(self):
        self.assertTrue(canopydeck.forecast_deck(mk(low_cloud=60)))
        self.assertFalse(canopydeck.forecast_deck(mk(low_cloud=59)))
        self.assertFalse(canopydeck.forecast_deck(mk(low_cloud=None)))

    def test_era5_band_and_canopy_state(self):
        b = lambda v, **kw: canopydeck.era5_band(mk(cv_observer_low_cloud=v, **kw))  # noqa: E731
        self.assertEqual((b(19), b(20), b(59), b(60)), (0, 1, 1, 2))
        self.assertIsNone(b(None))
        self.assertIsNone(b(10, cv_id=None))
        s = lambda m, h: canopydeck.era5_canopy_state(mk(cv_observer_mid_cloud=m, cv_observer_high_cloud=h))  # noqa
        self.assertEqual((s(60, 0), s(0, 0), s(None, 10)), (0, 1, 2))

    def test_rule_keys_on_forecast_inputs_not_era5(self):
        base = triaged(low_cloud=100, mid_cloud=80)
        self.assertTrue(canopydeck.released_by_rule(mk(**dict(base, cv_observer_mid_cloud=0.0, cv_observer_high_cloud=0.0))))
        self.assertTrue(canopydeck.released_by_rule(mk(**dict(base, cv_id=None))))        # no ERA5 needed
        self.assertFalse(canopydeck.released_by_rule(mk(**dict(base, mid_cloud=10, high_cloud=10,
                                                               cv_observer_mid_cloud=95.0))))   # ERA5 canopy ignored
        self.assertFalse(canopydeck.released_by_rule(mk(**dict(base, mid_cloud=None, high_cloud=10))))  # unknown
        self.assertFalse(canopydeck.released_by_rule(mk(**dict(base, triage_reason='PRECIPITATION',
                                                               triage_message='Precipitation 2.10 mm'))))
        self.assertFalse(canopydeck.released_by_rule(mk(**dict(base, triage_message=None))))   # not low-cloud triage


class CostTest(unittest.TestCase):
    def test_call_cost(self):
        self.assertAlmostEqual(canopydeck.call_cost_usd('SONNET_55', 3500, 400), (3500 * 2.0 + 400 * 10.0) / 1e6)
        self.assertAlmostEqual(canopydeck.call_cost_usd('HAIKU', 1000, 100, 0.5), (1000 * 1.0 + 100 * 5.0) / 2e6)
        self.assertIsNone(canopydeck.call_cost_usd('OPUS', 1, 1))

    def test_calendar_days_span_includes_gaps(self):
        s = prim([row(f_low=1, e_low=1, target_date='2026-10-01',
                      forecast_run_at=datetime(2026, 10, 1, 8), solar_event_time=datetime(2026, 10, 1, 17)),
                  row(f_low=1, e_low=1, location_id=2, target_date='2026-10-04',
                      forecast_run_at=datetime(2026, 10, 4, 8), solar_event_time=datetime(2026, 10, 4, 17))])
        self.assertEqual(canopydeck.calendar_days(s), 4)
        self.assertEqual(canopydeck.calendar_days([]), 0)

    def test_runs_are_counted_per_slot_under_the_latest_runs_model_and_include_earlier_leads(self):
        base = dict(low_cloud=100, mid_cloud=80)
        rows = [row(**triaged(forecast_run_at=datetime(2026, 10, 13, 8), evaluation_model='HAIKU', days_ahead=2, **base)),
                row(**triaged(forecast_run_at=datetime(2026, 10, 13, 12), **base)),   # latest run: SONNET_55, T+0
                row(**triaged(forecast_run_at=datetime(2026, 10, 13, 18), **base)),   # after the event: ignored
                row(**triaged(forecast_run_at=datetime(2026, 10, 13, 9), batch_state='PENDING', **base)),
                row(**triaged(location_id=9, days_ahead=3, **base))]                    # a slot not in the T+0/T+1 set
        slots = [s for s in lowdeck.population(rows)['sky'] if s['lead'] in (0, 1)]
        self.assertEqual(len(slots), 1)
        # the HAIKU T+2 run belongs to the SONNET_55 slot's era and counts there, not under HAIKU
        self.assertEqual(dict(canopydeck.released_runs_by_model(rows, slots)), {'SONNET_55': 2})

    def test_era_rows_slots_runs_and_prices(self):
        rows = [row(**triaged(low_cloud=100, mid_cloud=80, location_id=1, forecast_run_at=datetime(2026, 10, 13, 8))),
                row(**triaged(low_cloud=100, mid_cloud=80, location_id=1, forecast_run_at=datetime(2026, 10, 13, 12))),
                row(**triaged(low_cloud=100, mid_cloud=80, location_id=2, evaluation_model='HAIKU',
                              target_date='2026-10-12', forecast_run_at=datetime(2026, 10, 12, 8),
                              solar_event_time=datetime(2026, 10, 12, 17))),
                row(f_low=100, e_low=0, location_id=3, rating=2)]      # rated, not triaged: never released
        slots = [s for s in lowdeck.population(rows)['sky'] if s['lead'] in (0, 1)]
        table = canopydeck.era_rows(slots, canopydeck.released_runs_by_model(rows, slots), 3500, 400)
        by = {r[0].split(' ')[0]: r for r in table}
        self.assertEqual((by['HAIKU'][3], by['HAIKU'][5]), (1, 1))                  # slots, runs
        self.assertEqual((by['SONNET_55'][3], by['SONNET_55'][5]), (1, 2))          # two runs of one slot
        self.assertEqual(by['SONNET_55'][9], '$%.2f' % ((3500 * 2.0 + 400 * 10.0) / 1e6))
        self.assertEqual(by['SONNET_55'][8], '$%.2f' % ((3500 * 2.0 + 400 * 10.0) / 2e6))
        self.assertEqual(by['SONNET_55'][10], '$%.2f' % (2 * (3500 * 2.0 + 400 * 10.0) / 1e6))   # two runs, one day
        total = table[-1]
        self.assertEqual((total[2], total[3], total[5]), (2, 2, 3))                 # 2 calendar days overall


    def test_unpriced_era_with_released_slots_is_named_and_one_without_is_not(self):
        released = row(**triaged(low_cloud=100, mid_cloud=80, evaluation_model='OPUS'))
        idle = row(f_low=100, e_low=0, location_id=2, rating=2, evaluation_model='OPUS')
        slots = [s for s in lowdeck.population([idle])['sky']]
        self.assertNotIn('unpriced', canopydeck.era_rows(slots, {}, 3500, 400)[-1][0])
        slots = [s for s in lowdeck.population([released])['sky']]
        self.assertIn('unpriced: OPUS', canopydeck.era_rows(slots, {}, 3500, 400)[-1][0])


class CountsTest(unittest.TestCase):
    def rows(self):
        return [
            # phantom + forecast canopy: triaged on low cloud / rated 2 / rated 4 (ERA5 low 0)
            row(**triaged(low_cloud=100, cv_observer_low_cloud=0.0, mid_cloud=80, location_id=1)),
            row(f_low=90, e_low=5.0, mid=70, location_id=2, rating=2),
            row(f_low=90, e_low=5.0, high=90, location_id=3, rating=4),
            # phantom without a forecast canopy
            row(f_low=90, e_low=5.0, location_id=4, rating=3),
            # forecast deck + canopy, ERA5 obstructed, with and without an ERA5 canopy; and one middle band
            row(f_low=90, e_low=80.0, mid=70, location_id=5, rating=2, cv_observer_mid_cloud=90.0),
            row(f_low=90, e_low=80.0, mid=70, location_id=6, rating=2),
            row(f_low=90, e_low=40.0, mid=70, location_id=7, rating=2),
            # no forecast deck at all
            row(f_low=10, e_low=0.0, mid=70, location_id=8, rating=5),
        ]

    def text(self):
        return canopydeck.render(self.rows())

    def test_count_one_and_two(self):
        t = self.text()
        self.assertIn('Observer phantom-deck slots: **4**. Forecast canopy present **3**, absent 1, unknown 0.', t)
        self.assertIn('Phantom decks with a forecast canopy: **3**. Triaged on low cloud **1**; rated <= 3 **1** '
                      '(1-2: 1, 3: 0); rated >= 4 **1**; every other outcome: triaged, HIGH_CLOUD without a '
                      'low-cloud message 0, triaged, other reason 0, unrated, not triaged 0.', t)
        self.assertIn('Phantom decks with a forecast canopy: **3** = triaged on low cloud **1** + triaged, HIGH_CLOUD '
                      'without a low-cloud message **0** + triaged, other reason **0** + rated 1-2 **1** + rated 3 '
                      '**0** + rated 4-5 **1** + unrated, not triaged **0**.', t)

    def test_count_three_exposure_includes_phantoms_and_non_phantoms(self):
        t = self.text()
        sec = t.split('## 3.')[1].split('## 4.')[0]
        self.assertIn('Every slot with forecast low >= 60 and a forecast canopy, phantom or not (ERA5 observer low '
                      'known): **6**. ERA5 observer low >= 60 (a genuinely obstructed sky the rule would send to '
                      'Claude): **2**, 33.3%', sec)
        self.assertIn('| ERA5 < 20 (phantom) | 3 | 0 | 3 | 0 |', sec)
        self.assertIn('| ERA5 20-59 | 1 | 0 | 1 | 0 |', sec)
        self.assertIn('| ERA5 >= 60 (obstructed) | 2 | 1 | 1 | 0 |', sec)
        self.assertIn('forecast decks WITHOUT a forecast canopy (1 with a known absence', sec)

    def test_released_slots_section_lists_the_triaged_ones(self):
        sec = self.text().split('actually release')[1].split('## 4.')[0]
        self.assertIn('triaged on low cloud and a forecast canopy present (ERA5 observer low known): **1**', sec)

    def test_definitions_and_no_verdict(self):
        t = self.text()
        head = t.split('## Population')[0]
        for needle in ('## Definitions', 'The candidate rule', 'forecast inputs, never ERA5', 'ASSUMED, not measured',
                       '3500 input and 400 output tokens', 'Sizing, not a decision', 'reanalysis, not an observation'):
            self.assertIn(needle, head)
        for word in ('should', 'recommend ', 'we propose', 'conclude'):
            self.assertNotIn(word, t.lower())

    def test_selection_ignores_era5_for_count_four(self):
        # a triaged slot with a forecast canopy and NO verification row is still released and costed
        rows = [row(**triaged(low_cloud=100, mid_cloud=80, cv_id=None, location_id=1))]
        t = canopydeck.render(rows)
        sec = t.split('## 4.')[1]
        self.assertIn('| SONNET_55 | 2026-10-13 .. 2026-10-13 | 1 | 1 | 1.00 | 1 | 1.00 |', sec)

    def test_empty_population_renders(self):
        t = canopydeck.render([])
        self.assertIn('Phantom deck under a forecast canopy', t)

    def test_main_writes_report_and_reports_a_bad_header(self):
        rows = self.rows()
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
                self.assertEqual(canopydeck.main([src, '--out', out, '--input-tokens', '1000',
                                                  '--output-tokens', '100']), 0)
            with open(os.path.join(out, 'canopydeck.md')) as f:
                self.assertIn('1000 input and 100 output tokens', f.read())
            bad = os.path.join(d, 'bad.csv')
            with open(bad, 'w') as f:
                f.write('a,b\n1,2\n')
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(canopydeck.main([bad, '--out', d]), 2)


if __name__ == '__main__':
    unittest.main()
