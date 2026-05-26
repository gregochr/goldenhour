# Nightly Pipeline Orchestration — Investigation

**Status:** Read-only investigation. No code changes proposed in this document; the orchestrator design is a separate follow-up shaped by these findings.

**Goal of the investigation:** map the *real* dependency graph of the stages the recent stabilisation work converged on (forecast batch → briefing → gloss → best-bet → aurora), the *real* mechanism that coordinates them today, and the *real* completion signals each stage emits — so the orchestrator that follows is grounded in code rather than assumption.

---

## TL;DR — the headline findings

1. **Gloss and best-bet are NOT separate stages.** They are synchronous sub-steps of `BriefingService.refreshBriefing()`. There is no scheduler entry, no API endpoint, no separate trigger — `BriefingGlossService.generateGlosses(...)` and `BriefingBestBetAdvisor.advise(...)` are called inline at [`BriefingService.java:314`](backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java:314) and [`:319`](backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java:319). They cannot be skewed in time from the briefing.
2. **Aurora is an independent branch, not a sequential stage.** Aurora real-time polling runs every 5 min on its own FSM (`AuroraStateCache`) and consumes NOAA SWPC, not Open-Meteo. Aurora outputs feed *into* the briefing (best-bet reads `auroraStateCache.getCurrentLevel()`), but no part of the forecast batch pipeline gates on aurora and aurora does not gate on the forecast batch. The orchestrator should leave aurora alone.
3. **The dependency on the forecast batch is implicit and time-cached.** The 04:00 briefing depends on the 03:00 batch *only* through the `cached_evaluation` table. `BriefingService.enrichWithCachedScores()` reads whatever rows happen to be there. If the batch is slow, gloss and best-bet silently run with stale or empty score data; the briefing succeeds either way.
4. **The "batch set complete" signal does NOT exist.** `forecast_batch` rows track status per-batch (SUBMITTED → COMPLETED/FAILED), but the system has no concept of "the 03:00 cycle's set of 1–4 batches has finished." Today the briefing simply runs at 04:00 and hopes. This is the central problem the orchestrator needs to solve, and the signal it needs must be created.
5. **Event-based coordination between stages does NOT currently exist.** The only inter-stage event is `BriefingRefreshedEvent`, which has exactly one listener — a no-op that logs "cache retained" ([`BriefingEvaluationService.java:325`](backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java:325)). The feared "web of observers across the codebase" hasn't been built yet. The orchestrator is therefore *replacing the cron-buffer coupling*, not unwinding a layer of event indirection.
6. **The right shape is a lightweight explicit sequencer, not a full FSM.** The graph is short (briefing-prior → batch → poll-to-completion → briefing-next), aurora lives outside it, and gloss/best-bet are already in-process synchronous calls. The hard part is the async batch wait — and that is one well-scoped engineering problem, not enough novel state to warrant an FSM.

---

## Section 1 — The real dependency graph

### Stages

The complete set of stages in the nightly forecast pipeline:

| Stage | Code entry | Output |
|-------|-----------|--------|
| **Daily briefing (any cycle)** | `BriefingService.refreshBriefing()` — [BriefingService.java:252](backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java:252) | `daily_briefing_cache`, in-memory `AtomicReference<DailyBriefingResponse>` |
| **Forecast batch submit** | `ScheduledBatchEvaluationService.submitForecastBatch()` — [ScheduledBatchEvaluationService.java:145](backend/src/main/java/com/gregochr/goldenhour/service/batch/ScheduledBatchEvaluationService.java:145) | `forecast_batch` rows (status=SUBMITTED), Anthropic Batch API jobs |
| **Batch result polling** | `BatchPollingService.pollPendingBatches()` — [BatchPollingService.java:79](backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchPollingService.java:79) | Updates each `forecast_batch` row to COMPLETED/FAILED; writes `cached_evaluation` rows via `BatchResultProcessor.flushCacheKey` |
| **Gloss (sub-step of briefing)** | `BriefingGlossService.generateGlosses(days, jobRunId)` — called from `BriefingService.refreshBriefing()` line 314 | Region-level `glossHeadline`/`glossDetail` on the in-memory `BriefingDay` tree |
| **Best-bet (sub-step of briefing)** | `BriefingBestBetAdvisor.advise(days, jobRunId, ...)` — called from `BriefingService.refreshBriefing()` line 319 | `bestBets` field on the `DailyBriefingResponse` |
| **Aurora real-time poll** | `AuroraPollingJob.poll()` → `AuroraOrchestrator.runForecastLookahead()` + `.run()` — [AuroraOrchestrator.java:108](backend/src/main/java/com/gregochr/goldenhour/service/aurora/AuroraOrchestrator.java:108) | Updates `AuroraStateCache` (in-memory FSM) and triggers Pushover/email |
| **Aurora batch eval** | `ScheduledBatchEvaluationService.submitAuroraBatch()` — [:187](backend/src/main/java/com/gregochr/goldenhour/service/batch/ScheduledBatchEvaluationService.java:187) | `forecast_batch` row (type=AURORA); on completion, `AuroraStateCache` |

There are also support jobs that are not part of the photocast pipeline and can be ignored by the orchestrator: `tide_refresh`, `refresh_token_cleanup`, `disposition_cleanup`, `run_progress_cleanup`, `briefing_model_comparison`.

### Per-stage inputs/outputs

#### Briefing
- **Reads**:
  - Open-Meteo forecast (per location, deduped by grid cell) — [BriefingService.java:498](backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java:498)
  - Open-Meteo horizon cloud (per unique 113 km horizon grid cell) — [:604](backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java:604)
  - DB tide extremes via `slotBuilder`
  - **`cached_evaluation` rows for each (region|date|targetType)** — `enrichWithCachedScores(...)` at [:310](backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java:310). *This is the only point at which prior batch results enter the briefing.*
  - `AuroraStateCache.getCurrentLevel()` (via `BriefingAuroraSummaryBuilder` and `BriefingBestBetAdvisor.appendAuroraEvent`)
- **Writes**: `daily_briefing_cache`, in-memory cache, publishes `BriefingRefreshedEvent`.

#### Forecast batch submit
- **Reads**: `BriefingService.getCachedBriefing()` — [ForecastTaskCollector.java:174](backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java:174). The candidate set is built from the *previous* briefing's GO/MARGINAL slots, with stability gating and tide alignment applied.
- **Writes**: 1–4 `forecast_batch` rows (the bucketed split: nearInland / nearCoastal / farInland / farCoastal), one for each non-empty bucket.

#### Batch result polling
- **Reads**: `forecast_batch` rows where status=SUBMITTED; Anthropic Batch API for processing-status and (when ENDED) streamed results.
- **Writes**: `cached_evaluation` rows via `BatchResultProcessor.flushCacheKey` (one per region|date|targetType cache key, containing per-location ratings + summaries).

#### Aurora real-time
- **Reads**: NOAA SWPC (Kp, OVATION, solar wind, alerts).
- **Writes**: `AuroraStateCache` FSM (in-memory). Independent of Open-Meteo and of `cached_evaluation`.

### The dependency graph

```
                ┌─────────────────────────────────────────────────────────────┐
                │  AURORA REAL-TIME (5-min fixed-delay, independent branch)   │
                │                                                             │
                │   AuroraPollingJob.poll()                                   │
                │      └─> AuroraOrchestrator.runForecastLookahead() / run()  │
                │             └─> AuroraStateCache (FSM, in-memory)           │
                │                                                             │
                │   Feeds: BriefingAuroraSummaryBuilder, BestBetAdvisor       │
                │   Does NOT depend on anything below.                        │
                └─────────────────────────────────────────────────────────────┘

  PHOTOCAST FORECAST PIPELINE (sequential, the only chain the orchestrator runs):

   Briefing(N)
     │   refreshes weather + tides + reads cached_evaluation
     │   calls gloss + best-bet INLINE
     │   publishes BriefingRefreshedEvent (currently no-op listener)
     │
     │   --- time gap (today: cron buffer) ---
     │
     ▼
   ForecastBatchSubmit (consumes Briefing(N).getCachedBriefing())
     │   submits 1–4 batches to Anthropic Batch API
     │   batches are now ASYNC — no return value carries completion
     │
     │   --- async wait (today: 60s polling + cron buffer hope) ---
     │
     ▼
   BatchResultPolling (every 60s, polls each SUBMITTED batch)
     │   on ENDED → BatchResultProcessor writes cached_evaluation rows
     │   per-batch status becomes COMPLETED/FAILED
     │
     │   --- "batch set complete" signal does not exist today ---
     │
     ▼
   Briefing(N+1)
       reads the cached_evaluation rows the polling wrote
       runs gloss + best-bet inline against enriched data
```

### Sequential vs parallel vs independent

| Relationship | Stages | Notes |
|--------------|--------|-------|
| **Sequential (data-dependent)** | Briefing(prior) → ForecastBatchSubmit → BatchResultPolling → Briefing(next) | The whole forecast pipeline is a single chain. Each step's output is the next step's input. |
| **In-process synchronous** | Briefing → Gloss → Best-bet | Within `refreshBriefing()`. Not separable. |
| **Independent / parallel** | Aurora real-time polling | Different data source (NOAA), different cache (`AuroraStateCache`), different cadence (5 min). Feeds into briefing but doesn't gate on or get gated by it. |
| **Independent (shares poller)** | Aurora batch eval | Submits a batch that the same `BatchPollingService` polls, but the trigger (NOAA Kp at 03:30) and consumer (`AuroraStateCache.updateScores`) are aurora-side. Different stage, different data, same async transport. |

The graph is **mostly linear with one parallel branch**. There are no diamond joins, no parallel forks within the forecast pipeline, and no cross-stage dependencies between aurora and the forecast chain.

---

## Section 2 — Current coordination mechanism

All scheduled jobs are stored in `scheduler_job_config` and run by `DynamicSchedulerService` (CRON or FIXED_DELAY). The owning service registers a runnable in `@PostConstruct` via `dynamicSchedulerService.registerJobTarget(jobKey, runnable)`. Schedules are then taken from the DB by `DynamicSchedulerBootstrap` on `ApplicationReadyEvent`.

### The registered jobs (from migrations V68, V72/V73, V79, V81, V101, minus V90's deletion)

| Job key | Display name | Schedule (default in migrations) | Status default | Implicit wait |
|---------|--------------|-----------------------------------|----------------|---------------|
| `tide_refresh` | Tide Refresh | CRON `0 0 2 * * MON` | ACTIVE | None — independent |
| `daily_briefing` | Daily Briefing | CRON `0 0 4,14,22 * * *` | ACTIVE | **Implicit: hopes the 03:00 batch has finished writing `cached_evaluation`** |
| `aurora_polling` | Aurora Polling | FIXED_DELAY 300 000 ms (5 min), initial 60 000 ms | ACTIVE | None — independent |
| `run_progress_cleanup` | Run Progress Cleanup | FIXED_DELAY 300 000 ms | ACTIVE | None |
| `near_term_batch_evaluation` | Near-Term Batch Evaluation | CRON `0 0 3,15 * * *` | PAUSED in V73 (must be enabled in prod via admin UI) | None for submission — but the briefing that consumes it at 04:00 depends on it implicitly |
| `aurora_batch_evaluation` | Aurora Batch Evaluation | CRON `0 30 3 * * *` | PAUSED in V73 | None — independent |
| `batch_result_polling` | Batch Result Polling | FIXED_DELAY 60 000 ms (60s) | ACTIVE | Not a stage trigger — the *transport* for async batch completion |
| `briefing_model_comparison` | Briefing Model Comparison | CRON `0 0 5 * * *` | ACTIVE | Tests, not pipeline |
| `refresh_token_cleanup` | Refresh Token Cleanup | CRON `0 0 3 * * *` | ACTIVE | Independent |
| `disposition_cleanup` | Forecast Disposition Cleanup | CRON `0 30 3 * * *` | ACTIVE | Independent |
| ~~`met_office_scrape`~~ | (deleted) | (deleted) | (deleted in V90) | n/a |

> **Caveat — live schedules may differ.** Production schedules are editable via the Scheduler admin UI (`SchedulerController.updateSchedule`), and the DB row is the source of truth. The defaults above are what migrations seed; the running cron expressions may have been adjusted in prod. The orchestrator design must read from the DB, not from the migration values. *Cannot confirm live values from the dev machine.*

### The single nightly time-coupling

There is one and only one inter-stage time coupling in the forecast pipeline today:

> **The briefing at 04:00 implicitly assumes the 03:00 forecast batch has finished populating `cached_evaluation`.** The buffer is ~1 hour. There is no check. If the batch is slow or fails partially, the briefing's 04:00 enrichment step reads a sparse cache, and `gloss` / `best-bet` run against incomplete data.

The 03:00 batch itself depends on the *previous* briefing — but a previous briefing is essentially always available (`getCachedBriefing()` returns the persisted DB cache if the in-memory one is empty), so this direction has no real fragility.

### Why this is brittle in both directions
- **Slow batch:** the briefing runs without waiting. Silent partial degradation. Surfaced only as a `[ZERO COVERAGE]` log line (visible at [BriefingService.java:441](backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java:441)).
- **Fast batch:** the briefing still waits the full hour. Wasted latency.
- **Intraday:** if a 15:00 batch follows a 14:00 briefing, the same problem appears but in a different position; and if intraday eventually adds *variable-time* batches (decision-window events), the fixed-time briefing schedule cannot follow them.

---

## Section 3 — Completion signals (the crux)

### Per-stage completion semantics

| Stage | Sync or async | How it signals "done" today |
|-------|---------------|---------------------------|
| Briefing refresh | Synchronous (method returns) | `BriefingService.refreshBriefing()` returns. Inline gloss + best-bet are part of this. Publishes `BriefingRefreshedEvent` at the end. |
| Forecast batch submit | Synchronous from caller's perspective | `submitForecastBatch()` returns once batches are submitted to Anthropic — but submission completion is NOT evaluation completion. Anthropic processes asynchronously, often for many minutes. |
| Anthropic batch processing | Asynchronous, external | No callback. The system learns of completion only by polling `MessageBatch.processingStatus`. |
| Batch result polling | Each tick is synchronous; the *transition* is async | Per-tick `pollPendingBatches()` runs the for-loop and returns. The *signal* of any individual batch reaching ENDED comes through `BatchResultProcessor.processResults(batch)` which sets `batch.status = COMPLETED` and writes `cached_evaluation`. |
| Aurora poll | Synchronous | Method returns. No external dependency. |

### The "batch set complete" signal

**The signal does not currently exist in any form a downstream stage can read.**

What exists:
- Per-batch lifecycle on `forecast_batch`: `BatchStatus { SUBMITTED, COMPLETED, FAILED, EXPIRED, CANCELLED }` ([ForecastBatchEntity.java:29](backend/src/main/java/com/gregochr/goldenhour/entity/ForecastBatchEntity.java:29)).
- A repository query for "are any batches still SUBMITTED?": `findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED)`, used at [BatchAdminController.java:185](backend/src/main/java/com/gregochr/goldenhour/controller/BatchAdminController.java:185) as the `hasActiveBatch()` admin guard.

What's missing:
- No grouping of batches by "cycle." A single nightly forecast cycle creates up to **four** `forecast_batch` rows (nearInland / nearCoastal / farInland / farCoastal). They are submitted in a tight loop inside `doSubmitForecastBatch()` ([ScheduledBatchEvaluationService.java:212](backend/src/main/java/com/gregochr/goldenhour/service/batch/ScheduledBatchEvaluationService.java:212)) but each row is independent — no cycle ID joins them.
- No way to ask "is the 03:00 cycle done." You can only ask "is any batch still SUBMITTED."

### What the orchestrator needs and where it can come from

The orchestrator needs to wait for a defined set of batches submitted by its own submission step. The cheapest derivation:

- `submitForecastBatch()` (in the orchestrator's wrapper) collects the set of `forecast_batch.id` values it just submitted.
- It then waits — by polling `forecast_batch.status` for that set, or by being notified.
- Done when every row in the set is in a terminal status (`COMPLETED`, `FAILED`, `EXPIRED`, `CANCELLED`).

The polling-vs-notification choice is a downstream design call. Either is tractable. The point for this investigation: **the data needed to derive the signal is present; only the cycle-grouping abstraction is missing**, and adding it is a single `cycle_id` column on `forecast_batch` (or, equivalently, collecting the set of returned batch IDs in-memory inside the orchestrator).

`BatchPollingService` already does the work of detecting per-batch completion and writing results. The orchestrator can stay out of polling entirely and simply observe `forecast_batch.status` transitions, or `BatchPollingService` can grow a callback hook.

### Other async-completion considerations

- Briefing, gloss, best-bet are synchronous within `refreshBriefing()`. The orchestrator can call `refreshBriefing()` and rely on its return.
- Aurora real-time poll is synchronous from the scheduler's perspective and runs on a 5-min cadence the orchestrator should leave alone.
- Aurora batch eval is async via the same `forecast_batch` table (type=AURORA). If the orchestrator's responsibility extends to gating "aurora batch finished before nightly summary," the same per-cycle waiting mechanism applies — but the simpler reading is that aurora batch is independent and the orchestrator does not need to gate on it.

---

## Section 4 — Existing event / observer usage to be replaced

The grep for `@EventListener`, `ApplicationEvent`, and `publishEvent` across the backend yields the complete picture:

| Event | Publisher | Listener(s) | Classification |
|-------|-----------|-------------|----------------|
| `BriefingRefreshedEvent` | `BriefingService.refreshBriefing()` line 344 | `BriefingEvaluationService.onBriefingRefreshed()` — line 325, body is one log line: *"Briefing refreshed — evaluation cache retained ({} entries)"*. No state change. | **Vestigial.** Originally written for cache invalidation; the cache is now retained across briefings. The event has zero behavioural effect. Safe to delete OR repurpose; **not** coordination today. |
| `LocationTaskEvent` | `ForecastService` (many call sites), `ForecastCommandExecutor.java:274`, `BortleEnrichmentService.java:142` | `RunProgressTracker.onTaskEvent()` line 90 (which buffers per-run state for the SSE progress endpoint) | **UI progress streaming, not pipeline coordination.** Drives the "running forecast" progress UI in the admin tools. Keep — not coordination. |
| `ApplicationReadyEvent` | Spring runtime | `DynamicSchedulerBootstrap.onApplicationReady()` line 38 | **Spring boot lifecycle.** Triggers `initSchedules()`. Keep — not coordination. |

**No other inter-stage events exist.** Specifically:
- No `BatchCompletedEvent`, no `BatchCycleCompletedEvent`, no `EvaluationCacheUpdatedEvent`.
- No listeners between `BatchResultProcessor` and the briefing.
- No listeners between gloss completion and best-bet (because they're inline anyway).
- No fan-out from `AuroraStateCache` changes (the briefing simply reads on next refresh).

### Implication for the orchestrator

The user brief warned that the orchestrator might end up *layered on top* of scattered event coordination. **That risk does not yet exist in this codebase.** Coordination today is `cron + timeout buffer` and one no-op event — not a publisher/subscriber web. The orchestrator will be replacing the cron-buffer mechanism, not unwinding a layer of event indirection.

`BriefingRefreshedEvent` and its no-op listener can simply be removed when the orchestrator lands; they have no functional consumers.

`LocationTaskEvent` should be left alone — it serves the UI, not stage coordination.

---

## Section 5 — Recommendation: lightweight explicit sequencer

### What the evidence points to

The graph is short, mostly linear, and the only branch (aurora real-time) is genuinely independent of the chain. Failure handling between stages is simple — partial failure of a batch produces a sparse `cached_evaluation`, which the next briefing handles gracefully via `[ZERO COVERAGE]` honesty filtering already in place. There is no need to model multi-step retry, no rollback, no compensating actions, no concurrent branches that synchronise.

The shape that fits is **a lightweight explicit sequencer** — a single method (or class) whose body reads top-to-bottom as the pipeline:

```
forecastCycle() {
    briefingService.refreshBriefing();        // T-0
    batchHandles = scheduledBatchEval.submitForecastBatch();
    waitForCompletion(batchHandles);          // the hard part
    briefingService.refreshBriefing();        // T-final, with batch results
}
```

That is the canonical "sequence readable in one place" the user explicitly wants. Anyone tracing nightly flow lands here and sees the order.

A full FSM is not warranted because:
- No state worth modelling lives between stages outside the database tables already in place (`forecast_batch`, `cached_evaluation`, `daily_briefing_cache`).
- No multi-step recovery: a failed batch doesn't trigger a "retry from stage X" — the next 04:00 cycle naturally re-runs everything.
- No external triggers reshape the sequence: aurora has its own loop, intraday is the same sequence with different parameters, not a different shape.

### The single hard part: the async batch wait

Building the orchestrator is straightforward *except* for the wait step. The options:

- **Option A — Orchestrator polls `forecast_batch.status` for its tracked set.** Easy to implement, fully synchronous in the orchestrator method, sleep-and-check. Adds a parallel poller alongside `BatchPollingService`, which is wasteful but simple. The poll itself is cheap (one DB query).
- **Option B — `BatchPollingService` exposes a completion callback / `CompletableFuture` per batch set.** The orchestrator submits, gets a future, awaits it. The poller already detects per-batch completion; this option threads that detection up to the orchestrator. Cleaner; requires a small map of "active waits."
- **Option C — Hybrid: a `BatchSetCompletionTracker` service** that the orchestrator queries, fed by the existing poller via a single side-effect call after each `processResults()`. The tracker is the single source of truth for "batch set X is done."

Either B or C is right. The choice should be made in the orchestrator design, not here — but the relevant fact for the investigation is that **the polling service already detects per-batch completion**, so the orchestrator does not need its own polling loop; it needs a way to be told.

A timeout still has a role: as a *failure ceiling*, not as a coordination primitive. "If the batch hasn't completed in N hours, mark the cycle FAILED and proceed with whatever rows landed in `cached_evaluation`." This is bounded fallback, not time-coupling.

---

## Section 6 — Intraday reusability

The intraday refresh (planned, per the user's brief) is conceptually the same shape — different parameters, identical sequence:

```
intradayCycle(triggerEvent) {
    briefingService.refreshBriefing();          // re-classify
    handles = scheduledBatchEval.submitForecastBatchFor(triggerEvent);  // conditional
    waitForCompletion(handles);
    briefingService.refreshBriefing();          // re-gloss + re-best-bet inline
}
```

This reuses **every** piece of the proposed orchestrator:
- The wait mechanism is identical (same `forecast_batch` table, same polling).
- The briefing call is identical.
- Gloss and best-bet are inline in the briefing and need no extra orchestration.
- The intraday-specific parts (stability-as-cost-gate, decision-window event selection) live inside `ScheduledBatchEvaluationService` / `ForecastTaskCollector`, not in the orchestrator — the orchestrator just calls a submit method with intraday parameters.

**No obstacle to reuse.** The orchestrator method body would parameterise on (a) what to submit and (b) what to refresh after — both are arguments, not new stages.

The fact that intraday batches can be event-relative (variable-time) rather than fixed (03:00, 15:00) is exactly why the cron-buffer model breaks for intraday but the explicit-sequencer model survives: a sequencer doesn't care *when* it was triggered, only that the sequence runs in order. This is the structural reason for doing the orchestrator before intraday rather than after.

---

## What surprised me most

**Gloss and best-bet are not stages.** The user's brief lists them as pipeline stages alongside batch and briefing, and reasoning about "does best-bet wait for gloss?" was a meaningful Section-1 question. But in the code they are simply method calls on lines 314 and 319 of `refreshBriefing()`. The Claude rate limit is the only thing they share with the batch — there is no scheduling concern between them, no completion signal to design around. The orchestrator's responsibility for these two is exactly: *do nothing*. They run when the briefing runs.

The corollary is that the *real* number of orchestrated stages in the forecast pipeline is **three** — briefing(prior) / batch+poll / briefing(next) — not the five the brief implied. This is good news for the design: it is even simpler than expected.

---

## Open questions before the orchestrator design

1. **Production schedules vs migration defaults.** What are the *live* `scheduler_job_config` cron expressions in prod (especially `near_term_batch_evaluation` which migrations leave PAUSED)? The orchestrator design must operate against reality, not seed data. Resolution: query prod DB or have the user confirm.
2. **Is the 22:00 briefing meant to feed the 03:00 batch, or is the 14:00 briefing meant to be a midday refresh that's *also* a batch input?** The current code reads `getCachedBriefing()` — whatever's there. If a midday batch (15:00) is also enabled, the dependency graph runs twice per day. The investigation modelled the nightly chain; the intraday brief implies a second daily cycle that the orchestrator should also handle.
3. **Aurora batch eval — is it actually enabled in prod?** Migrations seed it PAUSED. If it's running, it's an independent parallel branch the orchestrator can ignore; if it's off, the AURORA `batch_type` rows in `forecast_batch` only come from the rare real-time NOTIFY path.
4. **`BriefingRefreshedEvent` — safe to delete?** Confirmed it has no functional listener. The orchestrator could replace it with a method-return-driven flow. Worth confirming there are no external subscribers (e.g. tests, observability tools) before removing.
5. **Timeout-as-failure-ceiling: what is the right bound?** Anthropic batches typically complete in minutes to a few hours; the existing default `expires_at` from V73 implies a 24h cap. A 2–4h hard ceiling for the orchestrator's wait seems sensible but should be validated against observed production batch durations.
6. **What does "the 03:00 cycle" mean physically — a fixed cron, or a `cycle_id` we mint on submission?** The orchestrator design needs to choose. The investigation finding is that the cycle abstraction does not exist; either approach is viable; preference (probably) is for an in-memory cycle handle rather than a new DB column.

---

## Reference: files inspected

- `backend/src/main/java/com/gregochr/goldenhour/service/DynamicSchedulerService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/DynamicSchedulerBootstrap.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingGlossService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingBestBetAdvisor.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/ScheduledBatchEvaluationService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchPollingService.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchResultProcessor.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/aurora/AuroraPollingJob.java`
- `backend/src/main/java/com/gregochr/goldenhour/service/aurora/AuroraOrchestrator.java`
- `backend/src/main/java/com/gregochr/goldenhour/controller/BatchAdminController.java`
- `backend/src/main/java/com/gregochr/goldenhour/entity/ForecastBatchEntity.java`
- `backend/src/main/java/com/gregochr/goldenhour/model/BriefingRefreshedEvent.java`
- `backend/src/main/java/com/gregochr/goldenhour/model/LocationTaskEvent.java`
- Migrations: V68, V72/V73, V79, V81, V90, V96, V101
