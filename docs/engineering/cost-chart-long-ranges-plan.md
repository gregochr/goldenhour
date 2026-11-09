# Cost view: 7 days, 90 days and All time — plan

Operations → Runs → Cost view (`runs-deep-dive-plan.md` §4 D3/D4). The owner wants longer ranges on the "Daily spend
by type" chart to see spend trends (`claude-cost-ledger.md`). UI and contract change only: how a cost is computed is
untouched.

## Where it lives
Chart `frontend/src/components/runs/cost/DailySpendChart.jsx`, view `RunsCostView.jsx`, table `CostChangeTable.jsx`,
helpers `utils/runsCost.js` (`verdictHeadline`, `verdictBody`, `chartSeries`, `typeSeries`), ranges
`utils/runsTiming.js` (`COST_RANGES`, `DEFAULT_COST_RANGE`), URL `utils/runsQuery.js` (`range` key, digits only; a
non-digit `range` is treated as the legacy Day-filter word and deleted by `normalizeRunsQuery`), view state
`components/runs/RunsView.jsx` (~line 126), hook `hooks/useRunsServed.js` `useRunsCost`, API `api/runsApi.js`
`fetchRunCost`. Backend `GET /api/admin/runs/cost` → `AdminRunsController.cost(Integer days)` →
`RunsCostService.cost(Integer)`, `RANGES = [14, 30, 60]`, loading 2N days through `RunsLoader` (last N vs prior N).
`RunsBounds.earliestDay()` is the UK day of the earliest job_run / pipeline_run / sky eval, capped at 400 days back
(`MAX_DAYS_BACK`), today when nothing ran.

## Contract
- `days` becomes a String request param: missing → 30; `7|14|30|60|90` → that N with unchanged semantics (last N vs
  prior N); `all` (case-insensitive) → all time; anything else → 400 `days must be one of [7, 14, 30, 60, 90] or all`.
  Parsing lives in the service (no logic in the controller).
- All time: `firstDay = bounds.earliestDay()` (so capped at 400 days, the same history cap as the Day and Calendar
  views), `lastDay` = yesterday, `rangeDays = max(1, lastDay - firstDay + 1)` (nothing ran → earliest = today → one
  day, yesterday). No prior period: `days[]` covers only those N days, and every prior figure is what the existing
  "prior period spent nothing" path already produces (prior 0, deltaPercent null, verdict FLAT with a null percent).
- New response field `allTime` (boolean; false for a numeric range).

## Frontend
- `COST_RANGES = [7, 14, 30, 60, 90, 'all']` (owner asked for 7 too); Segmented labels `7 days`, `14 days`, `30 days`, `60 days`, `90 days`, `All time`.
- URL: `range=all` must survive `normalizeRunsQuery` (exclude `all` from the legacy test) and `readRunsQuery` returns
  `range: 'all'` for it (digits still a Number). The Timing view ignores it and falls back to its default.
- `fetchRunCost('all')` sends `days=all`; the hook's cache key becomes `cost:all`.
- `chartSeries`: unchanged (it slices the last `rangeDays`, which for all time is every served day; `rollingAverage`
  already shrinks its window at the start).
- Chart: aria-label and empty text say "since <date>" for all time instead of "last N days". X labels keep
  `everyNth = ceil(n / X_LABELS)` thinning, and gain the year when outside the current UK year (pass the real clock
  to `formatDayMonth`) so a range crossing New Year reads right. Bars: at 400 days a slot is about 2 px and the
  `max(1, slot * 0.2)` gap eats half of it; below a slot of about 5 px drop the gap so bars touch, bar width ≥ 1 px.
  The 7-day average line stays: it is the useful smoothing at long ranges.
- Verdict card, all-time branch: headline "£X spent since 3 Mar 2026." (no up/down claim); body: per day
  (`perDayLast`) and the biggest type by spend. No spend: "No spend recorded since <date>."
- Table, all time: subline "since <date> · click a row to isolate it in the chart"; columns Type, Spend and the
  sparkline (every day, none faint); Prior, Change and Why hidden (its own grid template); total row = spend + USD.
  Served order is biggest `deltaGbp` first, which equals `lastGbp` when prior is 0, so biggest spend first.
- Sparkline fades its first half only when not all time.

## Tests
- Backend `RunsCostServiceTest`: 7 and 90 accepted (14- and 180-day load windows); `all` → firstDay = earliestDay, N, no prior,
  `allTime` true; `ALL` case-insensitive; nothing-ran edge (N = 1); `5`, `abc`, `0`, blank → 400.
  `AdminRunsControllerTest`: `days=90` and `days=all` pass through; a bad value is 400.
- Frontend: runsQuery (`range=all` kept), runsCost helpers' all-time wording, DailySpendChart at 7, 90 and all time (at 7 days every bar is labelled and the 7-day average still averages the 7 days before the first bar, because 2N days are served)
  (label count, bar width ≥ 1, average line points = bars), CostChangeTable all-time columns, RunsView passing `all`.
- Docs: the `/api/admin/runs/cost` entry in `reference/api-endpoints.md`; a `changelog.d/` entry.

## Corrections from the plan review
1. `DailySpendChart` has its own `RANGES = [14, 30, 60]` and calls `onRangeChange(Number(value))`, which turns
   `'all'` into NaN (and `RunsView` then silently falls back to 30). Build its options from `COST_RANGES` and pass
   `value === 'all' ? 'all' : Number(value)`. `range` PropTypes and JSDoc widen to `number | 'all'` in
   `DailySpendChart`, `RunsCostView`, `useRunsCost`, `fetchRunCost` and `readRunsQuery`'s return type.
2. All-time edge: with nothing run, or the earliest run today, `earliestDay()` is today. Clamp
   `firstDay = min(earliestDay, yesterday)` so `days[]` always has `rangeDays` entries, and set the period split to
   `firstDay` explicitly for all time (not `today - n`), so every row counts in the last period.
3. "All time" is the Runs history cap, 400 days (`RunsBounds.MAX_DAYS_BACK`). The UI says "since <date>" (true
   either way); the docs entry states the cap.
4. The sparkline at 400 values is ~0.3 px a bar and `max(1, bar - 1)` overflows its 130 px. When there are more
   values than fit at 2 px a bar, sum them into equal buckets (weekly-ish) before drawing; no faint half in all time.
5. `cost(Integer)` → `cost(String)` changes every caller: `RunsCostServiceTest`, the `AdminRunsControllerTest` stubs
   (pin the exact argument, no `any()`), and any other test touching the service.
6. Keyboard: the chart keeps one roving tab stop (arrow keys move day by day), so 400 slots is one Tab, acceptable.
7. 7 days: invalid-value tests that used `7` as the bad example switch to `8`. At 7 bars a slot is ~100 px: cap a
   bar's width (about 40 px) and centre it in its slot so a short range does not read as blocks.
8. Six Segmented buttons may overflow at 375 px. On a phone only (`hooks/useIsMobile.js`), the labels shorten to
   `7d 14d 30d 60d 90d All` (the accessible name stays the long label); desktop and tablet keep the long labels. Check
   the header at 375 px in the test (no wrap assertion needed; the short labels are the assertion).
