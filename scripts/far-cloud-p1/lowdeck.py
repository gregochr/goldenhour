#!/usr/bin/env python3
"""Over-forecast low deck ("phantom deck") measurement for far-cloud-p1-plan.md section 18.
No network, no spend.

Usage: python3 lowdeck.py p1_forecasts.csv --out out/

Measures, on the whole verified history, how often the forecast puts a low-cloud deck where ERA5 reads
clear, what the rating did on those slots, and whether a real mid/high canopy overhead co-occurred.
This is measurement, not a decision: it prints counts, rates and intervals and recommends nothing.

ERA5 is a reanalysis, not an observation. It is a model reconstruction on a ~31 km grid; it cannot see
a river corridor, and where it and the forecast model share a bias the comparison flatters the forecast.
A disagreement means "the forecast differs from a better-informed model", not "the forecast was wrong".
Nothing here says whether any sky was good.

The Wilson intervals treat slots as independent. They are not: many locations share one weather situation on a
date, and the model is a date range, so the intervals understate the real uncertainty (premise.py resamples
dates for that reason; this tool does not). Read them as a floor on the width.

Population: premise.build_slots (latest eligible run per (location, date, event)), sky locations, with a
cloud_verification row, SUNRISE/SUNSET, T+0/T+1. Every engine is kept (batch, sync, triage): a phantom deck
that triage acted on is exactly the case of interest. T+2+ appears only in the by-days_ahead split.
"""
from __future__ import annotations

import argparse
import csv
import os
import sys
from collections import defaultdict
from typing import Callable, Dict, List, Optional, Sequence, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import premise  # noqa: E402
import stats  # noqa: E402

DECK_MIN = 60        # a deck: low cloud at or above this
CLEAR_MAX = 20       # clear: low cloud below this
CANOPY_MIN = 60      # ERA5 canopy overhead: observer mid or high at or above this
WORST_N = 20
PRIMARY_LEADS = (0, 1)

# (label, forecast column, ERA5 column)
VARIABLES = (('observer', 'low_cloud', 'cv_observer_low_cloud'),
             ('cone', 'solar_low_cloud', 'cv_horizon_low_cloud'))

OUTCOMES = ('triaged on low cloud', 'triaged, other reason', 'rated 1-2', 'rated 3', 'rated 4-5',
            'unrated, not triaged')


# ------------------------------------------------------------------ definitions

def band(v: float) -> str:
    return 'low' if v < CLEAR_MAX else ('high' if v >= DECK_MIN else 'mid')


def pair(r: Dict[str, object], fc: str, cv: str) -> Optional[Tuple[float, float]]:
    """(forecast, ERA5) low cloud, or None when either is NULL or there is no cloud_verification row."""
    if r['cv_id'] is None or r[fc] is None or r[cv] is None:
        return None
    return r[fc], r[cv]


def cell(r: Dict[str, object], fc: str, cv: str) -> Optional[str]:
    """phantom / missed / both_high / both_low / middle, or None when not testable."""
    p = pair(r, fc, cv)
    if p is None:
        return None
    f, e = band(p[0]), band(p[1])
    if f == 'high' and e == 'low':
        return 'phantom'
    if f == 'low' and e == 'high':
        return 'missed'
    if f == 'high' and e == 'high':
        return 'both_high'
    if f == 'low' and e == 'low':
        return 'both_low'
    return 'middle'


def era5_canopy(r: Dict[str, object]) -> Optional[bool]:
    """ERA5 observer mid or high >= 60. True on any known value at or above; False when both known below;
    None otherwise."""
    mid, high = r['cv_observer_mid_cloud'], r['cv_observer_high_cloud']
    if (mid is not None and mid >= CANOPY_MIN) or (high is not None and high >= CANOPY_MIN):
        return True
    if mid is None or high is None:
        return None
    return False


def low_cloud_triage(r: Dict[str, object]) -> bool:
    """Triaged on low cloud: reason HIGH_CLOUD (the weather triage's 'sun blocked' rule, whose message names low
    cloud). A HIGH_CLOUD row whose message exists and does not name low cloud counts as another reason."""
    if r['triage_reason'] != 'HIGH_CLOUD':
        return False
    msg = r['triage_message']
    return msg is None or 'low cloud' in msg.lower()


def outcome(r: Dict[str, object]) -> str:
    if r['triage_reason']:
        return 'triaged on low cloud' if low_cloud_triage(r) else 'triaged, other reason'
    rt = r['rating']
    if rt is None:
        return 'unrated, not triaged'
    return 'rated 1-2' if rt <= 2 else ('rated 3' if rt == 3 else 'rated 4-5')


def poor_or_blocked(r: Dict[str, object]) -> bool:
    """Triaged on low cloud, or rated 3 or lower."""
    return outcome(r) in ('triaged on low cloud', 'rated 1-2', 'rated 3')


# ------------------------------------------------------------------ population and splits

def population(rows: Sequence[dict]) -> Dict[str, object]:
    slots, none_eligible = premise.build_slots(rows)
    sky = [s for s in slots if s['sky']]
    verified = [s for s in sky if s['row']['cv_id'] is not None]
    primary = [s for s in verified if s['lead'] in PRIMARY_LEADS]
    return {'slots': slots, 'none_eligible': none_eligible, 'sky': sky, 'verified': verified, 'primary': primary}


def region_of(s: dict) -> str:
    return s['row']['region_name'] or 'no region'


def coastal_of(s: dict) -> str:
    c = s['row']['loc_is_coastal_tidal']
    return 'unknown' if c is None else ('coastal (tidal)' if c else 'inland')


def lead_of(s: dict) -> str:
    return 'unknown' if s['lead'] is None else 'T+%d' % s['lead']


DIMENSIONS: Tuple[Tuple[str, Callable[[dict], str], str], ...] = (
    ('evaluation model', lambda s: s['model'], 'n'),
    ('event', lambda s: s['event'], 'key'),
    ('month', lambda s: s['date'][:7], 'key'),
    ('days_ahead', lead_of, 'key'),
    ('region', region_of, 'n'),
    ('coastal / inland', coastal_of, 'key'),
)


def share(k: int, n: int) -> str:
    """'k (p%) [Wilson lo, hi]'."""
    w = stats.wilson(k, n)
    if w is None:
        return '-'
    return '%d (%s) %s' % (k, premise.pct(w[0]), premise.ci_txt((w[1], w[2])))


def rate(k: int, n: int) -> str:
    """'p% [Wilson lo, hi]' (the count sits in its own column)."""
    w = stats.wilson(k, n)
    return '-' if w is None else '%s %s' % (premise.pct(w[0]), premise.ci_txt((w[1], w[2])))


def cells_of(units: Sequence[dict], fc: str, cv: str) -> Dict[str, int]:
    out: Dict[str, int] = defaultdict(int)
    for u in units:
        c = cell(u['row'], fc, cv)
        if c is not None:
            out[c] += 1
            out['n'] += 1
            if u['row'][fc] >= DECK_MIN:
                out['fc_deck'] += 1
    return out


def errors(units: Sequence[dict], fc: str, cv: str) -> Tuple[Optional[float], Optional[float]]:
    d = [p[0] - p[1] for p in (pair(u['row'], fc, cv) for u in units) if p is not None]
    if not d:
        return None, None
    return sum(d) / len(d), sum(abs(x) for x in d) / len(d)


def fmt_err(x: Optional[float]) -> str:
    return '-' if x is None else '%+.1f' % x


def split_row(label: str, units: Sequence[dict], fc: str, cv: str) -> List[object]:
    c = cells_of(units, fc, cv)
    n = c['n']
    decks = c['fc_deck']
    me, mae = errors(units, fc, cv)
    return [label, n, share(c['phantom'], n), decks, share(c['phantom'], decks), share(c['missed'], n),
            fmt_err(me), '-' if mae is None else '%.1f' % mae]


SPLIT_HEADERS = ['group', 'n', 'phantom deck', 'forecast decks', 'phantom, of forecast decks', 'missed deck',
                 'mean error (pp)', 'MAE (pp)']


def split_table(units: Sequence[dict], fc: str, cv: str, key: Callable[[dict], str], order: str) -> List[str]:
    groups: Dict[str, List[dict]] = defaultdict(list)
    for u in units:
        groups[key(u)].append(u)
    names = sorted(groups)
    if order == 'n':
        names.sort(key=lambda g: (-len(groups[g]), g))
    return premise.md_table(SPLIT_HEADERS, [split_row(g, groups[g], fc, cv) for g in names])


def contingency(units: Sequence[dict], fc: str, cv: str) -> List[str]:
    c = cells_of(units, fc, cv)
    n = c['n']
    named = (('phantom deck: forecast >= %d, ERA5 < %d' % (DECK_MIN, CLEAR_MAX), 'phantom'),
             ('missed deck: forecast < %d, ERA5 >= %d' % (CLEAR_MAX, DECK_MIN), 'missed'),
             ('both >= %d' % DECK_MIN, 'both_high'), ('both < %d' % CLEAR_MAX, 'both_low'),
             ('middle band: any other combination', 'middle'))
    out = premise.md_table(['cell', 'slots', 'share of testable'], [[lab, c[k], rate(c[k], n)] for lab, k in named])
    grid: Dict[Tuple[str, str], int] = defaultdict(int)
    for u in units:
        p = pair(u['row'], fc, cv)
        if p is not None:
            grid[(band(p[0]), band(p[1]))] += 1
    names = (('low', '< %d' % CLEAR_MAX), ('mid', '%d-%d' % (CLEAR_MAX, DECK_MIN - 1)), ('high', '>= %d' % DECK_MIN))
    rows = [['forecast %s' % lab] + [grid[(fb, eb)] for eb, _ in names] for fb, lab in names]
    return out + premise.md_table(['forecast \\ ERA5'] + ['ERA5 %s' % lab for _, lab in names], rows)


# ------------------------------------------------------------------ ratings and canopy

def outcome_table(groups: Sequence[Tuple[str, Sequence[dict]]]) -> List[str]:
    heads = ['group', 'slots'] + list(OUTCOMES) + ['triaged on low cloud or rated <= 3']
    rows = []
    for label, units in groups:
        n = len(units)
        cnt: Dict[str, int] = defaultdict(int)
        for u in units:
            cnt[outcome(u['row'])] += 1
        rows.append([label, n] + [share(cnt[o], n) for o in OUTCOMES]
                    + [share(sum(1 for u in units if poor_or_blocked(u['row'])), n)])
    return premise.md_table(heads, rows)


def with_cell(units: Sequence[dict], fc: str, cv: str, name: str) -> List[dict]:
    return [u for u in units if cell(u['row'], fc, cv) == name]


def canopy_split(units: Sequence[dict]) -> Tuple[List[dict], List[dict], List[dict]]:
    yes = [u for u in units if era5_canopy(u['row']) is True]
    no = [u for u in units if era5_canopy(u['row']) is False]
    unk = [u for u in units if era5_canopy(u['row']) is None]
    return yes, no, unk


def canopy_cooccurrence(groups: Sequence[Tuple[str, Sequence[dict]]]) -> List[str]:
    rows = []
    for label, units in groups:
        yes, no, unk = canopy_split(units)
        rows.append([label, len(units), share(len(yes), len(yes) + len(no)), len(yes), len(no), len(unk)])
    return premise.md_table(['group', 'slots', 'ERA5 canopy overhead, of known', 'canopy', 'no canopy', 'unknown'],
                            rows)


# ------------------------------------------------------------------ worst list

def worst(units: Sequence[dict], fc: str, cv: str, n: int = WORST_N) -> List[dict]:
    ph = with_cell(units, fc, cv, 'phantom')
    return sorted(ph, key=lambda u: (-(u['row'][fc] - u['row'][cv]), _neg(u['date']), u['row']['loc_name'] or '',
                                     u['event']))[:n]


def _neg(date: str) -> Tuple[int, ...]:
    """Sort key putting the most recent date first."""
    return tuple(-int(x) for x in date.split('-'))


def top_ties(units: Sequence[dict], fc: str, cv: str) -> Tuple[int, int]:
    """(largest gap, number of phantom slots sharing it)."""
    gaps = [u['row'][fc] - u['row'][cv] for u in with_cell(units, fc, cv, 'phantom')]
    if not gaps:
        return 0, 0
    top = max(gaps)
    return int(top), sum(1 for g in gaps if g == top)


def num(v: object) -> str:
    return '-' if v is None else '%g' % v


def slot_cells(u: dict) -> List[object]:
    r = u['row']
    return [r['loc_name'], r['region_name'] or '-', u['date'], u['event'], lead_of(u), u['model'],
            num(r['low_cloud']), num(r['cv_observer_low_cloud']), num(r['solar_low_cloud']),
            num(r['cv_horizon_low_cloud']), num(r['cv_observer_mid_cloud']), num(r['cv_observer_high_cloud']),
            num(r['rating']), r['triage_reason'] or '-']


SLOT_HEADERS = ['location', 'region', 'date', 'event', 'lead', 'model', 'fc obs low', 'ERA5 obs low', 'fc cone low',
                'ERA5 cone low', 'ERA5 obs mid', 'ERA5 obs high', 'rating', 'triage']


# ------------------------------------------------------------------ report

def render(rows: Sequence[dict]) -> Tuple[str, List[dict]]:
    p = population(rows)
    prim, ver = p['primary'], p['verified']
    L: List[str] = []
    L.append('# Over-forecast low deck (far-cloud-p1-plan.md section 18)')
    L.append('')
    L.append('**Measurement, not a decision.** Counts, rates and 95% Wilson intervals on stored forecast rows against '
             'ERA5. No network, no spend. ERA5 is a reanalysis, not an observation: a ~31 km model field that can '
             'disagree with the forecast model in either direction. Read a disagreement as "the forecast differs from '
             'a better-informed model", not "the forecast was wrong". Nothing here says whether a sky was good. The Wilson intervals treat slots as independent; they are not '
             '(many locations share one weather situation on a date), so the true uncertainty is wider.')
    L.append('')
    L.append('## Definitions')
    L.append('')
    L.append('- **Slot:** the latest eligible run per (location, date, event) before the event (premise.build_slots); '
             'sky locations; with a `cloud_verification` row; T+0/T+1 for every table except the by-days_ahead split, '
             'where T+2 and later are descriptive. Every engine is kept (batch, sync, triaged). Training cases (the Angel 13 Oct, Low Hauxley, Copt Hill) and '
             'disabled locations are not excluded.')
    L.append('- **Observer low cloud:** forecast `low_cloud` vs ERA5 `cv_observer_low_cloud`. **Cone low cloud:** '
             'forecast `solar_low_cloud` (113 km cone) vs ERA5 `cv_horizon_low_cloud`. A slot is testable for a '
             'variable only when both values are non-NULL.')
    L.append('- **Phantom deck:** forecast >= %d and ERA5 < %d. **Missed deck:** forecast < %d and ERA5 >= %d. '
             '**Both high:** both >= %d. **Both clear:** both < %d. **Middle band:** every other combination '
             '(either value in %d-%d). "Phantom, of forecast decks" divides by every testable slot whose forecast is >= %d, '
             'whatever ERA5 reads (phantom, both high, and forecast-high with ERA5 in the middle band).'
             % (DECK_MIN, CLEAR_MAX, CLEAR_MAX, DECK_MIN, DECK_MIN, CLEAR_MAX, CLEAR_MAX, DECK_MIN - 1, DECK_MIN))
    L.append('- **Error:** forecast minus ERA5, percentage points; MAE is the mean absolute error.')
    L.append('- **Outcome of a slot** (its latest run): *triaged on low cloud* (`triage_reason` HIGH_CLOUD, the weather '
             'triage "sun blocked" rule, whose message names low cloud; the rule fires on a cone reading above 80%, so a cone phantom '
             '(>= 60) can only be triaged on it above 80, and no slot with a cone reading below 20 can be), *triaged, other '
             'reason*, *rated 1-2 / 3 / 4-5* (`rating`, the combined star, not the sky-only star), *unrated, not '
             'triaged*.')
    L.append('- **ERA5 canopy overhead:** `cv_observer_mid_cloud` >= %d or `cv_observer_high_cloud` >= %d.'
             % (CANOPY_MIN, CANOPY_MIN))
    L.append('- **Coastal / inland:** `loc_is_coastal_tidal`. **Month:** the slot date. The evaluation models cover '
             'different date ranges (see the model by month table), so a model split is also a weather split.')
    L.append('')
    L.append('## Population')
    L.append('')
    by_engine: Dict[str, int] = defaultdict(int)
    for s in prim:
        by_engine[s['engine']] += 1
    L.append('- Rows %d; slots with an eligible run %d; sky %d; with ERA5 %d; T+0/T+1 %d (%s).'
             % (len(rows), len(p['slots']), len(p['sky']), len(ver), len(prim),
                ', '.join('%s %d' % (k, by_engine[k]) for k in sorted(by_engine))))
    dates = sorted({s['date'] for s in prim})
    L.append('- Dates %s to %s (%d). Testable: observer %d, cone %d.'
             % (dates[0] if dates else '-', dates[-1] if dates else '-', len(dates),
                cells_of(prim, *VARIABLES[0][1:])['n'], cells_of(prim, *VARIABLES[1][1:])['n']))
    L.append('')
    sec = 1
    L.append('## 1. Forecast against ERA5 low cloud, contingency')
    L.append('')
    for name, fc, cv in VARIABLES:
        L.append('### %s low cloud' % name.capitalize())
        L.append('')
        L += contingency(prim, fc, cv)
    L.append('## 2 and 3. Phantom-deck rate, signed error and MAE, by split')
    L.append('')
    L.append('"Phantom deck" and "missed deck" are shares of the group\'s testable slots, with Wilson intervals; '
             '"phantom, of forecast decks" is the share of forecast-deck slots (forecast >= 60) that ERA5 '
             'reads clear. Groups with small n carry wide intervals; every group is printed.')
    L.append('')
    for name, fc, cv in VARIABLES:
        L.append('### %s low cloud' % name.capitalize())
        L.append('')
        L += premise.md_table(SPLIT_HEADERS, [split_row('all T+0/T+1', prim, fc, cv)])
        for dim, key, order in DIMENSIONS:
            L.append('#### By %s%s' % (dim, ' (T+2 and later descriptive)' if dim == 'days_ahead' else ''))
            L.append('')
            L += split_table(ver if dim == 'days_ahead' else prim, fc, cv, key, order)
    L.append('#### Phantom-deck rate (observer) by evaluation model and month')
    L.append('')
    months = sorted({s['date'][:7] for s in prim})
    models = sorted({s['model'] for s in prim})
    fc, cv = VARIABLES[0][1:]
    mrows = []
    for m in models:
        row: List[object] = [m]
        for mo in months:
            c = cells_of([s for s in prim if s['model'] == m and s['date'][:7] == mo], fc, cv)
            row.append('-' if c['n'] == 0 else '%d/%d' % (c['phantom'], c['n']))
        mrows.append(row)
    L += premise.md_table(['model (phantom/testable)'] + months, mrows)

    L.append('## 4. What the rating did on phantom-deck slots')
    L.append('')
    L.append('Shares of each group\'s slots by outcome of the latest run. "Both clear" is the comparison group '
             '(same variable). A triaged slot carries no rating.')
    L.append('')
    for name, fc, cv in VARIABLES:
        ph, bc = with_cell(prim, fc, cv, 'phantom'), with_cell(prim, fc, cv, 'both_low')
        bh = with_cell(prim, fc, cv, 'both_high')
        L.append('### %s low cloud' % name.capitalize())
        L.append('')
        L += outcome_table([('phantom deck', ph), ('both clear', bc), ('both >= %d' % DECK_MIN, bh)])
        L.append('Phantom-deck slots by evaluation model:')
        L.append('')
        L += outcome_table([(m, [u for u in ph if u['model'] == m]) for m in sorted({u['model'] for u in ph})])

    L.append('## 5. Phantom deck and a real canopy overhead')
    L.append('')
    L.append('ERA5 canopy overhead is the 15 October shape: the forecast saw a deck, ERA5 saw a mid/high screen '
             'overhead and no deck, and the sky may light anyway (only the owner ratings of 15 October bear on that). Outcomes below are for phantom-deck slots split '
             'by whether ERA5 had a canopy overhead.')
    L.append('')
    for name, fc, cv in VARIABLES:
        ph, bc = with_cell(prim, fc, cv, 'phantom'), with_cell(prim, fc, cv, 'both_low')
        yes, no, unk = canopy_split(ph)
        byes, _bno, _bunk = canopy_split(bc)
        tst = [u for u in prim if cell(u['row'], fc, cv) is not None]
        L.append('### %s low cloud' % name.capitalize())
        L.append('')
        L.append('Co-occurrence of an ERA5 canopy overhead:')
        L.append('')
        L += canopy_cooccurrence([('phantom deck', ph), ('both clear', bc), ('all testable', tst)])
        L.append('Outcome of the latest run:')
        L.append('')
        L += outcome_table([('phantom deck, ERA5 canopy overhead', yes), ('phantom deck, no ERA5 canopy', no),
                            ('phantom deck, canopy unknown', unk), ('both clear, ERA5 canopy overhead', byes)])
        L.append('Phantom-deck slots with an ERA5 canopy overhead: **%d**, of which triaged on low cloud **%d**, '
                 'rated 3 or lower **%d**, either **%d**, rated 4-5 **%d**.'
                 % (len(yes), sum(1 for u in yes if outcome(u['row']) == 'triaged on low cloud'),
                    sum(1 for u in yes if outcome(u['row']) in ('rated 1-2', 'rated 3')),
                    sum(1 for u in yes if poor_or_blocked(u['row'])),
                    sum(1 for u in yes if outcome(u['row']) == 'rated 4-5')))
        L.append('')
        yes_m = sorted({u['model'] for u in yes})
        if yes_m:
            L.append('By evaluation model (phantom deck with ERA5 canopy overhead):')
            L.append('')
            L += outcome_table([(m, [u for u in yes if u['model'] == m]) for m in yes_m])

    L.append('## 6. The %d largest phantom decks' % WORST_N)
    L.append('')
    L.append('Largest forecast-minus-ERA5 gap among phantom-deck slots (T+0/T+1). Ties are broken by most recent date, then name, so "largest" is a list of examples when many slots share the top gap (counts below); `lowdeck_phantom.csv` holds them all.')
    L.append('')
    for name, fc, cv in VARIABLES:
        L.append('### %s low cloud' % name.capitalize())
        L.append('')
        top, ties = top_ties(prim, fc, cv)
        if ties:
            L.append('%d phantom-deck slots share the largest gap (%d pp).' % (ties, top))
            L.append('')
        L += premise.md_table(SLOT_HEADERS, [slot_cells(u) for u in worst(prim, fc, cv)])
    phantom = [u for u in prim if any(cell(u['row'], fc, cv) == 'phantom' for _n, fc, cv in VARIABLES)]
    return '\n'.join(L) + '\n', phantom


CSV_HEADER = ['location', 'location_id', 'region', 'date', 'event', 'lead', 'model', 'engine', 'evaluation_id',
              'obs_phantom', 'cone_phantom', 'fc_obs_low', 'era5_obs_low', 'fc_cone_low', 'era5_cone_low',
              'era5_obs_mid', 'era5_obs_high', 'era5_canopy', 'rating', 'triage_reason', 'outcome']


def write_csv(slots: Sequence[dict], path: str) -> None:
    with open(path, 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(CSV_HEADER)
        for u in slots:
            r = u['row']
            w.writerow([r['loc_name'], r['location_id'], r['region_name'], u['date'], u['event'], lead_of(u),
                        u['model'], u['engine'], r['evaluation_id'],
                        int(cell(r, *VARIABLES[0][1:]) == 'phantom'), int(cell(r, *VARIABLES[1][1:]) == 'phantom'),
                        r['low_cloud'], r['cv_observer_low_cloud'], r['solar_low_cloud'], r['cv_horizon_low_cloud'],
                        r['cv_observer_mid_cloud'], r['cv_observer_high_cloud'], era5_canopy(r), r['rating'],
                        r['triage_reason'], outcome(r)])


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('csv')
    ap.add_argument('--out', default='out')
    args = ap.parse_args(argv)
    try:
        rows = premise.load_rows(args.csv)
    except ValueError as e:
        print('error: %s' % e, file=sys.stderr)
        return 2
    report, phantom = render(rows)
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, 'lowdeck.md'), 'w', encoding='utf-8') as f:
        f.write(report)
    write_csv(phantom, os.path.join(args.out, 'lowdeck_phantom.csv'))
    print('wrote %s' % os.path.join(args.out, 'lowdeck.md'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
