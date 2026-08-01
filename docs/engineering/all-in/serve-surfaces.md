# DRAFT — serve-surfaces

> ⚠️ **Working material, not the build source.** Written against a mix of `a484d1c4`
> (not an ancestor of `main`) and `d421ef5f`; citations of `ForecastTaskCollector.java`
> past line 534 are off by +16. Adversarial verification refuted claims in this draft —
> see `serve-surfaces.corrections.md`. **Resolved positions live in `../all-in-build-plan.md`,
> which supersedes this file wherever they disagree.**

---

## §8 — Staleness, the ops-UI shrink, and the calibration instrument

Everything below was read from the tree at `a484d1c4`. Where I could not check something I have written **UNVERIFIED** rather than assert it. Three things I expected to find do not exist, and one thing I did not expect to find is the single most consequential defect in my area — §8.5.

The unifying rule (*classify for display; never gate on the classification*) lands here as its third instance: **the freshness TTLs stop gating and become a staleness display, on the confidence channel that already exists.**

---

### 8.0 Corrections to §4.5 and §4.6 of the summary-depth design

Three claims in the document I am deepening are wrong against the tree. They must be fixed before anyone plans from it.

| §4.6 claim | Truth |
|---|---|
| "Remove: **stability view**" | **There is no stability view.** `grep -rni stability frontend/src` returns only copy strings in `ModelSelectionView.jsx:470,482-483,525,555`, a label in `DispositionBreakdown.jsx:19`, a phase name in `PipelineRunsView.jsx:14`, and a dialog sentence in `JobRunsMetricsView.jsx:883`. `StabilityController` (`controller/StabilityController.java:19`, `/api/admin/stability/summary`) has **zero** frontend callers — `grep -rn "admin/stability" frontend/src` exits 1. It is a dead endpoint, not a view. |
| "Remove: **freshness configuration**" | **There is no freshness configuration UI.** `FreshnessProperties` (`config/FreshnessProperties.java:20`) is bound to `photocast.freshness` in YAML only. Nothing in `frontend/src` reads it. |
| §4.5 "**age shown**" for STALE | Nothing on the wire carries a per-region produce time today. `EvaluationViewService.getScoresForEnrichmentBulk` (`:327`) computes `CachedEntry.evaluatedAt` internally (`:561`, `:588-591`, `:620`) and **discards it** — it returns bare `BriefingEvaluationResult`, which has no timestamp (`model/BriefingEvaluationResult.java:22-30`). Showing an age requires a new field. §8.1 designs it and argues for it explicitly rather than assuming it.

**The ops UI is already smaller than the design believes.** The deletion work in my area is 80% backend endpoints with no consumers, and one frontend block (`ModelSelectionView`'s Cost Optimisation panel).

---

### 8.1 Staleness — NONE / STALE / FRESH on the existing channel

#### 8.1.1 Where age comes from

Two timestamps already exist and are already correctly zoned. Neither is invented:

- **Cached path** — `BriefingEvaluationService.getCachedEvaluatedAt(regionName, date, targetType)`, used at `EvaluationViewService.java:588-589` and `:422`. For DB-hydrated rows the design already made the load-bearing choice of `getUpdatedAt()` over `getEvaluatedAt()` (`EvaluationViewService.java:613-620`) — *"A slot re-evaluated for three days running still carries its day-one evaluated_at"*. Staleness must reuse that, not re-derive it.
- **Forecast-row path** — `EvaluationViewService.forecastRunInstant` (`:539-544`). `forecast_run_at` is a naive `LocalDateTime` **recorded in Europe/London, not UTC**, and the method zones it before comparison. Its Javadoc names the failure mode: *"silently out by an hour through BST"*. Any staleness code that touches `forecastRunAt` goes through this method or repeats a known bug.

`produced_at` in §4.4's `evaluation_event` schema is the eventual single source; until that table lands, staleness reads these two, with the same precedence the merge already uses (`mergeToView`, `:449-500`: cached wins **only while at least as fresh**, `:518-525`).

#### 8.1.2 The one new backend field

`EvaluationViewService` gains one record and one method; the existing bulk method becomes a two-line wrapper so **no existing caller changes**:

```java
/**
 * The enrichment payload for one region/date/target: the per-location results plus the
 * OLDEST instant any of them was produced.
 *
 * <p>Oldest, not newest, for the same reason {@code ConfidenceDeriver} downgrades on thin
 * coverage rather than averaging it away: a region verdict is a rollup, so its age is the
 * age of its weakest constituent. In practice every slot in a region is written by one batch
 * bucket and the two agree to within seconds; the rule only bites when a cycle partly failed,
 * which is exactly when it should.
 *
 * @param results        locationName to evaluation result
 * @param oldestProducedAt the oldest produce instant across {@code results}, or {@code null}
 *                         when none carried one
 */
public record RegionScores(Map<String, BriefingEvaluationResult> results,
        Instant oldestProducedAt) { }

/** Bulk enrichment payload carrying produce instants. See {@link #getScoresForEnrichmentBulk}. */
public Map<String, RegionScores> getScoresForEnrichmentBulkWithAge(
        LocalDate start, LocalDate end, Set<TargetType> types);
```

`getScoresForEnrichmentBulk` (`EvaluationViewService.java:327`) keeps its signature and delegates:

```java
return getScoresForEnrichmentBulkWithAge(start, end, types).entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().results()));
```

**Both load-bearing behaviours of the underlying bulk query survive untouched** — it does not filter target type (so the `types.contains` guard at `:363` stays) and its `MAX(forecastRunAt)` predicate returns both rows on an exact tie (so the strict-`isAfter` reduction at `:370-372` stays). These are pinned by the comment at `:349-354` and must not be "simplified" while adding the timestamp.

#### 8.1.3 One TTL, and why 24 hours

The per-stability TTLs die with the gate: `FreshnessProperties.settledHours=36` (`:29`), `transitionalHours=12` (`:35`), `unsettledHours=4` (`:41`), `safetyFloorHours=2` (`:48`). With `ForecastStability` no longer persisted there is nothing to key three numbers on, and — more importantly — **all four were tuned as *spend* thresholds, not *trust* thresholds.** `safetyFloorHours` in particular is documented as *"Prevents rapid successive triggers from intraday + JFDI + admin actions"* (`:44-46`): a rate limiter, not a statement about the sky. None of them transfers.

The display threshold answers a different question: *has the pipeline failed to cover this slot?* That makes it a function of **cycle cadence**, not meteorology.

- Nightly cycle: `near_term_batch_evaluation` (`V73__forecast_batch.sql:20-24`), seeded `0 0 3,15 * * *`, **PAUSED** at seed. The production row is DB-managed and I could not read it; `V105__intraday_forecast_refresh_job.sql:3` says *"~01:00 UTC in production"* — a code comment, **UNVERIFIED against production**.
- Intraday cycle: `intraday_forecast_refresh` (`V105:19-23`), `0 0 14 * * *`, ACTIVE.

Taking 01:00 and 14:00, the longest healthy gap between evaluations of a covered slot is **13 hours**.

```java
/**
 * Age beyond which a region's scores read as stale.
 *
 * <p>Cadence, not meteorology. The nightly and intraday cycles are ~13h apart at their widest,
 * so 24h is the smallest number that (a) never fires on a healthy slot, even when a cycle runs
 * late, and (b) cannot be reached without missing at least two consecutive cycles. Anything
 * shorter reports a late run as a data problem; anything longer stops distinguishing "the
 * pipeline skipped this" from "the pipeline is down".
 *
 * <p>Deliberately NOT inherited from {@code FreshnessProperties}: those four numbers were spend
 * thresholds keyed on a stability classification that no longer gates anything, and
 * {@code safetyFloorHours} was a rate limiter. Their meteorological rationale is preserved in
 * §0 of the all-in design; none of it applies to a display threshold.
 */
static final Duration STALE_AFTER = Duration.ofHours(24);
```

Hard-coded, not a config property. A configurable display threshold is a knob nobody turns that every reader must go and check.

#### 8.1.4 Riding the confidence channel — no second visual channel

`ConfidenceDeriver.derive` (`service/ConfidenceDeriver.java:100-127`) already has exactly the mechanism: three independent downgrade terms OR'd into one `downgrade(base)` (`:116-125`). Staleness becomes the fourth term. That is a **three-line change** and adds no tier, no colour, no mark.

```java
/** Whether a region's scores are absent, aged out, or current. */
public enum Staleness { NONE, STALE, FRESH }

/**
 * Classifies the age of a region's scores against {@link #STALE_AFTER}.
 *
 * @param producedAt the oldest produce instant across the region's scored slots, or {@code null}
 * @param now        the reference instant
 * @return NONE when {@code producedAt} is null, STALE when older than the threshold, else FRESH
 */
public static Staleness stalenessOf(Instant producedAt, Instant now) {
    if (producedAt == null) {
        return Staleness.NONE;
    }
    return Duration.between(producedAt, now).compareTo(STALE_AFTER) > 0
            ? Staleness.STALE : Staleness.FRESH;
}

public static Confidence derive(int daysAhead, BriefingRatingStats.Stats stats,
        RegionRoster roster, Staleness staleness) { ... }
```

Inside `derive`, one clause is added to the existing condition at `:123`:

```java
boolean stale = staleness == Staleness.STALE;
if (wideSpread || thinCoverage || fragileRoster || stale) {
    base = downgrade(base);
}
```

**How the three states land, and why NONE and STALE do not collapse:**

| State | Path | Result |
|---|---|---|
| **NONE**, no scores | `stats == null \|\| stats.isEmpty()` at `:102-104` | `null` — the existing deliberate return, unchanged |
| **NONE**, scores but no timestamp | falls through; `stale == false` | **no downgrade** |
| **STALE** | fourth downgrade term | one band lower, floored at LOW by `downgrade` (`:148-153`) |
| **FRESH** | no term fires | unchanged |

The second row is a decision, not an oversight. A region with real ratings and no timestamp is a **legacy `daily_briefing_cache` payload** re-served — the field did not exist when it was written, exactly as `BriefingRegion.confidence` was nullable for the same reason (`model/BriefingRegion.java:38-43`). Downgrading it would make every region provisional for one cycle after deploy, for zero information. The frontend's `resolveConfidence` already caps a field-less inference at `MAX_INFERRED_TIER = 'medium'` (`frontend/src/utils/confidenceUtils.js:58,73-81`), which is the correct under-report. Pinned by `ConfidenceDeriverTest.derive_unknownProducedAtWithScores_doesNotDowngrade`.

**Call site.** `BriefingService.enrichWithCachedScores` (`service/BriefingService.java:673-752`), which already runs on both build and serve paths with a request-time `today` (`:675-677`). The `RegionScoreResolver` functional interface (`:653-657`) widens by one return type; both suppliers change — the serve path at `:347-351` and the build path at `:665`. At `:735-736`:

```java
Confidence confidence = ConfidenceDeriver.derive(daysAhead, stats, rosterOf(enrichedSlots),
        ConfidenceDeriver.stalenessOf(regionScores.oldestProducedAt(), clock.instant()));
```

**The one new wire field.** `BriefingRegion` gains `Instant scoresProducedAt` (nullable) plus a `withScoresProducedAt` wither, following the exact pattern of `withConfidence` (`:withConfidence`, `model/BriefingRegion.java`). **No migration** — it rides the `daily_briefing_cache` JSON, and legacy payloads deserialize to null, identically to `confidence`. Note the existing warning on that record: `withGloss`'s Javadoc records that *"a plain constructor call would silently drop later-added fields (this is exactly how the confidence channel was being wiped on the build path before this wither existed)"* — so `scoresProducedAt` **must** be added to all four withers (`withLightlyEvaluated`, `withConfidence`, `withGloss`, and the 15-arg back-compat constructor) in the same commit.

*Is this field worth it?* It buys only the age **text**. The tier decay already happens server-side without it. I am recommending it because "from yesterday's run" is the difference between a user distrusting the cell and a user knowing why — but it is the one item in this section I would drop first if the wire shape is contested. See open questions.

#### 8.1.5 Frontend — two edits, zero new components

| File | Edit |
|---|---|
| `frontend/src/components/HeatmapGrid.jsx:636` | `<ProvisionalMark />` → `<ProvisionalMark title={provisionalTitle} />`, where `provisionalTitle` appends `formatRelativeAge(region.scoresProducedAt)` to `treatment.label` when the field is present |
| `frontend/src/components/HeatmapGrid.jsx:665-690` | add one line to the **existing** hover tooltip portal, under the weather line: `Scored {formatRelativeAge(region.scoresProducedAt)}` |

`formatRelativeAge` already exists at `frontend/src/utils/relativeTime.js` and is already the single home for these tiers — `DailyBriefing.jsx:81-83` wraps it as `formatAge`, and `DailyBriefing.jsx:1290-1291` already renders a briefing-level age with it. No new helper.

`confidenceUtils.js`, `ProvisionalMark.jsx`, `VerdictPill.jsx`, `BriefingSummaryStrip.jsx` and `DailyBriefing.jsx` are **unchanged**. The staleness signal reaches them for free, because it arrives inside `region.confidence`, which they already consume (`HeatmapGrid.jsx:587`, `DailyBriefing.jsx:212,722`, `BriefingSummaryStrip.jsx:160`, `VerdictPill.jsx:50`).

#### 8.1.6 Stars are never touched — verified, both ends

- **Backend:** `ConfidenceDeriver` returns a `Confidence`. It has no reference to a rating value; the only rating input is `stats.ratingRange()` / `stats.count()` (`:116-118`), read-only.
- **Frontend:** in `HeatmapGrid.jsx`, `treatment.fillScale` is applied at `:589-592` **only** to `cellBg.background` and `cellBg.borderColor`. The star pill is rendered at `:655-661` from `starPillStyle(meanRating)` and `meanRating` (`:613-615`), which never see `treatment`. The two are in the same function and do not touch.

Pinned by a new test, because this is the invariant the whole channel rests on: `HeatmapGrid.test.jsx` → `'a stale region dims the verdict fill and leaves the star pill byte-identical'`, asserting the `mean-score-badge` element's `style` attribute is equal across a `high` and a `low` render of the same rating.

---

### 8.2 Ops UI, component by component

#### Deleted — backend, with no UI to remove because there never was one

| Object | Lines | Why |
|---|---|---|
| `controller/StabilityController.java` | 51 | `/api/admin/stability/summary`, zero frontend callers (grep exit 1) |
| `model/StabilitySummaryResponse.java` | 64 | Its only consumer |
| `test/.../StabilityControllerTest.java` | 87 | test |
| `config/FreshnessProperties.java` | 51 | Four spend thresholds; the surviving one is a hard-coded `STALE_AFTER` (§8.1.3) |
| `photocast.freshness.*` YAML keys | — | in `application.yml` / `-dev` / `-prod` / `-example` |

#### Deleted — the one real UI block

**Cost Optimisation panel, `frontend/src/components/ModelSelectionView.jsx`.** The panel is `:623-720` (~98 lines) plus `STRATEGY_INFO`, `getConflictReason`, `handleStrategyToggle` (`:219`) and `handleParamChange` (`:244`), plus the `strategies` state and its load at `:179`. Approximately **200 lines** of a 721-line file. `api/modelsApi.js:54-72` (`updateOptimisationStrategy`, ~20 lines) goes with it, as does its import at `ModelSelectionView.jsx:2`.

Also in that file, **stale copy that will be actively wrong** once the gates go — this is documentation rot with a UI in front of it:

- `:470` — *"T+2 and T+3 — only evaluated when the weather stability classifier says SETTLED."*
- `:482-500` — the SETTLED/TRANSITIONAL/UNSETTLED "why some nights run further ahead" block
- `:525` — *"Requests vary by weather stability and triage filtering"*
- `:555` — *"depends on how many locations pass triage and the stability classification"*
- `:572` — *"before optimisation strategies"*
- `:713` — *"Cost optimisation strategies control which slots are skipped to save API costs"*

Replace `:470` with `T+2 and T+3 — same bound, cheaper model.` and delete `:482-500` outright. This is not cosmetic: an operator reading `:482-500` after Phase 2 would conclude the classifier still gates, which is the exact misdirection §2.6 exists to prevent.

Elsewhere: `JobRunsMetricsView.jsx:839-851` ("Active optimisations" chips) and `:883` (*"same triage and stability gates as the overnight scheduled job"*) both go; `:883`'s two branches collapse into one sentence, since with no gates the scheduled and JFDI batches differ only in date span.

#### Deleted — the gate config service layer that only the UI reached

| Object | Lines |
|---|---|
| `service/OptimisationStrategyService.java` | 241 |
| `service/OptimisationSkipEvaluator.java` | 206 |
| `entity/OptimisationStrategyEntity.java` | 60 |
| `entity/OptimisationStrategyType.java` | 39 |
| `repository/OptimisationStrategyRepository.java` | 41 |
| `model/OptimisationStrategyUpdateRequest.java` | 20 |
| **tests** `OptimisationSkipEvaluatorTest` 368 + `OptimisationStrategyServiceTest` 216 | 584 |

#### Kept

- **`SchedulerView.jsx` (406 lines) — unchanged.** Verified: it imports only `fetchSchedulerJobs / updateJobSchedule / pauseJob / resumeJob / triggerJob` (`:2-8`) and its logic is cron parsing and BST/GMT rendering (`:17-59`). It manages *when jobs run*, never *which slots they spend on*. Not a gate surface in any part.
- `PipelineRunsView.jsx`, `JobRunsMetricsView.jsx` (minus the two blocks above), `JobRunDetail.jsx`, `MetricsSummary.jsx`, `TravelDaysView.jsx` — travel days are the surviving gate and its UI is the only gate config left.
- `ModelSelectionView.jsx` minus the optimisation panel — per-run-type model selection survives, because `HorizonModelSelector` still needs a near/far model.

#### The disposition view — honest re-examination, and it does **not** survive intact

§4.6 says "Grow: the disposition view." Against the tree that is wrong, and the deletion bias applies.

`DispositionCategory` (`entity/DispositionCategory.java`) has 12 values. After this redesign:

| Survives | Dies with a gate |
|---|---|
| `EVALUATED` | `SKIPPED_TRIAGED` (weather triage) |
| `SKIPPED_TRAVEL_DAY` (the only policy gate left) | `SKIPPED_HARD_CONSTRAINT` (tide/verdict) |
| `SKIPPED_PAST_DATE` (structural) | `SKIPPED_CACHED` (freshness) |
| `SKIPPED_NO_PROMPT` (structural — no woodland prompt exists) | `SKIPPED_STABILITY` |
| `SKIPPED_UNKNOWN_LOCATION` (anomaly) | `FORCE_EVALUATED` |
| `SKIPPED_ERROR` (anomaly) | `SKIPPED_NO_REFRESH_NEEDED` (never written — *"not used yet"*) |

**Six survive, and on a healthy non-travel night the breakdown is one row.** `DispositionBreakdown.jsx` is 170 lines of click-to-expand drill-down, ten tone-coloured category rows, and forward-compat unknown-key handling, to render `EVALUATED 312`.

**Verdict: the record stays (§2.7 makes it structural — it is the denominator every downstream query must state); the drill-down UI shrinks by ~75%.** Replace `DispositionBreakdown.jsx` with `DispositionLine.jsx` (~45 lines):

- always render the one reconciliation line that already exists (`:99-109`): *"N candidates considered — 312 evaluated, 46 travel day"*;
- render the expandable per-location list **only** when `SKIPPED_ERROR` or `SKIPPED_UNKNOWN_LOCATION` is non-zero. Those two are the only surviving categories where the *identity* of the affected slot is information — a slot that silently vanished. For travel days and past dates the count is the whole story.
- `CATEGORY_DISPLAY` drops from 10 entries to 6.

Both call sites keep the same prop and are untouched: `JobRunDetail.jsx:340`, `PipelineRunsView.jsx:588`.

**Deleting the enum constants is safe, and this is verified rather than assumed.** `ForecastDispositionService.getBreakdownForJobRun` (`service/batch/ForecastDispositionService.java:115-137`) counts off the **raw string** (`counts.merge(e.getDisposition(), ...)` at `:126`) and never calls `DispositionCategory.fromString`. The column is `VARCHAR(40)`, not a native enum (`V101__forecast_run_disposition.sql:27`). Historical rows carrying `SKIPPED_TRIAGED` still count correctly, and the frontend's unknown-key branch (`DispositionBreakdown.jsx:83-85`) renders them with a generic label. Retention is 30 days (`ForecastDispositionService.java:41`), so legacy values age out within a month by themselves.

#### Measuring what the triage deletion costs — the conflict the brief asked me to surface

The design's §2.3 argued triage stays at 80 because loosening it *"buys nothing visible"* while costing one Claude call each. **The user has overridden that.** Consequently:

> **The "+£32–48/month at-home" figure in §1 of the all-in design was computed with triage RETAINED and no longer holds.** It is now a floor, not an estimate. The delta must be re-derived before Phase 2's cost gate can mean anything.

The measurement is runnable today, because `forecast_run_disposition`'s 30-day retention exactly matches the evidence window:

```sql
-- Per-cycle triage volume, 30d. Postgres 17; FILTER and DISTINCT ON are used natively.
SELECT date_trunc('day', created_at) AS cycle_day,
       job_run_id,
       count(*) FILTER (WHERE disposition = 'SKIPPED_TRIAGED')        AS triaged,
       count(*) FILTER (WHERE disposition = 'SKIPPED_HARD_CONSTRAINT') AS hard_constraint,
       count(*) FILTER (WHERE disposition = 'SKIPPED_CACHED')          AS cached,
       count(*) FILTER (WHERE disposition = 'SKIPPED_STABILITY')       AS stability,
       count(*) FILTER (WHERE disposition = 'EVALUATED')               AS evaluated,
       count(*)                                                        AS candidates
FROM forecast_run_disposition
WHERE created_at >= now() - interval '30 days'
GROUP BY 1, 2
ORDER BY 1 DESC, 2;

-- The single number: mean additional Claude calls per cycle from dropping every gate but travel.
SELECT round(avg(newly_admitted), 1) AS extra_calls_per_cycle,
       round(avg(newly_admitted) * 0.00206, 4) AS extra_usd_per_cycle
FROM (
  SELECT job_run_id,
         count(*) FILTER (WHERE disposition IN
             ('SKIPPED_TRIAGED','SKIPPED_HARD_CONSTRAINT','SKIPPED_CACHED','SKIPPED_STABILITY'))
             AS newly_admitted
  FROM forecast_run_disposition
  WHERE created_at >= now() - interval '30 days'
  GROUP BY job_run_id
) t;
```

`0.00206` is the brief's stated 30-day mean cost per call — **UNVERIFIED by me**; substitute the live figure from `api_call_log`. Note the second query's denominator is *cycles that recorded dispositions*, and travel days will drag the mean down, so normalise by non-travel days per §1 before comparing to any threshold.

#### Admin endpoints that die

| Endpoint | Source | Frontend caller |
|---|---|---|
| `GET /api/admin/stability/summary` | `StabilityController.java:42-50` | **none** (grep exit 1) |
| `PUT /api/models/optimisation` | `ModelsController.java:106-110` | `modelsApi.js:62-72` → `ModelSelectionView.jsx:219,244` — all deleted |
| `optimisationStrategies` key in `GET /api/models` | `ModelsController.java:52` | `ModelSelectionView.jsx:179`, `JobRunsMetricsView.jsx:232` — both deleted |

I checked every controller in `controller/` (36 files). Nothing else in my area is gate-facing. `SchedulerController`, `PipelineRunController`, `JobMetricsController`, `TravelDayController` all survive whole. `JobMetricsController`'s `/disposition-breakdown` (`:165-169`) survives — the record survives; only the UI shrinks.

CLAUDE.md's "Admin tools (ADMIN)" endpoint list must lose `PUT /api/models/optimisation` in the same commit.

---

### 8.3 The calibration fix

#### 8.3.1 Verification of the established finding — all of it confirmed

- `CalibrationBucket.java:58-61` returns `new CalibrationBucket(key, 0, null, null, null, null, 0, 0, null)`. **Confirmed verbatim.**
- Record signature `:28-37`: `meanRatingError`, `meanAbsoluteRatingError`, `exactMatchRate`, `withinOneRate`, `meanAbsoluteFierySkyError` are all `Double` and correctly null. `missedOpportunities` (`:35`) and `wastedTrips` (`:36`) are `int` and return literal `0`. **Confirmed.**
- CLAUDE.md:254 — *"An absolute rating ceiling can only create missed opportunities, so that count gates relaxing the cloud-approach veto"*. Corroborated independently at `docs/engineering/cloud-approach-veto-fix.md:275-278`, which makes it operational: *"if `wastedTrips` rises by more than `missedOpportunities` falls"* the change is rejected. **Confirmed — the false zero sits on a real gate.**
- The record's own Javadoc `:11-16` calls these *"the ones that matter for prompt changes"*. The asymmetry is against the record's stated intent.

**The existing test already flinches from it.** `CalibrationBucketTest.of_emptyBucket_reportsNullMetrics` (`:105-116`) asserts null on four rates and `sampleCount` — and conspicuously does **not** assert the two counters. The test author had the empty bucket in hand and did not write down what it returns.

#### 8.3.2 The change

```java
public record CalibrationBucket(
        String key,
        int sampleCount,
        Double meanRatingError,
        Double meanAbsoluteRatingError,
        Double exactMatchRate,
        Double withinOneRate,
        Integer missedOpportunities,   // was int
        Integer wastedTrips,           // was int
        Double meanAbsoluteFierySkyError) {
```

`:60` becomes:

```java
if (pairs == null || pairs.isEmpty()) {
    // Null, not zero. A literal 0 here reads as "we checked and found no missed
    // opportunities" — and missedOpportunities is the gate on relaxing the cloud-approach
    // veto (docs/engineering/cloud-approach-veto-fix.md:275). Same rule as
    // ConfidenceDeriver returning null on zero coverage, deliberately unlike
    // Confidence.fromString's MEDIUM default.
    return new CalibrationBucket(key, 0, null, null, null, null, null, null, null);
}
```

`sampleCount` **stays `int`**: `0` there is a true measurement (we counted the pairs; there were none). Only the derived findings become null. That distinction is the whole point and belongs in the comment.

#### 8.3.3 Who would NPE on null — grepped, backend and frontend, exhaustively

| Site | Risk | Verdict |
|---|---|---|
| `ForecastCalibrationService.java:94-95` | passes both to `LOG.info` with 7 placeholders and 7 args (`:90-95`) | **Safe** — SLF4J varargs is `Object...`; no unboxing. Prints `null`. |
| `CalibrationBucketTest:31,32` | `of_perfectForecast`, 2 pairs | **Safe** — non-empty → `Integer` 0; AssertJ `isZero()` on `Integer` is valid |
| `CalibrationBucketTest:56,57,65,66,75,76` | all non-empty buckets | **Safe** |
| `ForecastCalibrationServiceTest:64,78,82` | `overall()` of 1–2 deduped pairs | **Safe** — non-empty |
| `ForecastCalibrationServiceTest:99,100` | `bucket(report,"T+0"/"T+3")` | **Safe** — non-empty |
| `CalibrationControllerTest:56` | `jsonPath("$.overall.missedOpportunities").value(3)` | **Safe** |
| **Frontend, all of it** | — | **No consumer exists.** `grep -rn "admin/calibration" frontend/src` exits 1; `grep -rni calibrat frontend/src` returns only `SkyRatingEvalView.jsx` and `skyRatingEvalApi.js`, which are the *sky-rating eval* harness, an unrelated feature. There is no `calibrationApi.js`. |

**No existing call site breaks.** The `int → Integer` change is source- and behaviour-compatible everywhere it is used today.

#### 8.3.4 The no-data state on `CalibrationReport`

A null counter says *"we don't know"*. It does not say *why*. Investigating this endpoint produced three genuinely different zero-pair causes — and §8.5 shows the third one is live in production right now. The report must name which:

```java
/**
 * Why a calibration report has the coverage it has.
 *
 * <p>Zero scored pairs has three distinct causes and they demand different responses:
 * nobody recorded anything, outcomes were recorded without a star rating (the join column),
 * or rated outcomes exist but no rated forecast row matched them. Collapsing them into
 * "scoredPairs = 0" is how a broken write path stays invisible.
 */
public enum CalibrationDataState {

    /** No outcome rows at all in the window. */
    NO_OUTCOMES,

    /** Outcomes were recorded but none carried {@code actual_rating} — nothing can join. */
    OUTCOMES_UNRATED,

    /** Rated outcomes exist, but no rated forecast row shares their (location, date, type). */
    OUTCOMES_UNMATCHED,

    /** At least one pair scored. */
    SCORED;

    /**
     * Classifies a window's coverage.
     *
     * @param outcomesInRange      every outcome recorded in the window
     * @param ratedOutcomesInRange those carrying a 1–5 rating
     * @param scoredPairs          pairs that survived the join
     * @return the state naming why coverage is what it is
     */
    public static CalibrationDataState derive(int outcomesInRange, int ratedOutcomesInRange,
            int scoredPairs) {
        if (scoredPairs > 0) {
            return SCORED;
        }
        if (outcomesInRange == 0) {
            return NO_OUTCOMES;
        }
        return ratedOutcomesInRange == 0 ? OUTCOMES_UNRATED : OUTCOMES_UNMATCHED;
    }
}
```

`CalibrationReport` gains two components after `outcomesInRange`:

```java
public record CalibrationReport(
        LocalDate from,
        LocalDate to,
        int outcomesInRange,
        int ratedOutcomesInRange,   // NEW
        int scoredPairs,
        CalibrationDataState state, // NEW
        CalibrationBucket overall,
        List<CalibrationBucket> byDaysAhead,
        List<CalibrationBucket> byModel) {
```

**And a Javadoc bug is fixed while we are here.** `CalibrationReport.java:21` documents `outcomesInRange` as *"recorded outcomes in the window **that carried a rating**"*. That is false — `ForecastCalibrationService.java:79` is `actualOutcomeRepository.findAllByOutcomeDateBetween(from, to).size()`, with no rating filter. The doc claim is precisely what would have concealed `OUTCOMES_UNRATED`: a reader seeing `outcomesInRange = 12, scoredPairs = 0` and trusting the Javadoc concludes the *join* is broken, and never looks at the write path. Fix the Javadoc to *"every recorded outcome in the window"* and let `ratedOutcomesInRange` carry the other meaning. (Fourth doc-vs-code divergence found; §2.6 continues to pay.)

`ForecastCalibrationService.report` changes minimally — the list at `:79` is retained instead of immediately sized:

```java
List<ActualOutcomeEntity> outcomes = actualOutcomeRepository.findAllByOutcomeDateBetween(from, to);
int ratedOutcomes = (int) outcomes.stream()
        .filter(o -> o.getActualRating() != null)
        .count();
CalibrationDataState state =
        CalibrationDataState.derive(outcomes.size(), ratedOutcomes, pairs.size());
```

No new query. The log line at `:90-95` gains `state={} ratedOutcomes={}` — **the entire operator-facing surface of this fix, and it costs one line.**

#### 8.3.5 What the endpoint returns at zero pairs

`GET /api/admin/calibration` (`CalibrationController.java:51-61`) keeps returning **200 with a full body**. Not 204, not 404.

```json
{
  "from": "2026-05-02", "to": "2026-07-31",
  "outcomesInRange": 0, "ratedOutcomesInRange": 0, "scoredPairs": 0,
  "state": "NO_OUTCOMES",
  "overall": { "key": "ALL", "sampleCount": 0,
    "meanRatingError": null, "meanAbsoluteRatingError": null,
    "exactMatchRate": null, "withinOneRate": null,
    "missedOpportunities": null, "wastedTrips": null,
    "meanAbsoluteFierySkyError": null },
  "byDaysAhead": [], "byModel": []
}
```

204 would be worse than the bug it replaces: an empty body makes "the instrument has no data" indistinguishable from "the request failed", and `state` — the most useful field in the payload — is exactly what a 204 cannot carry. Do not add `@JsonInclude(NON_NULL)`: an **absent** key reads as a missing field, a **null** key reads as an absent finding, and this fix is entirely about that difference.

#### 8.3.6 Frontend rendering

**There is none, and this design does not add one.** No admin view calls this endpoint; the deletion bias says do not build a view for a report read three times a year around a prompt change. The consumption surface is `curl` plus the `[CALIBRATION]` log line, which now carries `state`.

Recorded so nobody rediscovers it: if a `CalibrationView.jsx` is ever wanted, it renders `state` as the headline (`"No outcomes recorded in this window"` / `"12 outcomes recorded, none rated"` / `"4 rated outcomes, none matched a forecast"`), every null as an em-dash **never a zero**, and `missedOpportunities` with the veto-gate caption from `cloud-approach-veto-fix.md:275-278`.

---

### 8.4 Outcome recording — the missing 20 rows

`actual_outcome` has never held a row. The design's §7 diagnoses this as *"What is missing is the prompt to use it."* That is not what the tree says. **Three independent breaks** stand between a user and a row, and the third makes the first two moot.

#### Break 1 — the recording UI is unreachable

`frontend/src/components/OutcomeModal.jsx` (189 lines) is complete and correct. It is imported by exactly one file: `frontend/src/test/OutcomeModal.test.jsx:5`. `grep -rn "OutcomeModal" frontend/src` returns nothing else. `grep -rn "recordOutcome" frontend/src` returns only the modal (`:3,:35`), the API function (`api/forecastApi.js:348`), and the test's mock. **No user can open it.**

#### Break 2 — the read path is wired and consumed by nothing

`useForecasts.js` fetches every outcome over a backward 7-day window (`:118-137`) and attaches the result to each location as `.outcomes` (`:47`). `grep -rn "\.outcomes" frontend/src --include=*.jsx` (excluding tests) returns **zero** consumers. Every page load makes a `GET /api/outcome/all` request whose response is discarded. The comment at `:122-129` even reasons carefully about the window — for data nothing renders.

#### Break 3 — the write path cannot populate the column the gate joins on

This is the decisive one and it is not written down anywhere.

- The calibration join **requires** `o.actualRating IS NOT NULL` — `ForecastEvaluationRepository.java` JPQL, the `findCalibrationPairs` `@Query`, clause `AND o.actualRating IS NOT NULL`.
- `ActualOutcomeEntity.actualRating` exists (`entity/ActualOutcomeEntity.java`, `@Column(name = "actual_rating")`), and the column has existed since `V2__create_actual_outcome.sql:10`.
- The **request** DTO `ActualOutcome` (`model/ActualOutcome.java:24-33`) has **no rating field**: `locationLat, locationLon, locationName, outcomeDate, targetType, wentOut, fierySkyActual, goldenHourActual, notes`.
- `OutcomeService.record` (`service/OutcomeService.java`) builds the entity with nine `.field(...)` calls and **`.actualRating` is not among them**.
- The **response** DTO `ActualOutcomeDto` *does* expose `actualRating` (`model/ActualOutcomeDto.java`), and `from(entity)` maps it. So the read half is complete.

> **`POST /api/outcome` cannot, by construction, write the one column the calibration gate joins on.** Wiring the modal to a button would have produced rows that the instrument still reports as `scoredPairs = 0` — and, before §8.3, as `missedOpportunities = 0`, a measured finding of none from data that was recorded, stored, and structurally excluded. This is the selection-effect rule (§2.7) in its purest form: the population was selected by a field nobody noticed was never written.

No test ever crossed from write to read. `OutcomeServiceTest` asserts persistence; `ForecastCalibrationServiceTest` mocks the repository (`:56-58`). Both are green. **The gap is exactly the seam between them.**

#### The minimal change

**Backend — 4 edits, ~20 lines net.**

1. `model/ActualOutcome.java` — add `Integer actualRating` (name matches the response DTO, so request and response agree); change `boolean wentOut` → `Boolean wentOut`, so a one-tap flow is not forced to assert something it did not ask. The entity column is already nullable `Boolean`, and `CalibrationPair`'s Javadoc confirms `wentOut` is *"carried but not filtered on"*.
2. `service/OutcomeService.java` — add `.actualRating(outcome.actualRating())` to the builder, and:
   ```java
   /**
    * Validates the star rating is 1–5 if present. Null is allowed: the calibration join
    * excludes unrated outcomes rather than rejecting them, so a note-only observation is
    * still worth keeping.
    *
    * @param value the rating, or {@code null}
    * @throws IllegalArgumentException if outside 1–5
    */
   private void validateRating(Integer value) {
       if (value != null && (value < STAR_MIN || value > STAR_MAX)) {
           throw new IllegalArgumentException("actualRating must be between 1 and 5");
       }
   }
   ```
3. Nothing else. `OutcomeController.record` (`:84-88`) and `ActualOutcomeDto` are already correct.

**Frontend — delete the modal, put five stars in the popup.**

Delete `OutcomeModal.jsx` (189) and `OutcomeModal.test.jsx` (123). Add `components/shared/OutcomeStars.jsx` (~55 lines): five tappable stars, POSTs on tap, renders read-only when an outcome already exists.

Render site: **`MarkerPopupContent.jsx`**, which needs no new data. It already receives `location` (the full `buildLocations` object, passed as `location={loc}` at `MapView.jsx:1229` and `:1313`, so `.outcomes` is present), `date`, `eventType`, and `role` (`MarkerPopupContent.propTypes`). The map already reaches past dates — `ForecastController.PAST_WINDOW_DAYS = 2` (`:85`) and `DateStrip.jsx:7-8` dims them with a divider at `:48`. So:

```jsx
{date < todayStr && (
  <OutcomeStars
    outcome={(location.outcomes ?? []).find(
      (o) => o.outcomeDate === date && o.targetType === eventType)}
    location={location} date={date} eventType={eventType} onSaved={onOutcomeRecorded}
  />
)}
```

**Why this is the change most likely to produce twenty rows.** It removes every unit of friction between seeing a past slot and recording it: no button to find, no modal to open, no dialog state, no required "did you go out?", no two 0–100 sliders (which are harder to answer than a star and feed nothing the gate reads). One tap on a surface the user is already on. And it makes `.outcomes` — fetched on every page load since it was written — finally render.

**What is deliberately lost, and how to bring it back.** With the modal gone, `notes`, `fierySkyActual` and `goldenHourActual` have no UI. `CalibrationBucket.meanAbsoluteFierySkyError` will therefore report **null** — correctly, under the same rule as §8.3, since `of` already skips pairs missing either score (`:89-92`). Bringing it back is a disclosure under the star row wired to the same POST; the request DTO fields, `validateScore`, and the entity columns all survive untouched, so it is UI-only. Do not restore it pre-emptively: a five-star tap that also demands two sliders is the flow that produced zero rows.

**Role gating:** none. `POST /api/outcome` carries no `@PreAuthorize` and CLAUDE.md's Roles table makes this explicit and deliberate — *"observations are the scarce input"*. `OutcomeStars` must render for `LITE_USER` too. Do not grey it.

---

### 8.5 Tests

**Backend.** JaCoCo is 80% line coverage per class, so the two new enums carry full-branch tests rather than one happy path.

| Class | Test | Asserts |
|---|---|---|
| `CalibrationBucketTest` | `of_emptyBucket_reportsNullDecisionCounts` | extends `:105-116`: `missedOpportunities()` and `wastedTrips()` are **null**, `sampleCount()` still **0** |
| `CalibrationBucketTest` | `of_noDecisionErrorsOverRealPairs_reportsZeroNotNull` | the discrimination test — `of("ALL", List.of(pair(3,3)))` returns **0**, not null. Without it, "null on empty" is satisfiable by always returning null |
| `CalibrationDataStateTest` | `derive_noOutcomes_isNoOutcomes` / `derive_outcomesWithoutRatings_isUnrated` / `derive_ratedButUnjoined_isUnmatched` / `derive_anyPair_isScored` | all four branches |
| `ForecastCalibrationServiceTest` | `report_outcomesRecordedWithoutRatings_reportsUnratedState` | **the test that would have caught the live bug** — 12 outcomes with `actualRating == null`, zero pairs → `OUTCOMES_UNRATED`, `ratedOutcomesInRange == 0` |
| `ForecastCalibrationServiceTest` | `report_zeroPairs_reportsNullDecisionCountsNotZero` | `overall().missedOpportunities()` is null |
| `CalibrationControllerTest` | `getCalibration_zeroPairs_returns200WithExplicitNullsAndState` | `status().isOk()`, `jsonPath("$.overall.missedOpportunities").value(nullValue())`, `jsonPath("$.state").value("NO_OUTCOMES")`. Uses `value(nullValue())` **not** `doesNotExist()` — the key must be present |
| `OutcomeServiceTest` | `record_persistsActualRating` | captor asserts `getActualRating() == 4` — the regression pin on the hole |
| `OutcomeServiceTest` | `record_nullRating_persistsNull` | the guard's null branch, for JaCoCo, with a real assertion |
| `OutcomeServiceTest` | `record_ratingOutsideOneToFive_throws` | 0 and 6 |
| **`CalibrationJoinIntegrationTest`** *(new, extends `IntegrationTestBase`)* | `recordedOutcomeWithRating_appearsAsAScoredPair` | **the seam test.** Inserts a rated `forecast_evaluation` row, `POST /api/outcome` with `actualRating`, `GET /api/admin/calibration` → `scoredPairs == 1`, `state == SCORED`. Postgres 17 Testcontainer, so the real JPQL join runs. *No test has ever crossed this seam; that is why the hole survived.* |
| `ConfidenceDeriverTest` | `stalenessOf_nullProducedAt_isNone` / `_within24h_isFresh` / `_beyond24h_isStale` | all three branches |
| `ConfidenceDeriverTest` | `derive_staleScores_downgradesOneBand` | T+0 HIGH + STALE → MEDIUM |
| `ConfidenceDeriverTest` | `derive_staleAtLow_staysLow` | the floor holds |
| `ConfidenceDeriverTest` | `derive_unknownProducedAtWithScores_doesNotDowngrade` | **NONE ≠ STALE** — legacy payload keeps its tier |
| `EvaluationViewServiceTest` | `getScoresForEnrichmentBulkWithAge_reportsOldestInstantAcrossSlots` | min, not max |
| `EvaluationViewServiceTest` | `getScoresForEnrichmentBulkWithAge_zonesForecastRunAtAsLondon` | pins `:539-544`'s BST rule against the new path |
| `BriefingServiceTest` | `enrich_staleRegion_downgradesConfidenceAndLeavesRatingsUntouched` | server-side "stars never touched" |

**Frontend.**

| File | Test |
|---|---|
| `HeatmapGrid.test.jsx` | `'a stale region dims the verdict fill and leaves the star pill byte-identical'` — compares the `mean-score-badge` `style` attribute across a `high` and `low` render |
| `HeatmapGrid.test.jsx` | `'the provisional marker title carries the score age when present'` |
| `OutcomeStars.test.jsx` | `'posts the tapped rating'`, `'renders read-only when an outcome exists'`, `'renders for a LITE_USER'` |
| `MarkerPopupContent.test.jsx` | `'a past date with no recorded outcome offers the star row'`, `'a past date with a recorded outcome shows it read-only'`, `'a future date shows neither'` |
| `DispositionLine.test.jsx` | `'renders the reconciliation line'`, `'expands only when an anomaly category is non-zero'`, `'renders an unrecognised legacy category with a generic label'` |
| **Delete** | `OutcomeModal.test.jsx` (123); the 3 optimisation cases in `ModelSelectionView.test.jsx` (`:88`, `:141-160`, `:205`, `:222`) and its `updateOptimisationStrategy` mock (`:9`, `:20`); `StabilityControllerTest.java` (87); `OptimisationSkipEvaluatorTest` (368); `OptimisationStrategyServiceTest` (216); `FreshnessResolverTest` (91) |

---

### 8.6 Sequencing within Phase 0

Both §8.3 and §8.4 are Phase 0 items and must ship **in this order**, in one branch:

1. `CalibrationBucket` `int → Integer`, `CalibrationDataState`, the `CalibrationReport` fields, the Javadoc fix, the log line. No consumer breaks (§8.3.3), so this is independently shippable today.
2. `ActualOutcome.actualRating` + `OutcomeService` + `CalibrationJoinIntegrationTest`. Without step 1, step 2's first rows would arrive at an instrument that still reports a measured zero beside them.
3. `OutcomeStars` in the map popup; delete `OutcomeModal`.

§8.1 (staleness) ships with Phase 3, in the same commit that deletes `FreshnessProperties` — re-home the threshold as it dies, per §5. §8.2 (UI shrink) stays in Phase 5.
