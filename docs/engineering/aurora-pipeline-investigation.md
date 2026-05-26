# Aurora Pipeline Investigation — code-path sharing vs divergence

**Status:** Read-only investigation. Completes the picture the nightly-pipeline-orchestration doc deliberately left unmapped: *do the aurora batch and the forecast batch share infrastructure, or do they run on parallel machinery?*

**Scope:** code-path sharing at each layer of the batch pipeline. Aurora's forecasting correctness is out of scope.

---

## TL;DR

**Verdict on Q3 (the crux):** Aurora batch is **(a) sharing** the forecast batch's transport, polling, result processing, and observability infrastructure — but **(b) divergent** in three specific places:

1. **Prompt construction** — aurora batch builds its request inline in `EvaluationServiceImpl.submitAurora()` without a system prompt block and without `CacheControlEphemeral`, while forecast batch goes through `BatchRequestFactory` which always sets the cached system block. **Aurora batch is missing prompt caching.**
2. **Candidate selection and weather prefetch** — `WeatherTriageService` (aurora's own triage + Open-Meteo call) is independent of `ForecastTaskCollector` (forecast's collection + prefetch). The two have different geometries (northward transect vs solar horizon), so the divergence is legitimate at the *geometry* layer but the *Open-Meteo plumbing* is duplicated machinery.
3. **Disposition observability** — aurora batches are invisible to `forecast_run_disposition`. The Job Run detail view can reconcile the forecast cycle's "242 candidates → 163 evaluated · 48 hard-constraint · 41 triaged · …" but the aurora cycle has no equivalent. Only raw `api_call_log` rows exist for aurora.

The shared layer is substantial: `BatchSubmissionService`, `BatchPollingService`, `BatchResultProcessor`, the `forecast_batch` table (discriminated by `BatchType`), `job_run`, `api_call_log`, `CustomIdFactory`, the `EvaluationTask` sealed type, and the `EvaluationService.submit/.evaluateNow` API. That's the bulk of the pipeline. The divergences are leaves, not trunks.

**Verdict on Q5:** No ordering dependency exists between aurora and the forecast→briefing edge. The briefing reads `AuroraStateCache` at evaluation time as a non-blocking in-memory check. The "one edge, aurora parallel" picture for the orchestrator holds.

---

## Q1 — `aurora_polling` dependencies

**Job:** `aurora_polling` (V68, FIXED_DELAY 5 min, initial 60 s — *not* "~30 min" as the brief stated; the seed is 300 000 ms).

**Entry chain:** `AuroraPollingJob.poll()` → `AuroraOrchestrator.runForecastLookahead(window)` always, plus `AuroraOrchestrator.run()` only when below nautical twilight ([AuroraPollingJob.java:107](backend/src/main/java/com/gregochr/goldenhour/service/aurora/AuroraPollingJob.java:107)).

**External data — NOAA SWPC only** (no Met Office; see "Stale CLAUDE.md note" below):
- Kp index (15 min cache) — `NoaaSwpcClient.fetchKpIndex`
- Kp forecast (15 min cache) — `fetchKpForecast`
- OVATION aurora probability (5 min cache) — `fetchOvation`
- Solar wind mag + plasma (1 min cache) — `fetchSolarWind`
- Space weather alerts (5 min cache) — `fetchAlerts`
- All US public domain, no API key required. Per-endpoint cached inside `NoaaSwpcClient`.

**What it writes:** `AuroraStateCache` only. The cache is **in-memory, `volatile` fields, no DB persistence**:
- `state` (IDLE / ACTIVE)
- `currentLevel` (`AlertLevel`)
- `cachedScores` (`List<AuroraForecastScore>`)
- `lastTriggerType`, `lastTriggerKp`, `darkSkyLocationCount`, `clearLocationCount`, `activeSince`

If the JVM restarts, the state cache resets to IDLE. The polling job picks it back up within 5 min. There's no `aurora_state` table.

**Does it call Claude?** Yes — but only when the FSM emits `Action.NOTIFY` (state IDLE → ACTIVE on a fresh MODERATE/STRONG alert, or ACTIVE → ACTIVE on escalation). On NOTIFY, `AuroraOrchestrator.scoreAndCache()` runs Bortle filter → weather triage → `EvaluationService.evaluateNow(EvaluationTask.Aurora, ...)` — a **synchronous** Claude call. Normal QUIET polling is pure NOAA-to-cache with no Claude expense.

**Notification lifecycle**:
- IDLE + MODERATE/STRONG → NOTIFY (fire push/email, transition to ACTIVE, score and cache locations)
- ACTIVE + higher level → NOTIFY (escalation)
- ACTIVE + same/lower alertable level → SUPPRESS (no duplicate alert)
- ACTIVE + QUIET/MINOR → CLEAR (transition to IDLE, drop cached scores)
- Two trigger paths share the same FSM: `runForecastLookahead` (daytime planning) and `run` (real-time after nautical twilight).

---

## Q2 — `aurora_batch_evaluation` dependencies

**Job:** `aurora_batch_evaluation` (V73, CRON `0 30 3 * * *`, status **PAUSED** in the seed). Whether it is currently ACTIVE in prod can only be confirmed by reading `scheduler_job_config` against the live DB.

**Entry chain:** `ScheduledBatchEvaluationService.submitAuroraBatch()` → `doSubmitAuroraBatch()` ([ScheduledBatchEvaluationService.java:355](backend/src/main/java/com/gregochr/goldenhour/service/batch/ScheduledBatchEvaluationService.java:355)).

**Inputs:**
- Does **not** read `AuroraStateCache`. Fetches fresh NOAA data (`noaaSwpcClient.fetchAll()`) and re-derives the alert level via `auroraOrchestrator.deriveAlertLevel(spaceWeather)`. **Independent of `aurora_polling`.**
- Does need weather: calls `weatherTriageService.triage(candidates)` which makes its own `OpenMeteoClient.fetchCloudOnlyBatch()` call (northward transect for each location, dedup'd at 0.1° grid). **Does not reuse the forecast batch's Open-Meteo prefetch.**
- Does not depend on the forecast batch in any way.

**Outputs:**
- One `forecast_batch` row with `batch_type = AURORA`.
- One `job_run` row (RunType.AURORA_EVALUATION; via `JobRunService.startBatchRun`).
- One `api_call_log` row per batch submission, written by `AuroraResultHandler.persistBatchLog` on result arrival.
- On result: `AuroraStateCache.updateScores(allScores)` — in-memory. **Does not write to `cached_evaluation`, `forecast_evaluation`, or `aurora_forecast_result`.**

**Note on `aurora_forecast_result`:** This table exists (V57) but is owned exclusively by `AuroraForecastRunService` — the **admin-driven manual** aurora forecast (UI in the Admin tab). It is not part of the `aurora_batch_evaluation` path. Three aurora code paths exist:
1. `AuroraPollingJob` (real-time/forecast-lookahead, sync Claude when NOTIFY) — writes `AuroraStateCache`.
2. `ScheduledBatchEvaluationService.submitAuroraBatch()` (overnight batch) — writes `AuroraStateCache`.
3. `AuroraForecastRunService` (admin manual) — writes `aurora_forecast_result`.

For the orchestrator question, only (2) is relevant.

---

## Q3 — Component-by-component comparison (the crux)

| Component | Forecast batch | Aurora batch | Verdict |
|-----------|----------------|--------------|---------|
| **Candidate collection** | `ForecastTaskCollector.collectScheduledBatches()` — reads cached briefing, stability gating per `daysAhead`, tide alignment, freshness check, disposition recording | `locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(threshold)` — 1-liner | **Legitimately different.** Aurora's selection rule (one Bortle threshold by alert level) is genuinely simpler than the forecast batch's multi-dimensional eligibility. |
| **Weather prefetch** | `ForecastTaskCollector` does bulk Open-Meteo forecast + horizon-cloud prefetch for solar horizon geometry (113 km bearing ±15°) | `WeatherTriageService.triage()` does its own dedup'd batch fetch for northward transect (50/100/150 km, bearing 0°) | **Partially divergent.** Both call `OpenMeteoClient.fetchCloudOnlyBatch()`, so the plumbing is shared; the geometry and threshold logic is different and legitimately so (aurora needs northward clarity, sunset needs western horizon clarity). |
| **Prompt construction** | `BatchRequestFactory.buildForecastRequest()` → `PromptBuilder` selects coastal/inland/surge builder, sets cached system block with `CacheControlEphemeral`, sets `outputConfig` | `EvaluationServiceImpl.submitAurora()` builds inline: `Request.builder().params(... .model(...).maxTokens(1024).addUserMessage(userMessage).build())` — **no system block, no cache control, no output config** | **DIVERGENT.** This is a real gap: the aurora system prompt is identical across every aurora call (`ClaudeAuroraInterpreter.SYSTEM_PROMPT`) and is exactly the case prompt caching exists for. Currently it isn't cached because it isn't a system block. The aurora user message includes the full prompt inline. |
| **Anthropic Batch API submission** | `BatchSubmissionService.submit(requests, BatchType.FORECAST, ...)` | `BatchSubmissionService.submit(requests, BatchType.AURORA, ...)` | **SHARED.** Same method, type discriminator. |
| **Custom ID** | `CustomIdFactory.forForecast(locationId, date, targetType)` → `fc-…` | `CustomIdFactory.forAurora(alertLevel, date)` → `au-…` | **SHARED factory, different schemas.** Correct — the domain shapes differ. |
| **Batch tracking entity** | `forecast_batch` row, `BatchType.FORECAST` | `forecast_batch` row, `BatchType.AURORA` | **SHARED table.** Same entity, type discriminator. |
| **Result polling** | `BatchPollingService.pollPendingBatches()` — polls all SUBMITTED rows, type-agnostic | Same poller, same query | **SHARED.** |
| **Result dispatch** | `BatchResultProcessor.processResults(batch)` → `processForecastBatch` | `BatchResultProcessor.processResults(batch)` → `processAuroraBatch` | **SHARED entry, type-discriminated dispatch.** |
| **Per-response parsing** | `ForecastResultHandler.parseBatchResponse()` → groups by region\|date\|targetType, flushes to `cached_evaluation` | `AuroraResultHandler.processBatchResponse()` → re-triages weather, parses, writes `AuroraStateCache.updateScores()` | **Legitimately different destinations.** Forecast writes durable cache rows; aurora writes in-memory FSM. |
| **`api_call_log`** | Via `ForecastResultHandler` → `jobRunService.logBatchResult` | Via `AuroraResultHandler.persistBatchLog` → `jobRunService.logBatchResult` | **SHARED writer.** |
| **`job_run`** | `RunType.SHORT_TERM` / etc., via `JobRunService.startBatchRun` | `RunType.AURORA_EVALUATION`, same starter | **SHARED.** |
| **Stability gating** | `ForecastStability` × `daysAhead` policy in `ForecastTaskCollector.resolveEligibility` | n/a — aurora has no concept of multi-day stability; its temporal window is fixed by the NOAA prediction itself | **Legitimately different.** Stability is a property of the atmosphere over the forecast window, not the geomagnetic environment. |
| **Disposition tracking** | `ForecastDispositionService.persist()` writes one `forecast_run_disposition` row per candidate per cycle, surfacing the "242 candidates → 163 evaluated · 48 hard-constraint · …" reconciliation in the Job Run detail view | **None.** Aurora batch passes through no disposition recorder; rejected-by-Bortle, triage-rejected, and "no NOAA data" cases are visible only in log lines, not the DB | **DIVERGENT (gap).** Aurora is invisible to the no-SSH reconciliation that disposition tracking enables for forecasts. |
| **Cycle identity / completion signal** | None — each cron firing is independent; orchestrator must create it | None — same | **Both stages have the same gap.** Consistent. |

### What the table shows

The shared region — submission, polling, dispatch, observability writers, custom-id factory, evaluation task taxonomy — is exactly the convergence target the stabilisation work aimed at. Aurora rides that train.

The divergent region — prompt caching, weather prefetch geometry, disposition recording — is a mix of legitimate domain difference (geometry, stability) and *not-yet-finished* convergence (prompt caching, disposition tracking).

So aurora batch is **not** a parallel implementation of "the batch pipeline." It is **the batch pipeline used by a second caller with two specific divergences from the forecast caller's contract.**

---

## Q4 — Implications

### What's good
- The pipeline is single-code-path at the layers that matter for orchestration (submission, polling, completion signalling, `forecast_batch` row lifecycle, `api_call_log`).
- The "wait for batch set complete" mechanism the orchestrator builds will observe aurora batches identically to forecast batches — they're rows in the same table with the same status transitions.
- Adding a cycle identity (whether a `cycle_id` column or an in-memory handle set) covers both stages uniformly.

### Recorded as single-code-path convergence candidates (future, NOT now)
1. **Aurora batch missing prompt caching.** `EvaluationServiceImpl.submitAurora()` should route through `BatchRequestFactory` (or a sibling aurora method) that sets a cached system block. The aurora system prompt is large (~30 lines) and identical across calls — this is the precise use case for `CacheControlEphemeral`. Estimated wasted spend per call is small in absolute terms (aurora batches are 1 request) but the divergence is a single-code-path violation of the kind the stabilisation work targeted.
2. **Aurora batch invisible to disposition tracking.** No DB record of "which Bortle-eligible candidates were rejected by weather triage" for an aurora cycle. The Job Run detail view can show *that* an aurora batch ran but not *which candidates were considered and dropped why.* Bringing aurora into `forecast_run_disposition` (or a sibling table) is the natural follow-up after the orchestrator lands. Same observability philosophy as recent forecast work.
3. **Two Open-Meteo prefetch pathways.** The forecast batch's prefetch and `WeatherTriageService`'s prefetch are independent fetches with different geometries. Not a bug — they need different data — but if intraday batch work eventually adds new prefetch geometries, the codebase will grow a third independent fetcher. Worth keeping an eye on a unification of "Open-Meteo prefetch for a geometry" abstraction over time.

### Stale CLAUDE.md note (worth flagging)
CLAUDE.md still lists `MetOfficeSpaceWeatherScraper` as part of the aurora pipeline. **V90 deleted the `met_office_scrape` scheduler row** and the class is not referenced anywhere in `src/main/java/` (grep returns no matches). The Met Office narrative has been fully removed from the aurora pipeline. Either the class file is also gone or it's dead code; either way the CLAUDE.md description is out of date and should be corrected in a future pass (out of scope here).

---

## Q5 — Does aurora affect the orchestrator design?

**No.** The orchestrator's "one edge" finding holds.

The briefing's read of aurora state is non-blocking and in-memory:

- `BriefingService.getCachedBriefing()` calls `auroraSummaryBuilder.buildAuroraTonightCached()` / `.buildAuroraTomorrowCached()` ([BriefingService.java:187](backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java:187)).
- `BriefingBestBetAdvisor.buildRollupJson()` calls `auroraStateCache.isActive()` / `.getCurrentLevel()` / `.getCachedScores()` ([BriefingBestBetAdvisor.java:635](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingBestBetAdvisor.java:635)).
- All reads are `volatile` field lookups against `AuroraStateCache`. No blocking, no completion check, no waiting.

When the 04:00 briefing runs:
- If the 03:30 aurora batch finished and updated the state cache, the briefing sees the fresh scores.
- If the 03:30 aurora batch is still SUBMITTED, the briefing reads whatever was there before (or `isActive() == false` if QUIET).
- The briefing succeeds either way. Aurora is *enrichment*, not a prerequisite.

This is structurally the same relationship as the briefing has to the forecast batch — except that the forecast batch is *the* signal the briefing was implicitly waiting on (silently via cron buffer), while aurora is genuinely optional content. The orchestrator should treat them differently for that reason: the forecast batch belongs in the cycle, aurora does not.

---

## What surprised me most

The aurora batch is **a half-finished convergence, not a parallel implementation.** I expected to find either (a) "aurora is its own pipeline, leave it alone" or (b) "aurora is fully unified with the forecast batch." Instead the codebase has done the hard work of unifying the transport/polling/dispatch layer — exactly the high-value convergence — but left the prompt-builder and disposition divergences in place. The shared layer is the *common* part; the divergent layer is the *aurora-specific* part. That's structurally correct, but two of the three divergences (prompt caching, disposition) look more like "we'll come back to this" than principled separation.

The two divergent leaves both look like the same kind of work the stabilisation effort already did: a parallel implementation that should converge onto the unified path. Worth a small explicit follow-up task each, after the orchestrator.

---

## Open questions

1. **Is `aurora_batch_evaluation` actually ACTIVE in prod?** V73 seeds it PAUSED. If it's still paused, the only aurora Claude calls are from `AuroraPollingJob` on rare NOTIFY events (sync). The orchestrator scope question is unchanged either way; the convergence-debt size is just smaller if the batch is paused.
2. **Should `AuroraForecastRunService` (admin manual aurora forecast) be in scope for any future convergence?** It writes a different table (`aurora_forecast_result`) and is admin-on-demand. Probably (c)-genuinely-separate, but worth confirming when the convergence candidates above get prioritised.
3. **Stale CLAUDE.md text on aurora.** "`MetOfficeSpaceWeatherScraper` (Jsoup scraper, 60 min refresh)" should be removed (V90 retired it). Out of scope for this investigation.
4. **The `aurora_polling` schedule.** Brief stated ~30 min; V68 seeds 5 min (300 000 ms). Confirm live cadence — the implementation polls every 5 min by default which is correct for a notification-driven service but worth checking the live `fixed_delay_ms` hasn't been tuned.

---

## Reference: files inspected

- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/EvaluationServiceImpl.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/AuroraResultHandler.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchSubmissionService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchPollingService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchResultProcessor.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/ScheduledBatchEvaluationService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastDispositionService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/aurora/AuroraPollingJob.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/aurora/AuroraOrchestrator.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/aurora/AuroraStateCache.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/aurora/WeatherTriageService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/aurora/ClaudeAuroraInterpreter.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/aurora/AuroraForecastRunService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/BriefingAuroraSummaryBuilder.java`
- `backend/src/main/java/com/gregochr/goldenhour/client/NoaaSwpcClient.java`
- Migrations: V57, V68, V73, V90
