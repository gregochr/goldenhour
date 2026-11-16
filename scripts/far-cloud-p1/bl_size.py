#!/usr/bin/env python3
"""Phase A of broken-low-canvas-plan.md: is there an error worth fixing on broken low cloud? No network, no spend.

Usage: python3 bl_size.py p1_forecasts.csv [--ratings bl_ratings.csv] [--protocol-start YYYY-MM-DD]
                          [--locations 1,2,3] [--out out/]

Reads the forecast export (export_forecasts.sql) and, optionally, the ratings export (bl_export_ratings.sql).
Writes out/bl.md and out/bl_slots.csv, prints the A5 gate as a decision block and the run chosen for the
31 Oct 2026 Angel of the North sunset.

Interpretations of the plan (also printed in the report):
 * Unit = premise.build_slots (latest eligible run per location, date and event before the event), kept when it
   is a prompted batch row at a sky location, T+0 or T+1, with a sky_rating. The 31 Oct 2026 Angel of the North
   sunset and the far-cloud-lid-plan section 1 training cases (premise.TRAINING_CASES) are excluded from every
   count and listed separately.
 * Broken-low (BL) and control (CL) use only stored forecast columns. A slot with a NULL input is untestable
   (counted, never classified). A NULL solar_trend_building is "not building".
 * Rates are over slots; "evenings" are distinct (date, event) pairs. The A5 criterion 2 rate is evenings with at
   least one BL slot over evenings with at least one testable slot, at the selected locations (all sky locations
   unless --locations is given, which is a looser test than "the owner's usual locations").
 * E1 is the share of BL slots whose sky_rating is 1 or 2 (the A3 operational definition: forecast only).
   E2 is the share of BL slots with a summary that matches PHRASES, over BL slots that have a summary.
 * Ratings: NOT_SEEN or empty rows are dropped; several ratings of one slot are averaged within the slot; the
   decision uses by_admin ratings only and every reader is reported beside them. Only ratings on or after
   --protocol-start are protocol evidence; without it there is none. Earlier ratings are descriptive only.
 * Matched comparison: slots are stratified by the forecast star the reader saw (forecast_rating 1, 2, 3, the
   rounded slot mean); each (evening, class, stratum) is one unit (the mean of its slots); the BL - CL difference
   is the average of the per-stratum differences weighted by nBL*nCL/(nBL+nCL) over strata holding both arms.
   The interval is a 5-calendar-day block bootstrap over evenings (stats.bootstrap_counts_ex); the gate needs
   the 2.5% bound above 0. More than 5% of draws undefined (no stratum with both arms) is "waiting".
 * Evenings counted for the 8 + 8 gate are counted only in strata holding both arms (a decision-usable rating on a
   slot with forecast_rating 1-3, in a stratum with at least one BL and one CL evening); units in other strata are
   neither counted nor resampled.
"""
from __future__ import annotations

import argparse
import csv
import os
import random
import sys
from collections import defaultdict
from datetime import date
from typing import Dict, List, Optional, Sequence, Set, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import premise  # noqa: E402
import stats  # noqa: E402

# A1 definitions. Fixed before the first run; the 31 Oct and P1 numbers were already seen.
BL_LOW_MIN = 20
BL_LOW_MAX = 39           # inclusive
SENS_LOW_BANDS = ((15, 19), (40, 45))   # sensitivity bands, never in a decision
CL_LOW_MAX = 10           # control: solar cone low below this
CANVAS_MAX = 10           # solar and antisolar mid/high below this
FAR_LOW_MAX = 30
OBSERVER_LOW_MAX = 60
OBSERVER_SPLIT = 5
ERA5_LOW_MIN, ERA5_LOW_MAX, ERA5_CANVAS_MAX = 10, 50, 20
HETEROGENEOUS_SPREAD = 20
SKY_STARS_E1 = 2

# A3 / A5.
PHRASES = ('no canvas', 'nothing to catch', 'no cloud to catch', 'no mid or high cloud', 'clear sky')
EXAMPLES = 20
EXAMPLE_SEED = 20261116
E1_MIN = 0.10
E2_MIN = 0.30
EVENING_SHARE_MIN = 0.03
MIN_EVENINGS_PER_ARM = 8
STRATA = (1, 2, 3)
LIST_LIMIT = 60

CASE_31_OCT = ('angel of the north', '2026-10-31', 'SUNSET')
CASE_31_OCT_EXPECTED_EVAL = 137844

REQUIRED_SLOT_INPUTS = ('solar_low_cloud', 'solar_mid_cloud', 'solar_high_cloud', 'antisolar_mid_cloud',
                        'antisolar_high_cloud', 'far_solar_low_cloud', 'low_cloud')


# ------------------------------------------------------------------ A1 definitions

def kind_of(r: Dict[str, object]) -> Tuple[Optional[str], str]:
    """(kind, band) for a stored forecast row. kind is 'BL', 'CL', 'SENS' (a sensitivity band, never in a
    decision), 'OTHER' (testable, in none of them) or 'UNTESTABLE' (a required input is NULL)."""
    if any(r[c] is None for c in REQUIRED_SLOT_INPUTS):
        return 'UNTESTABLE', ''
    low = r['solar_low_cloud']
    common = (r['solar_mid_cloud'] < CANVAS_MAX and r['solar_high_cloud'] < CANVAS_MAX
              and r['antisolar_mid_cloud'] < CANVAS_MAX and r['antisolar_high_cloud'] < CANVAS_MAX
              and r['far_solar_low_cloud'] < FAR_LOW_MAX and r['low_cloud'] < OBSERVER_LOW_MAX
              and not r['solar_trend_building'])
    if not common:
        return 'OTHER', ''
    if BL_LOW_MIN <= low <= BL_LOW_MAX:
        return 'BL', '20-29' if low < 30 else '30-39'
    if low < CL_LOW_MAX:
        return 'CL', '<10'
    for lo, hi in SENS_LOW_BANDS:
        if lo <= low <= hi:
            return 'SENS', '%d-%d' % (lo, hi)
    return 'OTHER', ''


def era5_consistent(r: Dict[str, object]) -> Optional[bool]:
    """Diagnostic only: ERA5 horizon low 10-50, mid < 20, high < 20. None when ERA5 is missing."""
    vals = (r['cv_horizon_low_cloud'], r['cv_horizon_mid_cloud'], r['cv_horizon_high_cloud'])
    if r['cv_id'] is None or any(v is None for v in vals):
        return None
    return ERA5_LOW_MIN <= vals[0] <= ERA5_LOW_MAX and vals[1] < ERA5_CANVAS_MAX and vals[2] < ERA5_CANVAS_MAX


def heterogeneous(r: Dict[str, object]) -> Optional[bool]:
    """Bearing spread of ERA5 cone low >= 20 (a label, not gappiness). None when either end is missing."""
    lo, hi = r['cv_horizon_low_min'], r['cv_horizon_low_max']
    if r['cv_id'] is None or lo is None or hi is None:
        return None
    return hi - lo >= HETEROGENEOUS_SPREAD


def e2_phrase(summary: Optional[str]) -> Optional[str]:
    """The first PHRASES entry found in the summary (case-insensitive), else None."""
    s = (summary or '').lower()
    for p in PHRASES:
        if p in s:
            return p
    return None


def is_case_31_oct(s: Dict[str, object]) -> bool:
    frag, d, ev = CASE_31_OCT
    return frag in (s['row']['loc_name'] or '').lower() and s['date'] == d and s['event'] == ev


# ------------------------------------------------------------------ units

def build_units(rows: Sequence[Dict[str, object]], locations: Optional[Set[int]] = None) -> Dict[str, object]:
    """Slots by premise.build_slots, filtered to the study population and classified. ``all_slots`` keeps every
    slot (for the 31 Oct row); ``excluded`` lists what was left out of every count."""
    all_slots, none_eligible = premise.build_slots(rows)
    base: List[dict] = []
    excluded_training: List[dict] = []
    excluded_31 = None
    for s in all_slots:
        if locations is not None and s['row']['location_id'] not in locations:
            continue
        if not (s['sky'] and s['engine'] == 'batch' and s['prompted'] and s['lead'] in premise.DECISION_LEADS
                and s['row']['sky_rating'] is not None):
            continue
        if is_case_31_oct(s):
            excluded_31 = s
            continue
        if s['training']:
            excluded_training.append(s)
            continue
        s['kind'], s['band'] = kind_of(s['row'])
        base.append(s)
    by_kind: Dict[str, List[dict]] = defaultdict(list)
    for s in base:
        by_kind[s['kind']].append(s)
    return {'all_slots': all_slots, 'none_eligible': none_eligible, 'base': base, 'by_kind': by_kind,
            'bl': by_kind['BL'], 'cl': by_kind['CL'], 'sens': by_kind['SENS'],
            'untestable': by_kind['UNTESTABLE'], 'excluded_training': excluded_training,
            'excluded_31': excluded_31}


def evening(s: dict) -> Tuple[str, str]:
    return s['date'], s['event']


def evenings(slots: Sequence[dict]) -> Set[Tuple[str, str]]:
    return {evening(s) for s in slots}


# ------------------------------------------------------------------ ratings

def _truthy(v: Optional[str]) -> bool:
    return (v or '').strip().lower() in ('t', 'true', '1', 'yes', 'y')


def safe_path(p: str, base: str, *, must_exist_file: bool = False, suffix: Optional[str] = None) -> str:
    """Resolve ``p`` (symlinks and ``..`` included) and require it to lie inside ``base``. Optionally require an
    existing regular file and a case-insensitive suffix. Returns the resolved path; ValueError otherwise."""
    resolved = os.path.realpath(p)
    root = os.path.realpath(base)
    if os.path.commonpath([root, resolved]) != root:
        raise ValueError('refusing path outside %s: %s' % (root, p))
    if must_exist_file and not os.path.isfile(resolved):
        raise ValueError('not an existing file: %s' % p)
    if suffix and not resolved.lower().endswith(suffix):
        raise ValueError('expected a %s file: %s' % (suffix, p))
    return resolved


def load_ratings(path: str, base: Optional[str] = None) -> List[Dict[str, object]]:
    """Parse the bl_export_ratings.sql CSV by column name. Every row is kept (NOT_SEEN and empty ratings included;
    ``valid`` marks the usable ones). A missing required column, including by_admin, is a ValueError. The path must
    be an existing .csv inside ``base`` (default: the working directory)."""
    path = safe_path(path, base or os.getcwd(), must_exist_file=True, suffix='.csv')
    need = ['location_id', 'event_date', 'event_type', 'rating', 'seen', 'forecast_rating', 'by_admin']
    out: List[Dict[str, object]] = []
    with open(path, newline='', encoding='utf-8-sig') as f:
        reader = csv.DictReader(f)
        missing = [c for c in need if c not in (reader.fieldnames or [])]
        if missing:
            raise ValueError('%s: missing column(s): %s' % (path, ', '.join(missing)))
        for r in reader:
            rating = None if (r['rating'] or '').strip() == '' else int(float(r['rating']))
            seen = (r['seen'] or '').strip().upper()
            fr = None if (r['forecast_rating'] or '').strip() == '' else int(float(r['forecast_rating']))
            out.append({'key': (int(float(r['location_id'])), r['event_date'][:10], r['event_type'].strip()),
                        'date': r['event_date'][:10], 'rating': rating, 'seen': seen, 'forecast_rating': fr,
                        'by_admin': _truthy(r['by_admin']),
                        'valid': rating is not None and seen != 'NOT_SEEN'})
    return out


def ratings_by_slot(ratings: Sequence[Dict[str, object]]) -> Dict[tuple, List[dict]]:
    acc: Dict[tuple, List[dict]] = defaultdict(list)
    for r in ratings:
        if r['valid']:
            acc[r['key']].append(r)
    return acc


def slot_outcome(s: dict, by_slot: Dict[tuple, List[dict]], admin_only: bool,
                 start: Optional[date] = None, before: Optional[date] = None) -> Optional[Tuple[float, Optional[int]]]:
    """(mean outcome stars, rounded mean forecast star or None) over the slot's valid ratings, optionally only the
    admin's, on or after ``start`` and strictly before ``before``. None when no rating qualifies."""
    rs = [r for r in by_slot.get((s['row']['location_id'], s['date'], s['event']), [])
          if (not admin_only or r['by_admin'])
          and (start is None or date.fromisoformat(r['date']) >= start)
          and (before is None or date.fromisoformat(r['date']) < before)]
    if not rs:
        return None
    mean = sum(r['rating'] for r in rs) / len(rs)
    frs = [r['forecast_rating'] for r in rs if r['forecast_rating'] is not None]
    fstar = int(sum(frs) / len(frs) + 0.5) if frs else None
    return mean, fstar


def matched_units(bl: Sequence[dict], cl: Sequence[dict], by_slot: Dict[tuple, List[dict]], admin_only: bool,
                  start: Optional[date] = None, before: Optional[date] = None) -> List[Tuple[str, str, str, int, float]]:
    """One unit per (evening, class, stratum): (date, event, 'BL'|'CL', stratum, mean outcome of its slots)."""
    acc: Dict[tuple, List[float]] = defaultdict(list)
    for cls, slots in (('BL', bl), ('CL', cl)):
        for s in slots:
            o = slot_outcome(s, by_slot, admin_only, start, before)
            if o is None or o[1] not in STRATA:
                continue
            acc[(s['date'], s['event'], cls, o[1])].append(o[0])
    return [(d, ev, cls, st, sum(v) / len(v)) for (d, ev, cls, st), v in sorted(acc.items())]


def matched_diff(counts: Sequence[float]) -> Optional[float]:
    """The weighted BL - CL difference from 12 additive counters: per stratum (sumBL, nBL, sumCL, nCL)."""
    num = den = 0.0
    for i in range(len(STRATA)):
        sb, nb, sc, nc = counts[4 * i:4 * i + 4]
        if nb > 0 and nc > 0:
            w = nb * nc / (nb + nc)
            num += w * (sb / nb - sc / nc)
            den += w
    return num / den if den else None


def unit_counts(units: Sequence[tuple]) -> Dict[str, Tuple[float, ...]]:
    acc: Dict[str, List[float]] = {}
    for d, _ev, cls, st, mean in units:
        cur = acc.setdefault(d, [0.0] * (4 * len(STRATA)))
        base = 4 * STRATA.index(st) + (0 if cls == 'BL' else 2)
        cur[base] += mean
        cur[base + 1] += 1
    return {d: tuple(v) for d, v in acc.items()}


def strata_table(units: Sequence[tuple]) -> List[dict]:
    """Per stratum: evenings and mean outcome for BL and CL, and the difference."""
    out = []
    for st in STRATA:
        row = {'stratum': st}
        for cls in ('BL', 'CL'):
            vals = [u[4] for u in units if u[2] == cls and u[3] == st]
            row[cls + '_n'] = len(vals)
            row[cls + '_mean'] = sum(vals) / len(vals) if vals else None
        row['diff'] = (row['BL_mean'] - row['CL_mean']) if row['BL_n'] and row['CL_n'] else None
        row['matched'] = bool(row['BL_n'] and row['CL_n'])
        out.append(row)
    return out


def matched_strata(units: Sequence[tuple]) -> Set[int]:
    """Strata holding at least one BL unit and at least one CL unit: the only ones a difference can come from."""
    return {st for st in STRATA
            if any(u[3] == st and u[2] == 'BL' for u in units) and any(u[3] == st and u[2] == 'CL' for u in units)}


def evaluate_matched(units: Sequence[tuple], draws: int) -> Dict[str, object]:
    """Evening counts, the weighted difference and its block-bootstrap interval for the A5.3 gate. Only units in
    strata holding both arms (the matched units) are counted, estimated and resampled, so the 8 + 8 evening gate
    and the bootstrap population are the same evenings. ``strata`` still reports every stratum (descriptive)."""
    ms = matched_strata(units)
    mu = [u for u in units if u[3] in ms]
    bl_ev = {(u[0], u[1]) for u in mu if u[2] == 'BL'}
    cl_ev = {(u[0], u[1]) for u in mu if u[2] == 'CL'}
    dc = unit_counts(mu)
    point = matched_diff([sum(v[i] for v in dc.values()) for i in range(4 * len(STRATA))]) if dc else None
    lo = hi = None
    dropped = 0.0
    if dc:
        bs, dr = stats.bootstrap_counts_ex(dc, matched_diff, draws=draws)
        dropped = dr / draws
        iv = stats.percentile_interval(bs)
        if iv:
            lo, hi = iv
    return {'bl_evenings': len(bl_ev), 'cl_evenings': len(cl_ev), 'point': point, 'lo': lo, 'hi': hi,
            'dropped': dropped, 'strata': strata_table(units), 'matched_strata': sorted(ms)}


# ------------------------------------------------------------------ A3 measures

def rate(k: int, n: int) -> str:
    if n == 0:
        return '0/0'
    w = stats.wilson(k, n)
    return '%d/%d %s [%s, %s]' % (k, n, premise.pct(w[0]), premise.pct(w[1]), premise.pct(w[2]))


def e1_counts(bl: Sequence[dict]) -> Tuple[int, int]:
    return sum(1 for s in bl if s['row']['sky_rating'] <= SKY_STARS_E1), len(bl)


def e2_counts(bl: Sequence[dict]) -> Tuple[int, int]:
    with_summary = [s for s in bl if (s['row']['summary'] or '').strip()]
    return sum(1 for s in with_summary if e2_phrase(s['row']['summary'])), len(with_summary)


def frequency(units: Dict[str, object]) -> Dict[str, object]:
    base = units['base']
    testable = [s for s in base if s['kind'] != 'UNTESTABLE']
    all_ev = evenings(testable)
    bl_ev = evenings(units['bl'])
    return {'testable': len(testable), 'evenings': len(all_ev), 'bl_evenings': len(bl_ev),
            'cl_evenings': len(evenings(units['cl'])),
            'share': (len(bl_ev) / len(all_ev)) if all_ev else None}


def share_txt(x: Optional[float]) -> str:
    return premise.pct(x)


# ------------------------------------------------------------------ the A5 gate

def gate(units: Dict[str, object], protocol: Optional[Dict[str, object]], have_ratings: bool,
         protocol_start: Optional[date]) -> Dict[str, object]:
    """The A5 decision. outcome is 'proceed', 'stop' or 'waiting'. Criteria 1 and 2 are checked first (a failure
    is 'stop'), then criterion 3."""
    bl = units['bl']
    k1, n1 = e1_counts(bl)
    k2, n2 = e2_counts(bl)
    e1 = (k1 / n1) if n1 else None
    e2 = (k2 / n2) if n2 else None
    via = []
    if e1 is not None and e1 >= E1_MIN:
        via.append('E1')
    if e2 is not None and e2 >= E2_MIN:
        via.append('E2')
    c1 = bool(via)
    freq = frequency(units)
    c2 = freq['share'] is not None and freq['share'] >= EVENING_SHARE_MIN
    lines = [
        '1. An error worth fixing (E1 >= %d%% or E2 >= %d%% of BL slots): %s. E1 %s, E2 %s.'
        % (round(100 * E1_MIN), round(100 * E2_MIN), 'MET via ' + ' and '.join(via) if c1 else 'NOT MET',
         rate(k1, n1), rate(k2, n2)),
        '2. It happens (BL on >= %d%% of distinct evenings): %s. %d of %d evenings (%s).'
        % (round(100 * EVENING_SHARE_MIN), 'MET' if c2 else 'NOT MET', freq['bl_evenings'], freq['evenings'],
           share_txt(freq['share'])),
    ]
    failed = [i for i, ok in ((1, c1), (2, c2)) if not ok]
    outcome, reason = None, ''
    if failed:
        outcome = 'stop'
        reason = 'criterion %s not met; stop and record it in broken-low-canvas-plan.md section 2' \
                 % ' and '.join(str(i) for i in failed)
        lines.append('3. Outcome evidence: not evaluated (stopped on criterion %s).' % failed[0])
    else:
        if not have_ratings or protocol_start is None or protocol is None:
            outcome = 'waiting'
            why = 'no ratings file' if not have_ratings else 'no --protocol-start (so no protocol evidence)'
            reason = 'waiting for protocol ratings: %s' % why
            lines.append('3. Outcome evidence (protocol ratings, by_admin only): NOT YET EVALUABLE, %s.' % why)
        else:
            nb, nc = protocol['bl_evenings'], protocol['cl_evenings']
            if nb < MIN_EVENINGS_PER_ARM or nc < MIN_EVENINGS_PER_ARM:
                outcome = 'waiting'
                reason = 'waiting for protocol ratings: %d BL and %d CL evenings, need %d of each' \
                         % (nb, nc, MIN_EVENINGS_PER_ARM)
                lines.append('3. Outcome evidence (protocol ratings, by_admin only): NOT YET EVALUABLE, %d BL and '
                             '%d CL evenings (need >= %d each).' % (nb, nc, MIN_EVENINGS_PER_ARM))
            elif protocol['lo'] is None or protocol['dropped'] > stats.MAX_DROPPED_FRACTION:
                outcome = 'waiting'
                reason = ('waiting for protocol ratings: no usable bootstrap interval (%.1f%% of draws undefined, '
                          'no stratum holds both arms)' % (100 * protocol['dropped']))
                lines.append('3. Outcome evidence (protocol ratings, by_admin only): NOT YET EVALUABLE, %s.'
                             % reason.split(': ', 1)[1])
            else:
                met = protocol['lo'] > 0
                lines.append('3. Outcome evidence (protocol ratings, by_admin only): %s. %d BL and %d CL evenings; '
                             'matched BL - CL = %+.2f stars, 95%% block-bootstrap interval [%+.2f, %+.2f] (lower '
                             'bound must be > 0).' % ('MET' if met else 'NOT MET', nb, nc, protocol['point'],
                                                     protocol['lo'], protocol['hi']))
                if met:
                    outcome = 'proceed'
                    reason = 'all three criteria met; proceed to Phase B (via ' + ' and '.join(via) + ')'
                else:
                    outcome = 'stop'
                    reason = 'criterion 3 not met (lower bound <= 0); stop and record it in the plan'
    return {'outcome': outcome, 'reason': reason, 'lines': lines, 'via': via, 'c1': c1, 'c2': c2,
            'e1': (k1, n1), 'e2': (k2, n2), 'freq': freq}


def decision_block(g: Dict[str, object]) -> List[str]:
    out = ['A5 GATE TO PHASE B', '==================']
    out += g['lines']
    label = {'proceed': 'proceed', 'stop': 'stop', 'waiting': 'waiting for protocol ratings'}[g['outcome']]
    out.append('OUTCOME: %s (%s)' % (label, g['reason']))
    return out


# ------------------------------------------------------------------ report

def case_31_oct_lines(units: Dict[str, object]) -> List[str]:
    """The 31 Oct row on its own (excluded from every count). Chooses its run from ALL slots."""
    frag, d, ev = CASE_31_OCT
    hits = [s for s in units['all_slots']
            if frag in (s['row']['loc_name'] or '').lower() and s['date'] == d and s['event'] == ev]
    if not hits:
        return ['No Angel of the North %s %s slot in this export.' % (d, ev)]
    out = []
    for s in hits:
        r = s['row']
        kind, band = kind_of(r)
        eid = r['evaluation_id']
        expect = ('as expected (%d or a later run)' % CASE_31_OCT_EXPECTED_EVAL
                  if eid is not None and eid >= CASE_31_OCT_EXPECTED_EVAL
                  else 'EARLIER than the expected %d: check the export' % CASE_31_OCT_EXPECTED_EVAL)
        out.append('Chosen run: evaluation %s, run at %s, %s, T+%s, rating %s, sky_rating %s, fiery %s; %s.'
                   % (eid, r['forecast_run_at'], s['model'], s['lead'], r['rating'], r['sky_rating'],
                      r['fiery_sky_potential'], expect))
        out.append('Cone low/mid/high %s/%s/%s, antisolar mid/high %s/%s, far low %s, observer low %s, building %s.'
                   % (r['solar_low_cloud'], r['solar_mid_cloud'], r['solar_high_cloud'], r['antisolar_mid_cloud'],
                      r['antisolar_high_cloud'], r['far_solar_low_cloud'], r['low_cloud'], r['solar_trend_building']))
        out.append('A1 class: %s%s; prompted %s, engine %s. E2 phrase: %s.'
                   % (kind, ' (%s)' % band if band else '', s['prompted'], s['engine'],
                      e2_phrase(r['summary']) or 'none'))
        out.append('ERA5 consistent: %s; heterogeneous: %s.' % (era5_consistent(r), heterogeneous(r)))
    return out


def render(units: Dict[str, object], g: Dict[str, object], by_slot: Optional[Dict[tuple, List[dict]]],
           protocol_start: Optional[date], draws: int, protocol: Optional[Dict[str, object]],
           locations: Optional[Set[int]]) -> str:
    bl, cl, sens = units['bl'], units['cl'], units['sens']
    base = units['base']
    L: List[str] = []
    A = L.append
    A('# Broken low cloud, Phase A sizing')
    A('')
    A('Plan: `docs/engineering/broken-low-canvas-plan.md` section 2. No network, no spend. Read the definitions '
      'below first; they were fixed before the first run.')
    A('')
    A('## 0. Definitions and what was counted')
    A('')
    A('- **Unit:** latest eligible run per (location, date, event) before the event (`premise.build_slots`), a prompted '
      'batch row at a sky location, T+0 or T+1, with a sky_rating. Locations: %s.'
      % ('all' if locations is None else 'only ' + ','.join(str(i) for i in sorted(locations))))
    A('- **BL:** cone low %d-%d (inclusive), cone mid/high < %d, antisolar mid/high < %d, far 226 km low < %d, '
      'observer low < %d, no [BUILDING] trend.' % (BL_LOW_MIN, BL_LOW_MAX, CANVAS_MAX, CANVAS_MAX, FAR_LOW_MAX,
                                                    OBSERVER_LOW_MAX))
    A('- **CL (control):** the same filters with cone low < %d.' % CL_LOW_MAX)
    A('- **Sensitivity bands** (cone low 15-19 and 40-45, same other filters) are reported, never in a decision.')
    A('- **Rates are over slots; evenings are distinct (date, event) pairs.**')
    A('- Slots in the population: %d (%d BL, %d CL, %d sensitivity-band, %d untestable because a required stored '
      'input is NULL). %d slot keys had no eligible run.'
      % (len(base), len(bl), len(cl), len(sens), len(units['untestable']), units['none_eligible']))
    ex = units['excluded_31']
    A('- **Excluded from every count:** the 31 Oct 2026 Angel of the North sunset (%s) and %d far-cloud-lid-plan '
      'section 1 training case slot(s)%s.'
      % ('present' if ex else 'absent from this population',
         len(units['excluded_training']),
         ': ' + ', '.join('%s %s %s' % (s['row']['loc_name'], s['date'], s['event'])
                          for s in units['excluded_training']) if units['excluded_training'] else ''))
    A('')

    # ---- A3.1 frequency
    freq = g['freq']
    A('## 1. Frequency (A3 item 1)')
    A('')
    A('- Testable slots %d on %d distinct evenings. BL on %d evenings (%s of evenings; the A5 criterion 2 rate). CL on '
      '%d evenings.' % (freq['testable'], freq['evenings'], freq['bl_evenings'], share_txt(freq['share']),
                        freq['cl_evenings']))
    A('- Slot level: BL %s of testable slots; CL %s.' % (
        premise.pct(len(bl) / freq['testable']) if freq['testable'] else '-',
        premise.pct(len(cl) / freq['testable']) if freq['testable'] else '-'))
    e5 = [era5_consistent(s['row']) for s in bl]
    k5 = sum(1 for v in e5 if v)
    n5 = sum(1 for v in e5 if v is not None)
    A('- **ERA5-consistent BL** (diagnostic, not a gate: ERA5 horizon low %d-%d, mid and high < %d): %s of BL slots '
      'with ERA5 (%d BL slots have none).' % (ERA5_LOW_MIN, ERA5_LOW_MAX, ERA5_CANVAS_MAX, rate(k5, n5), len(bl) - n5))
    h = [heterogeneous(s['row']) for s in bl]
    kh = sum(1 for v in h if v)
    nh = sum(1 for v in h if v is not None)
    A('- **Heterogeneous** (ERA5 bearing spread >= %d; a label, not gappiness: the cone mean cannot see which '
      'bearing is solid): %s.' % (HETEROGENEOUS_SPREAD, rate(kh, nh)))
    A('')
    A('By month and event (evenings):')
    A('')
    months = sorted({(s['date'][:7], s['event']) for s in base if s['kind'] != 'UNTESTABLE'})
    rows = []
    for m, ev in months:
        sl = [s for s in base if s['date'][:7] == m and s['event'] == ev and s['kind'] != 'UNTESTABLE']
        sb = [s for s in sl if s['kind'] == 'BL']
        sc = [s for s in sl if s['kind'] == 'CL']
        eb = [era5_consistent(s['row']) for s in sb]
        rows.append([m, ev, len(evenings(sl)), len(evenings(sb)), len(sb),
                     '%d/%d' % (sum(1 for v in eb if v), sum(1 for v in eb if v is not None)),
                     len(evenings(sc)), len(sc)])
    L += premise.md_table(['month', 'event', 'evenings', 'BL evenings', 'BL slots', 'ERA5-consistent', 'CL evenings',
                           'CL slots'], rows)
    A('Per location (first %d by BL slots; `bl_slots.csv` holds every slot):' % LIST_LIMIT)
    A('')
    locs: Dict[object, dict] = {}
    for s in base:
        if s['kind'] == 'UNTESTABLE':
            continue
        e = locs.setdefault(s['row']['location_id'], {'name': s['row']['loc_name'], 'n': 0, 'bl': [], 'cl': []})
        e['n'] += 1
        if s['kind'] == 'BL':
            e['bl'].append(s)
        elif s['kind'] == 'CL':
            e['cl'].append(s)
    lrows = sorted(locs.items(), key=lambda kv: (-len(kv[1]['bl']), str(kv[1]['name'])))[:LIST_LIMIT]
    L += premise.md_table(['location', 'id', 'testable slots', 'BL slots', 'BL evenings', 'CL slots'],
                          [[e['name'], i, e['n'], len(e['bl']), len(evenings(e['bl'])), len(e['cl'])]
                           for i, e in lrows])

    # ---- A3.2 / A3.3
    k1, n1 = g['e1']
    k2, n2 = g['e2']
    A('## 2. E1: stars (A3 item 2)')
    A('')
    A('Share of BL slots forecast 1-2 stars (sky_rating <= %d): **%s**. The gate needs >= %d%%.'
      % (SKY_STARS_E1, rate(k1, n1), round(100 * E1_MIN)))
    A('')
    A('## 3. E2: words (A3 item 3)')
    A('')
    A('Share of BL slots with a summary that matches any of %s (case-insensitive substring): **%s**. The gate needs '
      '>= %d%%.' % (', '.join('"%s"' % p for p in PHRASES), rate(k2, n2), round(100 * E2_MIN)))
    A('')
    cand = [s for s in bl if (s['row']['summary'] or '').strip()]
    pick = random.Random(EXAMPLE_SEED).sample(cand, min(EXAMPLES, len(cand)))
    A('%d random BL summaries (matched and unmatched alike) so the matcher can be checked by eye:' % len(pick))
    A('')
    for s in sorted(pick, key=lambda s: (s['date'], s['event'], str(s['row']['location_id']))):
        r = s['row']
        ph = e2_phrase(r['summary'])
        A('- %s, %s %s, %d star(s): %s %s' % (r['loc_name'], s['date'], s['event'], r['sky_rating'],
                                                '[MATCH "%s"]' % ph if ph else '[no match]',
                                                (r['summary'] or '').strip().replace('\n', ' ')))
    A('')

    # ---- A3.4 historic ratings
    A('## 4. Historic ratings, descriptive only (A3 item 4)')
    A('')
    if by_slot is None:
        A('No `--ratings` file given.')
        A('')
    else:
        before = protocol_start
        A('Ratings %s are historic: people notice broken low cloud mainly when it lights up, so these over-sample good '
          'BL evenings. **No pass or fail is drawn from them.** Outcome stars are the slot mean of valid ratings '
          '(NOT_SEEN and empty excluded), matched on the forecast star the reader saw. n is in evenings.'
          % ('before %s' % protocol_start if protocol_start else '(all of them: no --protocol-start was given)'))
        A('')
        for label, admin_only in (('by_admin ratings (the owner)', True), ('every reader', False)):
            us = matched_units(bl, cl, by_slot, admin_only, None, before)
            A('**%s:**' % label)
            A('')
            L += strata_md(strata_table(us))

    # ---- A3.5 splits
    A('## 5. Splits (A3 item 5)')
    A('')
    A('Observer low split (BL only) and cone-low sub-bands. The 15-19 and 40-45 bands are **sensitivity only** and '
      'never enter a decision.')
    A('')
    srows = []

    def split_row(label: str, sl: Sequence[dict]) -> None:
        a, b = e1_counts(sl)
        c, d = e2_counts(sl)
        srows.append([label, len(sl), len(evenings(sl)), rate(a, b), rate(c, d)])
    split_row('BL, observer low 0-%d' % OBSERVER_SPLIT, [s for s in bl if s['row']['low_cloud'] < OBSERVER_SPLIT])
    split_row('BL, observer low %d-%d' % (OBSERVER_SPLIT, OBSERVER_LOW_MAX),
              [s for s in bl if s['row']['low_cloud'] >= OBSERVER_SPLIT])
    split_row('sensitivity, cone low 15-19', [s for s in sens if s['band'] == '15-19'])
    split_row('BL, cone low 20-29', [s for s in bl if s['band'] == '20-29'])
    split_row('BL, cone low 30-39', [s for s in bl if s['band'] == '30-39'])
    split_row('sensitivity, cone low 40-45', [s for s in sens if s['band'] == '40-45'])
    L += premise.md_table(['split', 'slots', 'evenings', 'E1 (sky <= 2)', 'E2 (phrase)'], srows)

    # ---- A3.6
    A('## 6. The 31 Oct Angel of the North sunset (A3 item 6)')
    A('')
    A('Excluded from every count above and below.')
    A('')
    for ln in case_31_oct_lines(units):
        A('- ' + ln)
    A('')

    # ---- protocol
    A('## 7. Protocol evidence (A4, A5 criterion 3)')
    A('')
    if by_slot is None or protocol_start is None or protocol is None:
        A('No protocol evidence: %s.' % ('no `--ratings` file' if by_slot is None
                                         else 'no `--protocol-start`, so no rating counts as protocol evidence'))
        A('')
    else:
        A('Ratings on or after %s, by_admin only, valid ratings only. Evenings are counted only in strata '
          'holding both a BL and a CL unit (matched = yes; forecast star 1-3). %d BL and %d CL evenings; %d draws, 5-day blocks by evening.'
          % (protocol_start, protocol['bl_evenings'], protocol['cl_evenings'], draws))
        A('')
        L += strata_md(protocol['strata'])
        if protocol['point'] is not None:
            A('Matched BL - CL: %+.2f stars%s.' % (protocol['point'], ('; 95%% interval [%+.2f, %+.2f]; %.1f%% of '
                                                                   'draws undefined' % (protocol['lo'], protocol['hi'],
                                                                                        100 * protocol['dropped']))
                                                  if protocol['lo'] is not None else '; no interval'))
        A('')
        A('Every reader, beside the decision (not used in it):')
        A('')
        us = matched_units(bl, cl, by_slot, False, protocol_start)
        L += strata_md(strata_table(us))

    # ---- decision
    A('## 8. The A5 gate')
    A('')
    A('```')
    L += decision_block(g)
    A('```')
    A('')
    return '\n'.join(L)


def strata_md(strata: Sequence[dict]) -> List[str]:
    """The per-stratum table. Only strata holding both arms (matched = yes) count toward the gate."""
    return premise.md_table(['forecast stars', 'BL evenings', 'BL mean outcome', 'CL evenings', 'CL mean outcome',
                             'BL - CL', 'counts toward gate'],
                            [[t['stratum'], t['BL_n'], _f(t['BL_mean']), t['CL_n'], _f(t['CL_mean']),
                              _f(t['diff'], True), 'yes' if t['matched'] else 'no'] for t in strata])


def _f(x: Optional[float], signed: bool = False) -> str:
    if x is None:
        return '-'
    return ('%+.2f' if signed else '%.2f') % x


# ------------------------------------------------------------------ output

def slot_rows(units: Dict[str, object], by_slot: Optional[Dict[tuple, List[dict]]],
              protocol_start: Optional[date]) -> List[List[object]]:
    out = []
    for s in units['base']:
        if s['kind'] not in ('BL', 'CL', 'SENS'):
            continue
        r = s['row']
        adm = slot_outcome(s, by_slot, True) if by_slot is not None else None
        allr = slot_outcome(s, by_slot, False) if by_slot is not None else None
        n_all = len(by_slot.get((r['location_id'], s['date'], s['event']), [])) if by_slot is not None else 0
        n_adm = sum(1 for x in by_slot.get((r['location_id'], s['date'], s['event']), []) if x['by_admin']) \
            if by_slot is not None else 0
        out.append([s['kind'], s['band'], r['loc_name'], r['location_id'], s['date'], s['event'], r['evaluation_id'],
                    s['model'], s['lead'], r['sky_rating'], r['rating'], r['solar_low_cloud'], r['solar_mid_cloud'],
                    r['solar_high_cloud'], r['antisolar_mid_cloud'], r['antisolar_high_cloud'],
                    r['far_solar_low_cloud'], r['low_cloud'],
                    _nz(era5_consistent(r)), _nz(heterogeneous(r)), e2_phrase(r['summary']) or '',
                    n_all, n_adm, _f(allr[0]) if allr else '', _f(adm[0]) if adm else ''])
    return out


def _nz(v: Optional[bool]) -> object:
    return '' if v is None else int(v)


SLOT_HEADER = ['kind', 'band', 'location', 'location_id', 'date', 'event', 'evaluation_id', 'model', 'lead',
               'sky_rating', 'rating', 'cone_low', 'cone_mid', 'cone_high', 'antisolar_mid', 'antisolar_high',
               'far_low', 'observer_low', 'era5_consistent', 'heterogeneous', 'e2_phrase', 'n_ratings_all',
               'n_ratings_admin', 'mean_outcome_all', 'mean_outcome_admin']


def analyse(rows: Sequence[Dict[str, object]], ratings: Optional[List[Dict[str, object]]],
            protocol_start: Optional[date], draws: int, locations: Optional[Set[int]] = None) -> Dict[str, object]:
    units = build_units(rows, locations)
    by_slot = ratings_by_slot(ratings) if ratings is not None else None
    protocol = None
    if by_slot is not None and protocol_start is not None:
        us = matched_units(units['bl'], units['cl'], by_slot, True, protocol_start)
        protocol = evaluate_matched(us, draws)
    g = gate(units, protocol, ratings is not None, protocol_start)
    return {'units': units, 'by_slot': by_slot, 'protocol': protocol, 'gate': g}


def parse_locations(text: Optional[str]) -> Optional[Set[int]]:
    if not text:
        return None
    return {int(x) for x in text.split(',') if x.strip()}


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('csv', help='p1_forecasts.csv (export_forecasts.sql); every path must be inside the '
                    'directory the script is run from')
    ap.add_argument('--ratings', help='bl_ratings.csv (bl_export_ratings.sql)')
    ap.add_argument('--protocol-start', help='YYYY-MM-DD: only ratings on or after it are protocol evidence')
    ap.add_argument('--locations', help='comma-separated location ids to restrict to (default: all sky locations)')
    ap.add_argument('--out', default='out')
    ap.add_argument('--draws', type=int, default=stats.DEFAULT_DRAWS)
    args = ap.parse_args(argv)
    try:
        start = date.fromisoformat(args.protocol_start) if args.protocol_start else None
        locations = parse_locations(args.locations)
        base = os.path.realpath(os.getcwd())
        forecasts = safe_path(args.csv, base, must_exist_file=True, suffix='.csv')
        ratings_path = safe_path(args.ratings, base, must_exist_file=True, suffix='.csv') if args.ratings else None
        out_dir = safe_path(args.out, base)
        out_md = safe_path(os.path.join(out_dir, 'bl.md'), base)
        out_csv = safe_path(os.path.join(out_dir, 'bl_slots.csv'), base)
        rows = premise.load_rows(forecasts)
        ratings = load_ratings(ratings_path, base) if ratings_path else None
    except ValueError as e:
        print('error: %s' % e, file=sys.stderr)
        return 2
    a = analyse(rows, ratings, start, args.draws, locations)
    units, g = a['units'], a['gate']
    os.makedirs(out_dir, exist_ok=True)
    report = render(units, g, a['by_slot'], start, args.draws, a['protocol'], locations)
    with open(out_md, 'w', encoding='utf-8') as f:
        f.write(report)
    with open(out_csv, 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(SLOT_HEADER)
        w.writerows(slot_rows(units, a['by_slot'], start))
    print('31 Oct Angel of the North sunset (excluded from every count):')
    for ln in case_31_oct_lines(units)[:1]:
        print('  ' + ln)
    print()
    print('\n'.join(decision_block(g)))
    print()
    print('wrote %s and %s' % (out_md, out_csv))
    return 0


if __name__ == '__main__':
    sys.exit(main())
