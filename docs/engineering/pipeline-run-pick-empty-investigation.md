# `pipeline_run_pick` empty since run #21 — investigation findings

**Date:** 2026-06-10
**Question:** `pipeline_run_pick` has 19 rows spanning runs #4 → #20 and zero rows for
runs #21–#25 — including #24, a COMPLETED nightly whose briefing ran successfully and
which the Planner displays. Is this (a) the honest output of best-bet C's
decline-to-crown behaviour, or (b) a persistence gap where computed picks are silently
dropped?

**Verdict: (a) — by design / honest. There is no persistence gap.**

---

## The evidence chain (code, verified against the observed prod state)

The Planner currently shows the #24 nightly briefing with the headline
*"No standout recommendations right now — conditions are similar across all regions."*
That sentence is **frontend-only copy**: `DailyBriefing.jsx` renders it as the
empty-state when `briefing.bestBets` is empty for a PRO/ADMIN user
(`frontend/src/components/DailyBriefing.jsx`, the `best-bet-empty` block). The backend
never generates it. So the displayed headline *is* direct evidence that the persisted
#24 briefing contains **zero best bets**.

From there the chain is airtight:

1. **`BriefingService.refreshBriefing()`** calls `bestBetAdvisor.advise(...)` and stores
   whatever list comes back on the cached/persisted `DailyBriefingResponse.bestBets`.
2. **`BriefingHonestyFilter.apply(...)`** (the Gate 2 API read-path filter) passes
   `bestBets` through **unchanged** — it rewrites region verdicts only. So what the
   Planner shows is what the cache holds.
3. **`PipelineOrchestrator.persistPicksForCycle(runId)`** reads the same cached
   briefing after refresh and hands `briefing.bestBets()` to
   `PipelineRunPickService.persist(...)`.
4. **`PipelineRunPickService.persist(...)`** treats a null/empty list as a documented,
   legitimate no-op — it logs `[PICK] No picks to persist for pipelineRunId=…` at INFO
   and writes nothing.

Display and table therefore **agree**: the advisor produced no picks, the Planner shows
the honest empty state, and the table honestly records nothing. Case (b) would require
the briefing to show a concrete pick with no corresponding row — the opposite of what
prod shows.

## Why best-bet C cannot be dropping picks

The coverage-aware selection (`BriefingBestBetAdvisor.applyCoverageAwareRanking`) is
**reorder-only**: it demotes a thin-coverage headline *only by promoting* a
better-evidenced pick, and when no covered alternative exists it returns the list
untouched. It never removes a pick, and its return type is the same `List<BestBet>`
the persistence hook consumed before C. There is no shape mismatch for the hook to
mishandle.

The stale-briefing skip in `persistPicksForCycle` is also not the cause here: a
below-threshold run serves the last-known-good briefing with `stale=true`, whose
*carried-forward picks* would then be visible in the Planner — not the empty state.

## The one thing the empty state cannot distinguish (flag, not a bug fix)

`advise(...)` returns an empty list for **three different reasons**, and they are
indistinguishable in both the UI and the table:

1. **Honest zero from Claude** — the model returned `{"picks": []}`. Note this is
   mildly *off-contract*: the system prompt's floor for an all-STANDDOWN week is a
   single stay-home pick (`event`/`region` null), which **would** persist as a row.
   Zero picks means Claude chose silence beyond what the prompt asks for.
2. **All picks discarded by validation** — invalid event/region or a day-name outside
   the forecast window (`validateAndFilterPicks`).
3. **Silent advisor failure** — any exception (API error, parse failure) is caught and
   returns an empty list so the briefing always loads.

They ARE distinguishable in prod logs and `api_call_log`. To confirm which applied to
runs #21–#25, on the prod host:

```bash
# Advisor conclusion per refresh (INFO/WARN in goldenhour.log):
grep -E "Best-bet advisor (returned|failed)|Best bet validation|\[PICK\]" goldenhour.log
```

```sql
-- The advisor's raw responses (response body is stored on success):
SELECT created_at, status_code, succeeded, response_body
FROM api_call_log
WHERE endpoint = 'briefing-best-bet'
ORDER BY created_at DESC LIMIT 10;
```

Expected for honest-decline: `Best-bet advisor returned 0 pick(s)` (or
`returned 1` + `validation: 0/1 passed`) and a 200 row in `api_call_log` whose
`response_body` shows an empty `picks` array. A run of consecutive
`Best-bet advisor failed` WARNs instead would mean reason 3 and is worth a separate
look — but it would still not be a *persistence* bug.

The pick trajectory (healthy two-pick runs at 3.5–4.0 → single weak 3.3 rank-1 with no
rank-2 at #20 → nothing from #21) matches a genuinely flat week degrading through the
prompt's tiered thresholds: the "NO PICK 2" rule explains #20's single pick, and the
week's best visible rating (~3.3, below the 3.5 tier floors) explains the slide to
zero.

## Cross-run comparison view — already graceful

Checked as part of this investigation; **no code change needed**:

- `PipelineRunComparisonService` returns `null` when both runs have no picks (card
  hidden entirely) and emits a `PRESENCE` diff when one run has a pick the other lacks.
- `PickCell` in `PipelineRunsView.jsx` renders a muted *"none"* for a missing pick —
  there is no broken/empty-looking card state.

One accepted limitation: when an intraday run and its nightly baseline *both* declined
to crown, the comparison card is absent — indistinguishable from "no baseline exists".
Acceptable for now; revisit only if the explicit-decline record below is built.

## Flagged for product decision (do not build without one)

1. **Persist an explicit "declined to crown" record per run** — so pick history
   distinguishes "no pick — flat week" from "no row — run never got there". Today that
   distinction requires joining against `pipeline_run` status.
2. **Planner copy** — the current empty-state sentence reads as honest-flat, which is
   correct for case 1 above; if silent advisor failure (case 3) turns out to be
   occurring, the same copy would be misleading and the cases should be split.

## What was deliberately NOT done

- No change to best-bet C's selection logic — its decline-to-crown behaviour is the
  recently-shipped honesty fix working as designed.
- No forcing of pick persistence — persisting something when the advisor declined
  would corrupt the honesty of the data.
- No Planner rollback to #20's stale 3.3 pick — the Planner correctly shows the last
  successful cycle's briefing, and an honest "nothing stands out" beats a stale crowned
  pick for a possibly-passed event.
