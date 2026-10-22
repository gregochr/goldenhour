#!/usr/bin/env python3
"""Weak-canvas sizing for far-cloud-p1-plan.md section 17. No network, no spend.

Usage: python3 canvas.py p1_forecasts.csv --out out/ [--ratings outcome_ratings.csv]

Sizes two forecast shapes on stored data. This is sizing, not a decision: it prints counts and
shares and recommends nothing.

 * Weak canvas (the Angel's 13 October shape): forecast observer mid AND high < 20, cone (113 km) low
   < 20, cone high >= 60. Clear overhead, cirrus only at the horizon. Unlike premise.angel_shape it
   does NOT also require the antisolar mid/high < 20; the strict form is counted as a subset.
 * Canopy overhead (the 15 October shape): forecast observer mid >= 60 OR high >= 60.

Selection is premise.py's: the latest eligible run per (location, date, event), a go is a prompted
batch row with rating >= 4, T+0/T+1, sky locations. Training cases (the Angel 13 Oct among them)
are INCLUDED here and flagged in the lists, because excluding them would hide the case that
defined the shape. The mirror class takes every prompted, rated batch slot, not only gos.

The export carries no outcome_rating, so item 4 needs a second export (export_outcome_ratings.sql);
pass it with --ratings and the join is by (location_id, event_date, event_type).
"""
from __future__ import annotations

import argparse
import csv
import os
import statistics
import sys
from collections import defaultdict
from typing import Callable, Dict, List, Optional, Sequence, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import premise  # noqa: E402

OVERHEAD_MAX = 20     # weak canvas: observer mid and high below this
CONE_LOW_MAX = 20     # weak canvas: cone low below this
CONE_HIGH_MIN = 60    # weak canvas: cone high at or above this
CANOPY_MIN = 60       # canopy overhead: observer mid or high at or above this
MISSED_MAX = 3        # mirror class: rated at or below this
DECK_FORECAST_MIN = 60
DECK_ERA5_MAX = 20
LIST_LIMIT = 200      # slot keys printed in the report; the CSV holds all

WEAK_INPUTS = ('mid_cloud', 'high_cloud', 'solar_low_cloud', 'solar_high_cloud')


def weak_testable(r: Dict[str, object]) -> bool:
    return all(r[c] is not None for c in WEAK_INPUTS)


def weak_canvas(r: Dict[str, object]) -> bool:
    return (weak_testable(r) and r['mid_cloud'] < OVERHEAD_MAX and r['high_cloud'] < OVERHEAD_MAX
            and r['solar_low_cloud'] < CONE_LOW_MAX and r['solar_high_cloud'] >= CONE_HIGH_MIN)


def canopy_testable(r: Dict[str, object]) -> bool:
    return r['mid_cloud'] is not None and r['high_cloud'] is not None


def canopy_overhead(r: Dict[str, object]) -> bool:
    return canopy_testable(r) and (r['mid_cloud'] >= CANOPY_MIN or r['high_cloud'] >= CANOPY_MIN)


def era5_checks(r: Dict[str, object]) -> Dict[str, Optional[bool]]:
    """ERA5's view of the weak-canvas shape. None when there is no cloud_verification row or the value is NULL."""
    if r['cv_id'] is None:
        return {'overhead_clear': None, 'cone_high': None, 'cone_low_clear': None, 'all': None}
    mid, high = r['cv_observer_mid_cloud'], r['cv_observer_high_cloud']
    overhead = None if mid is None or high is None else (mid < OVERHEAD_MAX and high < OVERHEAD_MAX)
    cone_high = None if r['cv_horizon_high_cloud'] is None else r['cv_horizon_high_cloud'] >= CONE_HIGH_MIN
    cone_low = None if r['cv_horizon_low_cloud'] is None else r['cv_horizon_low_cloud'] < CONE_LOW_MAX
    parts = (overhead, cone_high, cone_low)
    if any(p is None for p in parts):
        allv: Optional[bool] = False if any(p is False for p in parts) else None
    else:
        allv = all(parts)
    return {'overhead_clear': overhead, 'cone_high': cone_high, 'cone_low_clear': cone_low, 'all': allv}


# ------------------------------------------------------------------ analysis

def population(rows: Sequence[dict]) -> Dict[str, object]:
    slots, none_eligible = premise.build_slots(rows)
    base = [s for s in slots if s['sky'] and s['engine'] == 'batch' and s['prompted']
            and s['lead'] in premise.DECISION_LEADS]
    gos = [s for s in base if s['go']]
    rated = [s for s in base if s['row']['rating'] is not None]
    return {'slots': slots, 'none_eligible': none_eligible, 'base': base, 'gos': gos, 'rated': rated}


def share(k: int, n: int) -> str:
    return '-' if n == 0 else '%d (%.1f%%)' % (k, 100.0 * k / n)


def kn(units: Sequence[dict], pred: Callable[[dict], Optional[bool]]) -> Tuple[int, int]:
    known = [u for u in units if pred(u) is not None]
    return sum(1 for u in known if pred(u)), len(known)


def med(vals: Sequence[float]) -> str:
    return '-' if not vals else ('%g' % statistics.median(vals))


def table(headers: Sequence[str], rows: Sequence[Sequence[object]]) -> List[str]:
    return premise.md_table(headers, rows)


def by_model_event(gos: Sequence[dict]) -> List[List[object]]:
    out = []
    models = sorted({s['model'] for s in gos}) + ['all']
    events = sorted({s['event'] for s in gos}) + ['all']
    for model in models:
        for ev in events:
            sel = [s for s in gos if (model == 'all' or s['model'] == model) and (ev == 'all' or s['event'] == ev)]
            test = [s for s in sel if weak_testable(s['row'])]
            hit = [s for s in test if weak_canvas(s['row'])]
            dates = sorted({s['date'] for s in sel})
            out.append([model, ev, len(sel), len(test), share(len(hit), len(test)), len([s for s in hit if s['angel']]),
                        len({s['date'] for s in hit}), '%s .. %s' % (dates[0], dates[-1]) if dates else '-'])
    return out


def by_date(gos: Sequence[dict]) -> List[List[object]]:
    out = []
    for d in sorted({s['date'] for s in gos}):
        sel = [s for s in gos if s['date'] == d]
        test = [s for s in sel if weak_testable(s['row'])]
        hit = [s for s in test if weak_canvas(s['row'])]
        if not hit:
            continue
        models = sorted({s['model'] for s in hit})
        out.append([d, len(sel), len(test), len(hit), len([s for s in hit if s['event'] == 'SUNRISE']),
                    len([s for s in hit if s['event'] == 'SUNSET']), len([s for s in hit if s['angel']]),
                    ','.join(models)])
    return out


ERA5_TESTS = (('observer mid and high < 20 (clear overhead)', 'overhead_clear'),
              ('cone high >= 60 (horizon cirrus)', 'cone_high'),
              ('cone low < 20 (horizon low clear)', 'cone_low_clear'),
              ('all three', 'all'))


def era5_view(hits: Sequence[dict]) -> List[List[object]]:
    """One row per ERA5 test; a column per model plus all. Cells are agrees/known."""
    models = sorted({s['model'] for s in hits})
    out = []
    for label, key in ERA5_TESTS:
        row: List[object] = [label]
        for m in models + ['all']:
            sel = [s for s in hits if m == 'all' or s['model'] == m]
            k, n = kn(sel, lambda s, key=key: era5_checks(s['row'])[key])
            row.append('%s of %d' % (k, n))
        out.append(row)
    return out


def era5_values(hits: Sequence[dict]) -> List[List[object]]:
    cols = (('observer mid', 'cv_observer_mid_cloud'), ('observer high', 'cv_observer_high_cloud'),
            ('cone low', 'cv_horizon_low_cloud'), ('cone mid', 'cv_horizon_mid_cloud'),
            ('cone high', 'cv_horizon_high_cloud'), ('far (226 km) low', 'cv_far_low_cloud'))
    out = []
    for label, c in cols:
        v = [s['row'][c] for s in hits if s['row'][c] is not None]
        out.append([label, len(v), med(v), '-' if not v else '%g .. %g' % (min(v), max(v))])
    return out


def mirror(rated: Sequence[dict]) -> Dict[str, object]:
    canopy = [s for s in rated if canopy_overhead(s['row'])]
    missed = [s for s in canopy if s['row']['rating'] <= MISSED_MAX]
    return {'canopy': canopy, 'missed': missed}


def mirror_by_date_model(canopy: Sequence[dict], missed: Sequence[dict]) -> List[List[object]]:
    out = []
    for d in sorted({s['date'] for s in missed}):
        for m in sorted({s['model'] for s in missed if s['date'] == d}):
            c = [s for s in canopy if s['date'] == d and s['model'] == m]
            x = [s for s in missed if s['date'] == d and s['model'] == m]
            out.append([d, m, len(c), len(x), '%.1f%%' % (100.0 * len(x) / len(c))])
    return out


def low_deck_rows(missed: Sequence[dict]) -> List[List[object]]:
    out = []
    pairs = (('overhead low: forecast low_cloud vs ERA5 observer low', 'low_cloud', 'cv_observer_low_cloud'),
             ('cone low: forecast solar_low_cloud vs ERA5 horizon low', 'solar_low_cloud', 'cv_horizon_low_cloud'))
    for label, fc, cv in pairs:
        both = [s for s in missed if s['row'][fc] is not None and s['row'][cv] is not None]
        over = [s for s in both if s['row'][fc] >= DECK_FORECAST_MIN and s['row'][cv] < DECK_ERA5_MAX]
        under = [s for s in both if s['row'][fc] < DECK_ERA5_MAX and s['row'][cv] >= DECK_FORECAST_MIN]
        out.append([label, len(both), med([s['row'][fc] for s in both]), med([s['row'][cv] for s in both]),
                    share(len(over), len(both)), share(len(under), len(both))])
    return out


def load_ratings(path: str) -> Dict[tuple, dict]:
    with open(path, newline='', encoding='utf-8-sig') as f:
        reader = csv.DictReader(f)
        need = ['location_id', 'event_date', 'event_type', 'rating']
        missing = [c for c in need if c not in (reader.fieldnames or [])]
        if missing:
            raise ValueError('%s: missing column(s): %s' % (path, ', '.join(missing)))
        out: Dict[tuple, dict] = {}
        for r in reader:
            key = (int(float(r['location_id'])), r['event_date'][:10], r['event_type'])
            out.setdefault(key, []).append(r)  # several readers may rate one slot
        return out


def slot_line(s: dict) -> List[object]:
    r = s['row']
    return [r['loc_name'], r['location_id'], s['date'], s['event'], r['evaluation_id'], s['model'], r['rating'],
            'yes' if s['training'] else '', 'yes' if s['angel'] else '']


# ------------------------------------------------------------------ report

def render(rows: Sequence[dict], ratings: Optional[Dict[tuple, list]] = None) -> Tuple[str, List[dict]]:
    p = population(rows)
    gos, rated = p['gos'], p['rated']
    test = [s for s in gos if weak_testable(s['row'])]
    hits = [s for s in test if weak_canvas(s['row'])]
    L: List[str] = []
    L.append('# Weak-canvas sizing (far-cloud-p1-plan.md section 17)')
    L.append('')
    L.append('**Sizing, not a decision.** Counts and shares on stored forecast rows; nothing here says what to change. '
             'No network, no spend.')
    L.append('')
    L.append('## Definitions')
    L.append('')
    L.append('- **Go:** latest eligible run per (location, date, event) is a prompted batch row with rating >= 4; '
             'T+0/T+1, sky locations (premise.py selection). Training cases are included and flagged.')
    L.append('- **Weak canvas:** forecast observer mid < %d AND high < %d, cone (113 km) low < %d, cone high >= %d. '
             'Clear overhead, cirrus only at the horizon. Needs all four values non-NULL (older rows lack the cone).'
             % (OVERHEAD_MAX, OVERHEAD_MAX, CONE_LOW_MAX, CONE_HIGH_MIN))
    L.append('- **Strict Angel shape (subset):** premise.angel_shape, which also needs antisolar mid and high < %d.'
             % OVERHEAD_MAX)
    L.append('- **ERA5 view:** `cloud_verification` observer mid/high, cone low/mid/high, far low, where a row exists. '
             'ERA5 is a reanalysis, not an observation.')
    L.append('- **Canopy overhead:** forecast observer mid >= %d OR high >= %d. **Missed-opportunity class:** a canopy-overhead '
             'slot rated <= %d (any prompted, rated batch slot, not only gos).' % (CANOPY_MIN, CANOPY_MIN, MISSED_MAX))
    L.append('')
    L.append('## Population')
    L.append('')
    L.append('- Rows %d; slots with an eligible run %d; prompted batch sky T+0/T+1 slots %d; rated %d; gos %d; '
             'gos testable for the shape %d.'
             % (len(rows), len(p['slots']), len(p['base']), len(rated), len(gos), len(test)))
    L.append('')
    L.append('## 1. Gos matching the weak-canvas shape')
    L.append('')
    L.append('### By model and event')
    L.append('')
    L += table(['model', 'event', 'gos', 'testable', 'weak canvas', 'of which strict', 'dates with a hit', 'go dates'],
               by_model_event(gos))
    L.append('### By date (dates with at least one hit)')
    L.append('')
    L += table(['date', 'gos', 'testable', 'weak canvas', 'sunrise', 'sunset', 'strict', 'models'], by_date(gos))
    L.append('## 2. ERA5 view of the weak-canvas gos')
    L.append('')
    verified = [s for s in hits if s['row']['cv_id'] is not None]
    L.append('%d weak-canvas gos (%d training-case rows among them), %d with a `cloud_verification` row. '
             'Cells are "ERA5 agrees of known".' % (len(hits), len([s for s in hits if s['training']]), len(verified)))
    L.append('')
    L += table(['ERA5 test'] + sorted({s['model'] for s in hits}) + ['all'], era5_view(hits))
    L += table(['ERA5 value', 'n', 'median', 'range'], era5_values(verified))
    L.append('## 3. Contrast by model era')
    L.append('')
    L.append('The per-model rows of section 1 with events pooled. A model is an era only where its go dates say so: '
             'see the last column, and note the Haiku and Sonnet 5.5 date ranges are different weather.')
    L.append('')
    eras = [r for r in by_model_event(gos) if r[1] == 'all']
    L += table(['model', 'event', 'gos', 'testable', 'weak canvas', 'strict', 'dates with a hit', 'go dates'], eras)
    L.append('## 4. Weak-canvas gos and outcome ratings')
    L.append('')
    csv_rows = [s for s in hits]
    if ratings is None:
        L.append('The forecast export carries no `outcome_rating`. A second export is needed '
                 '(`export_outcome_ratings.sql`, then `--ratings`). Slot keys to look up '
                 '(match on location_id + date + event; first %d of %d, all in `canvas_weak_gos.csv`):'
                 % (min(LIST_LIMIT, len(hits)), len(hits)))
        L.append('')
        L += table(['location', 'location_id', 'date', 'event', 'evaluation_id', 'model', 'rating', 'training',
                    'strict'], [slot_line(s) for s in hits[:LIST_LIMIT]])
    else:
        rated_hits = []
        for s in hits:
            for rr in ratings.get((s['row']['location_id'], s['date'], s['event']), []):
                rated_hits.append(slot_line(s) + [rr.get('rating') or 'not seen', rr.get('seen') or '',
                                                  rr.get('forecast_rating') or ''])
        L.append('%d weak-canvas gos, %d reader ratings joined (%d rating rows read).'
                 % (len(hits), len(rated_hits), sum(len(v) for v in ratings.values())))
        L.append('')
        L += table(['location', 'location_id', 'date', 'event', 'evaluation_id', 'model', 'rating', 'training',
                    'strict', 'owner rating', 'seen', 'forecast_rating'], rated_hits)
    m = mirror(rated)
    L.append('## 5. Mirror class: canopy overhead, rated <= %d' % MISSED_MAX)
    L.append('')
    L.append('%d rated slots with a canopy overhead; %s rated <= %d. A rating of %d or less is the ordinary outcome for '
             'most slots, so this class is large by construction; the low-cloud comparison below is the part that '
             'separates an over-forecast deck from an honest low rating.'
             % (len(m['canopy']), share(len(m['missed']), len(m['canopy'])), MISSED_MAX, MISSED_MAX))
    L.append('')
    L.append('### By date and model (dates with at least one)')
    L.append('')
    L += table(['date', 'model', 'canopy slots', 'rated <= %d' % MISSED_MAX, 'share'],
               mirror_by_date_model(m['canopy'], m['missed']))
    L.append('### Low cloud: forecast against ERA5, among the rated <= %d canopy slots with both values' % MISSED_MAX)
    L.append('')
    L.append('"Forecast deck, ERA5 clear" = forecast >= %d and ERA5 < %d; "ERA5 deck, forecast clear" is the reverse.'
             % (DECK_FORECAST_MIN, DECK_ERA5_MAX))
    L.append('')
    L += table(['pair', 'n', 'median forecast', 'median ERA5', 'forecast deck, ERA5 clear',
                'ERA5 deck, forecast clear'], low_deck_rows(m['missed']))
    L.append('### Same comparison for ALL canopy slots (rated any), for contrast')
    L.append('')
    L += table(['pair', 'n', 'median forecast', 'median ERA5', 'forecast deck, ERA5 clear',
                'ERA5 deck, forecast clear'], low_deck_rows(m['canopy']))
    return '\n'.join(L) + '\n', hits


def write_csv(hits: Sequence[dict], out: str) -> None:
    with open(os.path.join(out, 'canvas_weak_gos.csv'), 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(['location', 'location_id', 'date', 'event', 'evaluation_id', 'model', 'rating', 'training',
                    'strict_angel_shape', 'obs_mid', 'obs_high', 'cone_low', 'cone_high', 'era5_obs_mid',
                    'era5_obs_high', 'era5_cone_low', 'era5_cone_high'])
        for s in hits:
            r = s['row']
            w.writerow(slot_line(s)[:7] + [s['training'] or '', int(s['angel']), r['mid_cloud'], r['high_cloud'],
                                           r['solar_low_cloud'], r['solar_high_cloud'], r['cv_observer_mid_cloud'],
                                           r['cv_observer_high_cloud'], r['cv_horizon_low_cloud'],
                                           r['cv_horizon_high_cloud']])


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('csv')
    ap.add_argument('--out', default='out')
    ap.add_argument('--ratings', help='outcome_rating export (export_outcome_ratings.sql)')
    args = ap.parse_args(argv)
    try:
        rows = premise.load_rows(args.csv)
        ratings = load_ratings(args.ratings) if args.ratings else None
    except ValueError as e:
        print('error: %s' % e, file=sys.stderr)
        return 2
    report, hits = render(rows, ratings)
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, 'canvas.md'), 'w', encoding='utf-8') as f:
        f.write(report)
    write_csv(hits, args.out)
    print('wrote %s' % os.path.join(args.out, 'canvas.md'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
