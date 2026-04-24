# PhotoCast Forecast Evaluation Architecture (v2.11.8 baseline)

## Executive summary

PhotoCast's forecast evaluation system has 7 distinct code paths that invoke the Claude API, spanning scheduled batch processing, real-time SSE evaluation, force-submit diagnostics, and aurora assessment. These paths share core primitives — prompt building, rating validation, cache management — but express them through copy-paste rather than shared abstractions. The system prompt is static per location type (inland vs coastal), making it highly cache-friendly across batch requests. Gates and filters (triage, stability, freshness) are applied inconsistently: the scheduled batch path applies all 8 gates, the SSE path applies only 2, and the force-submit path intentionally applies none. Observability coverage is thorough for batch paths (api_call_log, job_run, evaluation_delta_log, forecast_batch) but has gaps in the command executor pipeline, which writes to forecast_evaluation but not to cached_evaluation or evaluation_delta_log. The largest consolidation opportunity is unifying the 4 batch request-building paths (scheduled near/far, force, JFDI) behind a single `BatchRequestFactory`, and extracting cache key construction, custom ID parsing, and builder selection into shared utilities. Aurora evaluation should remain a separate subsystem — its prompt, output schema, and state machine are fundamentally different from forecast evaluation. Test coverage is strong (490+ tests across evaluation-related classes) but integration tests exercising full batch submission → polling → result processing → cache write are absent.

---

## Section 1: Path inventory

### Path 1: SSE real-time evaluation (user-facing)

| Attribute | Value |
|-----------|-------|
| **Entry point** | `BriefingEvaluationController.evaluate()` → `GET /api/briefing/evaluate` |
| **Call chain** | Controller → `BriefingEvaluationService.evaluateRegion()` → `evaluateSingleLocation()` → `ForecastService.evaluateAndPersist()` → `ClaudeEvaluationStrategy.evaluateWithDetails()` → `invokeClaude()` → `AnthropicApiClient.createMessage()` |
| **Sync/async** | Async (spawned via executor, SSE streaming to client) |
| **Single/multi** | Multi-location, evaluated sequentially per location |
| **Trigger source** | User clicks region/date/event in briefing drill-down UI |
| **Auth** | PRO_USER or ADMIN |
| **Gates** | Verdict filter (GO/MARGINAL only), weather triage. **No** stability window, **no** cache freshness check |
| **Prompt builder** | `PromptBuilder` or `CoastalPromptBuilder` selected by `data.tide() != null` |
| **Model** | `modelSelectionService.getActiveModel(RunType.SHORT_TERM)` |
| **Result destination** | In-memory cache (`BriefingEvaluationService.cache`), DB (`cached_evaluation` with source=SSE), `forecast_evaluation` table |
| **Observability** | `job_run` created and completed; `api_call_log` per call (is_batch=false); `evaluation_delta_log` if prior cache entry exists |
| **Rating validation** | `RatingValidator.validateRating()` applied |

### Path 2: Scheduled overnight forecast batch

| Attribute | Value |
|-----------|-------|
| **Entry point** | `ScheduledBatchEvaluationService.submitForecastBatch()` — registered as `near_term_batch_evaluation` in `DynamicSchedulerService` |
| **Call chain** | `submitForecastBatch()` → `doSubmitForecastBatch()` → `collectForecastTasks()` → `prefetchBatchWeather()` → `prefetchBatchCloudPoints()` → triage loop → `buildForecastRequest()` per location → `submitBatch()` → `anthropicClient.messages().batches().create()` |
| **Sync/async** | Async (batch API — submit and poll later) |
| **Single/multi** | Multi-location, up to ~800 requests per batch |
| **Trigger source** | Cron via `DynamicSchedulerService` |
| **Auth** | System (scheduler) |
| **Gates** | PAST_DATE, CACHED (freshness per stability), VERDICT (GO/MARGINAL), UNKNOWN_LOCATION, TRIAGED (weather + tide), STABILITY (window by forecast reliability), ERROR (per-location exception), PREFETCH_FAILURE (bulk weather threshold) |
| **Prompt builder** | `PromptBuilder` or `CoastalPromptBuilder` selected by `data.tide() != null` in `buildForecastRequest()` |
| **Model** | Near-term (T+0/T+1): `getActiveModel(BATCH_NEAR_TERM)`; far-term (T+2/T+3): `getActiveModel(BATCH_FAR_TERM)` |
| **Result destination** | `forecast_batch` entity (SUBMITTED status); results arrive via `BatchPollingService` → `BatchResultProcessor` → in-memory cache + `cached_evaluation` (source=BATCH) |
| **Observability** | `job_run` created at submission; `forecast_batch` tracks lifecycle; `api_call_log` per result (is_batch=true, batch_id, custom_id); `evaluation_delta_log` on cache write; extensive `[BATCH DIAG]` structured logging |
| **Rating validation** | `RatingValidator.validateRating()` in `BatchResultProcessor` |

### Path 3: Admin-triggered region batch

| Attribute | Value |
|-----------|-------|
| **Entry point** | `BatchAdminController.submitScheduledBatch()` → `POST /api/admin/batches/submit-scheduled` |
| **Call chain** | Controller → `ScheduledBatchEvaluationService.doSubmitForecastBatchForRegions()` → same triage/build/submit as Path 2 but filtered by region IDs |
| **Sync/async** | Async (batch API) |
| **Single/multi** | Multi-location, filtered to selected regions |
| **Trigger source** | Admin UI click |
| **Auth** | ADMIN only |
| **Gates** | Same as Path 2 but uses near-term model only for all dates |
| **Prompt builder** | Same selection as Path 2 |
| **Model** | `getActiveModel(BATCH_NEAR_TERM)` only (no far-term split) |
| **Result destination** | Same as Path 2 |
| **Observability** | Same as Path 2 |
| **Rating validation** | Same as Path 2 |

### Path 4: Force-submit batch (admin diagnostics)

| Attribute | Value |
|-----------|-------|
| **Entry point** | `BatchAdminController.forceSubmit()` → `POST /api/admin/batches/force-submit` |
| **Call chain** | Controller → `ForceSubmitBatchService.forceSubmit()` → triple-nested loop (location × date × event) → weather fetch → prompt building → `anthropicClient.messages().batches().create()` |
| **Sync/async** | Async (batch API) |
| **Single/multi** | Single-region, single-date, single-event |
| **Trigger source** | Admin UI click |
| **Auth** | ADMIN only |
| **Gates** | **None** — force means "bypass all gates" |
| **Prompt builder** | `PromptBuilder` or `CoastalPromptBuilder` selected by `data.tide() != null` |
| **Model** | `getActiveModel(BATCH_NEAR_TERM)` |
| **Result destination** | `forecast_batch` entity; results via `BatchResultProcessor` |
| **Observability** | `job_run` created; `api_call_log` per result; no `evaluation_delta_log` |
| **Rating validation** | Via `BatchResultProcessor` |

### Path 5: JFDI batch (admin bypass)

| Attribute | Value |
|-----------|-------|
| **Entry point** | `BatchAdminController.submitJfdiBatch()` → `POST /api/admin/batches/submit-jfdi` |
| **Call chain** | Controller → `ForceSubmitBatchService.submitJfdiBatch()` → triple-nested loop (location × T+0..T+3 × SUNRISE/SUNSET) → weather fetch → prompt building → `anthropicClient.messages().batches().create()` |
| **Sync/async** | Async (batch API) |
| **Single/multi** | Multi-location, all dates T+0 to T+3, both events, selected regions |
| **Trigger source** | Admin UI click |
| **Auth** | ADMIN only |
| **Gates** | **None** — "just do it" bypasses everything |
| **Prompt builder** | Same selection as Path 4 |
| **Model** | `getActiveModel(BATCH_NEAR_TERM)` |
| **Result destination** | `forecast_batch` entity; results via `BatchResultProcessor` |
| **Observability** | `job_run` created; `api_call_log` per result |
| **Rating validation** | Via `BatchResultProcessor` |

### Path 6: Command executor pipeline (legacy inline)

| Attribute | Value |
|-----------|-------|
| **Entry point** | `ForecastController.runForecast()` → `POST /api/forecast/run` (+ `/run/short-term`, `/run/long-term`, `/run/very-short-term`) |
| **Call chain** | Controller → `ForecastCommandFactory.create()` → `ForecastCommandExecutor.execute()` → `executeThreePhasePipeline()` → Phase 1: triage → Phase 2: sentinel sampling (optional) → Phase 3: full evaluation → `ClaudeEvaluationStrategy.evaluate()` per location → `AnthropicApiClient.createMessage()` |
| **Sync/async** | Async (spawned via executor), synchronous Claude calls per location |
| **Single/multi** | Multi-location, parallel via virtual threads |
| **Trigger source** | Admin UI click |
| **Auth** | ADMIN only |
| **Gates** | Weather triage (Phase 1), sentinel sampling threshold (Phase 2), but **no** stability window, **no** cache freshness, **no** briefing verdict |
| **Prompt builder** | `PromptBuilder` or `CoastalPromptBuilder` |
| **Model** | From `ForecastCommand` (resolved by `ForecastCommandFactory` using `ModelSelectionService`) |
| **Result destination** | `forecast_evaluation` table directly (via `ForecastService.evaluateAndPersist()`). **Does NOT write to cached_evaluation** |
| **Observability** | `job_run` created and completed; `RunProgressTracker` for phase progress. **No api_call_log per Claude call. No evaluation_delta_log** |
| **Rating validation** | `RatingValidator.validateRating()` applied |

### Path 7: Aurora batch (scheduled)

| Attribute | Value |
|-----------|-------|
| **Entry point** | `ScheduledBatchEvaluationService.submitAuroraBatch()` — registered as `aurora_batch_evaluation` in `DynamicSchedulerService` |
| **Call chain** | `submitAuroraBatch()` → `doSubmitAuroraBatch()` → `NoaaSwpcClient.fetchAll()` → `AuroraOrchestrator.deriveAlertLevel()` → Bortle filter → `WeatherTriageService.triage()` → `ClaudeAuroraInterpreter.buildUserMessage()` → `submitBatch()` → `anthropicClient.messages().batches().create()` |
| **Sync/async** | Async (batch API — single request per batch) |
| **Single/multi** | Single batch request containing all viable locations |
| **Trigger source** | Cron via `DynamicSchedulerService` |
| **Auth** | System (scheduler) |
| **Gates** | Alert level QUIET check (skip entirely), Bortle threshold (by alert level), weather triage (cloud > 75%) |
| **Prompt builder** | `ClaudeAuroraInterpreter.buildUserMessage()` (separate system, not `PromptBuilder`) |
| **Model** | `getActiveModel(RunType.AURORA_EVALUATION)` |
| **Result destination** | `forecast_batch` entity; results via `BatchResultProcessor.processAuroraBatch()` → `AuroraStateCache` |
| **Observability** | `job_run` created; `forecast_batch` tracks lifecycle; `api_call_log` per result |
| **Rating validation** | None (aurora uses 1-5 stars parsed directly, no `RatingValidator` call) |

### Path 8: Aurora real-time (non-batch)

| Attribute | Value |
|-----------|-------|
| **Entry point** | `AuroraOrchestrator.run()` or `runForecastLookahead()` — triggered by `AuroraPollingJob` (scheduled) or `AuroraController.adminRun()` (admin manual) |
| **Call chain** | `AuroraOrchestrator.run/runForecastLookahead()` → `scoreAndCache()` → `ClaudeAuroraInterpreter.interpret()` → `AnthropicApiClient.createMessage()` |
| **Sync/async** | Synchronous |
| **Single/multi** | Single Claude call containing all viable locations |
| **Trigger source** | Cron (5-min polling, night-only) or admin UI click |
| **Auth** | System or ADMIN |
| **Gates** | Bortle threshold, weather triage, state machine (suppress duplicate NOTIFYs) |
| **Prompt builder** | `ClaudeAuroraInterpreter` (separate system prompt at lines 65-112) |
| **Model** | `getActiveModel(RunType.AURORA_EVALUATION)` |
| **Result destination** | `AuroraStateCache` (in-memory) |
| **Observability** | Logging only. **No job_run. No api_call_log. No evaluation_delta_log** |
| **Rating validation** | None |

---

## Section 2: Duplication map

Each row is a logical operation. Columns show every file and line where it occurs. Rows with count >1 are drift risks.

| Operation | Occurrences | Locations |
|-----------|-------------|-----------|
| **Select builder for location** (`data.tide() != null ? coastal : inland`) | **4** | `ClaudeEvaluationStrategy:131`, `ScheduledBatchEvaluationService:980`, `ForceSubmitBatchService:143`, `ForceSubmitBatchService:268` |
| **Build user message** (with/without surge overload selection) | **4** | `ClaudeEvaluationStrategy:135-137`, `ScheduledBatchEvaluationService:984-986`, `ForceSubmitBatchService:150-152`, `ForceSubmitBatchService:272-274` |
| **Build batch request with cache_control** (system block + CacheControlEphemeral) | **4** | `ClaudeEvaluationStrategy:234-235`, `ScheduledBatchEvaluationService:996-999`, `ForceSubmitBatchService:165-169`, `ForceSubmitBatchService:290-293` |
| **Submit batch to Anthropic** (`batches().create()`) | **3** | `ScheduledBatchEvaluationService:755`, `ForceSubmitBatchService:191 (JFDI)`, `ForceSubmitBatchService:327 (force)` |
| **Parse Claude response and validate rating** | **2** | `ClaudeEvaluationStrategy:149+167` (inline), `BatchResultProcessor:313-318` (batch) |
| **Write result to cached_evaluation** | **2** | `BriefingEvaluationService:240` (SSE path, source=SSE), `BriefingEvaluationService:334` (batch path, source=BATCH) — via `persistToDb()` at line 537 |
| **Write result to forecast_evaluation** | **2** | `ForecastService:433` (via `evaluateAndPersist()`), called from SSE path (`BriefingEvaluationService:589`) and command executor (`ForecastCommandExecutor:547,597`) |
| **Construct cache key** (`region|date|targetType`) | **5** | `ScheduledBatchEvaluationService:823`, `BatchResultProcessor:305`, `BriefingEvaluationService:149`, `BriefingEvaluationService:264`, `BriefingEvaluationService:278` |
| **Parse cache key** (`split("\\|")`) | **2** | `BriefingEvaluationService:540`, `BriefingEvaluationService:349` |
| **Construct custom ID** | **4** | `ScheduledBatchEvaluationService:988-989` (fc-), `ScheduledBatchEvaluationService:722` (au-), `ForceSubmitBatchService:154-155` (jfdi-), `ForceSubmitBatchService:276-278` (force-) |
| **Parse custom ID** | **1** | `BatchResultProcessor:243-262` |
| **Apply weather triage** | **3** | `ForecastService.fetchWeatherAndTriage()` used by: SSE path, batch triage loop, command executor |
| **Apply stability gate** | **2** | `ScheduledBatchEvaluationService:403-410`, `ScheduledBatchEvaluationService:596` (region variant) |
| **Apply cache freshness gate** | **1** | `ScheduledBatchEvaluationService:829` (via `hasFreshEvaluation()`) |
| **Write api_call_log row** | **2** | `JobRunService.logAnthropicApiCall()` (SSE path), `JobRunService.logBatchResult()` (batch path) |
| **Write job_run row** | **3** | `BriefingEvaluationService:197` (SSE), `ScheduledBatchEvaluationService:760` (batch), `ForecastCommandExecutor` (command) |
| **Log evaluation deltas** | **1** | `BriefingEvaluationService:335` (called from both SSE and batch write paths) |

**Highest drift risks:**
- **Cache key construction (5 places)** — any format change must be synchronized across 3 files
- **Builder selection + message building (4 places each)** — identical ternary repeated 4 times
- **Batch request building with cache_control (4 places)** — same TextBlockParam + CacheControlEphemeral pattern

---

## Section 3: Implicit shared logic

### Cache key format

**Canonical form:** `"regionName|date|targetType"` e.g. `"North East|2026-04-23|SUNRISE"`

**Constructed in:** 5 locations (see duplication map). All use string concatenation with `|` separator. No variant spellings found — format is consistent but fragile (no shared constant or utility method).

**Parsed in:** 2 locations in `BriefingEvaluationService` using `split("\\|")` with `parts[0]` = region, `parts[1]` = date, `parts[2]` = targetType.

**Risk:** A region name containing `|` would break parsing. No validation exists.

### Custom ID format

**Canonical forms:**
- Forecast: `fc-{locationId}-{date}-{targetType}` (e.g. `fc-42-2026-04-16-SUNRISE`)
- Aurora: `au-{alertLevel}-{date}` (e.g. `au-MODERATE-2026-04-16`)
- JFDI: `jfdi-{locationId}-{date}-{targetType}` (e.g. `jfdi-42-2026-04-16-SUNRISE`)
- Force: `force-{sanitisedRegion}-{locationId}-{date}-{targetType}` (e.g. `force-LakeDist-42-2026-04-16-SUNRISE`)

**Parsing:** `BatchResultProcessor:243-262` handles all formats by counting parts after `split("-")`. The date field spans 3 parts (`2026-04-16`), so fc- has 6 parts, force- has 7 parts. This implicit coupling is fragile — any format with extra hyphens would break.

**Truncation:** Both JFDI and force formats enforce 64-char Anthropic limit. The fc- format does not explicitly check length (relying on short location IDs).

### Model selection logic

**Who decides:** `ModelSelectionService.getActiveModel(RunType)` — a DB-backed lookup that maps `RunType` → `EvaluationModel`.

**Caller divergence:**
- SSE path uses `RunType.SHORT_TERM`
- Scheduled batch uses `BATCH_NEAR_TERM` + `BATCH_FAR_TERM`
- Region-filtered admin batch uses `BATCH_NEAR_TERM` only (no far-term split)
- Force/JFDI use `BATCH_NEAR_TERM`
- Aurora uses `AURORA_EVALUATION`
- Command executor uses model from `ForecastCommand` (resolved by `ForecastCommandFactory`)

This is architecturally sound — different paths legitimately need different models. No divergence risk here.

### Builder selection logic

**Canonical form:** `data.tide() != null ? coastalPromptBuilder : promptBuilder`

**Identical across all 4 forecast evaluation paths.** No divergence. But expressed as copy-paste rather than a shared method.

### Error handling on Claude API failures

**SSE path:** Exception propagates to SSE emitter, logged, client sees error event. Location is skipped; other locations continue.

**Batch paths:** Failures are per-request in the batch response. `BatchResultProcessor` handles `ERRORED`, `EXPIRED`, `CANCELED` result types and persists error details to `api_call_log` (error_type, error_message).

**Command executor:** Relies on `AnthropicApiClient`'s `@Retry` + `@CircuitBreaker`. If all retries fail, the location is counted as failed in job_run stats.

**Aurora real-time:** Catch-all in `AuroraOrchestrator.scoreAndCache()` — logs and continues.

**Divergence:** The command executor path has no per-call `api_call_log` entry, so failures are invisible in the observability layer. This is the biggest observability gap.

---

## Section 4: Prompt architecture

### System prompts

**`PromptBuilder.SYSTEM_PROMPT`** — `PromptBuilder.java:36-242`
- ~14,400 characters (~3,600 tokens estimated)
- Content: Rating scale (1-5), colour evaluation criteria, aerosol thresholds, directional cloud rules, mist/visibility guidance, location orientation, cloud inversion guidance, bluebell conditions, forecast reliability, clear sky cap rules, horizon cloud structure analysis, output format rules, dual-tier scoring instructions

**`CoastalPromptBuilder.COASTAL_SYSTEM_PROMPT_SUFFIX`** — `CoastalPromptBuilder.java:30-48`
- ~1,040 characters (~260 tokens estimated)
- Content: Tide guidance — sky score first, tide boost (+1 if sky ≥3), king/spring tide classification, storm surge notes, no penalty for misaligned tide

**`CoastalPromptBuilder.getSystemPrompt()`** — `CoastalPromptBuilder.java:56-58`
- Implementation: `return super.getSystemPrompt() + COASTAL_SYSTEM_PROMPT_SUFFIX;`
- Stable byte-identical construction: yes (both are compile-time constants concatenated at runtime; no per-request variation)

**`ClaudeAuroraInterpreter.SYSTEM_PROMPT`** — `ClaudeAuroraInterpreter.java:65-112`
- ~2,000 characters (~500 tokens estimated)
- Content: Aurora photography advisor, 1-5 star rating, Kp-based scoring, moon penalty, cloud cover scaling, trigger type tone guidance
- Completely separate system from forecast prompts

### System prompt callers

All callers of `getSystemPrompt()`:

| Caller | File:Line |
|--------|-----------|
| `ClaudeEvaluationStrategy.invokeClaude()` | `ClaudeEvaluationStrategy.java:234` |
| `ScheduledBatchEvaluationService.buildForecastRequest()` | `ScheduledBatchEvaluationService.java:998` |
| `ForceSubmitBatchService.submitJfdiBatch()` | `ForceSubmitBatchService.java:168` |
| `ForceSubmitBatchService.forceSubmit()` | `ForceSubmitBatchService.java:292` |

### Cache control placement

All 4 forecast paths place `CacheControlEphemeral` on the system block via `TextBlockParam.builder().text(builder.getSystemPrompt()).cacheControl(CacheControlEphemeral.builder().build()).build()`. No `.ttl()` override — uses Anthropic's default 5-minute ephemeral TTL.

The aurora real-time path (`ClaudeAuroraInterpreter.interpret()`) does **not** apply cache control to its system prompt. This is a minor gap — aurora calls are infrequent enough that cache hits would be rare anyway.

### User message construction

`PromptBuilder.buildUserMessage(AtmosphericData)` — `PromptBuilder.java:338-540`
- Fully dynamic per request: location name, target type, solar event time, cloud cover (low/mid/high), visibility, wind, precipitation, humidity, dew point, AOD, PM2.5, dust, boundary layer height, directional cloud data, cloud approach risk, mist/visibility trend
- Conditional blocks: Saharan dust context (AOD >0.3 or dust >50µg/m³), cloud inversion forecast (score ≥7.0), bluebell conditions (April-May, bluebell sites), forecast reliability (TRANSITIONAL/UNSETTLED)
- Appends `PROMPT_SUFFIX` (static, 3 lines at `PromptBuilder.java:245-247`)

Overloaded variant with storm surge: `PromptBuilder.buildUserMessage(AtmosphericData, StormSurgeBreakdown, Double, Double)` — `PromptBuilder.java:328-336` — calls base, inserts surge block via `SurgeBlockFormatter`

`CoastalPromptBuilder.buildUserMessage(AtmosphericData)` — `CoastalPromptBuilder.java:67-78` — calls `super.buildUserMessage(data)`, appends tide data block

### Output config

`PromptBuilder.buildOutputConfig()` — `PromptBuilder.java:548-581`
- **Deterministic** — identical JSON schema across all requests
- Fields: rating (1-5), fiery_sky (0-100), golden_hour (0-100), summary (string), optional basic_* variants, optional inversion_*, optional bluebell_*
- `additionalProperties: false` (strict schema enforcement)

### Cache prefix structure

The Anthropic request order is: system → messages (user). Cache control is on the system block only. This means:
- All inland locations share the same ~3,600-token cache prefix (system prompt)
- All coastal locations share the same ~3,860-token cache prefix (system + suffix)
- User message is outside the cache prefix — varies per request

Within a batch of ~400 inland + ~200 coastal requests, this yields excellent cache hit rates after the first request of each type.

---

## Section 5: Gates and filters

### Pre-evaluation gates (before Claude call)

| Gate | Description | Batch | Region batch | SSE | Command exec | Force/JFDI | Aurora |
|------|-------------|:-----:|:------------:|:---:|:------------:|:----------:|:------:|
| **PAST_DATE** | Skip dates before today | ✓ | ✓ | — | — | — | — |
| **CACHED** | Skip if fresh cache entry exists (per-stability thresholds) | ✓ | ✓ | — | — | — | — |
| **VERDICT** | Skip if not GO/MARGINAL in briefing | ✓ | ✓ | ✓ | — | — | — |
| **UNKNOWN_LOCATION** | Skip if location not found in DB | ✓ | ✓ | — | — | — | — |
| **TRIAGED** (weather) | Skip if weather unsuitable (cloud >80%, precip >2mm, vis <5km) | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| **TRIAGED** (tide) | Skip if tide misaligned with golden/blue hour (SEASCAPE only) | ✓ | ✓ | ✓ | ✓ | — | — |
| **STABILITY** | Skip if days ahead exceeds stability-based window | ✓ | ✓ | — | — | — | — |
| **ERROR** | Skip if weather fetch/augmentation throws | ✓ | ✓ | — | — | — | — |
| **PREFETCH_FAILURE** | Abort entire batch if bulk weather fetch too degraded | ✓ | ✓ | — | — | — | — |
| **BORTLE** | Skip aurora location if Bortle too high for alert level | — | — | — | — | — | ✓ |
| **ALERT_LEVEL** | Skip aurora entirely if Kp = QUIET | — | — | — | — | — | ✓ |
| **STATE_MACHINE** | Skip aurora if duplicate NOTIFY suppressed | — | — | — | — | — | ✓ |

### Post-evaluation gate

| Gate | Description | All paths |
|------|-------------|:---------:|
| **RATING_VALIDATOR** | Reject ratings outside [1,5] range (replace with null) | Forecast: ✓ (all paths). Aurora: — |

### Key gaps

1. **SSE path has no stability window** — users can evaluate any date regardless of forecast reliability
2. **SSE path has no cache freshness check** — will re-evaluate even if a recent batch result exists
3. **Command executor has no briefing verdict filter** — evaluates all enabled locations regardless of weather forecast
4. **Force/JFDI intentionally bypass all gates** — by design, for diagnostics

### Skip logging consistency

All batch gates log with `[BATCH DIAG] SKIP {location} | date={} event={} | reason={REASON}` pattern. SSE and command executor paths do not use this pattern — triage skips are logged at INFO with different format. No unified skip-reason taxonomy exists across paths.

---

## Section 6: Observability coverage

| Path | `job_run` | `forecast_batch` | `api_call_log` | `evaluation_delta_log` | `cached_evaluation` | `forecast_evaluation` | Structured logs |
|------|:---------:|:-----------------:|:--------------:|:---------------------:|:-------------------:|:--------------------:|:---------------:|
| SSE real-time | ✓ created + completed | — | ✓ per call (is_batch=false) | ✓ if prior cache entry | ✓ source=SSE | ✓ via evaluateAndPersist | INFO level |
| Scheduled batch | ✓ created | ✓ SUBMITTED→COMPLETED | ✓ per result (is_batch=true, batch_id, custom_id) | ✓ on cache write | ✓ source=BATCH | — | `[BATCH DIAG]` |
| Region batch | Same as scheduled | Same | Same | Same | Same | — | `[BATCH DIAG]` |
| Force-submit | ✓ created | ✓ SUBMITTED→COMPLETED | ✓ per result (is_batch=true) | — | ✓ via BatchResultProcessor | — | Minimal |
| JFDI | Same as force | Same | Same | — | Same | — | Minimal |
| Command executor | ✓ created + completed | — | **— (GAP)** | **— (GAP)** | **— (GAP)** | ✓ via evaluateAndPersist | RunProgressTracker |
| Aurora batch | ✓ created | ✓ SUBMITTED→COMPLETED | ✓ per result | — | — | — | Logging only |
| Aurora real-time | **— (GAP)** | — | **— (GAP)** | — | — | — | Logging only |

### Identified gaps

1. **Command executor has no api_call_log entries** — Claude calls are invisible in the observability layer. Token usage and costs are not tracked per-call.
2. **Command executor does not write to cached_evaluation** — results go to `forecast_evaluation` only, which means the unified read layer (`EvaluationViewService`) falls back to a lower-priority source.
3. **Command executor does not write evaluation_delta_log** — no empirical freshness data for this path.
4. **Aurora real-time has no job_run or api_call_log** — costs and usage are completely untracked.
5. **Force/JFDI paths skip evaluation_delta_log** — delta tracking only happens via the standard batch path.

---

## Section 7: State machine

The evaluation flow is a state machine per `(regionName, date, targetType)` tuple:

```
                              ┌─────────────────────────────────────┐
                              │                                     │
                              ▼                                     │
┌───────────┐  briefing  ┌─────────┐  triage  ┌──────────┐         │
│ NO_EVAL   │───────────▶│ TRIAGED │────────▶ │STANDDOWN │ (terminal)
│           │            │         │          └──────────┘         │
└───────────┘            └────┬────┘                               │
                              │ GO/MARGINAL                        │
                              ▼                                    │
                        ┌───────────┐  stability  ┌──────────────┐│
                        │ ELIGIBLE  │────────────▶ │STABILITY_SKIP││ (skipped, not evaluated)
                        └─────┬─────┘             └──────────────┘│
                              │                                    │
                              ▼                                    │
                        ┌───────────┐  fresh cache  ┌────────────┐│
                        │ CANDIDATE │──────────────▶│CACHE_FRESH ││ (uses prior result)
                        └─────┬─────┘               └────────────┘│
                              │ stale or no cache                  │
                              ▼                                    │
                    ┌─────────────────┐                            │
                    │ BATCH_SUBMITTED │                            │
                    └────────┬────────┘                            │
                             │ (async polling)                     │
                    ┌────────┼────────┐                            │
                    ▼        ▼        ▼                            │
              ┌──────┐ ┌────────┐ ┌─────────┐                     │
              │SCORED│ │ERRORED │ │EXPIRED/ │                      │
              │      │ │        │ │CANCELLED│                      │
              └──┬───┘ └────────┘ └─────────┘                     │
                 │                                                 │
                 ▼                                                 │
           ┌──────────┐  validate  ┌──────────────┐               │
           │ PARSED   │──────────▶ │RATING_INVALID│ (clamped to null)
           └────┬─────┘            └──────────────┘               │
                │ valid                                            │
                ▼                                                  │
           ┌──────────┐  age > threshold                           │
           │ CACHED   │───────────────────────────────────────────┘
           └──────────┘  (re-enter as CANDIDATE on next batch run)
```

### State transitions by path

- **Batch path:** Follows full state machine (NO_EVAL → TRIAGED → ELIGIBLE → CANDIDATE → BATCH_SUBMITTED → SCORED → PARSED → CACHED → re-evaluation cycle)
- **SSE path:** Skips ELIGIBLE/CANDIDATE gates, goes directly from TRIAGED (GO/MARGINAL) → inline evaluation → CACHED
- **Command executor:** Goes from NO_EVAL → TRIAGED → inline evaluation → written to `forecast_evaluation` (not CACHED state)
- **Force/JFDI:** Goes directly from NO_EVAL → BATCH_SUBMITTED (all gates skipped)

### Failure handling

| Failure | Handling | User sees |
|---------|----------|-----------|
| ERRORED (Anthropic) | `api_call_log` row with error_type/error_message; batch continues | No result for that cell |
| EXPIRED (batch TTL) | `api_call_log` row; batch entity marked EXPIRED | No result for that cell |
| CANCELLED (manual) | `api_call_log` row; batch entity marked CANCELLED | No result for that cell |
| Rating out of range | `RatingValidator` replaces with null; WARN logged | Cell has scores but no star rating |
| JSON parse failure | `ClaudeEvaluationStrategy.parseEvaluation()` regex fallback; if that fails, exception propagated | Error logged, location skipped |

### What the user sees at each state

- **NO_EVAL / STANDDOWN:** Empty cell in plan heatmap, no score in map view
- **CACHE_FRESH:** Prior scores displayed (may be hours old)
- **SCORED/CACHED:** Current scores displayed with star rating, fiery_sky, golden_hour, summary
- **ERRORED:** Empty cell (no score available)

---

## Section 8: External integrations

### Anthropic Claude API

| Attribute | Value |
|-----------|-------|
| **Client** | `AnthropicApiClient` (`service/evaluation/AnthropicApiClient.java`) |
| **SDK** | Anthropic Java SDK (direct `AnthropicClient` for batch API) |
| **Inline call** | `createMessage(MessageCreateParams)` — used by SSE, command executor, aurora real-time |
| **Batch calls** | `batches().create()`, `batches().retrieve()`, `batches().resultsStreaming()` — used by scheduled/force/JFDI/aurora batch |
| **Retry** | `@Retry(name="anthropic")`: 4 attempts, 1-30s exponential backoff |
| **Circuit breaker** | `@CircuitBreaker(name="anthropic")`: 50% failure rate over 10 calls, 60s open state |
| **Timeout** | 90s OkHttp call timeout |
| **Retry predicate** | `ClaudeRetryPredicate`: retries 500, 529 (overloaded), 400 with "content filtering" |
| **Error handling** | Transient errors auto-retry; non-transient propagate; circuit breaker fails fast when service degraded |

### Open-Meteo Forecast API

| Attribute | Value |
|-----------|-------|
| **Client** | `OpenMeteoClient` (`service/OpenMeteoClient.java`, 1155 lines) |
| **HTTP interface** | `OpenMeteoForecastApi` (`client/OpenMeteoForecastApi.java`) via `@HttpExchange` |
| **Methods** | `fetchForecast()`, `fetchForecastBatch()`, `fetchCloudOnly()`, `fetchCloudOnlyBatch()` + briefing variants |
| **Retry** | `@Retry(name="open-meteo")`: 3 attempts, 5s exponential backoff |
| **Circuit breaker** | `@CircuitBreaker(name="open-meteo")`: 50% failure over 20 calls, 65s open; **separate** `open-meteo-briefing` breaker for briefing path isolation |
| **Rate limiter** | `@RateLimiter(name="open-meteo")`: 5 req/sec (prod), 300s queue timeout |
| **Timeout** | 10s connect, 30s read |
| **Batch chunking** | 20 coords per chunk; 3s inter-chunk delay; 61s backoff on 429; 2 retries per chunk |
| **Error handling** | `TransientHttpErrorPredicate`: retries 5xx only; 429 handled by rate limiter backoff; failed chunks return null (caller guards) |

### Open-Meteo Air Quality API

| Attribute | Value |
|-----------|-------|
| **Client** | Same `OpenMeteoClient` |
| **HTTP interface** | `OpenMeteoAirQualityApi` (`client/OpenMeteoAirQualityApi.java`) |
| **Resilience** | Same as forecast API (shared retry/CB/rate-limiter configuration) |

### WorldTides API

| Attribute | Value |
|-----------|-------|
| **Client** | `TideService` (`service/TideService.java`) |
| **Methods** | `fetchAndStoreTideExtremes()`, `backfillTideExtremes()`, `deriveTideData()` |
| **Retry** | **None** — manual try-catch only |
| **Circuit breaker** | **None** |
| **Timeout** | RestClient defaults (no explicit configuration) |
| **Error handling** | Non-200 responses logged at WARN; exceptions caught and logged; existing DB data preserved on failure |
| **Fetch window** | 14 days per fetch; 7-day backfill chunks |

### NOAA SWPC (Aurora only)

| Attribute | Value |
|-----------|-------|
| **Client** | `NoaaSwpcClient` |
| **Data** | Kp index, Kp forecast, OVATION probability, solar wind Bz, G-scale alerts |
| **Caching** | Per-endpoint caching (5-minute Kp, longer for forecasts) |

---

## Section 9: Test coverage

### Test class inventory (evaluation-related)

| Test class | Tests | Type | Coverage area |
|------------|:-----:|------|---------------|
| `ScheduledBatchEvaluationServiceTest` | 48 | Unit | Batch submission, weather prefetch, triage, stability, concurrent guards |
| `ForceSubmitBatchServiceTest` | 33 | Unit | Force/JFDI batch, region validation |
| `BatchResultProcessorTest` | 62 | Unit | Result processing, FORECAST + AURORA, response parsing, error handling, token tracking |
| `BriefingEvaluationServiceTest` | 62 | Unit | Location filtering, SSE evaluation, caching, verdict evaluation |
| `BriefingEvaluationServiceCacheFreshnessTest` | 6 | Unit | Cache freshness per stability |
| `ClaudeEvaluationStrategyTest` | 40 | Unit | Claude API call, response parsing, dual-tier scoring, token extraction |
| `PromptBuilderTest` | 116 | Unit | System prompt, user message, rating scale, dual-score, one-sentence summary |
| `CoastalPromptBuilderTest` | 24 | Unit | Tide data inclusion, surge formatting |
| `ForecastCommandExecutorTest` | 77 | Unit | Command execution, stability snapshot, run progress |
| `ForecastCommandFactoryTest` | — | Unit | Command construction |
| `EvaluationViewServiceTest` | 29 | Unit | Merge precedence (cache > scored > triage > none) |
| `FreshnessResolverTest` | 7 | Unit | Stability-based cache TTL |
| `ForecastStabilityClassifierTest` | 65 | Unit | Pressure/cloud/wind stability classification |
| `RatingValidatorTest` | 16 | Unit | Rating 1-5 boundary validation |
| `EvaluationDeltaLogTest` | 6 | Unit | Delta tracking between evaluations |
| `EvaluationConfigTest` | 6 | Unit | Strategy bean configuration |
| `ClaudeAuroraInterpreterTest` | 39 | Unit | Aurora prompt/response parsing, scoring |
| `AuroraOrchestratorTest` | 18 | Unit | Alert level derivation, orchestration |
| `AuroraStateCacheTest` | 28 | Unit | State machine transitions, simulation |
| `BatchAdminControllerTest` | 46 | Integration | REST endpoints, role-based access |
| `BriefingEvaluationControllerTest` | 28 | Integration | SSE evaluate, cache endpoints |
| `WeatherTriageEvaluatorTest` | — | Unit | Cloud/precip/wind triage rules |
| `BriefingVerdictEvaluatorTest` | — | Unit | GO/MARGINAL/STANDDOWN rules |
| **Total** | **~750+** | | |

### What's covered well

- **Prompt construction** (140 tests across PromptBuilder + CoastalPromptBuilder) — thorough coverage of all conditional blocks
- **Batch result processing** (62 tests) — error types, token tracking, custom ID parsing, cache write
- **Stability classification** (65 tests) — pressure, cloud, wind patterns
- **Rating validation** (16 tests) — boundary conditions, clamping

### What's covered poorly

1. **No end-to-end integration test** that exercises: submit batch → poll → process results → write cache → read via EvaluationViewService. Each component is tested in isolation with mocks.
2. **EvaluationViewService** has only 29 tests for 398 lines — merge precedence logic is complex and under-tested relative to its criticality as the unified read layer.
3. **FreshnessResolver** has only 7 tests for a component that directly controls cache hit/miss decisions and therefore API cost.
4. **EvaluationDeltaLog** has only 6 tests — sufficient for current simplicity but will need more as empirical threshold analysis matures.
5. **Aurora real-time path** (`AuroraOrchestrator.scoreAndCache()` → `ClaudeAuroraInterpreter.interpret()`) has no api_call_log coverage and no test verifying that token usage is tracked.
6. **No test for the double-write scenario** — SSE path writes to both `forecast_evaluation` (via `evaluateAndPersist()`) and `cached_evaluation` (via `persistToDb()`). No test verifies consistency between the two writes.

### Tests that must be added during consolidation

1. Integration test: batch submit → poll → process → cache write → EvaluationViewService read
2. Test that command executor results are visible via EvaluationViewService (currently they're not — only via `forecast_evaluation` fallback)
3. Test for cache key format consistency across construction and parsing
4. Test for custom ID round-trip (construct → parse → verify fields match)
5. Test for concurrent batch guard (`AtomicBoolean` in ScheduledBatchEvaluationService)

---

## Section 10: Code smells

### Large classes (>500 lines)

| Class | Lines | Primary smell |
|-------|:-----:|---------------|
| `OpenMeteoService` | 1155 | God Object — weather fetch, parse, cache, grid ops, cloud points |
| `ScheduledBatchEvaluationService` | 1127 | God Object + 22-parameter constructor — batch submission, triage, stability, aurora |
| `BriefingBestBetAdvisor` | 1066 | God Object — aurora scoring, best bet selection, cloud analysis |
| `ForecastCommandExecutor` | 996 | Large orchestrator — 3-phase pipeline, stability, cloud prefetch |
| `ModelTestService` | 869 | God Object — model comparison test harness |
| `BatchResultProcessor` | 756 | Multiple responsibilities — result parsing, error handling, token tracking, persistence |
| `ForecastService` | 661 | Moderate — weather fetch, triage, evaluate, persist |
| `BriefingEvaluationService` | 657 | Moderate — SSE eval, caching, persistence, delta logging |
| `PromptBuilder` | 615 | Acceptable — mostly static prompt content |

### Constructor bloat

`ScheduledBatchEvaluationService` constructor: **22 parameters** spanning 48 lines (`ScheduledBatchEvaluationService.java:153-200`). Indicates too many direct dependencies — forecast and aurora batch are two different concerns sharing one class.

### Deep nesting

`ScheduledBatchEvaluationService.java:387-448` — triage loop with 4+ levels of nesting:
```
for (task : tasks)          // L1
  try                       // L2
    if (triaged) continue   // L3
    if (stability) continue // L3
    if (isNearTerm)         // L3
      if (isCoastal)        // L4
```

This could be flattened by extracting a `classifyTask()` method returning an enum (SKIP_TRIAGE, SKIP_STABILITY, NEAR_INLAND, NEAR_COASTAL, FAR_INLAND, FAR_COASTAL).

### Duplicated request building

The "select builder → choose message overload → build TextBlockParam with cache control → create BatchCreateParams.Request" pattern is repeated 4 times:
- `ScheduledBatchEvaluationService:977-1010`
- `ForceSubmitBatchService:143-178` (JFDI)
- `ForceSubmitBatchService:268-300` (force)
- `ClaudeEvaluationStrategy:130-240` (SSE/inline)

The first 3 are identical in structure — only the custom ID format differs. This is the primary extraction candidate for Pass 2.

### Dual batch submission methods

`ScheduledBatchEvaluationService` has two submit methods:
- `submitBatch()` at line 743 (void return, used by scheduled path)
- `submitBatchWithResult()` at line 649 (returns `BatchSubmitResult`, used by admin path)

These differ only in return type and whether they set `triggeredManually`. Should be unified.

### Mixed concerns in ScheduledBatchEvaluationService

This single class handles:
1. Forecast batch submission (scheduled + admin-triggered)
2. Aurora batch submission (scheduled)
3. Forecast task collection from briefing
4. Weather pre-fetch orchestration
5. Triage loop with stability gating
6. Near/far term + inland/coastal splitting
7. Batch submission to Anthropic API
8. DynamicSchedulerService job registration

Aurora batch (concern #2) is architecturally separate from forecast batch — different prompt, different output schema, different result handling. It should be its own service.

### No TODO/HACK/FIXME comments found

Clean codebase — no deferred work markers in evaluation-related code.

---

## Recommendations for Pass 2+

Based on the investigation, I recommend a 4-pass consolidation programme:

### Pass 2: Extract shared primitives (foundation)

**Why first:** Every subsequent pass depends on having clean shared utilities. Without these, consolidation just moves duplication around.

**Scope:**
1. **`CacheKeyFactory`** — extract cache key construction (`region|date|targetType`) and parsing into a dedicated utility. Replaces 5 construction sites and 2 parse sites.
2. **`CustomIdFactory`** — extract custom ID construction (fc-, au-, jfdi-, force-) and parsing into a dedicated utility with a `CustomIdParser` that returns a structured record. Replaces 4 construction sites and 1 parse site.
3. **`BatchRequestFactory`** — extract the builder-selection → message-building → cache-control → request-construction pipeline. Replaces 4 identical patterns. The factory accepts (location, date, targetType, model, AtmosphericData) and returns `BatchCreateParams.Request`.
4. **`BatchSubmissionService`** — unify `submitBatch()` and `submitBatchWithResult()` into a single method with a return value.

**Blocking concern:** None. These are additive extractions.

### Pass 3: Unify batch paths (consolidation)

**Why second:** With shared primitives in place, the 3 batch submission paths (scheduled, force, JFDI) can collapse.

**Scope:**
1. Extract `AuroraBatchService` from `ScheduledBatchEvaluationService` — aurora batch is a separate concern with different prompt, schema, and result handling. Keep it separate but give it its own class.
2. Consolidate `ForceSubmitBatchService` into `ScheduledBatchEvaluationService` (or a renamed `ForecastBatchService`) — force/JFDI differ from scheduled only in gate application. A `BatchMode` enum (SCHEDULED, ADMIN, FORCE) can parameterise which gates apply.
3. Flatten the triage loop — extract `classifyTask()` returning a classification record instead of nested if/else.
4. Reduce constructor from 22 to ~12 parameters by: (a) removing aurora dependencies after extraction, (b) grouping prompt builders into a `PromptBuilderFactory`.

**Blocking concern:** `ForceSubmitBatchService` has its own result-fetching path (`getResult()`) that bypasses `BatchPollingService`. This must be reconciled — either force-submit also goes through polling, or the inline result fetch is preserved as a batch mode option.

### Pass 4: Close observability gaps (completeness)

**Why third:** Architecture is stable after Pass 3; now ensure every path has consistent observability.

**Scope:**
1. **Command executor:** Wire `api_call_log` per Claude call (currently a gap). Wire `evaluation_delta_log`.
2. **Command executor:** Write to `cached_evaluation` as well as `forecast_evaluation`, so `EvaluationViewService` picks up results at the highest priority.
3. **Aurora real-time:** Add `job_run` and `api_call_log` for cost tracking.
4. **Force/JFDI:** Add `evaluation_delta_log` writes.
5. **Unified skip-reason taxonomy:** Standardise skip logging across all paths using a `SkipReason` enum.

**Blocking concern:** None. These are additive.

### Pass 5: Gate consistency (correctness)

**Why last:** This is a behavioural change — SSE and command executor paths will start rejecting more requests. Must be done carefully with the unified architecture in place.

**Scope:**
1. **SSE path:** Add optional cache freshness check — if a batch result exists and is fresh, serve it instead of re-evaluating. (This saves API cost when users click "Evaluate" soon after a batch run.)
2. **SSE path:** Add stability window awareness — warn (but don't block) when evaluating dates beyond the stability window.
3. **Command executor:** Consider whether this path is still needed or if it's superseded by the batch path + SSE path combination. If kept, align its gates.

**Blocking concern:** User-facing behaviour change. Needs product review before implementation.

### What should stay separate

- **Aurora evaluation** should remain a separate subsystem. Its prompt (`ClaudeAuroraInterpreter`), output schema (stars + summary + detail, no fiery_sky/golden_hour), state machine (`AuroraStateCache`), and external data source (NOAA SWPC) are fundamentally different from forecast evaluation. The batch submission mechanics can share `BatchRequestFactory` and `BatchSubmissionService`, but the orchestration layer should stay independent.

### Code smells: inline vs deferred

| Smell | Fix in | Rationale |
|-------|--------|-----------|
| Cache key construction duplication | Pass 2 | Foundation for all consolidation |
| Custom ID format duplication | Pass 2 | Foundation for all consolidation |
| Builder selection duplication | Pass 2 | Foundation for all consolidation |
| Dual submit methods | Pass 2 | Simple extraction |
| 22-parameter constructor | Pass 3 | After aurora extraction reduces deps |
| Deep nesting in triage loop | Pass 3 | During batch path consolidation |
| Mixed forecast/aurora in one class | Pass 3 | Primary goal of Pass 3 |
| `OpenMeteoService` God Object (1155 lines) | Deferred | Out of scope — not evaluation code |
| `BriefingBestBetAdvisor` (1066 lines) | Deferred | Out of scope — not evaluation code |
| `ModelTestService` (869 lines) | Deferred | Out of scope — test harness |
| Command executor observability gaps | Pass 4 | After architecture is stable |
| Aurora real-time observability gaps | Pass 4 | After architecture is stable |
| SSE gate consistency | Pass 5 | Behavioural change, needs review |

---

## Pass 2.5: Force-submit result path investigation

### Scope

Pass 1 flagged that `ForceSubmitBatchService` has its own `getResult()` path that bypasses `BatchPollingService`. This investigation characterises that bypass before Pass 3 attempts any result-path consolidation, so Pass 3 does not regress the force-submit admin UX or the observability coverage.

### Where the bypass lives

`ForceSubmitBatchService.getResult(String batchId)` — post-Pass-2 the method lives at roughly `ForceSubmitBatchService.java:246-296`. Entry point: `BatchAdminController` at `POST /api/admin/batches/{batchId}/result` (or similar; check controller for exact mapping).

Behaviour:

1. Calls `anthropicClient.messages().batches().retrieve(batchId)` synchronously to check `processingStatus`.
2. If the status is not `ENDED`, returns a `ForceResultResponse` with counts only (processing/succeeded/errored/cancelled) — no result streaming attempted.
3. If `ENDED`, opens a `resultsStreaming(batchId)` session and iterates every `MessageBatchIndividualResponse`. For each:
   - Counts successes vs errors.
   - For the first 5 entries only, captures `customId`, status, and a 500-char preview of the raw Claude text (no parsing, no schema validation).
4. Returns a `ForceResultResponse` summarising the admin-facing state.

**Crucially, `getResult` does not write anywhere.** It never touches `cached_evaluation`, `api_call_log`, `forecast_batch`, `evaluation_delta_log`, or `JobRunEntity`. It is a read-only peek for the admin UI.

### Does it actually bypass the standard result path?

No — it supplements it.

Force-submitted batches are still persisted as `ForecastBatchEntity` rows with `status=SUBMITTED` (via `BatchSubmissionService.submit` post-Pass-2). `BatchPollingService` sweeps every 60 seconds for all `SUBMITTED` rows regardless of trigger source, and when one reaches `ENDED` it calls `BatchResultProcessor.processResults(batch)` which performs the full write set:

- `cached_evaluation` via `BriefingEvaluationService.writeFromBatch` (source=`BATCH`)
- `evaluation_delta_log` conditionally via `BriefingEvaluationService.logEvaluationDeltas` (only when a prior cache entry existed — same gating as scheduled)
- `api_call_log` per request via `BatchResultProcessor.persistBatchResult`
- `forecast_batch` lifecycle: `SUBMITTED` → `COMPLETED` / `FAILED`, with `succeededCount`, `erroredCount`, `endedAt`, and token-usage totals populated
- `JobRunEntity` completion via `JobRunService.completeBatchRun`
- Rating validation via `RatingValidator.validateRating`
- Claude-response parsing via `ClaudeEvaluationStrategy.parseEvaluation`

So force-submit is fully observable through the standard chain. Pass 1's Section 6 claim that "Force/JFDI paths skip `evaluation_delta_log`" is inaccurate: the delta log is written on any cache write (scheduled or force) when a prior entry exists; the `—` in that table should be `conditional`.

### What does the admin peek actually show?

A temporal view. Because the poller runs on a 60 s cycle, there is a window of up to ~60 s where a batch has `processingStatus=ENDED` at Anthropic but `status=SUBMITTED` in our DB and the results are not yet streamed/processed. `getResult` gives the admin a way to see the raw Claude output during that window without waiting for the poller.

The first-5 cap and 500-char preview are deliberate for UI purposes — the admin wants to eyeball Claude's raw text, not read 400 parsed `SunsetEvaluation` objects.

### Comparison table

| Attribute | `BatchPollingService` → `BatchResultProcessor` | `ForceSubmitBatchService.getResult` |
|---|---|---|
| Trigger | Cron (60 s) | Admin HTTP GET |
| Sync/async | Async | Synchronous for the admin |
| Writes `cached_evaluation` | ✓ (BATCH source) | ✗ |
| Writes `evaluation_delta_log` | ✓ (conditional) | ✗ |
| Writes `api_call_log` | ✓ per request | ✗ |
| Updates `forecast_batch` status | ✓ (COMPLETED/FAILED + token totals) | ✗ |
| Completes `JobRunEntity` | ✓ | ✗ |
| Parses Claude JSON | ✓ via `ClaudeEvaluationStrategy.parseEvaluation` | ✗ raw text preview |
| Validates ratings | ✓ | ✗ |
| Streams all results | ✓ | ✓ but only first 5 captured |
| Returns batchId + counts | Internal | ✓ to admin |
| Returns raw text preview | ✗ | ✓ (first 5, 500 chars each) |

### Load-bearing vs drift

**Load-bearing (keep):**

- **Synchronous admin peek.** Force-submit is a diagnostic tool; the admin is literally sitting at the screen wanting to see Claude's output. Adding a 60 s wait for the poller would defeat the purpose.
- **Raw text preview.** For prompt debugging the admin needs to see what Claude actually said before parsing, not a post-parse `SunsetEvaluation`.
- **First-5 cap and 500-char truncation.** Intentional UI bounding.
- **Admin-facing status strings** (`"in_progress"`, `"ended"`) are separate from the domain `BatchStatus` enum and should stay that way — they describe the Anthropic processing state, not our persistence state.

**Drift / minor gaps (not blockers):**

- `getResult` re-streams results from Anthropic even when the poller has already persisted them. Once `BatchResultProcessor` has written a batch, a DB read from `api_call_log` would produce the same preview without the re-stream cost. This is an opportunistic optimisation, not a correctness issue.
- Pass 1's Section 6 table row for Force/JFDI `evaluation_delta_log` should be corrected to `conditional` rather than `—`.

### Recommendation for Pass 3

**Do not consolidate `getResult` into the polling path.** The two paths are complementary, not overlapping:

- The poller is the system-of-record writer — runs on its own schedule, writes everywhere the domain needs.
- The admin peek is a UI-facing diagnostic — synchronous, preview-only, read-only.

Pass 3's scope can safely ignore `getResult`. Batch request *building* is already consolidated (Pass 2, via `BatchRequestFactory`); batch request *submission* is already consolidated (Pass 2, via `BatchSubmissionService`). Result processing for scheduled/admin/force/JFDI is already unified — they all flow through `BatchPollingService` → `BatchResultProcessor`. There is nothing to collapse.

If Pass 3 is tempted to remove `getResult` and tell admins to wait for the poller, the UX regression is not worth the ~50 lines of deduplication. Leave it alone.

### What's left to touch in this area

1. Correct the Pass 1 Section 6 table row for `evaluation_delta_log` under Force/JFDI from `—` to `conditional`.
2. Consider (optional, low-priority) optimising `getResult` to read from `api_call_log` when the poller has already processed the batch. Not scoped for any specific pass.

