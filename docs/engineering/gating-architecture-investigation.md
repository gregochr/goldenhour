# Gating architecture investigation

Status: investigation only. Written 2026-05-18 against `main` at `cf6d8ec` (v2.11.16). No production code modified.

This document maps the four gates that decide whether Claude evaluates a (location, date, target_type) tuple in the overnight batch path, compares them to the April 22 2026 design intent, and recommends a redesign approach. It is the basis for a follow-up implementation prompt — it does not propose code changes itself.

---

## TL;DR

- **Gate 2 and Gate 3 share the same numeric thresholds but operate on different data.** Production data (559 Gate 3 firings in the trailing 7 days, ~80/day) shows they are not redundant in practice: 93% of Gate 3 catches come from the 3-point directional cone at the solar horizon disagreeing with the briefing's 1-point horizon sample (typically 93-99% cone reading where the briefing said < 70%); 7% come from weather evolving between briefing build (04/14/22 UTC) and batch fire (03/15 UTC). Different data, same policy. See Section 3.
- **The verdict gate has a labelling bug that explains the Tyne and Wear puzzle.** A slot can be STANDDOWN when the only signal pushing it there is horizon low cloud at the 113 km solar-azimuth sample, while observer-overhead cloud is 0% at every layer. `deriveStanddownReason` doesn't consider horizon cloud, so it falls through to "Clear sky — no canvas". The verdict is consistent; the reason label is wrong.
- **The cache freshness gate is per-region, and the batch result writer REPLACES rather than merges.** Result: locations dropped by later gates one cycle leave the region's cache fresh-but-thin, and the next cycle's Gate 1 skips the whole region. The granularity bug is real, though its impact depends on how often Gate 3/4 drops persist across cycles.
- **Gate 4 (stability) blocks T+1 evaluation for ~22% of cells today** because UNSETTLED → maxDays=0. The April 22 design said T+1 should always evaluate; the current `evaluationWindowDays` mapping silently contradicts that for active-weather cells.
- **SETTLED is functionally unreachable under UK May conditions.** The classifier requires score < 2; observed signals routinely push it to ≥ 4. T+2 / T+3 batched evaluation is therefore ~never happening — not because of a bug, but because the classifier's calibration treats "stable enough for 3-day forecast" as a rare event.

What surprised me most: the four gates separate more cleanly than I expected, but not in the way I first thought. My initial read called Gate 3 redundant with Gate 2 — that was wrong on the production evidence (559 catches in 7 days, dominated by the 3-point cone catching what the briefing's 1-point horizon sample missed). The honest picture is: two real gates with distinct data (Gate 2 verdict at briefing time, Gate 3 triage at batch time with deeper sampling), one labelling bug hiding the real cause of clear-sky STANDDOWNs, one stability gate silently encoding a depth policy that conflicts with the stated product intent, and one cache layer with a granularity defect. The Gate 3 question is no longer "is it redundant" but "is the Claude spend it saves worth the gate's complexity, or should those slots reach Claude per the April 22 design principle". The saving today is ~$2.40/month (current Haiku-for-everything pricing for the ~80 calls/day Gate 3 prevents); after the April 22 redesign moves near-term to Sonnet, the same prevented calls would have cost ~$24/month. Either way it's a product call.

---

## Section 1 — Data flow: cron → Anthropic batch submission

### Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│  CRON: scheduler_job_config                                         │
│  - daily_briefing             0 0 4,14,22 * * * UTC                 │
│  - near_term_batch_evaluation 0 0 3,15 * * * UTC                    │
│  - aurora_batch_evaluation    0 30 3 * * * UTC                      │
└────────────────────┬────────────────────────────────────────────────┘
                     │
                     ▼ (DynamicSchedulerService.fireJob)
┌─────────────────────────────────────────────────────────────────────┐
│  ScheduledBatchEvaluationService.submitForecastBatch()              │
│  - AtomicBoolean forecastBatchRunning guard                         │
│  - calls forecastTaskCollector.collectScheduledBatches()            │
└────────────────────┬────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ForecastTaskCollector.collectScheduledBatches()                    │
│                                                                     │
│  1. briefingService.getCachedBriefing()                             │
│  2. collectForecastCandidates(briefing)         ← Gate 1, Gate 2    │
│  3. prefetchBatchWeather(candidates)                                │
│  4. prefetchBatchCloudPoints(...)                                   │
│  5. classifyGridCellsAndPublishSnapshot(...)    ← writes snapshot   │
│  6. for each candidate:                                             │
│       forecastService.fetchWeatherAndTriage(...)  ← Gate 3 inside   │
│       getStabilityWindowDays(...)                  ← Gate 4 inside  │
│       → bucket near/far × inland/coastal                            │
└────────────────────┬────────────────────────────────────────────────┘
                     │
                     ▼ (4 buckets, each submitted separately)
┌─────────────────────────────────────────────────────────────────────┐
│  EvaluationService.submit(tasks, SCHEDULED)                         │
│  → BatchSubmissionService → Anthropic Batch API                     │
│  → forecast_batch row created (status=SUBMITTED)                    │
└─────────────────────────────────────────────────────────────────────┘

                  ⋮  asynchronous, ~minutes to hours later  ⋮

┌─────────────────────────────────────────────────────────────────────┐
│  BatchResultProcessor (poll loop, fixed-delay 60s)                  │
│  - fetches completed batches from Anthropic                         │
│  - per-result: ForecastResultHandler.handleSuccess(...)             │
│  - groups by region cacheKey, flushCacheKey(...) per region         │
└────────────────────┬────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────────┐
│  BriefingEvaluationService.writeFromBatch(cacheKey, results)        │
│  - REPLACES in-memory cache for cacheKey with new results           │
│  - persistToDb → upserts cached_evaluation row                      │
└────────────────────┬────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────────┐
│  UI consumption                                                     │
│  - Next briefing refresh (04:00/14:00/22:00) hydrates BriefingSlot  │
│    via BriefingService.enrichWithCachedScores                       │
│  - GET /api/briefing returns BriefingSlot with claudeRating         │
│    populated from cached_evaluation                                 │
│  - Plan tab heatmap colours by region.verdict (GO/MARGINAL/         │
│    STANDDOWN); drill-down shows slot.claudeRating when present      │
│  - On user-initiated "Run full forecast", SSE evaluateRegion runs   │
│    Claude inline (still Gate 2-filtered: GO/MARGINAL only)          │
└─────────────────────────────────────────────────────────────────────┘
```

### Numbered stages with file:line references

1. **Cron tick** — `DynamicSchedulerService` fires the `near_term_batch_evaluation` job per [V73__forecast_batch.sql:20-24](../../backend/src/main/resources/db/migration/V73__forecast_batch.sql) cron `0 0 3,15 * * *`. (Seeded as `PAUSED`; the user reports this has been enabled via the Scheduler admin UI in production.)
2. **Concurrency guard** — [ScheduledBatchEvaluationService.submitForecastBatch():140](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ScheduledBatchEvaluationService.java) `compareAndSet(false, true)` on `forecastBatchRunning`.
3. **Delegate to collector** — [ScheduledBatchEvaluationService.doSubmitForecastBatch():207-237](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ScheduledBatchEvaluationService.java) calls `forecastTaskCollector.collectScheduledBatches()`, then submits each non-empty bucket via `evaluationService.submit(...)`.
4. **Read briefing** — [ForecastTaskCollector.collectScheduledBatches():166-179](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) reads `briefingService.getCachedBriefing()` (built by the 04/14/22 UTC `daily_briefing` cron at [V68__scheduler_job_config.sql:25](../../backend/src/main/resources/db/migration/V68__scheduler_job_config.sql)).
5. **Collect candidates** — [ForecastTaskCollector.collectForecastCandidates():408-490](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) iterates briefing slots and applies **Gate 1** (`hasFreshEvaluation`, line 451, per region) and **Gate 2** (`slot.verdict()`, line 463, per slot).
6. **Prefetch weather** — [ForecastTaskCollector.prefetchBatchWeather():502-516](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) bulk-fetches Open-Meteo via `prefetchWeatherBatchResilient`. Aborts batch if `minPrefetchSuccessRatio` (default 0.5) is missed.
7. **Prefetch cloud points** — [ForecastTaskCollector.prefetchBatchCloudPoints():518-568](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) augments with directional cloud samples.
8. **Classify grid cells + publish snapshot** — [ForecastTaskCollector.classifyGridCellsAndPublishSnapshot():604-655](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) (post v2.11.15) classifies every unique grid cell touched by the candidates and writes `stability_snapshot`.
9. **Triage loop** — [ForecastTaskCollector.collectScheduledBatches():241-302](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) per-candidate loop applies **Gate 3** (`preEval.triaged()`, line 244) and **Gate 4** (`daysAhead > maxDays`, line 254).
10. **Bucket and submit** — same loop classifies each surviving task into `nearInland / nearCoastal / farInland / farCoastal` (line 264-290). [ScheduledBatchEvaluationService.doSubmitForecastBatch():213-228](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ScheduledBatchEvaluationService.java) submits each non-empty bucket.
11. **Async batch processing** — `BatchResultProcessor` polling job (registered at [V73__forecast_batch.sql:32-36](../../backend/src/main/resources/db/migration/V73__forecast_batch.sql), fixed-delay 60s) fetches completed batches.
12. **Per-result handling** — `ForecastResultHandler.handleSuccess` (referenced in flow at [ForecastResultHandler.java:98 + :132-153](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java)) returns a `BatchSuccess(cacheKey, result)`. Caller groups by `cacheKey` and calls `flushCacheKey(cacheKey, results)`.
13. **Cache write** — [ForecastResultHandler.flushCacheKey():173-175](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java) → [BriefingEvaluationService.writeFromBatch():328-337](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) REPLACES the in-memory cache for `cacheKey` and upserts the `cached_evaluation` row.
14. **UI hydration** — Next `daily_briefing` refresh calls [BriefingService.enrichWithCachedScores():401-437](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java); each `BriefingSlot` gets `claudeRating / claudeSummary / fierySkyPotential / goldenHourPotential` populated from the cache.
15. **UI consumption** — `GET /api/briefing` returns the enriched response. Plan tab colours cells by `region.verdict`; drill-down shows `slot.claudeRating` when present; user-initiated SSE evaluation at [BriefingEvaluationService.evaluateRegion():148-253](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) only iterates GO/MARGINAL slots ([:627](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) `verdict == GO || verdict == MARGINAL`).

### Critical observation about cache write semantics

`writeFromBatch` does NOT merge with the prior cache entry. It builds a fresh `ConcurrentHashMap` from the supplied `results` list and replaces the entry wholesale ([BriefingEvaluationService.java:330-334](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java)). Combined with Gate 1's per-region freshness check, this means: if cycle N produces results for 5 of a region's 15 GO/MARGINAL slots (because Gate 3/4 dropped the other 10), the region's cache becomes "fresh with 5 results". Cycle N+1's Gate 1 then short-circuits the entire region — the 10 dropped slots never get re-tried even if conditions evolved.

---

## Section 2 — Verdict gate: where do STANDDOWN verdicts come from?

### Construction site

`BriefingSlot.verdict()` is set by [BriefingSlotBuilder.buildSlot():88-197](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingSlotBuilder.java). The builder is invoked from [BriefingService.refreshBriefing():270-283](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java) once per (location, date, eventType) combination during the briefing build, three times daily via the `daily_briefing` cron. The verdict is **stored** on the slot, not recomputed lazily.

### Verdict pipeline (in order)

Each stage can demote but never promote. Source: [BriefingSlotBuilder.java:134-168](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingSlotBuilder.java).

| Stage | Method | Inputs | Effect |
|-------|--------|--------|--------|
| 1 | `determineVerdict` | low cloud, precip, visibility, humidity | Base verdict |
| 2 | `applyMidCloudDemotion` | mid cloud at event hour | GO/MARGINAL → MARGINAL/STANDDOWN |
| 3 | `applyCloudTrendDemotion` | low cloud T-2h, T-1h, T | GO → MARGINAL on BUILDING trend |
| 4 | `applyClearSkyDemotion` | low, mid, high cloud at event hour | GO → MARGINAL when all < 15% |
| 5 | `applyHorizonCloudDemotion` | horizon low cloud at 113 km, solar azimuth | GO/MARGINAL → MARGINAL/STANDDOWN |
| 6 | Coastal tide override | tide aligned, tide state | Non-STANDDOWN → STANDDOWN if coastal tide misaligned |

### Threshold table (from [BriefingVerdictEvaluator.java:27-64](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java))

| Signal | STANDDOWN | MARGINAL |
|--------|-----------|----------|
| Low cloud (observer point) | > 80% | > 50% |
| Precipitation | > 2.0 mm | > 0.5 mm |
| Visibility | < 5000 m | < 10 000 m |
| Humidity | — | > 90% |
| Mid cloud (observer point) | ≥ 80% | ≥ 60% (only demotes GO) |
| All three layers low | — | < 15% on all (only demotes GO) |
| Building cloud trend | — | peak ≥ 40% AND increasing (only demotes GO) |
| Horizon low cloud (113 km solar) | ≥ 70% | ≥ 40% (only demotes GO) |
| Coastal tide misaligned | override → STANDDOWN | — |

### Semantic definition of STANDDOWN

A slot is STANDDOWN if **any** of:

1. Overhead low cloud > 80% at the event hour, **or**
2. Precipitation > 2.0 mm at the event hour, **or**
3. Visibility < 5 km at the event hour, **or**
4. Mid-level cloud ≥ 80% at the event hour (grey ceiling), **or**
5. Horizon low cloud at 113 km along the solar azimuth ≥ 70% (sun blocked at the horizon), **or**
6. Coastal location AND tide data exists AND tide is not aligned to the location's preferred tide type.

There is **no path from "clear sky" alone to STANDDOWN**. The clear-sky demotion ([BriefingVerdictEvaluator.applyClearSkyDemotion():244-256](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java)) only demotes GO → MARGINAL.

### Concrete example: Tyne and Wear / 2026-05-17 / SUNRISE

Gloss text: "All cloud layers absent, 0% coverage at every level". Verdict: STANDDOWN.

The only verdict path consistent with this is **(5) horizon low cloud ≥ 70%**. The 113 km solar-azimuth sample point can carry very different cloud conditions from the observer location — the Tyne and Wear coast can be 0% overhead while a band of low cloud sits over the North Sea 113 km to the east-northeast at the sunrise azimuth. That's not a bug in the verdict logic; it's the design responding to "the sun will rise behind a wall of low cloud you can't see from where you're standing".

However: the **labelling** is wrong. [BriefingVerdictEvaluator.deriveStanddownReason():343-369](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) checks STANDDOWN reasons in this order:

1. low cloud > 80 → "Heavy cloud"
2. mid cloud ≥ 80 → "Overcast"
3. precip > 2.0 → "Rain"
4. visibility < 5000 → "Poor visibility"
5. building trend → "Building cloud"
6. tide misaligned → "Tide mismatch"
7. all three layers < 15 → "Clear sky — no canvas"
8. fallback → "Poor conditions"

**The function does not check horizon cloud at all.** So when (5) is the only STANDDOWN trigger and overhead conditions are clear, the label falls through to (7) "Clear sky — no canvas" — which then propagates into the gloss generator and produces the misleading "0% at every level" narrative. The `SUN_BLOCKED_HORIZON` enum value exists at [BriefingVerdictEvaluator.java:95](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) but is never actually selected by `deriveStanddownReason`.

This is a real, isolated bug worth fixing independently of any redesign.

> **For the record:** hotfix shipped in v2.11.17 — commit [`d39b520`](https://github.com/gregochr/goldenhour/commit/d39b520) wires `SUN_BLOCKED_HORIZON` into `deriveStanddownReason` between `TIDE_MISMATCH` and `CLEAR_SKY` using the same 70% threshold as `applyHorizonCloudDemotion`. Verdict behaviour unchanged; only the reason label is now accurate.

### Concrete example: Lake District / 2026-05-18 / SUNRISE

User reports widespread STANDDOWN with 100% precip probability. This is consistent with path (2) precipitation > 2.0 mm — the most common STANDDOWN trigger in active-weather conditions. The verdict reads correctly here.

### Is "clear sky → STANDDOWN" intentional?

No: the code is explicit that clear sky only demotes GO → MARGINAL. The apparent "clear sky → STANDDOWN" pattern users see is the labelling bug above. The underlying verdict mechanic is OK; it's `deriveStanddownReason` that lies about why.

### Verdict cases where Claude should definitely have been consulted

Two categories deserve highlighting:

1. **Horizon-cloud STANDDOWNs with clear overhead.** A 5★ "burning sky" reading is impossible if the sun is blocked at the horizon, but a 3★ "soft pastels overhead" might still be worth chasing if the photographer cares about the zenith more than the horizon. Claude could surface this nuance; the gate denies it.
2. **All-layers-clear MARGINALs (post clear-sky demotion).** These slots reach Claude as MARGINAL today, which is correct — but they're at the bottom of the priority list in dense modals, and users may not click through. UI presentation issue, not a gate issue.

The dominant skipped category — STANDDOWNs due to overhead low cloud > 80, precip > 2.0, or visibility < 5 km — represents genuinely unsuitable conditions where Claude evaluation is hard to defend as cost-effective. The April 22 design's intent to "evaluate everything" is most defensible for the borderline cases (mid-cloud demotion to STANDDOWN at exactly 80%, horizon-cloud at exactly 70%) where Claude's nuanced reading could revise the call.

---

## Section 3 — Triage gate: relationship to verdict gate

### What `preEval.triaged()` evaluates

[ForecastService.fetchWeatherAndTriage():363-381](../../backend/src/main/java/com/gregochr/goldenhour/service/ForecastService.java) calls `weatherTriageEvaluator.evaluate(forecastData)`, which has three rules in priority order ([WeatherTriageEvaluator.java](../../backend/src/main/java/com/gregochr/goldenhour/service/WeatherTriageEvaluator.java)):

| Rule | Threshold | Source |
|------|-----------|--------|
| Low cloud at solar horizon | > 80% | `directionalCloud.solarLowCloudPercent()` (3-point cone at 113 km) — falls back to observer-point if directional unavailable |
| Precipitation | > 2.0 mm | `weather.precipitationMm()` (observer point) |
| Visibility | < 5000 m | `weather.visibilityMetres()` (observer point) |

A separate optional rule fires for SEASCAPE locations with the TIDE_ALIGNMENT optimisation strategy enabled ([ForecastService.java:386-413](../../backend/src/main/java/com/gregochr/goldenhour/service/ForecastService.java)) — but the strategy isn't currently active for the scheduled batch path (see `tideAlignmentEnabled = false` at [ForecastTaskCollector.java:242](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java)).

### Relationship to Gate 2

| Aspect | Gate 2 (verdict) | Gate 3 (triage) |
|--------|------------------|-----------------|
| Where | Briefing build (cron) | Per-task in batch collector |
| Data freshness | Briefing's prefetched forecast (≤ 6h old) | Current prefetched forecast (≤ minutes old) |
| Low cloud threshold | > 80 (STANDDOWN); > 50 (MARGINAL) | > 80 (only) |
| Low cloud sample | Observer overhead AND 1-point horizon at 113 km | 3-point cone at 113 km solar (prefers directional, falls back to observer overhead) |
| Precip threshold | > 2.0 (STANDDOWN); > 0.5 (MARGINAL) | > 2.0 (only) |
| Precip sample | Observer point | Observer point |
| Visibility threshold | < 5000 (STANDDOWN); < 10 000 (MARGINAL) | < 5000 (only) |
| Mid cloud check | Yes (≥ 80 STANDDOWN, ≥ 60 MARGINAL) | No |
| Building trend | Yes | No |
| Clear-sky demotion | Yes | No |
| Tide override | Coastal misalignment → STANDDOWN | No (unless TIDE_ALIGNMENT enabled) |
| Marginal-tier checks | Yes (≥ 50 cloud etc.) | No |

**Gate 3 thresholds are an exact subset of Gate 2's STANDDOWN-tier thresholds.** The numeric policy is identical. The differences are:

1. **Sampling shape for low cloud.** Gate 3 uses a 3-point cone averaged at the solar horizon. Gate 2 uses observer-overhead PLUS a single horizon sample. Different data, same threshold.
2. **Data recency.** Gate 3 sees fresher weather (prefetched at batch-time, minutes old) vs Gate 2's briefing-time fetch (up to 6 hours old at the next batch fire). A briefing built at 04:00 UTC informs the batch at 15:00 UTC.

### What Gate 3 catches that Gate 2 doesn't — production evidence

My initial reasoning was that Gate 3 was near-redundant — same thresholds as Gate 2's STANDDOWN tier, only the sampling shape differs. A production query against `forecast_evaluation` (rows where `triage_reason IS NOT NULL`, trailing 7 days, 2026-05-12 → 2026-05-18) revised that:

| `triage_reason` | Firings | Unique locations | Unique dates |
|---|---|---|---|
| `HIGH_CLOUD` | 512 | 195 | 9 |
| `LOW_VISIBILITY` | 40 | 36 | 3 |
| `PRECIPITATION` | 7 | 7 | 4 |
| **Total** | **559** | — | — |

That's ~80 firings per day across the fleet. Every firing is, by construction, a slot that Gate 2 said GO/MARGINAL for and Gate 3 then triaged.

**Breakdown of `HIGH_CLOUD` firings by sample source** (parsing `triage_message`):

| Sample source | Firings |
|---|---|
| `directional_cone_solar` (3-point cone at 113 km along solar azimuth) | 512 |
| `observer_lowcloud_fallback` (when directional unavailable) | 0 |

All 512 came from the directional cone. The fallback never fired in 7 days, meaning the prefetched directional cloud was always available.

**Sample reasons from recent rows:**

- `Solar horizon low cloud 99% — sun blocked` (T+1, location 230)
- `Solar horizon low cloud 97% — sun blocked` (T+1, location 73)
- `Solar horizon low cloud 95% — sun blocked` (T+1, location 229)
- `Visibility 2240 m — poor visibility` (T+2, location 240)
- `Visibility 3200 m — poor visibility` (T+2, locations 195/196/199)
- `Precipitation 3.30 mm — active rain` (T+1, location 95)
- `Precipitation 2.20 mm — active rain` (T+1, location 231)

The cone readings are not marginal — they sit at 93-99%, while the briefing's horizon STANDDOWN threshold is 70%. The briefing's single-point sample at the same 113 km offset registered < 70% for the same slots; the 3-point cone average sees the cloud structure the single point sampled around.

### Two distinct catch categories

**(A) Sampling-shape disagreement on horizon cloud — 512 firings, 93% of Gate 3 catches.**

The briefing's `fetchHorizonCloud` samples a single point at 113 km on the solar azimuth. The triage path's `directionalCloud.solarLowCloudPercent` averages a 3-point cone (azimuth ± 15°) at the same offset. In dense or banded cloud the two methods disagree materially — the single point lands in a gap while the cone average sits at 93-99%. This catch is structurally addressable by upgrading the briefing's horizon sampling to the same 3-point cone (i.e. moving the deeper sampling earlier in the pipeline so Gate 2 sees it).

**(B) Weather evolution between briefing build and batch fire — 47 firings, 7% of Gate 3 catches.**

`LOW_VISIBILITY` and `PRECIPITATION` rules use the same observer-point data the briefing already saw. Gate 3 can only fire on these rules when the weather forecast has materially evolved between briefing build (04:00 / 14:00 / 22:00 UTC) and batch fire (03:00 / 15:00 UTC) — up to 11 hours of model evolution. The sample data shows visibility readings of 2240-3440 m and precip of 2.20-3.30 mm, both well past their thresholds — these are real degradations, not threshold-edge cases. This catch is NOT addressable by sampling upgrades; it requires either refreshing the briefing closer to the batch fire, or accepting that Gate 3 (or some equivalent post-prefetch check) does this job.

### Conclusion

Gate 3 is **not** redundant with Gate 2 on the production evidence. It performs real work that Gate 2 cannot replicate without either (a) upgrading the briefing's horizon sampling to a 3-point cone AND refreshing the briefing pre-batch, or (b) accepting the loss of ~80 catches/day. At current Haiku-for-everything pricing the saving is ~**$2.40/month** ; if the April 22 redesign moves near-term to Sonnet, the same ~80 calls/day would cost ~**$24/month**. Small in absolute terms; earned. See Section 7 for the three options on how to handle it.

---

## Section 4 — Cache freshness gate: granularity question

### Granularity is per-region — confirmed

[ForecastTaskCollector.collectForecastCandidates():444-460](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java):

```java
String cacheKey = CacheKeyFactory.build(region.regionName(), date, targetType);
...
if (briefingEvaluationService.hasFreshEvaluation(cacheKey, freshness)) {
    cachedByStability.get(regionStability)[0] += regionSlots;
    skippedCache += regionSlots;
    totalSlots += regionSlots;
    continue;  // entire region for this date+event skipped
}
```

The cache key is `regionName|date|targetType` (one per region per date per event). `hasFreshEvaluation` ([BriefingEvaluationService.java:310-316](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java)) returns true iff the entry exists, has at least one result, AND was last evaluated within `maxAge`. The presence of ANY result for ANY location in the region within the window means the entire region is skipped.

### Structure of a cached_evaluation row

One row per `(region_name, date, target_type)`. Per-location results live inside a `results_json` blob ([BriefingEvaluationService.persistToDb():552-571](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java)). The in-memory cache mirrors this: a `ConcurrentHashMap<String, BriefingEvaluationResult>` of locationName → result, wrapped in `CachedEvaluation(results, evaluatedAt)`.

### The 19-of-20 question

> If 19 of a region's 20 locations have stale evaluations and 1 has a fresh one, are the 19 forever stuck on stale data?

**Yes, until the 1 expires** — but with an important nuance about how that 1 entry got there.

The cache only ever holds GO/MARGINAL slot results (because Gate 2 filters STANDDOWN before they reach the batch). And `writeFromBatch` REPLACES the entry, doesn't merge ([BriefingEvaluationService.java:330-334](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java)). So the realistic failure path is:

- Cycle N: region has 15 GO/MARGINAL slots. Gate 3 drops 10 (sub-threshold weather degradation between briefing and batch). Gate 4 drops 0. Batch evaluates 5. Cache for the region now has 5 fresh results, ages 0 hours.
- Cycle N+1 (12 hours later): Gate 1 sees the region as fresh (TRANSITIONAL threshold = 12h, equal to the cycle interval, so depending on timing it might just clear). Even if it does, the freshness check `evaluatedAt.isAfter(Instant.now().minus(maxAge))` uses strict-after — so a 12h-old entry won't be considered fresh, but an 11h50m-old entry will.

In practice the cycle interval is 12 hours and the threshold for TRANSITIONAL is also 12 hours, so Gate 1's actual blocking impact on TRANSITIONAL regions is minor — entries just barely expire in time for the next cycle. The granularity bug is more acute for SETTLED (36h, unreachable today) and UNSETTLED (4h, well under the 12h cycle).

The bigger picture issue is: locations dropped by Gate 3/4 last cycle never re-enter the batch this cycle while the cache is fresh, even if weather has improved enough that they'd survive triage now. The replacement-not-merge semantic compounds this — there's no incremental refresh.

### Freshness thresholds

From [FreshnessProperties.java](../../backend/src/main/java/com/gregochr/goldenhour/config/FreshnessProperties.java) + [application-example.yml](../../backend/src/main/resources/application-example.yml):

| Stability | Threshold | Rationale (per source comments) |
|-----------|-----------|---------------------------------|
| SETTLED | 36 h | Blocking highs persist 4-5+ days; once-daily refresh rhythm |
| TRANSITIONAL | 12 h | Half a synoptic update cycle; 2× the NWS 6h operational cadence |
| UNSETTLED | 4 h | Outer edge of the nowcasting regime |
| Safety floor | 2 h | Absolute minimum regardless of stability |

These are reasonable for synoptic-scale forecast skill, but **the batch cron fires every 12 h** (03:00 and 15:00 UTC). So:

- SETTLED (36 h): could skip 3 consecutive batch cycles. Fine if conditions are truly settled.
- TRANSITIONAL (12 h): aligns with the cycle period — entries expire just in time. Borderline; might cause flap behaviour where a region alternates between "fresh skip" and "evaluate".
- UNSETTLED (4 h): expires well before the next batch — Gate 1 never blocks UNSETTLED regions.

### Is 12 h appropriate for T+1?

Weather model evolution between consecutive runs (ECMWF / GFS) can shift T+1 forecast detail significantly — cloud bands move, precipitation timing slides, ridge/trough positions update. The TRANSITIONAL=12 h threshold means the user looking at T+1 at 14:00 UTC sees Claude's read from the 03:00 batch using a briefing built at 22:00 UTC the day before — up to 16 hours of model-evolution lag in the worst case. For "what should I do tomorrow morning?" — borderline. For "what should I do this evening?" (same-day T+0) — possibly stale.

### Recommendation summary

Two separate issues that should be addressed together:

1. **Per-location granularity.** Replace the per-region freshness check with per-location: walk the slot list, partition into "fresh" and "needs-refresh", batch only the latter. Eliminates the replace-not-merge problem.
2. **Recalibrate thresholds.** TRANSITIONAL=12 h is too coarse for T+1 / T+0 forecasts that are being acted on within hours. Consider tier-by-days-ahead: T+0 uses 4 h regardless of stability; T+1 uses 6-8 h; T+2/T+3 can stay at 12-36 h depending on stability.

---

## Section 5 — Stability gate: depth calibration

### What produces SETTLED

[ForecastStabilityClassifier.classify():60-88](../../backend/src/main/java/com/gregochr/goldenhour/service/ForecastStabilityClassifier.java) sums signal scores from four assessments and applies the thresholds:

```java
if (score >= UNSETTLED_THRESHOLD)   // = 4
    stability = UNSETTLED;
else if (score >= TRANSITIONAL_THRESHOLD)  // = 2
    stability = TRANSITIONAL;
else
    stability = SETTLED;
```

So SETTLED requires **score < 2**. The signals (with score contributions):

| Signal | Source | +score | Comment |
|--------|--------|--------|---------|
| Pressure falling > 6 hPa/24 h | `pressureMsl` delta | +3 | Frontal passage likely |
| Pressure easing 3-6 hPa/24 h | same | +1 | |
| Pressure rising > 2 hPa/24 h | same | **-1** | Stabilising |
| Pressure < 990 hPa now | same | +2 | Deep low overhead |
| Pressure ≥ 1018 hPa AND not falling | same | **-1** | High pressure dominant |
| Precip prob max > 70 AND variance > 400 | `precipitationProbability` | +3 | Timing uncertain |
| Precip prob max > 50 | same | +1 | |
| Precip prob max < 20 throughout | same | **-1** | Quiet |
| Active weather codes ≥ 60 in T+2..T+3 | `weatherCode` | +2 | Rain/showers/snow in window |
| Wind gust variance > 100 | `windGusts10m` | +1 | Frontal activity likely |

### Why SETTLED is unreachable in UK May

To hit SETTLED (score < 2), a cell needs a combination like:

- Pressure not falling **AND** not deep low (0 or -1)
- Precip prob max ≤ 50 (0 or -1)
- No active weather codes in T+2..T+3 (0)
- No high gust variance (0)

In UK May with active fronts, the typical reading is:

- Precip prob max ≥ 70 with variance > 400 (timing uncertain) → +3
- Active weather codes T+2..T+3 → +2
- Possibly pressure falling → +1 or +3
- Possibly high gust variance → +1

Even ignoring pressure, a typical front-affected cell scores 5-6 → UNSETTLED. To reach SETTLED a cell must clear ALL four assessments simultaneously, which corresponds to an actual high-pressure dominant ridge with no incoming fronts and no convective forecast — a once-or-twice-monthly weather pattern in spring/summer UK, rarer in autumn/winter.

The 159-cell observation today (124 TRANSITIONAL / 35 UNSETTLED / 0 SETTLED) is consistent: it's an unsettled spring synoptic week, and the classifier correctly reflects this.

### Is the calibration "pragmatic for UK" or "calibrated for blue-moon"?

The thresholds (HIGH_PRECIP_PROB=70, HIGH_PRECIP_VARIANCE=400, ACTIVE_WEATHER_CODE_MIN=60) are reasonable per-signal. The COMPOSITION is what makes SETTLED rare — the additive scoring requires multiple signals to all be quiet simultaneously. There's no "any one signal clear → SETTLED" path.

This composition seems intentional but its consequence — T+2/T+3 batched evaluation effectively never happens — is unlikely to be intentional given the April 22 design said T+2 SETTLED should batch-evaluate to Haiku. The classifier is correctly identifying genuinely unsettled weather; the issue is that the `evaluationWindowDays` mapping treats anything-but-SETTLED as a hard depth cap.

### evaluationWindowDays mapping

[ForecastStability.evaluationWindowDays():40-46](../../backend/src/main/java/com/gregochr/goldenhour/entity/ForecastStability.java):

```java
case SETTLED      -> 3;   // T+0, T+1, T+2, T+3
case TRANSITIONAL -> 1;   // T+0, T+1
case UNSETTLED    -> 0;   // T+0 only
```

Verified against the snapshot data:

- TRANSITIONAL → maxDays=1 ✓ (124 cells today)
- UNSETTLED → maxDays=0 ✓ (35 cells today)
- SETTLED → maxDays=3 (unobserved today; correct per code)

### April 22 design alignment

The April 22 design said T+1 should always evaluate. The current mapping:

- TRANSITIONAL allows T+1 ✓
- UNSETTLED blocks T+1 ✗ — this is the 22% of cells today

In genuinely UNSETTLED conditions (active fronts, falling pressure, high precip variance), Claude evaluation of T+1 is arguably MORE valuable, not less — it's exactly the case where a photographer most needs nuance about whether to commit. "Active fronts and falling pressure" doesn't mean "T+1 forecast is useless"; it means "T+1 forecast carries real uncertainty and a human-AI judgment is more valuable than a 'too uncertain' verdict".

The current UNSETTLED → maxDays=0 setting is the most material misalignment with the April 22 design.

### Recommendation summary

The classifier itself is well-calibrated for what it measures — synoptic stability. The mapping from stability → permitted evaluation depth is too aggressive at the low end. Reasonable revision:

```java
case SETTLED      -> 3;   // unchanged
case TRANSITIONAL -> 2;   // T+0, T+1, T+2 (T+2 with Haiku)
case UNSETTLED    -> 1;   // T+0, T+1 (T+1 with reduced confidence framing)
```

Or, more aligned with the April 22 two-tier intent — decouple "permission to evaluate" from "which model":

- T+0 and T+1: always evaluate; model = SONNET regardless of stability
- T+2: evaluate iff TRANSITIONAL or SETTLED; model = HAIKU
- T+3: evaluate iff SETTLED; model = HAIKU

This decoupling is cleaner — the stability classifier informs MODEL CHOICE and T+2/T+3 PERMISSION, not T+0/T+1 permission.

---

## Section 6 — April 22 design vs current implementation

The April 22 design intent (per the prompt's context, with the caveat that I'm working from the prompt's summary rather than the original conversation):

> Step 3: T and T+1, ALL locations, Sonnet. Quality matters because this is what photographers act on tomorrow morning.
> Step 4: T+2 SETTLED → Haiku batch. T+2 UNSETTLED → triage only.
> Step 5: Same for T+3.

| Gate | April 22 design | Current implementation | Delta |
|------|-----------------|------------------------|-------|
| **1 — Cache freshness** | Not explicitly addressed in the summary I have. Reasonable inference: cache results until briefing refresh, no separate freshness gate. | Per-region freshness window 4-36 h via `hasFreshEvaluation`. Cache writer REPLACES rather than merges. | **Adds a gate the design didn't anticipate**, with a granularity that interacts badly with Gate 3/4 drops. |
| **2 — Verdict** | "All locations T+0/T+1 evaluate" implies verdict is informational, not gating. STANDDOWN slots still get Claude's read; verdict tags become display attributes. | Per-slot pre-filter. STANDDOWN slots never reach Claude (batch or SSE). Dominant skip reason in production. | **Tighter than design.** Design intent: don't gate; current: hard gate. |
| **3 — Triage** | "Triage demoted to information layer" — explicitly stated. Same role as verdict: attributes, not gates. | Per-task pre-filter inside `fetchWeatherAndTriage`. Three rules sharing Gate 2's STANDDOWN-tier thresholds but operating on different data (3-point cone at 113 km + fresher prefetch). 559 catches in 7 days; not redundant with Gate 2 on the production evidence. | **Tighter than design.** Design: no gate; current: hard gate. Not redundant — performs work Gate 2 doesn't, but at the cost of denying Claude the borderline cases the April 22 design wanted evaluated. |
| **4 — Stability** | T+0/T+1 always evaluate (no stability gating). T+2 SETTLED → Haiku batch, T+2 UNSETTLED → triage only (i.e. skip the batch). Same for T+3. | T+0 always. T+1 iff stability ≥ TRANSITIONAL. T+2 iff stability = SETTLED. T+3 iff stability = SETTLED. | **Different shape.** Design: stability controls T+2/T+3 only. Current: stability also blocks T+1 for UNSETTLED cells (~22% today). T+2/T+3 effectively never evaluate in UK conditions (SETTLED unreachable). |

### Reading of the cumulative effect

The April 22 design + perfect implementation would produce, per cycle, evaluations for:

- All colour locations × {T+0, T+1} × {SUNRISE, SUNSET} ≈ all-colour-locations × 4 evaluations per cycle
- Plus a smaller batch of {T+2, T+3} × SETTLED cells with Haiku

With ~140 colour locations enabled, that's ~560 Sonnet evaluations per cycle, plus maybe 0-50 Haiku evaluations.

The current implementation produces, per cycle (per the 7-region-baseline observation):

- 7 regions × ~5 GO/MARGINAL slots per region × ~2 dates (T+0/T+1, modulo stability gate) × 2 events ≈ 140 evaluations per cycle
- Plus near-zero T+2/T+3

The current system is delivering ~25% of the evaluation volume the April 22 design would produce. The bulk of that gap is Gate 2 (verdict) dropping STANDDOWN slots, with Gate 3 dropping a long tail and Gate 4 capping T+1 for UNSETTLED cells.

---

## Section 7 — Suggested redesign approach

These are recommendations for the follow-up implementation prompt to consider. Cost estimates are rough — they assume current pricing of ~$3/Mtok input Sonnet, ~$0.80/Mtok input Haiku, and a typical Claude evaluation using ~3K input + ~500 output tokens. Today's cost baseline is ~$0.50/day across both batches; the proposals below would scale this up.

### Gate 1 — Cache freshness

**Recommendation**: Make per-location AND recalibrate thresholds by days-ahead.

- **Per-location**: walk each region's slot list inside `collectForecastCandidates`; build a per-location freshness check; submit only stale slots to the batch. Eliminates the replace-not-merge granularity bug.
- **Recalibrate**: tier by days-ahead first, stability second:
  - T+0: 4 h regardless of stability
  - T+1: 8 h regardless of stability
  - T+2: 12 h (TRANSITIONAL or SETTLED only — UNSETTLED can't evaluate T+2 per Gate 4 anyway)
  - T+3: 24 h (SETTLED only)

**Impact**: Modest increase in evaluations per cycle (~10-15%) as previously-trapped locations now eligible. No structural change to batch volume.

**Risk**: If `writeFromBatch` is also changed to merge rather than replace, the cache could become stale-overall by accumulating old per-location entries that never expire. Mitigate by adding a per-row `evaluatedAt` so each location's freshness is checked individually.

**Effort**: ~2-3 days. Touches `BriefingEvaluationService.hasFreshEvaluation` signature, `writeFromBatch` semantic, the candidate loop in `ForecastTaskCollector`, and tests. New schema field on `cached_evaluation` for per-location timestamps (Flyway V100+).

### Gate 2 — Briefing verdict

**Status: honesty patch shipped** (read-path override only) ahead of the wider verdict-as-attribute work. The API now refuses to advertise a positive verdict on any region whose `scoredLocationCount == 0`: such regions are rewritten to `STAND_DOWN` with the pill label "Too unsettled to forecast", an honest replacement summary, replacement gloss, and an empty per-location list. The transform lives in `BriefingHonestyFilter` and is applied only on the API read path via `BriefingService.getCachedBriefingForApi()`; internal callers (the batch task collector, SSE drill-down, model-comparison harness) still call `getCachedBriefing()` and see the untransformed triage data they depend on. The wider verdict-as-attribute redesign remains queued.

**Architectural lesson, recorded for posterity:** the briefing's region-level display fields (`displayVerdict`, `summary`, `glossHeadline`, `glossDetail`, `slots`) were derived entirely from the triage layer — the "7 named Worth-it locations" in the drill-down come from `region.slots` filtered to `verdict !== STANDDOWN`, and the "Clear at N of M locations" copy is built by `BriefingVerdictEvaluator.buildRegionSummary` from triage GO/STANDDOWN counts. None of these consulted `cached_evaluation` coverage. Optimistic triage was therefore sufficient to mislead the UI even when zero Claude evaluations existed for the region. This is the actual mechanism behind the worked example (Lake District / Sat 23 May): the briefing was honest about *triage*, just dishonest about *coverage*.

**Recommendation (wider work, still queued)**: **Soften** — convert from gate to attribute. T+0 and T+1 locations evaluate regardless of verdict; verdict still surfaces in the UI as a colour and as flag chips ("Sun blocked", "Mist risk", etc.). T+2/T+3 retain Gate 2 filtering (with stability) because evaluating obvious washouts that far ahead is hard to defend.

**Impact**: T+0/T+1 evaluations roughly 4-5× current volume. Going from ~140 evaluations/cycle to ~560 evaluations/cycle. At ~$0.01 per Sonnet evaluation, that's ~$5-6 per cycle × 2 cycles/day = ~$10-12/day, ~$300-360/month for the batch alone — a significant cost increase but in proportion to the product principle ("Claude evaluates every photographable opportunity").

**Risk**: Claude evaluations on obvious washouts may produce repetitive low-confidence text ("rain at 100% prob makes this unsuitable") that adds noise without value. Mitigate by tightening the prompt to be explicit about "even if conditions are poor, surface what's notable" and accepting that some 1★ ratings will appear in the UI.

**Effort**: ~1 day code, ~1 day prompt tuning, ~1 day UI surfacing of verdict-as-attribute. The verdict logic itself stays in place — only the gate-check is removed.

**Alternative (cheaper)**: Keep Gate 2 for the obvious physical impossibility cases (precip > 5mm, visibility < 1km — beyond even MARGINAL territory) and let everything else through. Captures most of the cost savings while still passing borderline cases to Claude.

### Gate 3 — Triage

The Section 3 investigation revised the picture: Gate 3 performs real work (559 catches in 7 days, ~80/day). At current Haiku-for-everything pricing those skipped calls would have cost ~**$2.40/month**; at the April 22 design's post-redesign Sonnet pricing for near-term, the same volume is ~**$24/month**. 93% of catches come from the 3-point directional cone disagreeing with the briefing's 1-point horizon sample; 7% from weather evolving between briefing build and batch fire. The choice is therefore a product question — does the April 22 design principle ("Claude evaluates every photographable opportunity") outweigh the saved cost? — not a redundancy clean-up.

**Option A preserves the saving; Options B and C accept it.** Three options below. **Not recommending one** — this is a product decision:

**Option A — Keep Gate 3 as-is. Preserves the saving.**
- **Rationale**: Gate 3 catches conditions Gate 2 can't see (different sampling) or can't see yet (fresher data). The code duplication is at the threshold-values level; the data is distinct.
- **Impact**: Status quo. Continues saving ~$2.40/month at current Haiku pricing (~$24/month if/when near-term moves to Sonnet). Continues denying Claude ~80 borderline slots/day.
- **Risk**: Continues the divergence from the April 22 design principle. The 93-99% directional-cloud cases are *exactly* the borderline calls the design said Claude should make.
- **Effort**: 0 days.

**Option B — Delete Gate 3 + upgrade briefing. Accepts the saving.**
- **Rationale**: Move the deeper sampling earlier; the catches that Gate 2 can structurally absorb (via the 3-point cone upgrade) flow through Gate 2; the rest flow to Claude. Net architecture is simpler.
- **Steps**: (1) Upgrade `BriefingService.fetchHorizonCloud` to fetch the 3-point cone at 113 km along the solar azimuth (matching `ForecastDataAugmentor.augmentWithDirectionalCloud`'s sampling). (2) Wire that into `applyHorizonCloudDemotion` in the verdict pipeline. (3) Optionally add a pre-batch briefing refresh (e.g. cron at 02:30 / 14:30 UTC) to absorb the 7% weather-evolution catches; without it, those slots reach Claude. (4) Delete `WeatherTriageEvaluator` and the `preEval.triaged()` check.
- **Impact**: The 93% sampling catches are absorbed by Gate 2 → STANDDOWN → don't reach Claude. The 7% weather-evolution catches reach Claude unless the pre-batch briefing refresh is added. ~80 lines of code removed, one class deleted. Gate 3 as a saving mechanism is gone — what remains is whatever Gate 2 catches with the deeper sampling.
- **Risk**: The pre-batch briefing refresh adds Open-Meteo load (rate limiter consideration). The horizon-sampling upgrade is straightforward but needs regression tests against current Gate 2 behaviour. If briefing refresh is skipped, expect a small Claude-spend bump from the 7% catches reaching Claude (~$0.20/month at Haiku, ~$2/month at Sonnet).
- **Effort**: ~3-4 days with pre-batch briefing refresh; ~2 days without.

**Option C — Delete Gate 3 unconditionally. Accepts the saving.**
- **Rationale**: Aligns with the April 22 design principle. The 93-99% directional-cloud cases reach Claude, which surfaces nuance ("sun blocked but zenith pastels possible" type readings) rather than triage shortcutting the question.
- **Impact**: ~80 additional Claude evaluations/day. ~$2.40/month additional spend at current Haiku pricing; ~$24/month at the April 22 Sonnet target. Slots that became unsuitable since briefing build get evaluated anyway, with the prompt seeing the fresh prefetched weather.
- **Risk**: Cost increases (small but real). Prompt may need to handle "obviously blocked" cases gracefully — likely produces a 1★ rating with a "sun blocked at horizon" summary, which is what Claude would do anyway.
- **Effort**: ~1 day. Delete `WeatherTriageEvaluator`, the `triaged()` branch in `fetchWeatherAndTriage`, and the Gate 3 check in `ForecastTaskCollector`. Update tests.

**Sequencing note**: Option A leaves the system as-is. Options B and C both delete Gate 3, but in different orders relative to the rest of the redesign — see the combined-recommendation sequencing below for how they interact with the Gate 1 (per-location freshness) and Gate 2 (verdict softening) changes.

### Gate 4 — Stability

**Recommendation**: **Remap window_days**, decoupling permission-to-evaluate from model-choice.

- Keep `ForecastStabilityClassifier` as-is — it's well-calibrated.
- Remove `evaluationWindowDays` from `ForecastStability` enum (or repurpose it as "default depth hint" for display).
- Implement explicit per-days-ahead policy in `ForecastTaskCollector`:
  - T+0, T+1: always evaluate regardless of stability. Model = BATCH_NEAR_TERM (SONNET).
  - T+2: evaluate iff stability ∈ {SETTLED, TRANSITIONAL}. Model = BATCH_FAR_TERM (HAIKU).
  - T+3: evaluate iff stability = SETTLED. Model = BATCH_FAR_TERM (HAIKU).

**Impact**: T+1 evaluation now covers all 159 cells (today: ~124, missing the 35 UNSETTLED). About +22% T+1 volume. T+2 evaluation now covers the 124 TRANSITIONAL cells (today: ~0, missing all). About +124 location-batch slots per cycle. Modest cost increase concentrated in Haiku (cheap). T+3 unchanged because SETTLED is rare.

**Risk**: Evaluating T+2 TRANSITIONAL slots is exactly what the original April 22 design said NOT to do — design said "T+2 UNSETTLED → triage only" but didn't address T+2 TRANSITIONAL explicitly. The proposal above interprets TRANSITIONAL as "settled enough for T+2 Haiku". If that interpretation is wrong, T+2 should be SETTLED-only too.

**Effort**: ~2 days. Need to update tests that assert current `evaluationWindowDays` behaviour, possibly add a feature flag for gradual rollout.

### Combined recommendation

Implement in this order:

1. **Hotfix the Tyne-and-Wear labelling bug** (Section 2 finding). Add horizon-cloud check to `deriveStanddownReason` ahead of the `CLEAR_SKY` check. One-line fix, no design dependency, unblocks user confusion immediately.
2. **Gate 4 remap** with the explicit per-days-ahead policy. Medium risk, moderate cost increase concentrated in Haiku.
3. **Gate 2 softening** to attribute-only for T+0/T+1. Biggest design change, biggest cost impact, requires UI work to surface verdict-as-attribute well.
4. **Gate 1 per-location granularity**. Refactoring task that's easier to do AFTER Gate 2/4 changes because the cache write pattern changes shape.
5. **Gate 3 — pending product decision (A/B/C above)**. Sequence depends on choice:
   - Option A: no work, no sequencing impact.
   - Option B (delete + upgrade briefing): best done AFTER step 3 (Gate 2 softening). The 3-point cone sampling and the pre-batch briefing refresh both feed the Gate 2 verdict, so doing them after Gate 2's role is settled avoids reworking either piece. ~3-4 days.
   - Option C (delete unconditionally): can be done at any point; effectively independent of the other gates. Doing it AFTER step 3 keeps the design-principle change (Gate 2 softening + Gate 3 deletion) in one consistent batch from the user's perspective. ~1 day.

**Cost-saving context for the Gate 3 decision.** Today (Haiku-for-everything) Gate 3 saves ~$2.40/month by preventing ~80 calls/day. If the April 22 redesign moves near-term to Sonnet, the same prevented calls would have cost ~$24/month. Option A preserves the saving; Options B and C accept it — B absorbs most catches into Gate 2 via the 3-point cone upgrade (so the saving partially flows through, but Gate 3 as a distinct mechanism is gone), C lets the slots reach Claude. In the context of the steps 2/3 cost increase (from ~$0.50/day baseline to ~$10-15/day for full Gate 2 softening + Gate 4 remap), the Gate 3 figure is small (~0.5-2.5% of the projected new total) but not negligible — especially under Sonnet near-term, where each evaluation is ~10× the unit cost.

Total estimated effort: **~7-10 engineering days for steps 1-4**; **+0 / +2-4 / +1 days for Gate 3** depending on option (A / B / C). Total estimated Claude cost impact (steps 1-4): from ~$0.50/day to ~$10-15/day, ~$300/month additional. Gate 3 option adds **±$0 / ±$0 to +$0.07/day / +$0.08/day** at Haiku — or **±$0 / ±$0 to +$0.07/day / +$0.80/day** at Sonnet (A / B-with-refresh / B-without-refresh / C).

---

## Areas of uncertainty

Things I couldn't determine confidently from code alone:

1. **Production state of `near_term_batch_evaluation` scheduler entry.** V73 seed marks it `PAUSED`. The user reports batches run, so it must be `ACTIVE` in prod (via the Scheduler admin UI). I'm trusting that report; a DB query against prod would confirm.
2. **Actual T+1 / T+2 evaluation counts in the last 7 days.** I'd want to run an `api_call_log` query against prod (e.g. counting calls per `custom_id` prefix) to verify the 7-region-per-cycle / ~140-evaluations/cycle estimate. The investigation rests on the user's observation rather than my own count.
3. **Whether `BATCH_NEAR_TERM=SONNET, BATCH_FAR_TERM=HAIKU` is the actual production state.** V92 seeds those values, but the Admin UI Model Selection page can override them. Should verify against the `model_selection` table.
4. **The historical context conversation ("Submitting JDFI forecasts via Anthropic Batch API")** isn't accessible in this session's project context. I worked from the prompt's summary of the April 22 design. If the actual conversation had nuances (e.g. specific guidance on UNSETTLED T+1), my Gate 4 recommendation may need revision.
5. **Whether Lake District / 2026-05-18 / SUNRISE was actually verdict-correct.** I asserted "probably correct" based on 100% precip probability but didn't query the actual `BriefingSlot` data for those slots to confirm precip > 2.0 mm at the event hour specifically.

---

## Appendix: Files referenced

| File | Lines | Purpose |
|------|-------|---------|
| [ForecastTaskCollector.java](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) | 408-490, 241-302, 604-655 | Candidate loop; all four gates fire in this class |
| [ScheduledBatchEvaluationService.java](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ScheduledBatchEvaluationService.java) | 140-150, 207-237 | Cron entry point; bucket submission |
| [BriefingService.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java) | 240-300, 401-450 | Briefing build + enrichment with cached scores |
| [BriefingSlotBuilder.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingSlotBuilder.java) | 88-197 | Per-slot verdict pipeline (5 demotion stages + tide override) |
| [BriefingVerdictEvaluator.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingVerdictEvaluator.java) | 27-64, 167-277, 343-369 | Verdict thresholds and `deriveStanddownReason` labelling |
| [BriefingEvaluationService.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) | 148-253, 310-316, 328-337, 401-413, 544-581 | Cache read/write, SSE evaluate, freshness gate |
| [ForecastService.java](../../backend/src/main/java/com/gregochr/goldenhour/service/ForecastService.java) | 255-418 | `fetchWeatherAndTriage` — Gate 3 invocation site |
| [WeatherTriageEvaluator.java](../../backend/src/main/java/com/gregochr/goldenhour/service/WeatherTriageEvaluator.java) | full file | Gate 3 three-rule evaluator |
| [FreshnessResolver.java](../../backend/src/main/java/com/gregochr/goldenhour/service/FreshnessResolver.java) | 58-66 | Stability → max-age mapping |
| [FreshnessProperties.java](../../backend/src/main/java/com/gregochr/goldenhour/config/FreshnessProperties.java) | full file | Threshold defaults (SETTLED=36, TRANSITIONAL=12, UNSETTLED=4, floor=2) |
| [ForecastStabilityClassifier.java](../../backend/src/main/java/com/gregochr/goldenhour/service/ForecastStabilityClassifier.java) | 60-88, 90-189 | Score composition; signal assessments |
| [ForecastStability.java](../../backend/src/main/java/com/gregochr/goldenhour/entity/ForecastStability.java) | 40-46 | `evaluationWindowDays` mapping |
| [ForecastResultHandler.java](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java) | 173-175, 209-213 | Batch result → cache write path |
| [V68__scheduler_job_config.sql](../../backend/src/main/resources/db/migration/V68__scheduler_job_config.sql) | 25 | `daily_briefing` cron |
| [V73__forecast_batch.sql](../../backend/src/main/resources/db/migration/V73__forecast_batch.sql) | 20-36 | `near_term_batch_evaluation` cron seed |
| [V92__batch_near_far_term_model_selection.sql](../../backend/src/main/resources/db/migration/V92__batch_near_far_term_model_selection.sql) | full file | BATCH_NEAR_TERM=SONNET, BATCH_FAR_TERM=HAIKU |
