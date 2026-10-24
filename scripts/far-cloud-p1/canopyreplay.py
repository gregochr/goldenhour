#!/usr/bin/env python3
"""Replay of rated "forecast deck under a forecast canopy" analogues, for far-cloud-p1-plan.md section 20.

Usage:
  python3 canopyreplay.py select --prompts p1_prompts.csv --forecasts p1_forecasts.csv --out SET_DIR
  python3 canopyreplay.py report --manifest SET_DIR/manifest.csv --runs RUNS_CSV --forecasts p1_forecasts.csv --out out/

Why analogues. The slots the section 19 rule would release were stood down by weather triage before any prompt was
built, so none has a stored prompt and none can be replayed. The nearest stored thing is the set of slots that
carried the same FORECAST shape (forecast observer low >= 60 and a forecast canopy, mid or high >= 60) but whose
latest run was RATED, so Claude saw them and a prompt was stored. `select` writes those prompts in the replay
harness's layout (PromptReplayHarnessTest#replayStoredPrompts, README section 3) with a manifest; `report` joins the
harness's runs.csv to the manifest and to ERA5 and tabulates how the replayed rating relates to ERA5's reading.

What this can and cannot say. The set is selected on the forecast shape and on having been rated, so its forecast
cone low cloud is at most 80 (above that triage stands the slot down), while the slots the rule would release are above
80 by definition. A replay of the analogues is therefore evidence about how the replay model rates such skies when it
is shown them, not about what it would say for a released slot. ERA5 is a reanalysis, not an observation, and no
owner rating is joined. No verdict is printed.

Standard library only; no network and no Anthropic call here (the replay itself is the Maven harness).
"""
from __future__ import annotations

import argparse
import csv
import os
import statistics
import sys
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Sequence

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import canopydeck  # noqa: E402
import lowdeck  # noqa: E402
import premise  # noqa: E402
import replay_set  # noqa: E402
import stats  # noqa: E402

GROUP = 'canopy'
GO = 4
BANDS = canopydeck.ERA5_BANDS


# ------------------------------------------------------------------ select

def is_analogue(s: dict) -> bool:
    """A T+0/T+1 sky slot whose latest run was rated (not triaged) and whose forecast shows an observer deck and
    a canopy, both from FORECAST columns only."""
    r = s['row']
    return (s['lead'] in lowdeck.PRIMARY_LEADS and s['sky'] and not r['triage_reason'] and r['rating'] is not None
            and canopydeck.forecast_deck(r) and canopydeck.forecast_canopy(r) is True)


def select(rows: Sequence[dict], raw_forecasts: Sequence[dict], prompts: Sequence[dict]) -> List[dict]:
    """Analogue slots with a stored prompt, as replay_set-shaped dicts (forecast row + user_message, basis,
    basis_rating), ordered by evaluation id."""
    prompt_by_id = {p['evaluation_id']: p for p in prompts}
    raw_by_id = {r['evaluation_id']: r for r in raw_forecasts}
    slots, _ = premise.build_slots(rows)
    out = []
    for s in slots:
        if not is_analogue(s):
            continue
        eid = str(s['row']['evaluation_id'])
        raw, prompt = raw_by_id.get(eid), prompt_by_id.get(eid)
        if raw is None or prompt is None:
            continue
        basis, rating = replay_set.basis_of(raw)
        if rating is None:
            continue
        out.append(dict(raw, user_message=prompt['user_message'], basis=basis, basis_rating=rating))
    out.sort(key=lambda r: int(r['evaluation_id']))
    return out


def cmd_select(args) -> int:
    forecasts = replay_set.read_csv(args.forecasts)
    if forecasts and 'loc_tide_types' not in forecasts[0]:
        print('ERROR: --forecasts has no loc_tide_types column; re-export', file=sys.stderr)
        return 2
    rows = premise.load_rows(args.forecasts)
    chosen = select(rows, forecasts, replay_set.read_csv(args.prompts))
    out = Path(args.out)
    manifest = []
    kinds = {'inland': 0, 'coastal': 0}
    for r in chosen:
        m = replay_set.write_prompt(out, r, set(), GROUP, False, out)
        kinds['coastal' if m['coastal'] else 'inland'] += 1
        manifest.append(m)
    out.mkdir(parents=True, exist_ok=True)
    with open(out / 'manifest.csv', 'w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=replay_set.MANIFEST_FIELDS)
        w.writeheader()
        w.writerows(manifest)
    n = len(chosen)
    total, per_arm = replay_set.estimate_cost(n, args.runs, ['SONNET_55'], args.input_tokens, args.output_tokens)
    models = sorted({r.get('evaluation_model', '') for r in chosen})
    print(f'analogues with a stored prompt: {n} ({kinds["inland"]} inland, {kinds["coastal"]} coastal); '
          f'stored models: {", ".join(models)}')
    print(f'written to {out}; manifest: {out / "manifest.csv"}')
    print(f'COST ESTIMATE: {n} prompts x N={args.runs} on SONNET_55 = {n * args.runs} calls; assumed '
          f'{args.input_tokens} input + {args.output_tokens} output tokens per call (ASSUMED, thinking tokens and caching '
          f'not included), standard prices: ${total:,.2f} to ${total * replay_set.COST_HIGH_FACTOR:,.2f}; '
          f'Message Batches bill half.')
    return 0


# ------------------------------------------------------------------ report

def replay_medians(runs_path: str, model: str) -> Dict[str, Optional[float]]:
    """{label: median of the successful attempts for ``model`` with the production prompt}; a label whose every
    attempt failed maps to None."""
    got: Dict[str, List[int]] = defaultdict(list)
    for r in replay_set.read_csv(runs_path):
        if r.get('model') != model or (r.get('system_prompt') or 'production') != 'production':
            continue
        got[r['label']]  # noqa: B018  (touch: a label with only failures still appears)
        v = replay_set.to_int(r.get('rating'))
        if v is not None:
            got[r['label']].append(v)
    return {k: (statistics.median(v) if v else None) for k, v in got.items()}


def pct(k: int, n: int) -> str:
    w = stats.wilson(k, n)
    return '-' if w is None else '%d/%d (%s) %s' % (k, n, premise.pct(w[0]), premise.ci_txt((w[1], w[2])))


def table(headers, rows) -> List[str]:
    return premise.md_table(headers, rows)


def band_label(b: Optional[int]) -> str:
    return 'ERA5 unknown' if b is None else BANDS[b]


def render(manifest: Sequence[dict], replay: Dict[str, Optional[float]], typed_by_id: Dict[str, dict],
           model: str = 'SONNET_55') -> str:
    """Markdown report. ``typed_by_id`` maps evaluation_id to the premise-typed forecast row."""
    items = []
    for m in manifest:
        if m['group'] != GROUP or m['training'] == '1':
            continue
        r = typed_by_id.get(m['evaluation_id'])
        if r is None:
            continue
        items.append({'label': m['label'], 'row': r, 'basis': replay_set.to_int(m['basis_rating']),
                      'stored_model': m['model'], 'replay': replay.get(m['label']),
                      'date': r['target_date'], 'obs': canopydeck.era5_band(r), 'cone': canopydeck.cone_band(r)})
    scored = [i for i in items if i['replay'] is not None]
    L: List[str] = []
    L.append('# Replay of rated forecast-deck-under-canopy analogues (far-cloud-p1-plan.md section 20)')
    L.append('')
    L.append('**Measurement, not a decision.** No verdict is printed. The slots the section 19 rule would release were '
             'stood down before any prompt existed, so they cannot be replayed; this replays the slots with the same '
             'FORECAST shape (observer low >= %d, observer mid or high >= %d) whose latest run was rated, so a prompt was '
             'stored. Selection is on forecast columns and on having been rated, so their forecast cone low cloud is at '
             'most 80 (above 80 triage stands the slot down) while a released slot is above 80 by definition: this is '
             'evidence about how the replay model rates such skies when shown them, not about a released slot. ERA5 is a '
             'reanalysis, not an observation, and no owner rating is joined. Wilson intervals treat slots as '
             'independent; they are not (one weather day covers many locations).'
             % (canopydeck.FORECAST_DECK_MIN, canopydeck.FORECAST_CANOPY_MIN))
    L.append('')
    L.append('## Definitions')
    L.append('')
    L.append('- **Analogue:** the latest eligible run per (location, date, event), sky location, T+0/T+1, rated, not '
             'triaged, forecast observer `low_cloud` >= %d and forecast observer `mid_cloud` or `high_cloud` >= %d, with a '
             'stored prompt. **Stored rating:** the `sky_rating` where stored, else the combined `rating` (the replay '
             'returns Claude\'s sky rating). **Replayed rating:** the median of the successful attempts on %s with the '
             'production system prompt. A **go** is a rating >= %d.'
             % (canopydeck.FORECAST_DECK_MIN, canopydeck.FORECAST_CANOPY_MIN, model, GO))
    L.append('- **ERA5 band** (observer low, `cv_observer_low_cloud`; cone low, `cv_horizon_low_cloud`): %s; a slot with no '
             'ERA5 row is unknown. **Phantom analogue:** ERA5 observer low < %d.'
             % (', '.join(BANDS), lowdeck.CLEAR_MAX))
    L.append('')
    L.append('## Population')
    L.append('')
    stored_models = defaultdict(int)
    for i in items:
        stored_models[i['stored_model'] or 'unknown'] += 1
    dates = sorted({i['date'] for i in items})
    cones = [i['row']['solar_low_cloud'] for i in items if i['row']['solar_low_cloud'] is not None]
    L.append('- Analogues in the manifest %d (stored by %s); replayed on %s with a rating %d; dates %s to %s (%d); '
             'forecast cone low median %s, maximum %s.'
             % (len(items), ', '.join('%s %d' % (k, v) for k, v in sorted(stored_models.items())), model, len(scored),
                dates[0] if dates else '-', dates[-1] if dates else '-', len(dates),
                '-' if not cones else '%g' % statistics.median(cones), '-' if not cones else '%g' % max(cones)))
    L.append('- Not replayed (every attempt failed or absent): %d.' % (len(items) - len(scored)))
    L.append('')

    def go_table(title: str, key: str) -> None:
        L.append('## ' + title)
        L.append('')
        rows = []
        for b in (0, 1, 2, None):
            sel = [i for i in scored if i[key] == b]
            if not sel:
                continue
            stored_go = sum(1 for i in sel if i['basis'] is not None and i['basis'] >= GO)
            replay_go = sum(1 for i in sel if i['replay'] >= GO)
            rows.append([band_label(b), len(sel), pct(stored_go, len(sel)), pct(replay_go, len(sel)),
                         '%g' % statistics.median([i['replay'] for i in sel])])
        L.extend(table(['ERA5 band', 'analogues', 'stored go (>= 4)', 'replayed go (>= 4)', 'replayed median'], rows))

    go_table('1. Go rate by the ERA5 OBSERVER low band (the reading the phantom-deck definition uses)', 'obs')
    go_table('2. Go rate by the ERA5 CONE low band (the reading weather triage\'s rule 1 uses)', 'cone')

    L.append('## 3. Replayed against stored rating')
    L.append('')
    pairs = [(i['basis'], i['replay']) for i in scored if i['basis'] is not None]
    cross = defaultdict(int)
    for st, rp in pairs:
        cross[(min(int(st), 5), int(round(rp)))] += 1
    L.append('Rows are the stored rating, columns the replayed median (rounded). Stored models differ from the replay '
             'model, so a difference is a model difference as well as a sampling one.')
    L.append('')
    L += table(['stored \\ replay'] + [str(c) for c in range(1, 6)],
               [[str(r)] + [cross[(r, c)] for c in range(1, 6)] for r in range(1, 6)])

    L.append('## 4. Replayed rating by ERA5 canopy overhead, within the phantom and the obstructed bands')
    L.append('')
    rows = []
    for b in (0, 2):
        for c, name in enumerate(canopydeck.CANOPY_STATES):
            sel = [i for i in scored if i['obs'] == b and canopydeck.era5_canopy_state(i['row']) == c]
            if sel:
                rows.append([BANDS[b], name, len(sel), pct(sum(1 for i in sel if i['replay'] >= GO), len(sel))])
    L += table(['ERA5 observer low', 'ERA5 canopy', 'analogues', 'replayed go'], rows)

    ph = [i for i in scored if i['obs'] == 0]
    L.append('## 5. The phantom analogues, one by one')
    L.append('')
    L.append('ERA5 observer low < %d: %d analogues. Largest first by forecast observer low, then date.'
             % (lowdeck.CLEAR_MAX, len(ph)))
    L.append('')
    ph.sort(key=lambda i: (-(i['row']['low_cloud'] or 0), i['date'], i['row']['loc_name'] or ''))
    L += table(['evaluation', 'location', 'date', 'event', 'fc obs low', 'fc cone low', 'ERA5 obs low', 'ERA5 cone low',
                'ERA5 obs mid', 'ERA5 obs high', 'stored', 'replayed'],
               [[i['label'], i['row']['loc_name'], i['date'], i['row']['target_type'], lowdeck.num(i['row']['low_cloud']),
                 lowdeck.num(i['row']['solar_low_cloud']), lowdeck.num(i['row']['cv_observer_low_cloud']),
                 lowdeck.num(i['row']['cv_horizon_low_cloud']), lowdeck.num(i['row']['cv_observer_mid_cloud']),
                 lowdeck.num(i['row']['cv_observer_high_cloud']), i['basis'], '%g' % i['replay']] for i in ph[:60]])
    return '\n'.join(L) + '\n'


def cmd_report(args) -> int:
    manifest = replay_set.read_csv(args.manifest)
    rows = premise.load_rows(args.forecasts)
    typed = {str(r['evaluation_id']): r for r in rows}
    replay = replay_medians(args.runs, args.model)
    with premise.study_span([r['target_date'] for r in rows if str(r['evaluation_id']) in {m['evaluation_id'] for m in manifest}]):
        text = render(manifest, replay, typed, args.model)
    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, 'canopyreplay.md')
    with open(path, 'w', encoding='utf-8') as f:
        f.write(text)
    print('wrote %s' % path)
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    sub = ap.add_subparsers(dest='cmd', required=True)
    s = sub.add_parser('select', help='write the analogue prompt set and manifest')
    s.add_argument('--prompts', required=True)
    s.add_argument('--forecasts', required=True)
    s.add_argument('--out', required=True)
    s.add_argument('--runs', type=int, default=1, help='runs per prompt, for the cost estimate only')
    s.add_argument('--input-tokens', type=int, default=replay_set.DEFAULT_INPUT_TOKENS)
    s.add_argument('--output-tokens', type=int, default=replay_set.DEFAULT_OUTPUT_TOKENS)
    r = sub.add_parser('report', help='tabulate a finished replay against ERA5')
    r.add_argument('--manifest', required=True)
    r.add_argument('--runs', required=True, help='the harness runs.csv')
    r.add_argument('--forecasts', required=True)
    r.add_argument('--out', default='out')
    r.add_argument('--model', default='SONNET_55')
    args = ap.parse_args(argv)
    try:
        return cmd_select(args) if args.cmd == 'select' else cmd_report(args)
    except ValueError as e:
        print('error: %s' % e, file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
