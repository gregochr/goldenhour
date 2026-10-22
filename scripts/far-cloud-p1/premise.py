#!/usr/bin/env python3
"""Part B of far-cloud-p1-plan.md: premise check of recent gos against ERA5. No network.

Usage: python3 premise.py p1_forecasts.csv --out out/

Interpretations of the plan (also printed in the report):
 * "latest run" = the eligible-state row (batch_state NULL or SCORED) with the greatest
   forecast_run_at before the solar event. A go is that row having rating >= 4 and a prompt
   (triage_reason empty). A later triaged/unrated row therefore means "not a go".
 * Main analyses pool batch-engine rows only (batch_state SCORED). Synchronous rows
   (batch_state and triage_reason both empty) are reported separately. Training cases are listed
   individually and excluded from every pooled figure.
 * Primary test: location-go unit, T+0/T+1, both events pooled, outcome P-low, units whose P-low is
   known. Pass = count gates met, Newcombe AND clustered-bootstrap intervals above 0, and the
   Holm-adjusted (across R1-R3) bootstrap p < 0.05.
 * Cost side (D-1 step 3) = flagged gos with any of ERA5 cone/observer mid/high >= 20, as a share
   of flagged gos whose four values are known (for R1 this is the plan's definition).
 * Region-event robustness: slots of T+0/T+1 non-sync sky locations; needs >= 5 locations with a
   latest run; flagged when ANY of its location-gos is flagged; outcome fires when >= half of its
   gos with known P-low have P-low.
 * Gates (plan section 5 plus two pre-registered additions): >= 30 units per arm, >= 10 events and
   >= 8 DISTINCT DATES in the flagged arm; more than 5% of bootstrap draws dropped (an arm empty
   in the resample) also makes a comparison inconclusive.
 * Disabled locations are kept for history (loc_enabled is read but deliberately unused).
 * The CSV header may be a superset of the required columns, in any order; columns are by name.
"""
from __future__ import annotations

import argparse
import contextlib
import csv
import os
import sys
from collections import defaultdict
from datetime import datetime
from typing import Callable, Dict, List, Optional, Sequence, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stats  # noqa: E402
from slots import find_best_index  # noqa: E402
from sun import sun_position  # noqa: E402

COLUMNS = ['evaluation_id', 'location_id', 'target_date', 'target_type', 'forecast_run_at', 'solar_event_time',
           'azimuth_deg', 'days_ahead', 'evaluation_model', 'batch_state', 'triage_reason', 'triage_message',
           'rating', 'sky_rating', 'fiery_sky_potential', 'golden_hour_potential', 'basic_fiery_sky_potential',
           'basic_golden_hour_potential', 'confidence', 'headline', 'summary', 'low_cloud', 'mid_cloud',
           'high_cloud', 'visibility', 'wind_speed', 'wind_direction', 'precipitation', 'humidity',
           'weather_code', 'boundary_layer_height', 'shortwave_radiation', 'pm2_5', 'dust',
           'aerosol_optical_depth', 'temperature_celsius', 'dew_point_celsius', 'solar_low_cloud',
           'solar_mid_cloud', 'solar_high_cloud', 'antisolar_low_cloud', 'antisolar_mid_cloud',
           'antisolar_high_cloud', 'far_solar_low_cloud', 'far_solar_mid_cloud', 'far_solar_high_cloud',
           'far_solar_low_cloud_dark', 'far_solar_mid_cloud_dark', 'far_solar_high_cloud_dark',
           'far_solar_cloud_850hpa', 'far_solar_cloud_700hpa', 'far_solar_cloud_600hpa',
           'far_solar_cloud_500hpa', 'far_solar_cloud_400hpa', 'far_solar_cloud_300hpa',
           'solar_trend_event_low_cloud', 'solar_trend_earliest_low_cloud', 'solar_trend_building',
           'upwind_current_low_cloud', 'upwind_event_low_cloud', 'upwind_distance_km', 'loc_name', 'loc_lat',
           'loc_lon', 'loc_enabled', 'loc_is_coastal_tidal', 'loc_region_id', 'region_name', 'loc_types',
           'cv_id', 'cv_horizon_sample_lat', 'cv_horizon_sample_lon', 'cv_horizon_low_cloud',
           'cv_horizon_low_min', 'cv_horizon_low_max', 'cv_horizon_mid_cloud', 'cv_horizon_high_cloud',
           'cv_far_low_cloud', 'cv_far_mid_cloud', 'cv_far_high_cloud', 'cv_far_low_cloud_dark',
           'cv_far_mid_cloud_dark', 'cv_far_high_cloud_dark', 'cv_observer_low_cloud', 'cv_observer_mid_cloud',
           'cv_observer_high_cloud', 'cv_observed_at', 'cv_verified_at']

STR_COLS = {'target_date', 'target_type', 'evaluation_model', 'batch_state', 'triage_reason', 'triage_message',
            'confidence', 'headline', 'summary', 'loc_name', 'region_name', 'loc_types'}
TIME_COLS = {'forecast_run_at', 'solar_event_time', 'cv_observed_at', 'cv_verified_at'}
BOOL_COLS = {'solar_trend_building', 'loc_enabled', 'loc_is_coastal_tidal'}
INT_COLS = {'evaluation_id', 'location_id', 'days_ahead', 'rating', 'sky_rating', 'loc_region_id', 'cv_id'}

# Plan thresholds.
GO_RATING = 4
LOW_BLOCKED = 60
CANVAS_MAX = 20
ANGEL_LOW_MAX = 20
ANGEL_HIGH_MIN = 60
RAISED_PREV_MAX = 3
COST_SHARE_MAX = 0.25
SKY_TYPES = {'LANDSCAPE', 'SEASCAPE', 'WATERFALL'}
DECISION_LEADS = (0, 1)
REGION_MIN_LOCATIONS = 5
BANDS = ('<0', '0-2', '2-5', '5-9', '>=9')
ANGEL_WARNING = ("Part B cannot see the Angel class: its absolute rates are not evidence that the "
                 "class is rare.")

TRAINING_CASES = [  # (label, name fragment, dates, target type)
    ('Angel of the North', 'angel of the north', ('2026-10-13',), 'SUNSET'),
    ('Low Hauxley', 'low hauxley', ('2026-10-04',), 'SUNRISE'),
    ('Copt Hill', 'copt hill', ('2026-03-05', '2026-03-11'), 'SUNSET'),
]


# ------------------------------------------------------------------ loading

def _parse_row(raw: Dict[str, str]) -> Dict[str, object]:
    out: Dict[str, object] = {}
    for c in COLUMNS:
        v = raw.get(c)
        if v is None or v == '':
            out[c] = None
        elif c in STR_COLS:
            out[c] = v
        elif c in TIME_COLS:
            out[c] = datetime.strptime(v[:19], '%Y-%m-%dT%H:%M:%S')
        elif c in BOOL_COLS:
            out[c] = v.strip().lower() in ('t', 'true', '1')
        elif c in INT_COLS:
            out[c] = int(float(v))
        else:
            out[c] = float(v)
    return out


def load_rows(path: str) -> List[Dict[str, object]]:
    """Parse by column NAME. The header may hold extra columns and any order; a missing required
    column is a ValueError naming it."""
    with open(path, newline='', encoding='utf-8-sig') as f:
        reader = csv.DictReader(f)
        have = set(reader.fieldnames or [])
        missing = [c for c in COLUMNS if c not in have]
        if missing:
            raise ValueError('%s: header is missing required column(s): %s' % (path, ', '.join(missing)))
        return [_parse_row(r) for r in reader]


# ------------------------------------------------------------------ classification

def is_sky_location(loc_types: Optional[str]) -> bool:
    if not loc_types:
        return True
    return any(t.strip() in SKY_TYPES for t in loc_types.split(','))


def training_label(name: Optional[str], date: Optional[str], ttype: Optional[str]) -> Optional[str]:
    n = (name or '').lower()
    for label, frag, dates, tt in TRAINING_CASES:
        if frag in n and date in dates and ttype == tt:
            return label if label != 'Copt Hill' else 'Copt Hill %s' % date
    return None


def engine_of(r: Dict[str, object]) -> str:
    if r['batch_state']:
        return 'batch'
    return 'sync' if not r['triage_reason'] else 'triage'


def premises(r: Dict[str, object]) -> Dict[str, Optional[bool]]:
    """P-low / P-far / P-canvas as True / False / None (None = P-unknown)."""
    if r['cv_id'] is None:
        return {'low': None, 'far': None, 'canvas': None}
    low = None if r['cv_horizon_low_cloud'] is None else r['cv_horizon_low_cloud'] >= LOW_BLOCKED
    far = None if r['cv_far_low_cloud'] is None else r['cv_far_low_cloud'] >= LOW_BLOCKED
    vals = [r['cv_observer_mid_cloud'], r['cv_observer_high_cloud'],
            r['cv_horizon_mid_cloud'], r['cv_horizon_high_cloud']]
    canvas = None if any(v is None for v in vals) else all(v < CANVAS_MAX for v in vals)
    return {'low': low, 'far': far, 'canvas': canvas}


def era5_canvas(r: Dict[str, object]) -> Optional[bool]:
    """R1 cost side: True if any ERA5 cone/observer mid/high >= 20; False if all known < 20;
    None when no value reaches 20 and some are missing."""
    vals = [r['cv_horizon_mid_cloud'], r['cv_horizon_high_cloud'],
            r['cv_observer_mid_cloud'], r['cv_observer_high_cloud']]
    if any(v is not None and v >= CANVAS_MAX for v in vals):
        return True
    return None if any(v is None for v in vals) else False


def _shape_inputs(r: Dict[str, object]) -> list:
    return [r['solar_low_cloud'], r['solar_high_cloud'], r['mid_cloud'], r['high_cloud'],
            r['antisolar_mid_cloud'], r['antisolar_high_cloud']]


def angel_shape_testable(r: Dict[str, object]) -> bool:
    """False when a shape input is NULL (pre-directional capture): the row cannot be tested."""
    return all(v is not None for v in _shape_inputs(r))


def angel_shape(r: Dict[str, object]) -> bool:
    need = _shape_inputs(r)
    if any(v is None for v in need):
        return False
    return (r['solar_low_cloud'] < ANGEL_LOW_MAX and r['solar_high_cloud'] >= ANGEL_HIGH_MIN
            and all(v < CANVAS_MAX for v in need[2:]))


def already_capped(r: Dict[str, object]) -> bool:
    return bool(r['solar_trend_building']) and (r['upwind_current_low_cloud'] or 0) >= LOW_BLOCKED \
        and r['upwind_current_low_cloud'] is not None


def sun_band(r: Dict[str, object]) -> Tuple[Optional[float], str]:
    ev = r['solar_event_time']
    if ev is None or r['loc_lat'] is None or r['loc_lon'] is None:
        return None, 'unknown'
    d = ev.date().isoformat()
    times = ['%sT%02d:00' % (d, h) for h in range(24)]
    idx = find_best_index(times, ev, r['target_type'])
    slot = datetime.strptime(times[idx], '%Y-%m-%dT%H:%M')
    alt = sun_position(slot, r['loc_lat'], r['loc_lon'])[0]
    if alt < 0:
        return alt, '<0'
    if alt < 2:
        return alt, '0-2'
    if alt < 5:
        return alt, '2-5'
    if alt < 9:
        return alt, '5-9'
    return alt, '>=9'


def build_slots(rows: Sequence[Dict[str, object]]) -> Tuple[List[Dict[str, object]], int]:
    """One entry per (location, date, type) with a latest eligible run before the event.
    Returns (slots, count of slot keys with no eligible run)."""
    groups: Dict[tuple, List[Dict[str, object]]] = defaultdict(list)
    for r in rows:
        groups[(r['location_id'], r['target_date'], r['target_type'])].append(r)
    slots: List[Dict[str, object]] = []
    none_eligible = 0
    for key, grp in groups.items():
        elig = [r for r in grp if r['solar_event_time'] is not None and r['forecast_run_at'] is not None
                and r['forecast_run_at'] < r['solar_event_time']
                and (r['batch_state'] is None or r['batch_state'] == 'SCORED')]
        if not elig:
            none_eligible += 1
            continue
        elig.sort(key=lambda r: (r['forecast_run_at'], r['evaluation_id'] or 0))
        last = elig[-1]
        prev = next((r for r in reversed(elig[:-1]) if r['rating'] is not None), None)
        prompted = not last['triage_reason']
        go = prompted and last['rating'] is not None and last['rating'] >= GO_RATING
        eng = engine_of(last)
        alt, band = sun_band(last)
        pr = premises(last)
        if go and prev is None:
            raised = 'noprev'
        elif go and prev['rating'] <= RAISED_PREV_MAX:
            raised = 'same' if prev['evaluation_model'] == last['evaluation_model'] else 'changed'
        else:
            raised = None
        tl = training_label(last['loc_name'], last['target_date'], last['target_type'])
        slots.append({
            'key': key, 'row': last, 'prev': prev, 'date': last['target_date'], 'event': last['target_type'],
            'lead': last['days_ahead'], 'model': last['evaluation_model'] or 'unknown', 'engine': eng,
            'prompted': prompted, 'go': go, 'sky': is_sky_location(last['loc_types']), 'training': tl,
            'pr': pr, 'alt': alt, 'band': band, 'angel': angel_shape(last), 'angel_testable': angel_shape_testable(last), 'raised': raised,
            'capped': already_capped(last), 'canvas_era5': era5_canvas(last),
            'lead_hours': (last['solar_event_time'] - last['forecast_run_at']).total_seconds() / 3600.0,
            'region': last['loc_region_id'],
        })
    slots.sort(key=lambda s: (s['date'], s['event'], str(s['key'][0])))
    return slots, none_eligible


# ------------------------------------------------------------------ formatting helpers

def pct(x: Optional[float]) -> str:
    return '-' if x is None else '%.1f%%' % (100 * x)


def ci_txt(ci: Optional[Tuple[float, float]]) -> str:
    return 'n/a' if ci is None else '[%s, %s]' % (pct(ci[0]), pct(ci[1]))


def md_table(headers: Sequence[str], rows: Sequence[Sequence[object]]) -> List[str]:
    out = ['| ' + ' | '.join(headers) + ' |', '|' + '|'.join('---' for _ in headers) + '|']
    for r in rows:
        out.append('| ' + ' | '.join(str(c) for c in r) + ' |')
    return out + ['']


def date_counts(units: Sequence[dict], fn: Callable[[dict], Tuple[int, ...]]) -> Dict[str, Tuple[int, ...]]:
    acc: Dict[str, List[int]] = {}
    for u in units:
        inc = fn(u)
        cur = acc.setdefault(u['date'], [0] * len(inc))
        for i, v in enumerate(inc):
            cur[i] += v
    return {d: tuple(v) for d, v in acc.items()}


def prop_cell(units: Sequence[dict], pred: Callable[[dict], Optional[bool]], draws: int) -> Tuple[str, int, int]:
    """'k/n p [Wilson] boot [..]' for the units whose predicate is known; plus (k, n)."""
    known = [u for u in units if pred(u) is not None]
    n = len(known)
    k = sum(1 for u in known if pred(u))
    if n == 0:
        return '0/0', 0, 0
    w = stats.wilson(k, n)
    dc = date_counts(known, lambda u: (1 if pred(u) else 0, 1))
    bs, dropped = stats.bootstrap_counts_ex(dc, lambda t: t[0] / t[1] if t[1] else None, draws=draws)
    drop = ' drop %s' % pct(dropped / draws) if dropped else ''
    return ('%d/%d %s W%s B%s%s' % (k, n, pct(w[0]), ci_txt((w[1], w[2])),
                                    ci_txt(stats.percentile_interval(bs)), drop), k, n)


_STUDY_SPAN: Optional[Tuple[str, str]] = None


@contextlib.contextmanager
def study_span(dates: Sequence[str]):
    """Within the block, date-block bootstraps resample the calendar days from the first to the last of ``dates``
    (the study window), not each subset's own first-to-last range. A subgroup concentrated on a few days then
    keeps its real share of empty days instead of looking better spread than it is; a resample that holds no unit
    of the subgroup is a dropped draw (reported as 'drop N%'). Outside the block each subset's own range is used."""
    global _STUDY_SPAN
    ds = sorted({str(d)[:10] for d in dates})
    prev = _STUDY_SPAN
    _STUDY_SPAN = (ds[0], ds[-1]) if ds else None
    try:
        yield
    finally:
        _STUDY_SPAN = prev


def block_rate(units: Sequence[dict], hit: Callable[[dict], bool],
               block_days: int = stats.DEFAULT_BLOCK_DAYS, draws: int = stats.DEFAULT_DRAWS
               ) -> Tuple[Optional[Tuple[float, float]], float]:
    """Date-block bootstrap of the share of ``units`` satisfying ``hit``: (95% percentile interval or None,
    fraction of draws dropped because a resample held no unit). Units carry their date in ``u['date']``;
    blocks are ``block_days`` consecutive CALENDAR days (stats.bootstrap_counts_ex, as prop_cell uses) over the
    subset's own first-to-last date, or over the study window inside ``study_span``."""
    dc = date_counts(units, lambda u: (1 if hit(u) else 0, 1))
    if dc and _STUDY_SPAN is not None:
        for d in _STUDY_SPAN:
            dc.setdefault(d, (0, 0))
    bs, dropped = stats.bootstrap_counts_ex(dc, lambda t: t[0] / t[1] if t[1] else None,
                                            block_days=block_days, draws=draws)
    return stats.percentile_interval(bs), dropped / draws


def block_txt(units: Sequence[dict], hit: Callable[[dict], bool], block_days: int = stats.DEFAULT_BLOCK_DAYS,
              draws: int = stats.DEFAULT_DRAWS) -> str:
    """'[lo, hi]' of block_rate, with ' (drop N%)' when any draw was dropped; 'n/a' for no units."""
    ci, dropped = block_rate(units, hit, block_days, draws)
    return ci_txt(ci) + (' (drop %s)' % pct(dropped) if dropped else '')


def block_sensitivity_txt(units: Sequence[dict], hit: Callable[[dict], bool],
                          draws: int = stats.DEFAULT_DRAWS) -> str:
    """'5-day [..]; 3-day [..]; 7-day [..]' (the plan's block lengths, 5 first)."""
    order = [stats.DEFAULT_BLOCK_DAYS] + [b for b in stats.SENSITIVITY_BLOCKS if b != stats.DEFAULT_BLOCK_DAYS]
    return '; '.join('%d-day %s' % (b, block_txt(units, hit, b, draws)) for b in order)


def wilson_block_txt(k: int, n: int, units: Sequence[dict], hit: Callable[[dict], bool],
                     draws: int = stats.DEFAULT_DRAWS) -> str:
    """'k (p%) W[lo, hi] B[lo, hi]': Wilson beside the 5-day date-block interval, for ``units`` as the
    denominator and ``hit`` as the numerator (k and n are passed so the caller's own counts print)."""
    w = stats.wilson(k, n)
    if w is None:
        return '-'
    return '%d (%s) W%s B%s' % (k, pct(w[0]), ci_txt((w[1], w[2])), block_txt(units, hit, draws=draws))


PREMISE_NAMES = (('low', 'P-low (cone mean low >= 60)'),
                 ('far', 'P-far (diagnostic only, used in no decision)'),
                 ('canvas', 'P-canvas (UPPER BOUND on "no canvas": ERA5 has no antisolar point)'))


def lead_label(d: Optional[int]) -> str:
    return 'T+%s' % d if d is not None else 'T+?'


# ------------------------------------------------------------------ tests

def make_test(units: List[dict], draws: int) -> Dict[str, object]:
    """units: dicts with 'flag', 'out' (bool), 'date'. Flagged vs other risk difference."""
    f = [u for u in units if u['flag']]
    o = [u for u in units if not u['flag']]
    k1, n1 = sum(u['out'] for u in f), len(f)
    k2, n2 = sum(u['out'] for u in o), len(o)
    res: Dict[str, object] = {'n1': n1, 'k1': k1, 'n2': n2, 'k2': k2, 'newcombe': stats.newcombe_diff(k1, n1, k2, n2),
                              'p_score': stats.two_prop_pvalue(k1, n1, k2, n2), 'boot_ci': None, 'p_boot': None,
                              'sens': {}, 'dates1': len({u['date'] for u in f}), 'dates2': len({u['date'] for u in o}),
                              'dropped': 0.0, 'dropped_by_block': {}}
    if n1 and n2:
        dc = date_counts(units, lambda u: (int(u['flag'] and u['out']), int(u['flag']),
                                           int((not u['flag']) and u['out']), int(not u['flag'])))

        def stat(t):
            return t[0] / t[1] - t[2] / t[3] if t[1] and t[3] else None
        for b in stats.SENSITIVITY_BLOCKS:
            bsb, drb = stats.bootstrap_counts_ex(dc, stat, block_days=b, draws=draws)
            res['sens'][b] = stats.percentile_interval(bsb)
            res['dropped_by_block'][b] = drb / draws if draws else 0.0
        bs, dr = stats.bootstrap_counts_ex(dc, stat, draws=draws)
        res['boot_ci'] = stats.percentile_interval(bs)
        res['p_boot'] = stats.bootstrap_pvalue(bs)
        res['dropped'] = dr / draws if draws else 0.0
    return res


def finish_tests(tests: Dict[str, Dict[str, object]]) -> None:
    names = list(tests)
    adj = stats.holm([tests[n]['p_boot'] for n in names])
    for n, a in zip(names, adj):
        t = tests[n]
        t['p_holm'] = a
        nc, bc = t['newcombe'], t['boot_ci']
        lo = None if nc is None or bc is None else min(nc[1], bc[0])
        worst = max([t['dropped']] + list(t['dropped_by_block'].values()))
        t['verdict'], t['reason'] = stats.verdict_reason(t['n1'], t['k1'], t['n2'], lo, a,
                                                          dates_flagged=t['dates1'], dropped_fraction=worst)


def flag_fns() -> Dict[str, Callable[[dict], bool]]:
    return {'R1 (Angel shape / canvas)': lambda s: s['go'] and s['angel'],
            'R2 (raised, same model)': lambda s: s['go'] and s['raised'] == 'same',
            'R3 (R1 or R2)': lambda s: s['go'] and (s['angel'] or s['raised'] == 'same')}


def cost_side(flagged: Sequence[dict]) -> Tuple[int, int, int, Optional[float]]:
    t = sum(1 for s in flagged if s['canvas_era5'] is True)
    kn = sum(1 for s in flagged if s['canvas_era5'] is not None)
    unk = len(flagged) - kn
    return t, kn, unk, (t / kn if kn else None)


def cost_verdict(t: int, kn: int, unk: int) -> str:
    """D-1 step 3. Unknown canvases count against the rule: 'ok' only if the share stays within the cap
    even when every unknown is a canvas; 'EXCEEDS' if the known share alone breaks it; else inconclusive."""
    if kn + unk == 0:
        return 'n/a'
    if (t + unk) / (kn + unk) <= COST_SHARE_MAX:
        return 'ok'
    if kn and t / kn > COST_SHARE_MAX:
        return 'EXCEEDS'
    return 'inconclusive (%d unknown)' % unk


def region_event_units(pop: Sequence[dict], flag: Callable[[dict], bool]) -> List[dict]:
    groups: Dict[tuple, List[dict]] = defaultdict(list)
    for s in pop:
        if s['lead'] in DECISION_LEADS and s['engine'] != 'sync' and s['region'] is not None:
            groups[(s['region'], s['date'], s['event'])].append(s)
    out = []
    for key, g in groups.items():
        if len(g) < REGION_MIN_LOCATIONS:
            continue
        gos = [s for s in g if s['go'] and s['pr']['low'] is not None]
        if not gos:
            continue
        flagged = any(flag(s) for s in g)
        outcome = sum(1 for s in gos if s['pr']['low']) * 2 >= len(gos)
        out.append({'flag': flagged, 'out': outcome, 'date': key[1], 'key': key})
    return out


# ------------------------------------------------------------------ report

def analyse(rows: Sequence[dict], draws: int = stats.DEFAULT_DRAWS) -> Dict[str, object]:
    slots, none_eligible = build_slots(rows)
    sky = [s for s in slots if s['sky']]
    pooled = [s for s in sky if not s['training']]
    pop = [s for s in pooled if s['engine'] == 'batch' and s['prompted']]
    gos = [s for s in pop if s['go']]
    sync = [s for s in pooled if s['engine'] == 'sync']
    tests_loc: Dict[str, dict] = {}
    tests_reg: Dict[str, dict] = {}
    loc_units_base = [s for s in gos if s['lead'] in DECISION_LEADS and s['pr']['low'] is not None]
    for name, fn in flag_fns().items():
        tests_loc[name] = make_test([{'flag': fn(s), 'out': bool(s['pr']['low']), 'date': s['date']}
                                     for s in loc_units_base], draws)
        tests_reg[name] = make_test(region_event_units(pooled, fn), draws)
    finish_tests(tests_loc)
    finish_tests(tests_reg)
    return {'rows': len(rows), 'slots': slots, 'none_eligible': none_eligible, 'sky': sky, 'pooled': pooled,
            'pop': pop, 'gos': gos, 'sync': sync, 'tests_loc': tests_loc, 'tests_reg': tests_reg,
            'loc_units': loc_units_base, 'draws': draws}


def _tri_long(v: Optional[bool]) -> str:
    return 'unknown (not yet verified)' if v is None else ('FLAGS' if v else 'passes')


def angel_status(slots: Sequence[dict]) -> Tuple[str, bool]:
    """(line, can_see). ``can_see`` is True ONLY when P-low flags the Angel row: P-far feeds no
    decision and P-canvas must not judge R1, so neither can show Part B sees the class."""
    ang = [s for s in slots if (s['training'] or '') == 'Angel of the North']
    if not ang:
        return 'Angel of the North 2026-10-13 SUNSET: not in the export (unverified).', False
    pr = ang[0]['pr']
    line = ('Angel of the North 2026-10-13 SUNSET: P-low (the only premise that counts) %s; '
            'P-far (diagnostic only, feeds no decision) %s; '
            'P-canvas (upper bound, does not judge R1) %s.'
            % (_tri_long(pr['low']), _tri_long(pr['far']), _tri_long(pr['canvas'])))
    return line, pr['low'] is True


def _tri(v: Optional[bool]) -> str:
    return 'unknown' if v is None else ('FLAGS' if v else 'passes')


def render(a: Dict[str, object]) -> str:
    draws = a['draws']
    slots, pop, gos = a['slots'], a['pop'], a['gos']
    L: List[str] = []
    ang_line, ang_flag = angel_status(slots)
    L.append('# Part B: premise check of recent gos')
    L.append('')
    L.append('**Angel sanity check:** ' + ang_line)
    L.append('')
    if not ang_flag:
        L.append('> **' + ANGEL_WARNING + '**')
        L.append('')
    dates = sorted({r for r in (s['date'] for s in slots)})
    L.append('- commit: %s' % os.environ.get('P1_COMMIT', 'unknown (set P1_COMMIT)'))
    L.append('- input rows: %d; date span: %s to %s' % (a['rows'], dates[0] if dates else '-', dates[-1] if dates else '-'))
    L.append('- bootstrap: %d draws, 5-day non-circular moving blocks of CALENDAR days (min..max date, empty days are zero units), seed %d; sensitivity at 3 and 7 days in the table'
             % (draws, stats.DEFAULT_SEED))
    L.append('- units: slots with a latest run %d (no eligible run: %d); sky %d; pooled (sky, not training) %d; '
             'batch-prompted %d; location-gos %d; synchronous-engine %d' % (
                 len(slots), a['none_eligible'], len(a['sky']), len(a['pooled']), len(pop), len(gos), len(a['sync'])))
    dec = [s for s in gos if s['lead'] in DECISION_LEADS]
    L.append('- decision population (gos, T+0/T+1): %d; with a cloud_verification row: %d'
             % (len(dec), sum(1 for s in dec if s['row']['cv_id'] is not None)))
    reu = region_event_units(a['pooled'], lambda s: False)
    L.append('- region-events (T+0/T+1, >= %d locations, with a go with known P-low): %d' % (REGION_MIN_LOCATIONS, len(reu)))
    L.append('- Disabled locations are kept for history: loc_enabled is read but deliberately unused.')
    L.append('- Gos use the latest eligible run; a stability skip that retracts a rating at serve time is not '
             'modelled (rare at T+0/T+1). T+2 and later are descriptive only.')
    L.append('')

    # ---- primary tests
    L.append('## Primary tests (R1-R3, location-go unit, T+0/T+1, both events, outcome P-low, Holm across R1-R3)')
    L.append('')
    for lvl, key in (('Location-go (primary)', 'tests_loc'), ('Region-event (robustness)', 'tests_reg')):
        L.append('### ' + lvl)
        rows_ = []
        for name, t in a[key].items():
            nc = t['newcombe']
            rows_.append([name, '%d/%d (%d dates)' % (t['k1'], t['n1'], t['dates1']),
                          '%d/%d (%d dates)' % (t['k2'], t['n2'], t['dates2']),
                          pct(nc[0]) if nc else '-', ci_txt((nc[1], nc[2]) if nc else None), ci_txt(t['boot_ci']),
                          ' '.join('%d:%s' % (b, ci_txt(c)) for b, c in sorted(t['sens'].items())) or '-',
                          '-' if t['p_boot'] is None else '%.4f' % t['p_boot'],
                          '-' if t['p_holm'] is None else '%.4f' % t['p_holm'],
                          '%s (3:%s 5:%s 7:%s)' % (pct(t['dropped']), *(pct(t['dropped_by_block'].get(b))
                                                                      for b in stats.SENSITIVITY_BLOCKS)),
                          '**%s**' % t['verdict']])
        L += md_table(['rule', 'flagged P-low k/n (dates)', 'other k/n (dates)', 'diff', 'Newcombe',
                       'bootstrap(5)', 'bootstrap by block', 'p boot', 'p Holm', 'draws dropped', 'verdict'], rows_)
        for name, t in a[key].items():
            if t['reason']:
                L.append('- %s: inconclusive because %s.' % (name, t['reason']))
        L.append('Gate: >= %d units in each arm, >= %d P-low events and >= %d distinct dates in the flagged arm '
                 '(pre-registered addition to plan section 5), else inconclusive (inconclusive is not "no effect"). '
                 'More than %d%% of bootstrap draws dropped (an arm empty in the resample) is also inconclusive.'
                 % (stats.MIN_UNITS_PER_ARM, stats.MIN_FLAGGED_EVENTS, stats.MIN_FLAGGED_DATES,
                    int(100 * stats.MAX_DROPPED_FRACTION)))
        if key == 'tests_reg':
            L.append('Region-event rule: flagged if ANY of its location-gos is flagged; its outcome fires if at '
                     'least half of its gos with known P-low are P-low.')
        L.append('')
    L.append('### D-1 step 3: cost side (flagged gos with an ERA5 canvas, share must be <= %d%%)' % int(100 * COST_SHARE_MAX))
    rows_ = []
    for name, fn in flag_fns().items():
        fl = [s for s in dec if fn(s)]
        t, kn, unk, share = cost_side(fl)
        ok = cost_verdict(t, kn, unk)
        rows_.append([name, len(fl), '%d/%d' % (t, kn), unk, pct(share), ok])
    L += md_table(['rule', 'flagged gos', 'ERA5 canvas k/known', 'unknown', 'cost share', 'vs 25%'], rows_)
    L.append('D-1 step 1 (Part D replay) is not computed here.')
    L.append('')

    # ---- 1 base rates
    L.append('## 1. Base rates per premise (gos; W = Wilson, B = block bootstrap; SUNRISE first)')
    L.append('')
    ev_order = ('SUNRISE', 'SUNSET')
    for key, title in PREMISE_NAMES:
        L.append('### ' + title)
        for axis, fn in (('lead', lambda s: lead_label(s['lead'])), ('model', lambda s: s['model']),
                         ('sun-altitude band', lambda s: s['band'])):
            rows_ = []
            for ev in ev_order:
                grp = sorted({fn(s) for s in gos if s['event'] == ev})
                for g in grp:
                    us = [s for s in gos if s['event'] == ev and fn(s) == g]
                    cell, _, n = prop_cell(us, lambda s, key=key: s['pr'][key], draws)
                    note = ' (descriptive)' if axis == 'lead' and g not in ('T+0', 'T+1') else ''
                    rows_.append([ev, g + note, len(us), len(us) - n, cell])
            L += md_table(['event', axis, 'gos', 'P-unknown', 'rate'], rows_)
        if key == 'low':
            lows = [s for s in gos if s['pr']['low']]
            mins = [s['row']['cv_horizon_low_min'] for s in lows if s['row']['cv_horizon_low_min'] is not None]
            L.append('cv_horizon_low_min beside the %d P-low gos: min %s, median %s.' % (
                len(lows), min(mins) if mins else '-', sorted(mins)[len(mins) // 2] if mins else '-'))
            L.append('P-low is partly a model-compliance figure: it counts the forecast crossing its own BLOCKED line.')
            L.append('')
    L.append('Median hours from run to event by lead: ' + ', '.join(
        '%s=%.1f' % (lead_label(d), sorted(s['lead_hours'] for s in gos if s['lead'] == d)[sum(1 for s in gos if s['lead'] == d) // 2])
        for d in sorted({s['lead'] for s in gos if s['lead'] is not None})))
    L.append('')

    # ---- 2 hypothesis M
    L.append('## 2. Hypothesis M: go rate and premise shares by evaluation model, matched bins (T+0/T+1, batch prompted)')
    first55 = min((r['forecast_run_at'] for s in slots for r in [s['row']] if r['evaluation_model'] == 'SONNET_55'),
                  default=None)
    L.append('First SONNET_55 run in the export: %s' % (first55.isoformat() if first55 else 'none'))
    L.append('')

    def lowbin(v): return '<20' if v < 20 else ('20-40' if v <= 40 else '>40')

    def highbin(v): return '<60' if v < 60 else '>=60'
    mpop = [s for s in pop if s['lead'] in DECISION_LEADS and s['row']['solar_low_cloud'] is not None
            and s['row']['solar_high_cloud'] is not None]
    rows_ = []
    for ev in ev_order:
        for lb in ('<20', '20-40', '>40'):
            for hb in ('<60', '>=60'):
                for m in sorted({s['model'] for s in mpop}):
                    us = [s for s in mpop if s['event'] == ev and s['model'] == m
                          and lowbin(s['row']['solar_low_cloud']) == lb and highbin(s['row']['solar_high_cloud']) == hb]
                    if not us:
                        continue
                    gcell = prop_cell(us, lambda s: s['go'], draws)[0]
                    gg = [s for s in us if s['go']]
                    lcell = prop_cell(gg, lambda s: s['pr']['low'], draws)[0]
                    ccell = prop_cell(gg, lambda s: s['pr']['canvas'], draws)[0]
                    rows_.append([ev, lb, hb, m, len(us), gcell, lcell, ccell])
    L += md_table(['event', 'cone low', 'cone high', 'model', 'slots', 'go rate', 'P-low among gos',
                   'P-canvas among gos (upper bound)'], rows_)

    # ---- 3 Angel shape
    L.append('## 3. The Angel shape (cone low < 20, cone high >= 60, observer and antisolar mid/high all < 20)')
    L.append('')
    ang = [s for s in gos if s['angel']]
    oth = [s for s in gos if not s['angel']]
    L.append('Angel-shape gos: %d of %d gos (T+0/T+1: %d of %d).' % (
        len(ang), len(gos), sum(1 for s in ang if s['lead'] in DECISION_LEADS), len(dec)))
    L.append('Gos excluded from the shape test because an input was NULL (pre-directional capture): %d of %d.'
             % (sum(1 for s in gos if not s['angel_testable']), len(gos)))
    L += md_table(['group', 'gos', 'P-low share (W, B)', 'P-canvas (NOT USED to judge R1: circular)'], [
        ['Angel shape', len(ang), prop_cell(ang, lambda s: s['pr']['low'], draws)[0],
         prop_cell(ang, lambda s: s['pr']['canvas'], draws)[0]],
        ['other gos', len(oth), prop_cell(oth, lambda s: s['pr']['low'], draws)[0],
         prop_cell(oth, lambda s: s['pr']['canvas'], draws)[0]]])
    t, kn, unk, share = cost_side(ang)
    L.append('R1 cost side: Angel-shape gos with an ERA5 cone/observer mid or high >= 20: %d of %d known (%d unknown), share %s.'
             % (t, kn, unk, pct(share)))
    L.append('')

    # ---- 4 raised
    L.append('## 4. Raised gos (final >= 4, previous rated run <= 3)')
    L.append('')
    rows_ = []
    for lab, k in (('raised, same model (R2 flagged)', 'same'), ('raised, changed model', 'changed'),
                   ('no previous rated run', 'noprev'), ('not raised', None)):
        us = [s for s in gos if s['raised'] == k]
        rows_.append([lab, len(us), prop_cell(us, lambda s: s['pr']['low'], draws)[0],
                      prop_cell(us, lambda s: s['pr']['canvas'], draws)[0]])
    L += md_table(['bucket', 'gos', 'P-low', 'P-canvas (upper bound)'], rows_)

    # ---- 5 capped
    L.append('## 5. Already capped (solar_trend_building and upwind_current_low_cloud >= 60)')
    L.append('')
    cap = [s for s in gos if s['capped']]
    L.append('Gos carrying the combined signal: %d of %d; of them Angel-shape: %d; raised-same: %d.' % (
        len(cap), len(gos), sum(1 for s in cap if s['angel']), sum(1 for s in cap if s['raised'] == 'same')))
    L.append('')

    # ---- 6 sun bands
    L.append('## 6. Sun altitude at the production slot (gos)')
    L.append('')
    rows_ = [[ev] + [sum(1 for s in gos if s['event'] == ev and s['band'] == b) for b in BANDS + ('unknown',)]
             for ev in ev_order]
    L += md_table(['event'] + list(BANDS) + ['unknown'], rows_)
    L.append('Every premise rate by band is in section 1.')
    L.append('')

    # ---- sync
    L.append('## Synchronous-engine rows (batch_state and triage_reason empty), reported separately, never pooled')
    L.append('')
    sgo = [s for s in a['sync'] if s['go']]
    rows_ = []
    for ev in ev_order:
        us = [s for s in sgo if s['event'] == ev and s['lead'] in DECISION_LEADS]
        rows_.append([ev, sum(1 for s in a['sync'] if s['event'] == ev), len(us),
                      prop_cell(us, lambda s: s['pr']['low'], draws)[0],
                      prop_cell(us, lambda s: s['pr']['canvas'], draws)[0]])
    L += md_table(['event', 'sync slots', 'gos T+0/T+1', 'P-low', 'P-canvas (upper bound)'], rows_)

    # ---- training
    L.append('## Training cases (listed individually, never pooled)')
    L.append('')
    tr = [s for s in slots if s['training']]
    rows_ = []
    for s in tr:
        r = s['row']
        rows_.append([s['training'], r['loc_name'], s['date'], s['event'], r['evaluation_model'], r['rating'],
                      s['engine'], 'go' if s['go'] else 'no',
                      'low=%s (min %s) far=%s canvas=%s' % (_tri(s['pr']['low']), r['cv_horizon_low_min'],
                                                          _tri(s['pr']['far']), _tri(s['pr']['canvas'])),
                      s['band'], 'yes' if s['angel'] else 'no', s['raised'] or '-', 'yes' if s['capped'] else 'no'])
    if not rows_:
        L.append('None of the training cases is in the export.')
        L.append('')
    else:
        L += md_table(['case', 'location', 'date', 'event', 'model', 'rating', 'engine', 'go', 'premises',
                       'sun band', 'Angel shape', 'raised', 'capped'], rows_)
    return '\n'.join(L) + '\n'


def write_csvs(a: Dict[str, object], out: str) -> None:
    os.makedirs(out, exist_ok=True)
    cols = ['location_id', 'loc_name', 'target_date', 'event', 'lead', 'model', 'engine', 'prompted', 'go', 'sky',
            'training', 'rating', 'prev_rating', 'prev_model', 'raised', 'p_low', 'p_far', 'p_canvas', 'angel_shape',
            'capped', 'era5_canvas', 'sun_alt', 'band', 'lead_hours', 'region']
    with open(os.path.join(out, 'slots.csv'), 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(cols)
        for s in a['slots']:
            r, p = s['row'], s['prev']
            w.writerow([r['location_id'], r['loc_name'], s['date'], s['event'], s['lead'], s['model'], s['engine'],
                        int(s['prompted']), int(s['go']), int(s['sky']), s['training'] or '', r['rating'],
                        p['rating'] if p else '', p['evaluation_model'] if p else '', s['raised'] or '',
                        _tri(s['pr']['low']), _tri(s['pr']['far']), _tri(s['pr']['canvas']), int(s['angel']),
                        int(s['capped']), '' if s['canvas_era5'] is None else int(s['canvas_era5']),
                        '' if s['alt'] is None else '%.2f' % s['alt'], s['band'], '%.2f' % s['lead_hours'],
                        s['region'] if s['region'] is not None else ''])
    with open(os.path.join(out, 'region_events.csv'), 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(['rule', 'region_id', 'date', 'event', 'flagged', 'p_low_fires'])
        for name, fn in flag_fns().items():
            for u in region_event_units(a['pooled'], fn):
                w.writerow([name, u['key'][0], u['key'][1], u['key'][2], int(u['flag']), int(u['out'])])


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('csv')
    ap.add_argument('--out', default='out')
    ap.add_argument('--draws', type=int, default=stats.DEFAULT_DRAWS)
    args = ap.parse_args(argv)
    try:
        rows = load_rows(args.csv)
    except ValueError as e:
        print('error: %s' % e, file=sys.stderr)
        return 2
    a = analyse(rows, args.draws)
    os.makedirs(args.out, exist_ok=True)
    report = render(a)
    with open(os.path.join(args.out, 'part_b.md'), 'w', encoding='utf-8') as f:
        f.write(report)
    write_csvs(a, args.out)
    print('wrote %s' % os.path.join(args.out, 'part_b.md'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
