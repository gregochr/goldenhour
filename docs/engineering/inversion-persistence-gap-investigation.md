# Inversion persistence gap — investigation

**Status:** Read-only investigation. No code changed. Findings + fix scope only.
**Date:** 2026-06-20
**Trigger:** The INVERSION hot topic never fires despite genuine Lake District inversion
conditions. The detector reads `forecast_evaluation.inversion_potential`; prod shows that
column is empty (3 writes in 21,696 rows, last 2026-04-19), while sibling Group-B columns
`dust` (852/852 this cycle) and `surge_risk_level` (77/852, the coastal subset) are healthy.

---

## TL;DR

The investigation **refines the original hypothesis and partly refutes it.**

- The framing "`dust` rides the nightly batch, `inversion` is sync-only" is **wrong about
  dust**. Neither `dust` nor `inversion` is persisted by the Anthropic batch result path —
  that path (`ForecastResultHandler`) writes **only** `cached_evaluation`, never
  `forecast_evaluation`.
- The real mechanism is a **single difference inside one method** (`ForecastService.buildEntity`):
  - `dust` and `surge` are persisted from **pre-evaluation atmospheric data** (`data.aerosol()`,
    `data.surge()`) — values present on **every** persisted row, including the
    triaged/un-evaluated rows the nightly pipeline writes.
  - `inversion_potential` is persisted from the **Claude evaluation output**
    (`evaluation.inversionScore()` / `evaluation.inversionPotential()`) — which is **null on
    triaged rows**, and the nightly pipeline writes **no Claude-evaluated colour rows to
    `forecast_evaluation` at all** (survivors land in `cached_evaluation`, which has no
    inversion field).
- Therefore `inversion_potential` can only ever be written by the **sync admin "Run Forecast"
  path** (`evaluateAndPersist`). The 3 all-time writes (last 2026-04-19) are manual sync runs
  during the inversion feature's April development. `V86` (which populated the
  `overlooks_water` locations) is dated **Apr 14**, matching the **Apr 19** last write.
- Inversion is **not dead and not sync-only-computed**: the calculator runs on both paths and
  the inversion prompt block is sent to Claude on the batch path too (that's why the Lake
  District drill-down prose says "inversion spectacular"). What's missing is purely
  **persistence of the structured value on the nightly path** — Claude's batch inversion answer
  is parsed and then **discarded** because `BriefingEvaluationResult` / `cached_evaluation` has
  no inversion field.
- The detector revealed a pre-existing gap; it did not cause it.

---

## 1. Why `dust` persists nightly and `inversion` almost never — the wiring difference

### The single seam: `ForecastService.buildEntity`

`ForecastEvaluationEntity` is built in exactly one place —
[`ForecastService.buildEntity`](../../backend/src/main/java/com/gregochr/goldenhour/service/ForecastService.java)
(lines 593–682). Three Group-B columns are populated there with **three different source
shapes**:

```java
// line 619 — UNCONDITIONAL, pure atmospheric data
.dust(data.aerosol().dustUgm3())

// line 669 — gated on data only (coastal => surge computed), pure atmospheric data
.surgeRiskLevel(data.surge() != null ? data.surge().riskLevel().name() : null)

// lines 673–676 — gated on data AND reads the CLAUDE EVALUATION output
.inversionScore(data.inversionScore() != null ? evaluation.inversionScore() : null)
.inversionPotential(data.inversionScore() != null ? evaluation.inversionPotential() : null)
```

- `dust` reads `data.aerosol()` — set on **every** row `buildEntity` produces.
- `surge` reads `data.surge()` — set whenever the location is coastal (the augmentor computed
  it), independent of Claude. Hence the 77-coastal subset.
- `inversion` reads **`evaluation.inversionScore()`** — Claude's returned value. The
  `data.inversionScore() != null` guard is just the eligibility gate; the **value actually
  stored comes from the Claude evaluation**, not the calculator.

### Which rows does the nightly pipeline write to `forecast_evaluation`?

The nightly run is the Anthropic batch pipeline. In
[`ForecastTaskCollector`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java)
(lines 348–377) each candidate goes through `forecastService.fetchWeatherAndTriage(...)`, then:

- **Triaged candidates** — `fetchWeatherAndTriage` has *already* written a
  `forecast_evaluation` row (inside the method, lines 368–373 / 399–404) with an **empty
  evaluation** (`new SunsetEvaluation(null, null, null, null)`), then the collector `continue`s
  (excluded from the batch).
- **Surviving candidates** — returned un-persisted and submitted to the Anthropic batch. Their
  results are handled by
  [`ForecastResultHandler`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java):
  `parseBatchResponse` → `flushCacheKey` → `briefingEvaluationService.writeFromBatch(...)`,
  which writes **`cached_evaluation` only**. `buildEntity` is never called; `forecast_evaluation`
  is never touched for survivors.

So the future-dated rows in `forecast_evaluation` are the **triaged (rejected) candidates**
(plus WILDLIFE hourly rows). On those rows:

| column | source | on a triaged row | result |
|---|---|---|---|
| `dust` | `data.aerosol().dustUgm3()` | populated (pre-eval data) | **852/852** |
| `surge_risk_level` | `data.surge()` | populated if coastal | **77/852** |
| `inversion_potential` | `evaluation.inversionPotential()` | **null** (empty eval) | **≈0** |

This is the precise reason for the counts. It is not "dust is wired into the batch and
inversion isn't" — both flow through the same `buildEntity`. **Dust survives because it is
pre-evaluation data stamped on every row; inversion does not because it is sourced from a Claude
evaluation that triaged rows never have.**

### Where inversion *can* be written

The only writer that calls `buildEntity` with a **real Claude evaluation** is
`evaluateAndPersist` (line 449), invoked from
[`ForecastCommandExecutor`](../../backend/src/main/java/com/gregochr/goldenhour/service/ForecastCommandExecutor.java)
Phase 2 sentinel (line 535) and Phase 3 full-eval (line 585). `ForecastCommandExecutor.execute`
is the **sync admin "Run Forecast" path** — invoked only from `ForecastController` via
`CompletableFuture` (controller lines 278–465). The legacy `runForecasts` is now the
**WILDLIFE-only** shortcut (its sole caller is `ForecastCommandExecutor:823`), so it also writes
empty evaluations.

Net: `inversion_potential` is written **exclusively by the sync admin "Run Forecast" path**, for
eligible locations where the chain below completes. The nightly batch never writes it.

---

## 2. Is inversion sync-only, dead, or batch-gated?

**It is computed on both paths and sent to Claude on both paths; it is *persisted* only on the
sync path. On the batch path Claude's structured answer is computed and then discarded.**

Evidence:

- **Calculator runs on both paths.**
  `augmentor.augmentWithInversionScore(...)` is invoked at `ForecastService:204` (sync
  `runForecasts`) **and** `ForecastService:358` (inside `fetchWeatherAndTriage`, which the batch
  collector calls). `InversionScoreCalculator.calculate` consumes temp, dew point, wind,
  humidity, low cloud (all present on the batch path).
- **Prompt block sent to Claude on both paths.**
  [`PromptBuilder`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java)
  adds the `CLOUD INVERSION FORECAST:` block when `data.inversionScore() >= 7.0`
  (`INVERSION_SCORE_THRESHOLD = 7.0`, line 267; gate at line 507). Batch survivors carry the
  augmented `inversionScore`, so eligible Lake District locations get the block — which is why
  the drill-down **prose** reasons about inversion ("inversion spectacular", "valley inversion
  likely"). The prose is the live proof that inputs + prompt are correct on the batch path.
- **Structured answer parsed on the batch path…** `ForecastResultHandler.parseBatchResponse`
  → `ClaudeEvaluationStrategy.parseEvaluationWithMetadata` returns a `SunsetEvaluation`
  carrying `inversionScore` / `inversionPotential`.
- **…then dropped.** `ForecastResultHandler.buildResult` (lines 386–412) builds a
  `BriefingEvaluationResult`, which has **no inversion field**
  ([`BriefingEvaluationResult`](../../backend/src/main/java/com/gregochr/goldenhour/model/BriefingEvaluationResult.java)).
  So the parsed inversion value is discarded; `cached_evaluation` never stores it.

**Verification Chris can run now:** trigger a sync admin "Run Forecast" over a Lake District
region (or POST `/api/forecast/run`) on a morning where eligible fells score `inversion_score ≥ 7`,
then re-query `forecast_evaluation` for `inversion_potential`. It should write fresh rows today —
confirming the sync path is live and the April rows are simply the last time sync was run on
eligible locations, not removed code.

### The full gate chain (why even sync writes are rare)

For `inversion_potential = 'STRONG'` (the only value the detector fires on) to land in
`forecast_evaluation`:

1. Location eligible: `elevation_m ≥ 200` **and** `overlooks_water = true`
   (`ForecastDataAugmentor:391-394`). ~19 Lake District locations qualify (V86) — **not** the
   bottleneck.
2. Calculator score `≥ 7.0` so the prompt asks Claude (`PromptBuilder` gate).
3. Claude returns `inversion_score` / `inversion_potential` and classifies it `STRONG` (9–10).
4. The row is written by a **Claude-evaluated** `buildEntity` call — i.e. the **sync admin
   path**, not triage and not the batch.

Steps 1–3 are inherently narrow (genuine strong inversions are rare and seasonal). **Step 4 is
the hard blocker**: nightly there is no Claude-evaluated `forecast_evaluation` write at all.

---

## 3. The raw inputs are on the batch path — fix is wiring, not new data

Confirmed. `InversionScoreCalculator` consumes temperature (`comfort.temperatureCelsius`), dew
point (`weather.dewPointCelsius`), wind (`weather.windSpeedMs`), humidity
(`weather.humidityPercent`), and low cloud (`cloud.lowCloudPercent`). All are fetched and present
on the batch path — the calculator already runs there (`ForecastService:358`) and the prose
proves Claude reasons from them. **No new fetch, no new model, no new prompt is required.** The
gap is purely persisting a structured value already being computed (and already returned by
Claude on the batch path).

---

## 4. Fix scope (not built)

The original "mirror the dust wiring" framing **does not transfer**, because dust only works by
being pre-eval data stamped on the *triaged* rows — and real inversion mornings are **good**
weather that **survives** triage and routes to `cached_evaluation`, where there is no inversion
column and no `forecast_evaluation` row. Persisting the calculator value on triaged rows would
populate the wrong subset (the rejects) and miss every actual inversion morning.

There are two layers to choose between.

### Recommended: carry inversion through the batch survivor surface (`cached_evaluation`) and point the detector at it

This is the data-faithful fix — it puts the value where the survivors actually land, the same
way the batch already surfaces rating/summary/headline.

- **Insertion point:** `ForecastResultHandler.buildResult`
  (`.../evaluation/ForecastResultHandler.java:386-412`). The method already holds the parsed
  `SunsetEvaluation eval` with `eval.inversionScore()` / `eval.inversionPotential()`. Add those
  to the result.
- **Carrier:** add `inversionScore` / `inversionPotential` to
  `BriefingEvaluationResult` and to the `cached_evaluation` persistence
  (`BriefingEvaluationService.writeFromBatch` → the cache entity / a new migration column or the
  existing JSON payload).
- **Detector:** repoint `InversionHotTopicStrategy.findInversionDaysByPotential` to read the
  survivor surface (`cached_evaluation`) instead of `forecast_evaluation`.
- This also fixes the **sync** path for free if both transports flow `eval` through the same
  result builder (they already share `buildResult`).

### Minimal but insufficient alternative (document why it's rejected)

Changing `buildEntity` lines 673–676 to persist the **calculator's** `data.inversionScore()`
(deriving potential via `InversionPotential.fromScore`) instead of `evaluation.inversionScore()`
would populate triaged rows — but those are the rejected bad-weather rows; the good inversion
mornings still bypass `forecast_evaluation` entirely. Insufficient on its own.

### Single-seam consideration

There are **two** entity-population seams that have already drifted:
`ForecastService.buildEntity` (writes `forecast_evaluation`, sync + triage) and
`ForecastResultHandler.buildResult` (writes `cached_evaluation`, batch survivors). `buildResult`
silently drops not just inversion but surge, directional cloud, cloud-approach, etc. The
durable fix is to make the **survivor surface** (`cached_evaluation`) carry the structured
signals the hot-topic detectors need, and have **all** detectors read that one surface — so a
new detector can't be wired against a column the nightly pipeline never fills for survivors.
Folding the two seams (or at least giving the detectors a single, survivor-backed read model) is
the single-seam move; mirroring inversion into `cached_evaluation` is the targeted first step.

---

## 5. Are DUST and SURGE genuinely working? (so we know only inversion is broken)

**Yes — both are functional; only inversion has an empty column.**

- **DUST** — `DustHotTopicStrategy` → `findDustDays` matches on `aerosol_optical_depth`, `dust`,
  `pm25`. All three are set **unconditionally** in `buildEntity` (`data.aerosol()`), so every
  nightly row carries them → 852/852. The query has no rating/triage filter, so it fires
  correctly off the populated columns.
- **SURGE** — `StormSurgeHotTopicStrategy` → `findSurgeDaysByRiskLevel` matches
  `surge_risk_level`, set from `data.surge()` for coastal locations → 77/852 (correct coastal
  sparsity). Fires correctly.

**Caveat (not a break, worth banking):** because nightly `forecast_evaluation` rows are the
**triaged** subset, dust/surge are effectively sampled from the *rejected* forecasts only — a
dusty or surge-y day that **survives** triage (good colour) sits in `cached_evaluation` and is
invisible to these detectors. The columns are populated and the detectors function, but their
coverage is the triage rejects. That's a latent accuracy gap shared by every
`forecast_evaluation`-reading detector — and the same root that makes inversion fully inert. Out
of scope here; flagged for the broader survivor-surface cleanup.

---

## What surprised me most

The investigation's own premise is wrong about dust, and the truth is more uncomfortable:
**`forecast_evaluation`'s future-dated rows are the forecasts we *rejected*.** The good ones —
the survivors Claude actually scores — live in `cached_evaluation`. Every hot-topic detector
reads `forecast_evaluation`, i.e. samples the triage rejects. Dust and surge "work" only by
accident of being weather inputs that exist *before* triage and get stamped on every row.
Inversion is the one Group-B signal sourced from Claude's *verdict* — exactly the thing the
rejected rows lack and the survivors carry elsewhere — so it is the first detector to expose
that the table the detectors read is not the table where the live forecasts are scored. The
detector tested green and shipped inert because its column is filled only by a manual admin
button nobody presses in production.

---

## Open questions for Chris

1. **Is `cached_evaluation` the intended survivor surface for hot-topic detection?** If yes, the
   detectors should read it (and we add inversion to it). If `forecast_evaluation` is meant to
   hold survivors too, the gap is bigger — the batch path needs to write survivor rows there.
2. **Should the structured inversion value be Claude's or the calculator's?** Today the column
   stores Claude's classification (`evaluation.inversionPotential()`), gated on the calculator's
   score ≥ 7. Persisting the calculator's value directly would decouple from Claude (cheaper,
   deterministic) but loses Claude's confirmation. The fix shape differs accordingly.
3. **Do you want the broader fix** (all detectors → survivor surface) or just the **targeted**
   inversion repoint now? The targeted fix unblocks the INVERSION topic; the broader fix closes
   the latent dust/surge coverage gap too.
4. **Confirm via a sync run:** does a manual "Run Forecast" over a Lake District region today
   write `inversion_potential` rows? That confirms the sync path is live (April rows are stale
   data, not dead code) before any change is made.

---

## Appendix — key references

| Concern | File:line |
|---|---|
| Single entity builder (dust/surge/inversion populated) | `service/ForecastService.java:593-682` (dust 619, surge 669, inversion 673-676) |
| Inversion calculator | `service/InversionScoreCalculator.java` (gate: elevation ≥ 200 + overlooks water) |
| Inversion augmentation (both paths) | `service/ForecastDataAugmentor.java:389-402`; called `ForecastService.java:204` (sync), `:358` (batch-collector triage) |
| Prompt inversion block (threshold 7.0) | `service/evaluation/PromptBuilder.java:267, 505-516` |
| Batch result → `cached_evaluation` only (no `forecast_evaluation`, drops inversion) | `service/evaluation/ForecastResultHandler.java:175-216, 283-285, 386-412` |
| Batch survivor surface lacks inversion field | `model/BriefingEvaluationResult.java` |
| Sync admin path (only Claude-evaluated `forecast_evaluation` writer) | `service/ForecastCommandExecutor.java:535, 585`; `service/ForecastService.java:428-481` |
| Triaged rows written with empty eval | `service/ForecastService.java:367-373, 398-404` |
| Nightly batch collector (triaged persisted, survivors → batch) | `service/batch/ForecastTaskCollector.java:348-377` |
| Inversion detector + query | `service/InversionHotTopicStrategy.java`; `repository/ForecastEvaluationRepository.java:103-109` |
| Dust detector + query | `service/DustHotTopicStrategy.java`; `repository/ForecastEvaluationRepository.java:142-153` |
| Surge query | `repository/ForecastEvaluationRepository.java:122-128` |
| Eligible locations seeded | `db/migration/V86__populate_elevation_and_water_data.sql` (dated Apr 14 — matches Apr 19 last write) |
