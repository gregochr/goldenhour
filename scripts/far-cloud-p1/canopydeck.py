#!/usr/bin/env python3
"""Phantom deck under a forecast canopy, offline sizing for far-cloud-p1-plan.md section 19. No network, no spend.

Usage: python3 canopydeck.py p1_forecasts.csv --out out/ [--input-tokens N] [--output-tokens N]

Sizes one candidate rule, "do not stand down on low cloud alone when the forecast also shows a mid/high canopy
>= 60", on stored data. It prints four counts and recommends nothing:

 1. how many observer phantom-deck slots (forecast observer low >= 60, ERA5 < 20: lowdeck.py's cell) had a FORECAST
    canopy (forecast observer mid >= 60 or high >= 60);
 2. what the latest run did on those: triaged on low cloud / rated <= 3 / rated >= 4 (and every other outcome, so
    the categories sum to the count);
 3. the exposure: among ALL slots with forecast low >= 60 and a forecast canopy (phantom or not), how many had
    ERA5 low >= 60 (a genuinely obstructed sky), with an ERA5 canopy present or not;
 4. the cost: extra Claude evaluations per day the rule would have caused, by model era, priced at the
    application-example.yml rates with an ASSUMED token count per call.

The rule keys on FORECAST inputs only. ERA5 is used here to describe what the slots turned out to be, never to
select them, because the forecast cannot see ERA5 at forecast time. ERA5 is a reanalysis, not an observation, and
nothing here says whether any sky was good.

Populations: the same as lowdeck.py (latest eligible run per slot, sky locations, T+0/T+1) with a
cloud_verification row for counts 1 to 3. Count 4 needs no ERA5, so it takes every sky T+0/T+1 slot.
"""
from __future__ import annotations

import argparse
import os
import sys
from collections import defaultdict
from datetime import date, timedelta
from typing import Callable, Dict, List, Optional, Sequence, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lowdeck  # noqa: E402
import premise  # noqa: E402
import replay_set  # noqa: E402
import stats  # noqa: E402

FORECAST_CANOPY_MIN = 60    # forecast observer mid or high at or above this
FORECAST_DECK_MIN = lowdeck.DECK_MIN
OBS = lowdeck.VARIABLES[0][1:]          # ('low_cloud', 'cv_observer_low_cloud')
BATCH_DISCOUNT = 0.5                    # Message Batches bill at half the standard per-token price
ERA5_BANDS = ('ERA5 < %d (phantom)' % lowdeck.CLEAR_MAX,
              'ERA5 %d-%d' % (lowdeck.CLEAR_MAX, lowdeck.DECK_MIN - 1),
              'ERA5 >= %d (obstructed)' % lowdeck.DECK_MIN)
CANOPY_STATES = ('ERA5 canopy overhead', 'no ERA5 canopy', 'ERA5 canopy unknown')


# ------------------------------------------------------------------ definitions

def forecast_canopy(r: Dict[str, object]) -> Optional[bool]:
    """Forecast observer mid or high >= 60. True on any known value at or above; False when both known below;
    None otherwise (a NULL input with nothing known to be >= 60)."""
    mid, high = r['mid_cloud'], r['high_cloud']
    if (mid is not None and mid >= FORECAST_CANOPY_MIN) or (high is not None and high >= FORECAST_CANOPY_MIN):
        return True
    if mid is None or high is None:
        return None
    return False


def forecast_deck(r: Dict[str, object]) -> bool:
    """Forecast observer low cloud >= 60 (a NULL is not a deck)."""
    return r['low_cloud'] is not None and r['low_cloud'] >= FORECAST_DECK_MIN


def era5_band(r: Dict[str, object]) -> Optional[int]:
    """0 phantom (< 20), 1 middle, 2 obstructed (>= 60) by ERA5 observer low; None when unknown or unverified."""
    if r['cv_id'] is None or r['cv_observer_low_cloud'] is None:
        return None
    v = r['cv_observer_low_cloud']
    return 0 if v < lowdeck.CLEAR_MAX else (2 if v >= lowdeck.DECK_MIN else 1)


def era5_canopy_state(r: Dict[str, object]) -> int:
    """Index into CANOPY_STATES."""
    c = lowdeck.era5_canopy(r)
    return 0 if c is True else (1 if c is False else 2)


def released_by_rule(r: Dict[str, object]) -> bool:
    """A slot the rule would not stand down: its latest run was triaged on low cloud and the forecast shows a
    canopy. (The rule changes rule 1 of the weather triage only.)"""
    return lowdeck.low_cloud_triage(r) and forecast_canopy(r) is True


# ------------------------------------------------------------------ cost

def calendar_days(slots: Sequence[dict]) -> int:
    """Calendar days from the first to the last slot date, inclusive; 0 for no slots."""
    ds = sorted({s['date'] for s in slots})
    if not ds:
        return 0
    return (date.fromisoformat(ds[-1]) - date.fromisoformat(ds[0])).days + 1


def call_cost_usd(model: str, in_tok: int, out_tok: int, discount: float = 1.0) -> Optional[float]:
    """USD for one call at the application-example.yml rates in replay_set.PRICES; None for an unpriced model."""
    prices = replay_set.PRICES.get(model)
    if prices is None:
        return None
    return discount * (in_tok * prices[0] + out_tok * prices[1]) / 1_000_000


def eligible_runs(rows: Sequence[dict]) -> List[dict]:
    """Every stored run row the pipeline could have acted on: sky location, T+0/T+1, a run before the event, in an
    eligible batch state (premise.build_slots' rule, applied to every run rather than the latest)."""
    out = []
    for r in rows:
        if r['solar_event_time'] is None or r['forecast_run_at'] is None or r['forecast_run_at'] >= r['solar_event_time']:
            continue
        if r['batch_state'] is not None and r['batch_state'] != 'SCORED':
            continue
        if r['days_ahead'] not in lowdeck.PRIMARY_LEADS or not premise.is_sky_location(r['loc_types']):
            continue
        out.append(r)
    return out


def era_rows(slots: Sequence[dict], runs: Sequence[dict], in_tok: int, out_tok: int) -> List[List[object]]:
    """One row per evaluation-model era plus a total: released slots and runs, calendar days, per-day counts and
    the per-day cost at the era's own price and at the Sonnet 5.5 price (list and batch)."""
    by_model: Dict[str, List[dict]] = defaultdict(list)
    for s in slots:
        by_model[s['model']].append(s)
    runs_by_model: Dict[str, int] = defaultdict(int)
    for r in runs:
        runs_by_model[r['evaluation_model'] or 'unknown'] += 1

    def usd(x: Optional[float]) -> str:
        return '-' if x is None else '$%.2f' % x

    rows: List[List[object]] = []
    total_slots = total_runs = 0
    cost_own = cost_55 = 0.0
    unpriced = []
    for m in sorted(by_model):
        sl = by_model[m]
        days = calendar_days(sl)
        n_slots = sum(1 for s in sl if released_by_rule(s['row']))
        n_runs = runs_by_model.get(m, 0)
        c = call_cost_usd(m, in_tok, out_tok)
        c55 = call_cost_usd('SONNET_55', in_tok, out_tok)
        total_slots += n_slots
        total_runs += n_runs
        if c is None:
            if n_slots:
                unpriced.append(m)
        else:
            cost_own += n_slots * c
        cost_55 += n_slots * c55
        dates = sorted({s['date'] for s in sl})
        rows.append([m, '%s .. %s' % (dates[0], dates[-1]), days, n_slots, '%.2f' % (n_slots / days),
                     n_runs, '%.2f' % (n_runs / days),
                     usd(None if c is None else n_slots * c / days),
                     usd(None if c is None else n_slots * c * BATCH_DISCOUNT / days),
                     usd(n_slots * c55 / days), usd(n_runs * c55 / days)])
    days_all = calendar_days(slots)
    if days_all:
        dates = sorted({s['date'] for s in slots})
        rows.append(['all (priced at each slot\'s own era%s)' % (', unpriced: ' + ','.join(unpriced) if unpriced else ''),
                     '%s .. %s' % (dates[0], dates[-1]), days_all, total_slots, '%.2f' % (total_slots / days_all),
                     total_runs, '%.2f' % (total_runs / days_all), usd(cost_own / days_all),
                     usd(cost_own * BATCH_DISCOUNT / days_all), usd(cost_55 / days_all),
                     usd(total_runs * call_cost_usd('SONNET_55', in_tok, out_tok) / days_all)])
    return rows


# ------------------------------------------------------------------ report

def exposure_table(units: Sequence[dict]) -> List[str]:
    """Rows: ERA5 observer low band; columns: ERA5 canopy state. Units must have a known ERA5 low band."""
    grid: Dict[Tuple[int, int], int] = defaultdict(int)
    for u in units:
        grid[(era5_band(u['row']), era5_canopy_state(u['row']))] += 1
    rows = [[ERA5_BANDS[b], sum(grid[(b, c)] for c in range(3))] + [grid[(b, c)] for c in range(3)]
            for b in range(3)]
    return premise.md_table(['ERA5 observer low', 'slots'] + list(CANOPY_STATES), rows)


def render(rows: Sequence[dict], in_tok: int = replay_set.DEFAULT_INPUT_TOKENS,
           out_tok: int = replay_set.DEFAULT_OUTPUT_TOKENS) -> str:
    p = lowdeck.population(rows)
    prim = p['primary']
    sky_leads = [s for s in p['sky'] if s['lead'] in lowdeck.PRIMARY_LEADS]
    L: List[str] = []
    L.append('# Phantom deck under a forecast canopy (far-cloud-p1-plan.md section 19)')
    L.append('')
    L.append('**Sizing, not a decision.** Counts and shares on stored forecast rows; it recommends nothing. No network, '
             'no spend. ERA5 is a reanalysis, not an observation: a ~31 km model field that can disagree with the '
             'forecast in either direction, and it is used below only to DESCRIBE slots, never to select them. '
             'Wilson intervals (W) treat slots as independent; they are not, so B is a 95%% date-block bootstrap interval '
             '(5-day moving blocks of calendar days, seed %d). Cells read "k (p%%) W[lo, hi] B[lo, hi]".'
             % stats.DEFAULT_SEED)
    L.append('')
    L.append('## Definitions')
    L.append('')
    L.append('- **The candidate rule:** do not stand down on low cloud alone when the forecast also shows a mid/high '
             'canopy >= %d. It changes the weather triage\'s rule 1 (HIGH_CLOUD, "sun blocked") only, and it keys on '
             'forecast inputs, never ERA5.' % FORECAST_CANOPY_MIN)
    L.append('- **Slot, population:** as lowdeck.py: the latest eligible run per (location, date, event) before the event '
             '(premise.build_slots), sky locations, T+0/T+1. Counts 1 to 3 use slots with a `cloud_verification` row; '
             'count 4 uses every sky T+0/T+1 slot (it needs no ERA5).')
    L.append('- **Forecast deck:** forecast observer `low_cloud` >= %d. **Forecast canopy:** forecast observer '
             '`mid_cloud` >= %d or `high_cloud` >= %d (true on any known value at or above; unknown when a value is '
             'NULL and none known is >= %d).' % (FORECAST_DECK_MIN, FORECAST_CANOPY_MIN, FORECAST_CANOPY_MIN,
                                                  FORECAST_CANOPY_MIN))
    L.append('- **Observer phantom deck:** forecast `low_cloud` >= %d and ERA5 `cv_observer_low_cloud` < %d '
             '(lowdeck.py\'s cell). **ERA5 canopy overhead:** `cv_observer_mid_cloud` >= %d or `cv_observer_high_cloud` '
             '>= %d. **Obstructed:** ERA5 observer low >= %d.'
             % (lowdeck.DECK_MIN, lowdeck.CLEAR_MAX, lowdeck.CANOPY_MIN, lowdeck.CANOPY_MIN, lowdeck.DECK_MIN))
    L.append('- **Outcome of a slot** (its latest run): *triaged on low cloud* (`triage_reason` HIGH_CLOUD with a message '
             'naming low cloud), *triaged, HIGH_CLOUD without a low-cloud message*, *triaged, other reason*, *rated 1-2 / '
             '3 / 4-5* (`rating`, the combined star), *unrated, not triaged*. They sum to the slot count.')
    L.append('- **Released by the rule (count 4):** a slot whose latest run was *triaged on low cloud* and shows a '
             'forecast canopy. **Runs** are every stored run row of a sky T+0/T+1 slot (eligible state, before the '
             'event) that is triaged on low cloud and shows a forecast canopy.')
    L.append('- **Cost assumption (ASSUMED, not measured):** %d input and %d output tokens per call (the replay '
             'harness\'s default: the user message plus the uncached share of the system prompt; thinking tokens and '
             'prompt caching are not modelled). Rates are USD per million tokens from application-example.yml: %s. '
             '"Batch" applies the Message Batches 50%% discount.'
             % (in_tok, out_tok, ', '.join('%s %.2f in / %.2f out' % (m, a, b)
                                           for m, (a, b) in replay_set.PRICES.items())))
    L.append('')
    L.append('## Population')
    L.append('')
    dates = sorted({s['date'] for s in prim})
    L.append('- Rows %d; slots with an eligible run %d; sky %d; with ERA5 %d; T+0/T+1 with ERA5 %d '
             '(%s to %s, %d dates); sky T+0/T+1 slots without the ERA5 requirement %d.'
             % (len(rows), len(p['slots']), len(p['sky']), len(p['verified']), len(prim),
                dates[0] if dates else '-', dates[-1] if dates else '-', len(dates), len(sky_leads)))
    L.append('')

    # ---- 1
    ph = lowdeck.with_cell(prim, *OBS, 'phantom')
    ph_yes = [u for u in ph if forecast_canopy(u['row']) is True]
    ph_no = [u for u in ph if forecast_canopy(u['row']) is False]
    ph_unk = [u for u in ph if forecast_canopy(u['row']) is None]
    ph_known = ph_yes + ph_no
    L.append('## 1. Observer phantom-deck slots with a forecast canopy')
    L.append('')
    L.append('Observer phantom-deck slots: **%d**. Forecast canopy present **%d**, absent %d, unknown %d.'
             % (len(ph), len(ph_yes), len(ph_no), len(ph_unk)))
    L.append('')
    L.append('Share with a forecast canopy, of the %d with a known forecast canopy: %s'
             % (len(ph_known), lowdeck.bshare(ph_known, lambda u: forecast_canopy(u['row']) is True)))
    L.append('')
    L.append('By evaluation model:')
    L.append('')
    models = sorted({u['model'] for u in ph})
    L += premise.md_table(['model', 'phantom decks', 'forecast canopy', 'no forecast canopy', 'unknown'],
                          [[m] + [sum(1 for u in ph if u['model'] == m and f(u))
                                  for f in (lambda u: True, lambda u: forecast_canopy(u['row']) is True,
                                            lambda u: forecast_canopy(u['row']) is False,
                                            lambda u: forecast_canopy(u['row']) is None)] for m in models])

    # ---- 2
    L.append('## 2. What the latest run did on those slots')
    L.append('')
    cnt = lowdeck.outcome_counts(ph_yes)
    rated_le3 = cnt['rated 1-2'] + cnt['rated 3']
    L.append('Phantom decks with a forecast canopy: **%d**. Triaged on low cloud **%d**; rated <= 3 **%d** '
             '(1-2: %d, 3: %d); rated >= 4 **%d**; every other outcome: %s.'
             % (len(ph_yes), cnt['triaged on low cloud'], rated_le3, cnt['rated 1-2'], cnt['rated 3'],
                cnt['rated 4-5'],
                ', '.join('%s %d' % (o, cnt[o]) for o in lowdeck.OUTCOMES
                          if o not in ('triaged on low cloud', 'rated 1-2', 'rated 3', 'rated 4-5'))))
    L.append('')
    L += lowdeck.outcome_table([('phantom deck, forecast canopy', ph_yes), ('phantom deck, no forecast canopy', ph_no),
                                ('phantom deck, forecast canopy unknown', ph_unk), ('all phantom decks', ph)])
    L.append(lowdeck.outcome_sentence('Phantom decks with a forecast canopy', ph_yes))
    L.append('')
    L.append('By evaluation model (phantom decks with a forecast canopy):')
    L.append('')
    L += lowdeck.outcome_table([(m, [u for u in ph_yes if u['model'] == m])
                                for m in sorted({u['model'] for u in ph_yes})])

    # ---- 3
    exp = [u for u in prim if forecast_deck(u['row']) and forecast_canopy(u['row']) is True
           and era5_band(u['row']) is not None]
    nocan = [u for u in prim if forecast_deck(u['row']) and forecast_canopy(u['row']) is False
             and era5_band(u['row']) is not None]
    unk_can = [u for u in prim if forecast_deck(u['row']) and forecast_canopy(u['row']) is None
               and era5_band(u['row']) is not None]
    obstructed = [u for u in exp if era5_band(u['row']) == 2]
    L.append('## 3. The exposure: forecast deck and forecast canopy, whatever ERA5 reads')
    L.append('')
    L.append('Every slot with forecast low >= %d and a forecast canopy, phantom or not (ERA5 observer low known): '
             '**%d**. ERA5 observer low >= %d (a genuinely obstructed sky the rule would send to Claude): **%d**, %s.'
             % (FORECAST_DECK_MIN, len(exp), lowdeck.DECK_MIN, len(obstructed),
                lowdeck.brate(exp, lambda u: era5_band(u['row']) == 2)))
    L.append('')
    L += exposure_table(exp)
    L.append('For comparison, forecast decks WITHOUT a forecast canopy (%d with a known absence, %d with an unknown):'
             % (len(nocan), len(unk_can)))
    L.append('')
    L += premise.md_table(['group', 'slots'] + list(ERA5_BANDS),
                          [[lab, len(us)] + [lowdeck.bshare(us, lambda u, b=b: era5_band(u['row']) == b)
                                             for b in range(3)]
                           for lab, us in (('forecast deck, forecast canopy', exp),
                                           ('forecast deck, no forecast canopy', nocan),
                                           ('forecast deck, canopy unknown', unk_can))])
    rel = [u for u in prim if released_by_rule(u['row']) and era5_band(u['row']) is not None]
    L.append('### Of those, the slots the rule would actually release')
    L.append('')
    L.append('Latest run triaged on low cloud and a forecast canopy present (ERA5 observer low known): **%d**. '
             'ERA5 observer low >= %d for **%d**, %s; < %d for **%d**, %s.'
             % (len(rel), lowdeck.DECK_MIN, sum(1 for u in rel if era5_band(u['row']) == 2),
                lowdeck.brate(rel, lambda u: era5_band(u['row']) == 2), lowdeck.CLEAR_MAX,
                sum(1 for u in rel if era5_band(u['row']) == 0), lowdeck.brate(rel, lambda u: era5_band(u['row']) == 0)))
    L.append('')
    L += exposure_table(rel)

    # ---- 4
    runs = [r for r in eligible_runs(rows) if released_by_rule(r)]
    L.append('## 4. The cost: extra Claude evaluations per day')
    L.append('')
    L.append('Sky T+0/T+1 slots whose latest run was triaged on low cloud with a forecast canopy ("released"), and '
             'every stored run of such a slot that was (runs). Slots are a lower bound (each released slot evaluated '
             'once); runs are an upper bound (every triaged run re-evaluated, none skipped by the freshness cache). '
             'Per-day figures divide by the calendar days spanned by the era\'s slots. The rule stops at rule 1 '
             'today: a released slot could still fail the precipitation or visibility rule, which is not '
             'evaluated once rule 1 has fired, so both counts are upper bounds on that account.')
    L.append('')
    L += premise.md_table(['era (model)', 'first .. last date', 'days', 'released slots', 'slots / day',
                           'released runs', 'runs / day', 'USD / day, era price (slots)',
                           'USD / day, era price, batch', 'USD / day at Sonnet 5.5 (slots)',
                           'USD / day at Sonnet 5.5 (runs)'],
                          era_rows(sky_leads, runs, in_tok, out_tok))
    return '\n'.join(L) + '\n'


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('csv')
    ap.add_argument('--out', default='out')
    ap.add_argument('--input-tokens', type=int, default=replay_set.DEFAULT_INPUT_TOKENS)
    ap.add_argument('--output-tokens', type=int, default=replay_set.DEFAULT_OUTPUT_TOKENS)
    args = ap.parse_args(argv)
    try:
        rows = premise.load_rows(args.csv)
    except ValueError as e:
        print('error: %s' % e, file=sys.stderr)
        return 2
    report = render(rows, args.input_tokens, args.output_tokens)
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, 'canopydeck.md'), 'w', encoding='utf-8') as f:
        f.write(report)
    print('wrote %s' % os.path.join(args.out, 'canopydeck.md'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
