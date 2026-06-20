# Stage B — atmospheric-detector survivor-surface fix — Step 0 (carrier design)

**Status:** Read-only carrier design. No code changed. Hard stop after this doc.
**Date:** 2026-06-20
**Predecessors:** `survivor-surface-investigation.md` (Stage A Step 0), `inversion-persistence-gap-investigation.md`. Stage A (INVERSION → `forecast_score`) is shipped (PR #158, merged).

**The bug (settled, not re-litigated):** hot-topic detectors read `forecast_evaluation`, which nightly holds only the **triaged-out rejects**; the survivors Claude scores route to `cached_evaluation`. Stage B is the four detectors that read **pre-evaluation atmospheric data** — **DUST, SURGE, SNOW_FRESH, SNOW_TOPS** — which therefore **actively misfire** off the reject population (worse than inversion's silent inertness). DUST is the only one **live-misfiring today** (dust is year-round); SURGE/SNOW are dormant-by-season.

---

## TL;DR

- **The data exists at submission and is destroyed at the async boundary.** Each survivor's full `AtmosphericData` is carried on `EvaluationTask.Forecast.data()` at collection/submission time, but it is only **rendered into the prompt text** — never persisted structurally. The Anthropic batch round-trip carries across only the `custom_id` = `(locationId, date, targetType)`; the result handler (`ForecastResultHandler.parseBatchResponse` / `buildResult`) receives **only the parsed `eval` + that identity**, no `AtmosphericData`. (Tide is *re-derived* at result time for exactly this reason.)
- **Therefore Option 1 ("thread through to `buildResult`") is dominated.** The batch is async and survives restarts, so nothing can be held in memory across it. To have atmospheric data at result time you must **persist it at submission** anyway — at which point re-loading and re-writing it at result is pure overhead. The honest mechanism is **write at submission, let the detector read it directly**.
- **Recommended carrier: a new survivor-only sibling table written at submission**, keyed `(location_id, evaluation_date, event_type)` with latest-wins upsert (mirroring `forecast_score`/`cached_evaluation` semantics), holding the per-signal atmospheric fields. **Not `forecast_score`** (these signals are not score-shaped). **Not piggybacked on `forecast_run_disposition`** (wrong lifecycle — append-per-cycle, not upsert — and it includes rejects + pollutes a triage ledger).
- **Survivor-by-construction.** Like `forecast_score`, the carrier gets a row only when a survivor is submitted, so the detectors reading it sample survivors only — the whole point.
- **Dust-first staging.** Build carrier → repoint DUST (live misfirer, verifiable now) → repoint SURGE/SNOW (dormant). Each gated on threshold re-verification against the survivor population.
- **Correction to Stage A Step 0:** KING_TIDE/SPRING_TIDE's `ForecastEvaluationRepository` injection is **not vestigial** — it reads `tide_aligned` counts for the alignment-info enrichment, which is itself a *sixth* (secondary) reject-sampling read. See §6.

---

## Section 1 — where the atmospheric data IS (submission) vs LOST (async boundary)

### 1.1 Computed and in-hand at collection/submission

`ForecastTaskCollector` iterates candidates; for each it calls
`forecastService.fetchWeatherAndTriage(...)` which returns a `ForecastPreEvalResult` carrying the fully-populated `AtmosphericData` (aerosol/surge/snow all set) — for **both** survivors and rejects.

- `ForecastTaskCollector.java:351` — `fetchWeatherAndTriage` → `preEval.atmosphericData()` in scope.
- Survivors (non-triaged) are turned into an `EvaluationTask.Forecast` **carrying that data**:
  `ForecastTaskCollector.java:432` → `new EvaluationTask.Forecast(location, date, targetType, model, preEval.atmosphericData(), WriteTarget.BRIEFING_CACHE)`.
- `EvaluationTask.java:74` — the record's `AtmosphericData data` component is **required** (`Objects.requireNonNull(data)`).

So at the moment a survivor is confirmed and bucketed for submission, its atmospheric snapshot is present and keyed by `(location, date, event_type)`.

### 1.2 Destroyed at the async boundary

- **Rendered to text, not persisted:** `BatchRequestFactory.buildForecastRequest(customId, model, data, maxTokens)` uses `data` only to build the prompt user message (`PromptBuilder.buildUserMessage(data, ...)`). The request JSON sent to Anthropic contains **prose**, not a structured atmospheric payload.
- **`custom_id` carries identity only:** `CustomIdFactory.forForecast` → `"fc-{locationId}-{date}-{targetType}"`; `parse` → `ParsedCustomId.Forecast(locationId, date, targetType)`. No atmospheric state.
- **`forecast_batch` (V73) is batch-level only** — `anthropic_batch_id`, counts, timestamps, `job_run_id`, `pipeline_run_id`. **No per-task rows.** (`BatchSubmissionService.submit` saves one `ForecastBatchEntity` per batch.)
- **Result handler has no `AtmosphericData`:** `ForecastResultHandler.parseBatchResponse(LocationEntity, ForecastIdentity, ClaudeBatchOutcome, ResultContext)` → `buildResult(location, eval, date, targetType, regionName, modelName, pipelineRunId)`. Confirmed: only the parsed `eval` + identity. Tide is **re-derived** here (`forecastDataAugmentor.deriveTideContext(location, date, targetType)`), which is the existing proof that atmospheric context does not survive the boundary.

### 1.3 The write-point candidates

| Seam | When | `AtmosphericData` available? | Notes |
|---|---|---|---|
| **`ForecastTaskCollector` survivor branch** (`:432`, where the `EvaluationTask.Forecast` + EVALUATED disposition are produced) | **Submission/collection** | **Yes** (`preEval.atmosphericData()`) | Natural seam — same moment the survivor is confirmed; same grain as the carrier. |
| **`BatchRequestFactory.buildForecastRequest`** (`:66`, per request) | Submission | **Yes** (`data` param) | Per-request granularity; but mixes a side-effecting DB write into a pure request builder. |
| `forecast_run_disposition` write (`ForecastDispositionService.persist`) | Submission | only identity passed (no atmos today) | Could be extended, but wrong lifecycle (see §2). |
| `ForecastResultHandler.buildResult` | **Result (hours later)** | **No** | Where inversion/scores land; atmospheric data is gone here. |

**The collector survivor branch is the clean write seam** — the survivor is confirmed, the atmospheric snapshot is in hand, and the EVALUATED disposition is already being recorded there, so a sibling atmospheric write rides alongside it.

### 1.4 Why the rejects are populated today (the contrast)

`ForecastService.buildEntity` stamps `dust`/`aerosolOpticalDepth`/`pm25`/`surgeRiskLevel`/`snowDepthMetres`/`freezingLevelMetres` on **every** `forecast_evaluation` row from `data.*` — and nightly the only `forecast_evaluation` rows written are the **triaged rejects** (survivors go to `cached_evaluation`). Hence dust = 852/852 **on rejects**, and the detectors sample rejects. The fix is to get the **survivor** atmospheric snapshot onto a **survivor** surface.

---

## Section 2 — the three carrier options (the central decision)

### Option 1 — thread `AtmosphericData` through submission → result. **Dominated.**

The appeal was "single write-point at `buildResult`, consistent with inversion." But the batch boundary is **async and restart-surviving** — there is no in-memory carry. To have `AtmosphericData` at `buildResult` you must **persist it at submission keyed by `custom_id`/identity and reload it at result**. That is strictly *more* than Option 2 (persist at submission; detector reads it directly): it adds a reload + a second write, for no gain, because the detector can read the submission-time row directly. The only thing Option 1 buys is co-locating the write with inversion's `forecast_score` write — which doesn't help, since these signals can't live in `forecast_score` anyway (§2 below). **Recommend against.**

### Option 2 — write at submission time (where the data exists). **Recommended mechanism.**

When a survivor is bucketed for submission, write its atmospheric signals immediately, keyed `(location_id, evaluation_date, event_type)`, latest-wins upsert. The eval fills the score-shaped signals later via the existing `forecast_score` dual-write; the two are independent and never need to meet.

- **Pro:** data is in hand at submission — no async threading; survivor-by-construction; mirrors how `forecast_score`/`cached_evaluation` already key and upsert.
- **Con / must-confirm:** two write moments per survivor (atmospheric at submission, score at result) — but they are *independent surfaces*, so a survivor whose eval later fails still leaves a correct atmospheric row standing alone (which is fine — the atmospheric reading is valid regardless of whether Claude scored the sky). No reconciliation race because they don't share a row.

The open sub-question is **which table** Option 2 writes to:

- **2a — extend `forecast_run_disposition`.** Reuses an existing submission-time per-candidate row. **Rejected:** (i) lifecycle mismatch — disposition is **append-per-cycle** keyed by `job_run_id` (no uniqueness on `location/date/event`; V101 has only `job_run` and `disposition,created_at` indexes), so a detector would read N cycles of rows and must dedup to latest; the carrier wants **latest-wins upsert** like `forecast_score`. (ii) it records **rejects too** (SKIPPED_* rows), so the detector would re-acquire the reject-sampling bug unless it filters `disposition='EVALUATED'`. (iii) concern-pollution — a triage ledger becoming an atmospheric archive. (iv) 30-day retention prune is tuned for the ledger, not for a detector source.
- **2b — a new survivor-only sibling table.** = **Option 3**, below. **Recommended.**

### Option 3 — a dedicated sibling survivor-atmosphere table. **Recommended carrier.**

A new table — e.g. `survivor_atmosphere` (or `forecast_atmosphere`) — written **only** when a survivor is submitted, keyed `(location_id, evaluation_date, event_type)` with a unique constraint + latest-wins upsert (the `forecast_score` pattern), holding the per-signal field set (§3). Detectors read it directly (the Bluebell/inversion read pattern, but a non-score projection).

- **Pro:** clean shape for non-score data (aerosol triple, surge enum + metres, snow depth/level + humidity); **survivor-by-construction** (no reject rows, so no `disposition='EVALUATED'` filter needed); right grain; SQL-queryable; doesn't pollute `forecast_score` or the disposition ledger; upsert semantics match the nightly re-evaluation cadence.
- **Con:** a new table to maintain, and detectors now read **two** survivor surfaces — `forecast_score` (score-shaped: inversion, sky, tidal, bluebell) and `survivor_atmosphere` (readings-shaped: dust, surge, snow). That two-surface split is defensible (scores vs. raw readings are genuinely different shapes), but it is the one thing to get Chris's explicit blessing on (Open Question 1).

### Why not `forecast_score`

`forecast_score` is `(forecast_type, location, date, event) → score INTEGER + summary`. The Stage B signals are not single scores: DUST is three readings feeding a proxy; SURGE is a risk enum (+ metres); SNOW is depth + freezing-level (+ humidity for the mist variant). Encoding these as integer "scores" would lose information or require multiple synthetic forecast_types per signal — over-fitting the table. Stage A's inversion fit because it genuinely *is* a 0–10 score. **Confirmed: keep atmospheric readings out of `forecast_score`.**

### Considered and rejected — Option 0: write survivor `forecast_evaluation` rows at submission

Writing a full (eval-less, rating-null) `forecast_evaluation` row per survivor at submission would make **every** existing detector query work **with zero repoint** (the atmospheric columns already exist there). **Rejected:** (i) it reverses the deliberate architecture decision that survivors live in `cached_evaluation`, not `forecast_evaluation`; (ii) the row would carry null rating/eval forever (the eval result goes to `cached_evaluation`, not back to this row), commingling with the rating-null triage rows — muddy semantics; (iii) the population becomes rejects + survivors, not survivors-only, so it doesn't deliver the clean "fire off survivors" semantics the fix is for. Noted for completeness; not recommended.

**Recommendation:** Option 2 mechanism (write at submission) into an Option 3 carrier (dedicated survivor-only sibling table). This is the minimal durable fit — it follows the submission-time-write insight, matches `forecast_score`/`cached_evaluation` keying+upsert, and doesn't force non-score data into a score column.

---

## Section 3 — per-signal carrier field set

Each detector reads pre-eval atmospheric columns that `buildEntity` already stamps; the carrier must hold the same per `(location_id, evaluation_date, event_type)`. (Region comes via `location → region`; elevation is on `location`.)

| Detector | Fires on | Carrier fields needed | Source on `AtmosphericData` |
|---|---|---|---|
| **DUST** | proxy: `AOD > 0.3` OR `dust > 50`, AND `PM2.5 < 35`-or-null (`findDustDays`) | `aerosol_optical_depth`, `dust` (µg/m³), `pm2_5` — all three | `data.aerosol().aerosolOpticalDepth()/dustUgm3()/pm25()` |
| **SURGE** | `surge_risk_level = HIGH` (`findSurgeDaysByRiskLevel`, HIGH only) | `surge_risk_level` (enum name); **optionally** `surge_total_metres`/`pressure`/`wind`/`adjusted`/`astronomical` if the serving path wants them | `data.surge().riskLevel().name()` (+ metres) |
| **SNOW_FRESH** (+ SNOW_MIST variant) | `snow_depth_m ≥ 0.02`; **MIST variant** when co-occurring `humidity > 90` (`findSnowFreshDays` returns humidity alongside) | `snow_depth_m` **and** `humidity` | `data.weather().snowDepthMetres()`, `data.weather().humidityPercent()` |
| **SNOW_TOPS** | `freezing_level_m ≤ location.elevation_m − 100` (`findSnowTopsDays`) | `freezing_level_m` (elevation already on `location`) | `data.weather().freezingLevelMetres()` |

**Note (don't miss):** `findSnowFreshDays` co-returns `humidity` so the SNOW_MIST upgrade needs no second query — the carrier must therefore hold `humidity`, not just snow depth. The minimal union of carrier columns is: `aerosol_optical_depth, dust, pm2_5, surge_risk_level, snow_depth_m, freezing_level_m, humidity` (+ optional surge metres). All are nullable (inland has no surge; summer has no snow).

---

## Section 4 — staging (dust first — the live misfirer)

1. **Build the carrier** (Option 3 table + the submission-time write in the `ForecastTaskCollector` survivor branch from `preEval.atmosphericData()`). Foundation only; nothing reads it yet — mirror the `forecast_score` Pass-1/Pass-2 gating discipline (additive table, write behind a flag, no reader until proven).
2. **DUST repoint** — the **live, year-round misfirer**. Repoint `DustHotTopicStrategy`/`findDustDays` to the carrier; verify it fires off survivors not rejects; **threshold re-verification against the survivor aerosol population** (checkable now — §5). This is the stage that fixes a real wrong-signal in production.
3. **SURGE repoint** — dormant (needs storms). Repoint `findSurgeDaysByRiskLevel`; verify via fixtures/sim now, real frequency in storm season.
4. **SNOW_FRESH + SNOW_TOPS repoint** — dormant (needs winter). Repoint `findSnowFreshDays`/`findSnowTopsDays` (carry `humidity` for the MIST variant); verify via fixtures/sim now, real frequency in winter.
5. Each stage verified against real survivor data before the next; leave the legacy `forecast_evaluation` writes + queries in place until all four are repointed (then a separate cleanup can retire them, as with inversion's `findInversionDaysByPotential`).

**Per-stage gate:** detector reads the carrier (survivors), AND fires at a sensible frequency against the survivor population.

---

## Section 5 — threshold re-verification (the Stage A guard, applied to four)

Every threshold was calibrated against the **reject** population. Reading the right surface is necessary but not sufficient.

- **DUST (verifiable now — aerosol is year-round):** after repoint, query the carrier over a representative window and count survivor location/days tripping the proxy (`AOD>0.3 OR dust>50`, `PM2.5<35`). Compare to the reject-population count the detector produces today — the population shift is the thing to see. Target: non-zero but not flooding. Recalibrate if the survivor distribution differs materially.
- **SURGE / SNOW (dormant):** verify against forced fixtures / the admin sim now (the detectors *can* read the carrier and fire at the band); real fire-frequency verification waits for storm/winter season. "Green at build time ≠ seen firing live" — same posture as the snow trio's seasonal caveat.
- All frequency checks run against prod/staging (remote DB — Chris's gate per stage), as in Stage A.

---

## What surprised me most

Option 1 — the "elegant" one, threading the data through to a single write-point at `buildResult` — **isn't actually possible as imagined**, and seeing why reframes the whole stage. The batch boundary is asynchronous and restart-surviving, so there is no in-memory hand-off to thread; the only way to have the atmospheric data at result time is to **persist it at submission first** — at which point the result-time re-write is dead weight, because the detector can just read the submission-time row. The async boundary that *caused* the bug (atmospheric data discarded before the result returns) also **dictates the fix**: stop trying to carry the data forward to where the score lands, and instead write it where it already lives — at submission. The data was never missing; it was being thrown away one step too early, and the cheapest correct move is to catch it before the throw, not to reconstruct it after. The second surprise was smaller but worth it: the "vestigial" tide-repository injection from Stage A Step 0 turned out to be a *live sixth reject-sampling read* — a reminder to verify "looks unused" against the code every time.

---

## Open questions for Chris

1. **Two survivor surfaces, or one?** Recommendation is a dedicated `survivor_atmosphere` sibling table (readings-shaped) alongside `forecast_score` (score-shaped), so detectors read whichever fits the signal. Is that two-surface split acceptable, or do you want a single unified survivor read model (which would mean forcing readings into a score-ish shape — not recommended)? **This is the carrier decision.**
2. **Carrier table name + scope of fields:** minimal union (`aerosol_optical_depth, dust, pm2_5, surge_risk_level, snow_depth_m, freezing_level_m, humidity`) only, or also the supporting surge metres for the serving path? Lean: minimal now, add metres only if the serving path needs them.
3. **Write seam:** alongside the EVALUATED disposition in `ForecastTaskCollector` (recommended — survivor confirmed, data in hand), or in `BatchRequestFactory` per-request? Lean: the collector, to keep `BatchRequestFactory` a pure request builder.
4. **Sync/admin path parity:** the sync "Run Forecast" path writes survivors to `forecast_evaluation` *with* atmospheric columns (via `buildEntity`), so its detectors already see survivors there. Should the carrier also be written on the sync path for consistency (so both paths feed one surface), or is sync left reading `forecast_evaluation`? Lean: write the carrier on both paths (single seam), as inversion does, so behaviour can't drift.
5. **Reject inclusion:** confirm the intent is **survivors-only** on the carrier (recommended, matching `forecast_score`). If dusty *rejected* days should still surface dust, that's a different semantic and argues for Option 0 — flag if so.
6. **KING_TIDE/SPRING_TIDE (correction to Stage A Step 0):** their `ForecastEvaluationRepository` is **not vestigial** — `parseTideAlignmentCounts(...)` → `countTideAlignedByTargetType(date)` reads `forecast_evaluation.tide_aligned` for the alignment-info enrichment (a secondary, reject-sampled read; not the firing condition, which is the deterministic lunar/tide cache). Out of Stage B's four, but it's a *sixth* reject-sampling touchpoint on `tide_aligned` — track it for the same survivor-surface treatment or accept it as enrichment-only. **Not** a dead dependency to remove.

---

## Appendix — key references

| Concern | File:line |
|---|---|
| AtmosphericData computed per candidate at collection | `service/batch/ForecastTaskCollector.java:351` |
| Survivor task carries AtmosphericData | `service/batch/ForecastTaskCollector.java:432`; `service/evaluation/EvaluationTask.java:74` (required `data`) |
| AtmosphericData → prompt text only | `service/evaluation/BatchRequestFactory.java:66` |
| custom_id = identity only | `service/evaluation/CustomIdFactory.java` (`forForecast`/`parse`) |
| Batch persistence is batch-level | `service/batch/BatchSubmissionService.java`; `db/migration/V73__forecast_batch.sql` |
| Result handler has no AtmosphericData (tide re-derived) | `service/evaluation/ForecastResultHandler.java:175,386,399` |
| Rejects populated via buildEntity | `service/ForecastService.java:593` (dust 619, surge 669, snow 677–680) |
| Submission-time per-candidate row (disposition) | `entity/ForecastRunDispositionEntity.java`; `service/batch/ForecastDispositionService.java`; `db/migration/V101__forecast_run_disposition.sql` (append-per-cycle, no location/date/event uniqueness) |
| forecast_score dual-write is RESULT-time | `service/evaluation/ForecastResultHandler.java:406`; `service/evaluation/ForecastScoreWriter.java:write` |
| DUST detector + query | `service/DustHotTopicStrategy.java`; `repository/ForecastEvaluationRepository.java:142-153` |
| SURGE detector + query (HIGH only) | `service/StormSurgeHotTopicStrategy.java:39`; `…Repository.java:122-128` |
| SNOW_FRESH (+humidity) / SNOW_TOPS queries | `service/SnowFreshHotTopicStrategy.java`; `service/SnowTopsHotTopicStrategy.java`; `…Repository.java:166-194` |
| KING/SPRING read tide_aligned counts (not vestigial) | `service/KingTideHotTopicStrategy.java:114`; `service/SpringTideHotTopicStrategy.java:105`; `…Repository.java:88-90` (`countTideAlignedByTargetType`) |
