# Sun-side change gate — plan (2026-11-16) — REJECTED at Gate 0, not built

**Result (2026-11-16, production, looks 5–16 November, `cost-queries/sun-side-gate-replay.sql`).** Of 2,712
re-ratings in today's pool, the gate as specified skips:

| Setting | Skipped | Saving / 30 days | Hidden 1★ | Hidden 2★+ | Hidden 4★-line crossings |
|---|---|---|---|---|---|
| 10 pts / 18 h | 230 (8.5%) | $1.48 | 27.0% | 0.43% | 39 (17%) |
| 15 pts / 18 h | 319 (11.8%) | $2.10 | 30.1% | 0.63% | 51 (16%) |
| 10 pts / 12 h | 198 (7.3%) | $1.29 | 21.2% | 0.51% | 25 (13%) |

10 / 18 h saves under the $2 bar, so by the rule set beforehand the gate is not built. 15 / 18 h clears $2 by
ten cents but was not the rule's setting. The other columns also argue against it: a skipped look would have
hidden a 1★ move 21–30% of the time and a 4★-line crossing 13–17% of the time, above the ~13.5% wobble on
identical sun-side inputs, so the kept stars hide real change. The kept rating was also typically 17.7 h old
(p50), right against the age limit. The first measurement's $3.53 shrank mainly because the 60% rule and the
afternoon's T+1 deferral had already removed most of the skippable looks. The plan below is kept as the record.

**Goal.** Stop paying Sonnet to re-rate a T+0/1 sunrise or sunset when the weather toward the sun has barely
moved since the rating it already holds. The kept rating keeps serving. First measured in
`claude-cost-ledger.md` ("Sun-side change gate and the afternoon's looks"; `cost-queries/sun-side-change-gate.sql`):
at a 10-point threshold, ~1,333 skippable re-ratings a month ($3.53), 2★+ moves 0.3–0.6%. One phase, one PR.

## Gate 0: re-measure before building (review finding)

The first measurement compared each rating with the one just before it. The gate compares against an
**anchor** (the last rating actually made) that ages with every skip, so chains skip less and each skip hides
more. Its 30 days also predate the 60% rule (#1838) and the afternoon's T+1 deferral (#1601, #1662), both of
which remove skippable looks. `cost-queries/sun-side-gate-replay.sql` replays the gate as specified on today's
pool (looks from 5 November, afternoon T+1 looks and would-be 60% stand-downs removed), for 10 points / 18 h,
15 / 18 h and 10 / 12 h.

**Decision rule (set before running):** build only if the 10 / 18 h setting saves **at least $2 per 30 days**
with **hidden 2★+ moves under 1%** of skipped looks. Pick the setting with the larger saving that passes.
Record the result in the ledger either way.

## The rule

Skip the re-rating when **all** hold, in `collectScheduledBatches` only. That path serves every scheduled cycle
(NIGHTLY, INTRADAY, EVENING), so all three are gated.

1. **Sky lane only**: not woodland, not bluebell wood, **not an OPEN_FELL pair in bluebell season** (its paired
   bluebell task would be dropped too), `daysAhead <= 1`, not forced.
2. Runs right after the evening-refresh deferral, so triage and eligibility have already run: a triage
   stand-down still records `SKIPPED_TRIAGED` and retracts, exactly as today.
3. The slot holds a **live rating** (`LiveRatings`: present, non-null, not retracted).
4. **Anchor identity.** The slot's latest **SCORED** `forecast_evaluation` row with a non-null `rating` (the
   new query below) must match the live rating: `rating` equal, `sky_rating` equal (both null counts as
   equal), and **`headline` equal and non-null on both sides**. Any mismatch means the served rating did not
   come from that row: rate. Its `evaluation_model` must equal `decision.model()` (the active near-term model;
   a T+2 Haiku anchor crossing into T+1, or a `model_selection` change, rates).
5. **Anchor age.** `scored_at` no more than **`max-anchor-age-hours`** (default 18) before now. This
   deliberately overrides the T+0 horizon freshness cap (`FreshnessProperties.Horizon`) for these slots, and
   says so in `reference/pipeline.md`.
6. **Sun-side cloud held:** solar low, mid, high and far-solar low cloud all non-null on both the anchor row
   and today's `AtmosphericData.directionalCloud()` (null `directionalCloud` rates), with the largest absolute
   change `<= max-cloud-delta` (default 10).
7. **Rain held:** both under 0.2 mm, or the change under 0.2 mm (null as 0). Compare `BigDecimal` with
   `compareTo` against `new BigDecimal("0.2")`, never doubles.
8. **Visibility held:** both ≥ 10,000 m, or the change under 2,000 m. Null on either side rates.

**No drift.** The comparison is always against the anchor's stored inputs; a skip writes no
`forecast_evaluation` row. A newer PENDING row from an in-flight batch does not matter: the live rating still
comes from the anchor and the in-flight result overwrites it when it lands (submission order). Say this in the
javadoc.

## Disposition `SKIPPED_INPUTS_UNCHANGED`

Detail: `"sun-side Δ{n} ≤ {max}, rain and visibility held; kept {stars}★ rated {scored_at}"`. The column is a
plain `VARCHAR(40)` (not `@Enumerated`); no migration, no `LocalH2EnumWidener` line. A new category, not
`SKIPPED_NO_REFRESH_NEEDED` (that would count as "settled-skipped" and hide the saving) or `SKIPPED_CACHED`.

Code changes, and only these:

- `DispositionCategory`: the constant with javadoc. While there, fix `SKIPPED_NO_REFRESH_NEEDED`'s stale
  "reserved, not used yet" javadoc and the stray duplicate javadoc above `SKIPPED_NO_PROMPT`.
- `ReclassSummary`: a `keptUnchanged` count and a "kept (sun-side unchanged)" clause in `detail()`, so
  "considered" still adds up.
- `ForecastTaskCollector`: the summary line adds `skippedUnchanged` into the skipped total.
- `ForecastRunDispositionRepository`: **do not** add it to the `<> 'SKIPPED_CACHED'` exclusions (excluding it
  would let an old `SKIPPED_TRIAGED` resurface as the latest decision); one javadoc sentence saying so, and one
  in the stability-skip retraction query's javadoc that it is a keep, not a decision against the slot.
- `SupersedingDispositionService`: javadoc list gains it as **OUT**. `CycleLocationOutcomeResolver`: javadoc
  list only (nothing sent; a place whose slots were all kept gives no failure evidence, so its streak does not
  reset, accepted). `CoverageReasons`: a comment line, no case (falls to UNKNOWN, the safe direction).
- No code in `BriefingCandidateCollector`, `BatchRetryService`, `ScheduledBatchEvaluationService`,
  `EligibilityDecision`, `VerdictSampleGate`, the rollup, or `AsOfEventResolver`: none switches on this.
- Frontend `components/DispositionBreakdown.jsx`: the key next to "No refresh needed", label "Sun-side
  unchanged", the same muted swatch (Operations, admin only).

## Configuration

`SunSideGateProperties`, `@ConfigurationProperties("photocast.batch.sun-side-gate")`, `@Validated`:
`enabled` (default `true`), `max-cloud-delta` (default 10, 0–100), `max-anchor-age-hours` (default 18,
1–48), set to the setting Gate 0 picks. Add to `application-example.yml` and a new `photocast.batch.*`
paragraph in `reference/configuration.md`.

## Code shape

- **`SunSideChangeGate`** (new, `service/batch/`, pure apart from an injected `Clock` and the properties):
  `Optional<String> keepReason(AtmosphericData now, ForecastEvaluationEntity anchor, LiveStars live,
  EvaluationModel model)`; empty means rate.
- **Repository:** a new bulk query for the latest row per (location, date, event) with `batch_state =
  'SCORED' AND rating IS NOT NULL AND target_type IN (SUNRISE, SUNSET)`, the **same predicate inside the
  correlated MAX subquery**, over the candidates' date range (index `idx_forecast_eval_latest_run`, V128).
  Loaded lazily once per pass beside `LiveRatings`; a load failure reads as "no anchor", which rates.
- **`LiveRatings`** returns the stored `BriefingEvaluationResult` (rating, sky rating, headline) for the gate.
- **`ForecastTaskCollector`**: the gate after the evening-refresh deferral in `collectScheduledBatches`' loop;
  **not** in `collectRegionFilteredBatches` (admin JFDI and region runs always rate) and not in retries. Log
  `[BATCH DIAG] SKIP … reason=INPUTS_UNCHANGED (…)`, `agg.recordExcluded`, and `skippedUnchanged`.

## Tests (backend standards; pin every stub argument)

- `SunSideChangeGateTest`: each of rules 4–8 failing alone → empty; delta exactly 10 (skips) and 11 (rates);
  a null on each cloud field either side; null `directionalCloud`; null visibility; rain 0.15/0.19 (held),
  0.10 → 0.35 (moved); anchor age exactly 18 h (skips) and just over (rates); model, rating, sky rating and
  headline mismatches; null headline.
- Collector: held near-term sky slot with a matching anchor → no task, `SKIPPED_INPUTS_UNCHANGED`; disabled →
  task; T+2 → task; woodland, bluebell wood and OPEN_FELL in season → task; triaged → `SKIPPED_TRIAGED`;
  region-filtered path → task; anchor load failure → task; summary totals add up.
- Repository query: PENDING, ABANDONED, triage-only and null-rating rows are never the anchor.
- `ReclassSummary` detail; `EvaluationViewService.loadServeTriage`: a keep is not triaged, and a
  `SKIPPED_TRIAGED` arriving after a keep still retracts; `CycleLocationOutcomeResolver.foldDisposition`;
  frontend `DispositionBreakdown.test.jsx` renders the label.

## Docs in the same PR

`reference/ratings-stores.md` (both category lists, ~160 and ~248), `reference/pipeline.md` (the gate, the
T+0 override), `reference/configuration.md`, the ledger, and a `changelog.d/` entry.

## Measuring it

After 14 days live, count `SKIPPED_INPUTS_UNCHANGED` and its saved cost, re-run the replay on looks since
release, and record both in the ledger. Kill switch: `enabled: false`.

## Not in scope

The far-term (Haiku) lane; comparing the prompt hash (a prompt-changing release keeps an older-prompt rating
until inputs move or the anchor ages out; accepted).
