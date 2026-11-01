# Runs redesign: Runs, Scheduler and Travel days

**Status:** planned on 2026-10-31 and revised after a backend and a frontend adversarial review. **R1 is built**
(2026-10-31, the two endpoints; see "Built departures (R1)" under §9). **R2a is built** (2026-10-31, the Runs tab, the
job-run panel and Start a run; see "Built departures (R2a)" under §9). **R2b is built** (2026-11-01, the cycle panel, the live
strip, the nested batch hash, the live count and the `pipelineruns` fold; see "Built departures (R2b)" under §9). **R3 is
built** (2026-11-01, Start a run as a choice of runs with inline scope; see "Built departures (R3)" under §9). **R4 is
built** (2026-11-01, the Scheduler on the list-and-panel shell with each job's last and recent runs; see "Built
departures (R4)" under §9). R5 is not yet built.

**Source:** the owner's Claude Design export "PhotoCast Runs" (`design_handoff_runs`: `README.md`, `Runs Redesign.html`,
`runs-redesign.js`, 14 frames). It lives with the owner, not in the repo. For the life of the planning session it is
unpacked at
`/private/tmp/claude-501/-Users-chrisgregory-IdeaProjects-goldenhour--claude-worktrees-admin-screens-ux-update-aafc59/afa8f5a3-b229-46ea-b584-5b224ca1f616/scratchpad/runs-bundle/design_handoff_runs/`.
The copy sheet is reproduced in §10 so this plan stands alone. Where the handoff and the codebase disagree, **the
codebase wins**.

The handoff rebuilds Manage → Runs on the list-and-panel shell from the Data tabs series
(`data-tabs-redesign-plan.md` §4, `components/data/*`).

- **Pipeline Runs and Job Runs merge into one Runs tab.** The group's sub-tabs become Runs · Scheduler · Travel days.
- **The shared rules hold:** rows carry no buttons, a row opens a 440px panel, and edits happen in the panel.
- **The panel has three responsive modes:** split from 1000px; overlay at 640–999px; a phone page at ≤639px.

## 1. Planning decisions (2026-10-31)

The owner said to move straight on after the Data tabs. These choices follow that series' rule: do it right, not quick,
and keep the server as the source of truth (CLAUDE.md "Backend-heavy").

- **R-D1. One server endpoint for the merged list.** `GET /api/admin/runs` returns cycles and top-level job runs,
  already merged, grouped and given a status, plus range-scoped aggregates.
  - The client never merges two lists, works out a status, or decides what a batch belongs to.
  - The summary figures are server aggregates, so they don't move as more pages load.
- **R-D2. Dropped, because the server has no truthful source** (the handoff allows each):
  - The sentence "Batch latency this time of day has been 98–173 min".
  - The sentence "Saves roughly {n} forecasts a day".
  - The cycle panel's **Trigger** row. A cycle doesn't record whether a schedule, Run now or a catch-up started it.
  - The list note "Plan B changed". The comparison exists only in the detail.
- **R-D3. No search box.** Frames 2–4 show "Search runs" while a panel is open. The README doesn't specify it and the
  server has no run search, so the tools row stays and wraps.
- **R-D4. Every trigger stays reachable at every merge.**
  - R2a deletes `JobRunsMetricsView`, so R2a's **Start a run** panel carries today's trigger buttons, dialogs and modals
    lifted as they stand.
  - R3 rebuilds that body into the handoff's radio options.
- **R-D5. Travel days gain the two server pieces the handoff needs** (R5): past ranges for the admin list, and a `PUT`
  for Edit.
- **R-D6. The Scheduler gets each job's last and recent runs** (R4), only where the server can prove which runs a job
  wrote. That is three mappings (§5.5). Every other job keeps today's last-fire time.
- **R-D7. Operations stays on the real clock.**
  - Every clock read in the Runs group uses `new Date()`, never `appNow()`. This applies to "Today" / "Yesterday",
    elapsed times, the BST/GMT label and the travel calendar. It is the rule `JobRunsMetricsView.jsx` (~431),
    `MetricsSummary.jsx` (~34) and `reference/platform.md` (Rewind) already state.
  - A test pins that a set rewind moves none of them.
  - The server side uses its injected `Clock`.

## 2. What stays as it is

- **`ManageView`'s layout:** the sidebar from 1280px, the group and sub-tab bars at 640–1279px, and the phone `<select>`.
  `key={activeTab}` remounts the content on a tab switch.
- **`activeRunId` stays in ManageView** (lifted there by c82f849a).
  - It is passed to the Runs view, so a run's progress survives leaving the tab and coming back.
  - `RunProgressKeptAfterFailures.test.jsx`'s "leaving the tab" tests stay as they are.
  - Only the run held in `activeRunId` subscribes to SSE progress, never any other running job.
- **`DispositionBreakdown`'s data and logic:** its category order (`CATEGORY_DISPLAY`) and its fallback for unknown keys.
  - It is restyled in place, and `SKIPPED_TRAVEL_DAY` and `FORCE_EVALUATED` are added (the server emits both).
  - Its 16 tests move with it.
- **`RunProgressPanel`:** its SSE subscription, auto-clear on a clean finish, Dismiss and its expired / no-longer-
  available states.
- **`RunNowButton`:** pending and triggered states, `disabled || pending || triggered`, and the 2s confirm.
- **The scheduler controls:** `SolarEditForm`, `ScheduleSwitchDialog` and the rest of `SolarScheduleControls.jsx`, with
  their disabling rules.
- **`AuroraForecastModal` and `AuroraSimulateModal`.**
- **The scheduler request guards** (`jobsRequest`, `previewRequest`) and its 30s poll.
- **The batch region rules** (`openBatchDialog` / `handleBatchSubmit`): `null` means all regions, and an empty
  selection is never submitted.
- **The tide confirm's location summary** (`getLocationSummary(..., 'TIDE' | 'SEASCAPE')`).
- **Every existing API module and every server 403** (all Runs-group controllers are `hasRole('ADMIN')`; the Operations
  pane is admin-only, so nothing here is PRO-visible).
- **Everything a reader of today's screens can learn.** §2.1 lists what moves where, and §9 lists the deliberate drops.

### 2.1 Today's content that must survive (from the frontend review)

- **From `JobRunDetail` → the job-run panel:**
  - the **Anthropic evaluation breakdown** (date × event × model counts), as its own "Evaluations" section;
  - per service: calls, average duration ("avg 210 ms"), error % (amber under 5%), tokens in/out/cache-read/cache-write,
    and cost in GBP with USD small;
  - the **total cost** block;
  - "No token data — batch failed before processing";
  - the batch status;
  - the Anthropic batch id (from `notes` for a top-level batch);
  - failed calls with `failedCallHeading`, the prompt-cache diagnostics, and the fallback-parsed responses.
- **From `JobRunsGrid`:**
  - the full `notes` of any run, as a "Notes" KV, not only the failure line;
  - `appVersion`, as a KV;
  - an in-progress batch's "{succeeded} / {locationsProcessed} succeeded";
  - "No detail available" when a run has nothing to show.
- **From `PipelineRunsView`:**
  - a **Refresh** action: in the header, and as the phone back bar's `backAction` (frame 12);
  - the Completed time, as a KV;
  - a batch's submitted time with its relative age;
  - phases ordered by `sequenceOrder`, with `PHASE_LABEL[p] || p` for an unknown phase;
  - comparison ranks beyond 2;
  - a `none` / "stay home" pick.
- **From `SchedulerView` / `SolarSchedulePanel`:**
  - `lastFireTime` for jobs with no attributable run;
  - "Unrecognised schedule type…";
  - the "(UTC)" label on the fallback cron;
  - "never before 00:05 UK";
  - the reference point with "Computed {time}";
  - **the proposed-rule preview for cycle jobs still on cron** (what the admin reads before switching);
  - the stale and unavailable states.

## 3. Server facts (checked 2026-10-31, corrected by the backend review)

### Pipeline runs

- **List:** `GET /api/admin/pipeline-runs` returns the top 50 by trigger time. It has no paging, range or batch ids.
- **Detail:** `GET /api/admin/pipeline-runs/{id}` returns `{run, phases[], batches[], comparison}`.
  - `PipelineRunBatch`: `jobRunId`, `anthropicBatchId`, `status`, `requestCount`, `succeededCount`, `erroredCount`,
    `submittedAt`, `endedAt`, `retry`.
  - Phases: `phase`, `sequenceOrder`, `status`, `startedAt`, `completedAt`, `durationSeconds`, `detail`.
  - `PipelineRunStatus`: RUNNING, COMPLETED, FAILED, DEGRADED.
  - `CycleType`: NIGHTLY, INTRADAY, EVENING.
- **What belongs to a cycle:**
  - Every `forecast_batch` row with a non-null `pipeline_run_id`, retries included; its `job_run_id` is that batch's run.
  - **`pipeline_run.disposition_job_run_id`**: when the cycle submitted batches, this is **the first batch's job run**
    (`ScheduledBatchEvaluationService` ~904–930). Only for a cycle with no batch is it a separate disposition-only
    anchor run.
  - `job_run` itself has no pipeline column.
- **Exceptions:**
  - A batch whose `linkJobRun` failed (best effort, `BatchSubmissionService` ~188–196) has no link.
  - An anchor whose disposition link rolled back (`ForecastDispositionService` ~90) also has no link.

### Job runs

- **Which `RunType`s are actually written:** `WEATHER`, `TIDE`, `LIGHT_POLLUTION`, `BRIEFING`, `AURORA_EVALUATION`, `ASK`,
  `ASK_READY` and `SCHEDULED_BATCH`.
- **Never written** (they are `model_selection` keys only): `BATCH_NEAR_TERM`, `BATCH_FAR_TERM`, `BRIEFING_GLOSS`,
  `BRIEFING_BEST_BET`, `AURORA_GLOSS` and `BLUEBELL_GLOSS`. Gloss and best-bet calls log onto the one BRIEFING run per
  build.
- **Every Anthropic batch's run is `SCHEDULED_BATCH`,** aurora batches included. `forecast_batch.batch_type` (FORECAST /
  AURORA) tells them apart.
- **Time base:** `job_run.started_at` is a naive `LocalDateTime` in UTC, written with `LocalDateTime.now(UTC)`.
  `pipeline_run.trigger_time` is an `Instant`.
- **No failure flag.** `RunCompletion.abort` can close a run 0/0 with a `RunCompletion.REASON_*` constant in `notes`.
  Informational notes ("Anthropic batch: …", "Disposition-only cycle …") are not failures.
- **Nothing closes an orphaned `job_run`.** An escaped exception, or a restart mid-run, leaves `completedAt` null for
  ever. Pipeline runs are swept at startup; job runs are not.
- **The `ASK` daily run** is closed 0/0 at creation and its counts are incremented all day (`AskJobRunService` ~164–169).
- **Volume** is tens of runs a day, so an in-memory merge over 30 days is fine.
- **Endpoints today:**
  - `GET /api/metrics/job-runs` is a paged list (no type means 7 days; `total` is a lower bound).
  - `/api-calls`, `/batch-summary` and `/disposition-breakdown` are keyed by `jobRunId`.
  - **There is no get-by-id**, so R1 adds one.

### Triggers

| Option | Endpoint | Returns `jobRunId` | 409 when |
|---|---|---|---|
| Scheduled batch | POST `/api/admin/batches/submit-scheduled {regionIds}` | yes | a batch is SUBMITTED |
| JFDI batch | POST `/api/admin/batches/submit-jfdi` | yes | same |
| Briefing | POST `/api/briefing/run` | no (sync) | a refresh is running |
| Aurora forecast | POST `/api/aurora/forecast/run {nights}` | as today's handler shows | — |
| Tide data | POST `/api/forecast/run/tide` | as today's handler shows | a tide refresh is running |
| Tide history | POST `/api/forecast/run/tide/backfill` | no | — |
| Light pollution | POST `/api/aurora/admin/enrich-bortle` | yes | — (400 with no key) |

### Scheduler

- **Jobs:** `GET /api/admin/scheduler/jobs` carries `jobKey`, `displayName`, `description`, `scheduleType`,
  `cronExpression`, `solarEvent`, `solarOffsetMinutes`, `fixedDelayMs`, `status` (`ACTIVE | PAUSED |
  DISABLED_BY_CONFIG`), `lastFireTime`, `nextFireTime` and `configSource`. It has no last-run outcome.
- **Cycle jobs:** `ForecastCycleJobs.allCycleJobs()` / `Job.cycleType()` map all three cycle jobs to a `CycleType`.
  `cycleOf()` returns null for `evening_sunrise_refresh`, so R4 must not use it.
- **`triggerNow` on a `DISABLED_BY_CONFIG` job** throws `IllegalStateException` (`DynamicSchedulerService` ~420).
  R4 checks what status the handler serves and makes it a 409.

### Travel days

- **Endpoints:** GET/POST `/api/admin/travel-days` and DELETE `/{id}`. There is no PUT.
- **`list()`** drops ended ranges and also backs the public `/api/travel-days`, which the Plan, Map and Ask consumers
  read. It reads "today" with `LocalDate.now(Europe/London)`, not `ForecastHorizon`.
- **The note** is ≤200 characters only through `@Column(length = 200)`. A longer note is a 500 today.
- **Writes call `servedEpoch.bump()`.**

### Elsewhere

- **Caching:** `HttpCachingConfig` is an exact-match allowlist, so `/api/admin/*` is never cached.
- **Security:** new admin controllers carry `@PreAuthorize("hasRole('ADMIN')")`; there is no URL rule.
- **Migrations:** the latest is V209. No phase needs one.

## 4. Shared pieces

- **`StatusDot` gains a `tide` tone** (`#6FA8B0`; use the existing token if `index.css` has one, else add `plex-tide`).
  - A `halo` prop adds the 3px ring Running uses.
  - Running and Degraded become different hues.
- **`useHashSelection(tab, { parse, format })`.**
  - `parse` receives **every path segment after the tab**, and `format` drives `select`.
  - The default stays numeric, so the Data tabs and their tests are untouched.
  - `select` and `clear` keep an inbound `?query` when one is present.
- **The Runs hash:**
  - `cycle/{id}`, `job/{id}`, `cycle/{id}/job/{jobRunId}` (a batch opened from its cycle), and `start` (the Start a run
    panel, R-D4).
  - Opening one closes any other.
  - Composite ids (`cycle-412`, `job-9876`) are used for `selectionKey` and the row ids, because a cycle and a job run
    can share a number.
- **Other hashes:** the Scheduler uses `{jobKey}` (`[a-z0-9_]+`); Travel days uses a numeric id.
- **Filters are read-only from an inbound query** (`?range=&kind=&attention=`), like Locations' `?region=`.
  - The chips never write the hash, so there is no history entry per chip, and `select` never erases it.
  - The hash's `aurora` maps to the server's `AURORA` (lower-case in the hash, upper-case on the wire).
- **`Segmented`** (`components/data/Segmented.jsx`): a single-select button group with `aria-pressed`.
- **`utils/runStatus.js`:** the served status → `{label, tone, halo}`, plus the batch-status vocabulary (§5.2). It is a
  pure lookup.
- **The ManageView tab merge:**
  - R2a: `metrics` → `runs`. R2b: `pipelineruns` is folded into `runs`.
  - **Redirects:** the mapping lives inside `manageTabFromHash`, so both mount and `hashchange` see it.
    - `#manage/metrics` → `#manage/runs`.
    - `#manage/pipelineruns[/{id}]` → `#manage/runs[/cycle/{id}]`.
    - `history.replaceState` runs synchronously before the Runs view's initialiser reads the hash. Because
      `replaceState` fires no `hashchange`, the state is set directly.
  - A `#manage` with no tab defaults to `runs`.
  - **The aurora banner's door** (`App.jsx` `openOperations`) aims at **`#manage/runs/start`**. A SIMULATED alert sends
    the admin to where simulation is turned off.
  - **Tests that move to the new hashes:** `AppAuroraBannerDoor.test.jsx` (~246, ~440),
    `RunProgressKeptAfterFailures.test.jsx` (~86), `ManageViewGroups.test.jsx` (~32, ~85), `ManageViewLayouts.test.jsx`
    (~47, ~108, ~170, ~238), `ManageViewNoSubRegionsTab.test.jsx` (~37) and `e2e/forecast.spec.js` (~137–142).
    `oneTimeLinkToken.test.js` only uses the hash as a sample fragment and stays.
  - **Live count (R2b):** a tide-coloured count beside **Runs** in the sidebar, the sub-tab bar and the phone select,
    shown only when greater than 0.
    - It is the server's `runningCount` (§5.1), which follows no range or kind.
    - ManageView polls `GET /api/admin/runs?size=1` every 60s, and the Runs view reports it through
      `onLiveCountChange` on each of its own refreshes.

## 5. Phases

One PR per phase, each branched from the updated `main` after the previous merged (CLAUDE.md workflow).

| Phase | Scope | Side |
|---|---|---|
| **R1** | `GET /api/admin/runs` and `GET /api/admin/runs/job/{id}`. This plan. | backend |
| **R2a** | Shared pieces; the `metrics` → `runs` merge; the list, summary and tools; the job-run panel; Start a run with today's triggers (R-D4) at `#manage/runs/start`; the aurora door. Delete `JobRunsMetricsView`, `JobRunsGrid`, `JobRunDetail` and `MetricsSummary`. **Pipeline Runs stays a sub-tab for this one phase**, and cycle rows open it until R2b. | frontend |
| **R2b** | The cycle panel, the live strip, the nested batch hash, and the live count. The `pipelineruns` redirect; delete `PipelineRunsView`. | frontend |
| **R3** | Start a run rebuilt: radio options, inline scope, and the new run opening on start. | frontend |
| **R4** | The Scheduler on the shell, plus each job's last and recent runs (server). | both |
| **R5** | Travel days on the shell: the calendar, past ranges, and edit (server). | both |

### 5.1 R1: the runs endpoints (ADMIN)

#### `GET /api/admin/runs`

**Params:**

- `range`: `TODAY`, `WEEK` or `MONTH`; the default is `WEEK`. All three are **UK-day aligned** through
  `ForecastHorizon` and the injected `Clock`:
  - TODAY is from the start of today's UK day;
  - WEEK is from the start of `today − 6`;
  - MONTH is from the start of `today − 29`.
  - Job runs are bounded with `ForecastHorizon.ukDayStartUtc(...)` as a `LocalDateTime`; cycles with the same instant.
- `kind`: `ALL`, `CYCLES`, `BATCHES`, `TIDE`, `BRIEFING`, `AURORA` or `OTHER`.
- `attention`: boolean.
- `before`: an opaque cursor `{startedAt, kind, id}` for Load more.
- `size`: the default is 20, the maximum 100.
- Unknown values return 400.

**Groups** (one server mapping, with a test that every `RunType` maps):

- CYCLES: pipeline runs.
- BATCHES: top-level `SCHEDULED_BATCH` runs whose `forecast_batch.batch_type` is FORECAST, or that have no batch row.
- AURORA: `AURORA_EVALUATION`, and `SCHEDULED_BATCH` runs whose batch type is **AURORA** (one set query, not one per
  row).
- TIDE: `TIDE`.
- BRIEFING: `BRIEFING`.
- OTHER: `WEATHER`, `LIGHT_POLLUTION`, `ASK`, `ASK_READY`, and any type never written today or added later. The
  model-selection-only types map here for completeness, documented as never written.

**Top level:** every pipeline run in range, plus every job run in range that is not one of these:

- **Globally**, not only for cycles in range, the `job_run_id` of any `forecast_batch` with a non-null
  `pipeline_run_id`, so a batch of a cycle that started before the range boundary stays hidden.
- Any `pipeline_run.disposition_job_run_id`.
- **The fallback:** a `SCHEDULED_BATCH` run whose `notes` is `'Anthropic batch: ' || anthropic_batch_id` of a cycle's
  batch (this closes the `linkJobRun` gap).

An anchor whose link rolled back stays top-level; this is documented and accepted. All exclusions are set queries.

**Status** (`RunListStatus`: `RUNNING`, `DONE`, `DEGRADED`, `PARTIAL`, `FAILED`):

- **Cycles:** RUNNING → RUNNING, COMPLETED → DONE, DEGRADED → DEGRADED, FAILED → FAILED.
- **Job runs, in order:**
  1. **No `completedAt`:**
     - RUNNING while younger than its ceiling: 26h for `SCHEDULED_BATCH` (Anthropic expires a batch at 24h), 6h for
       everything else.
     - Past the ceiling: **FAILED** with the note "Did not finish".
  2. **0/0 with `notes` equal to a `RunCompletion.REASON_*` constant:** FAILED, and the note is that reason.
  3. **0/0 otherwise:** DONE.
  4. **Else, by `ok = succeeded / (succeeded + failed)`:**
     - 1 → DONE;
     - ≥ 0.8 → PARTIAL;
     - < 0.8 → FAILED.

  This ratio is a **display rule**, independent of each job's own serve threshold (the briefing serves at ≥50%). It is
  documented as such in `reference/pipeline.md`.
- **Need attention** = DEGRADED, PARTIAL or FAILED.

**Item** (`RunListItem`):

- `kind`: `CYCLE` or `JOB`.
- `id`.
- `group`.
- `name`.
- `startedAt` and `endedAt`: `Instant`s; job runs are converted with `atOffset(UTC).toInstant()`.
- `durationSeconds`: null while running, and always null for `ASK` and `ASK_READY`.
- `status`.
- `note` and `noteTone`.
- `costMicroDollars` and `costGbpRate`.
- **Result:**
  - cycles: `batchesDone` and `batchesTotal`;
  - job runs: `succeeded`, `failed` and `locationsProcessed`.
- **Scope:**
  - cycles: `cycleType` and the batch count;
  - job runs: `runType`, `triggeredManually` and the `batchSummary` scope.

**Names as served:**

- Cycles: NIGHTLY "Overnight cycle", INTRADAY "Afternoon refresh", EVENING "Evening sunrise refresh".
- Job runs: one name per group and run type. A batch is "Scheduled batch" or "JFDI batch" (from its strategies, as
  today's labels do). An aurora batch is "Aurora batch".
- The builder lifts today's frontend labels wherever one exists.

**Note:**

- Cycles: `waitingOn` while running, else `failureReason`.
- Job runs, in this order:
  1. "Did not finish";
  2. the `REASON_*` text;
  3. "{n} requests errored" when PARTIAL or FAILED with `failed > 0`;
  4. otherwise null.

  Informational notes are never served as the row note; the panel shows the full `notes` (§2.1).

**Cycle cost:** the sum over the **distinct** job run ids in {its batches' `job_run_id`} ∪ {its
`disposition_job_run_id`}, so the first batch is never counted twice. The GBP rate comes from the latest of those runs.
It covers batch spend only; the briefing's cost sits on its own unlinked run. The panel labels it **"Batch cost"**.

**Response:**

```json
{
  "items": [...],
  "nextCursor": "...|null",
  "total": 64,
  "summary": {
    "runs": 64,
    "cycles": 21,
    "other": 43,
    "needAttention": 3,
    "degraded": 1,
    "partial": 1,
    "failed": 1,
    "spendMicroDollars": 23310000,
    "spendGbpRate": 0.79,
    "medianOvernightSeconds": 8340,
    "medianOvernightSample": 7
  },
  "kindCounts": { "ALL": 64, "CYCLES": 21, "BATCHES": 6, "...": 0 },
  "attentionCount": 3,
  "runningCount": 1,
  "liveCycleId": 412,
  "nextOvernightAt": "...|null",
  "truncated": false
}
```

**What each figure follows:**

- `total`, `summary`, `kindCounts` and `attentionCount` follow **range**, and are independent of the cursor.
- `summary` also follows **kind**. It ignores `attention`, or "Runs" would equal "Need attention".
- `kindCounts` follow range only.
- **Spend** counts every job run in range, including cycle batches, each once.
- **The median** covers NIGHTLY cycles that ended in range as DONE or DEGRADED. It is null with no sample.
- **`runningCount` and `liveCycleId` ignore every filter.** `runningCount` is cycles RUNNING plus job runs RUNNING by the
  rule above, so stale runs never count. `liveCycleId` is the newest RUNNING cycle.
- **`nextOvernightAt`** is the next fire of the nightly cycle job from the scheduler, or null.
- **`truncated`** is set if the job runs in range exceed the 5,000 scan cap.

**Order:** newest first, ties broken by kind then id descending. `before` returns the items strictly after the cursor in
that order, so new rows arriving at the head never shift a Load more.

#### `GET /api/admin/runs/job/{id}`

- Returns the full `JobRunDto` (with `batchSummary`), plus the served `status`, `group`, `name` and `note`.
- Also returns `batch: {anthropicBatchId, batchType, status, pipelineRunId} | null` from `forecast_batch`.
- 404 when the job run is missing.
- It works for any job run, including a cycle's batch, which never appears at the top level.

#### Tests

- **Service unit tests:**
  - the global exclusion, including a cycle that started before the range;
  - the notes fallback;
  - an aurora batch landing in AURORA;
  - every status rule: 0/0 plain and with a `REASON_*` note, the 80% edge, a stale run past each ceiling, ASK duration
    null;
  - every `RunType` maps;
  - the summary is independent of the cursor;
  - spend includes cycle batches, each once;
  - **the cycle cost when `disposition_job_run_id` equals batch 1's run**;
  - the median with an odd sample, an even one, and none;
  - the TODAY / WEEK / MONTH bounds at a BST↔GMT change;
  - `runningCount` ignores filters and stale runs;
  - cursor paging with a row inserted at the head between pages.
- **`@WebMvcTest`:** 403 for non-admin, 400 for a bad `range` or `kind`, 404 for a missing job.
- **`@DataJpaTest`:** the exclusion and batch-type queries.
- **`JsonDateFormatContractTest`:** a case for the new DTO (mandatory).
- **Docs:** `reference/api-endpoints.md` and `reference/pipeline.md` (the status rules).

The old `/api/admin/pipeline-runs` and `/api/metrics/*` endpoints stay. The panels still use them.

### 5.2 R2a and R2b: the Runs tab (frames 1, 2, 3, 9, 11, 12)

**Files:**

- `components/runs/`: `RunsView` (receives `activeRunId`, `onActiveRunChange` and `onActiveRunClear` from ManageView),
  `RunsListPane`, `RunsSummary`, `JobRunPanel` and its sections, and `StartRunPanel`; in R2b, `RunsLiveStrip`,
  `CyclePanel`, `CyclePhases`, `CycleBatches` and `CycleComparison`.
- `api/runsApi.js`.
- Hooks: `useRunsList` and `useCycleDetail(id)`. `useCycleDetail` is one shared, de-duplicated fetch with a 15s poll
  while RUNNING, so the live strip and the open panel never double-poll.

**Deletions:**

- R2a: `JobRunsMetricsView`, `JobRunsGrid`, `JobRunDetail` and `MetricsSummary`.
- R2b: `PipelineRunsView`.

Tests pinning behaviour that survives **move**. Tests of a removed feature (the run-type select, the two checkboxes, the
sort by duration or succeeded, the `MetricsSummary` cards §9 drops) are deleted, with a list in the PR body.

**Run progress:**

- `RunProgressPanel` for `activeRunId` renders **above the list**, not inside the Start panel, so it survives closing the
  panel and switching tabs.
- When its run's panel is open, the job-run panel shows a "Progress" note pointing at it rather than a second
  subscription.
- On Dismiss, focus moves to the Runs heading (`h2` "Runs", `tabIndex=-1`).

**Header:** "Runs", then the meta "Updated {HH:MM} · refreshes every minute", then **Refresh** (a text button), then
**Start a run** (`btn-primary`, opens `#manage/runs/start`).

**Summary:** four cells.

- **Runs:** "{cycles} cycles · {other} other".
- **Need attention:** amber when greater than 0, with the sub-line "{d} degraded · {p} partial · {f} failed" (zero parts
  dropped).
- **Spend:** GBP, with USD below.
- **Median overnight cycle:** "last {n}", or "—".
- Two columns at ≤640px.

**Tools:**

- A `Segmented` range: Today · 7 days · 30 days.
- Kind chips with `kindCounts`.
- A **Need attention** chip with `attentionCount`.
- Initial values come from the inbound query (§4).
- **On a filter change,** an open panel closes only if **its own item** (its `group` and `status`; for a nested batch,
  its parent cycle) no longer matches. This is decided from the item, never from the loaded page.

**List** (`DataGrid`):

- **Columns:**
  - **Started** 118px, mono: "Today 15:00" / "Yesterday 19:40" / "Thu 29 Oct 11:02", in UK time, `new Date()`.
  - **Run** 1.9fr: a `CYCLE` tag plus the name; line 2 is the scope; line 3 is the note in its tone.
  - **Status** .8fr.
  - **Result** .8fr, secondary:
    - cycles: "{done} of {total} batches";
    - a running batch: "{succeeded} / {locationsProcessed}";
    - BRIEFING DONE: "Built";
    - AURORA_EVALUATION: "{succeeded} scored";
    - otherwise "{ok} / {total}", or "—" for 0/0.
  - **Duration** .8fr, secondary: "{x} so far" while running, "—" when null.
  - **Cost** .5fr, secondary: `formatCostGbp`, "—" when 0 or null.
- **Card at ≤540px:** name, status and note on top; "{started} · {duration} · {cost}" below.
- **Paging:** "Showing {n} of {total}" and **Load more**, which sends `before=nextCursor`.
- **Polling:**
  - Fetch the head (`size` 20) and merge it by `kind:id` over the loaded rows; older loaded rows stay as they are.
  - Every 60s, or every 15s while `runningCount > 0`.
  - An open job-run panel refetches its own item on the same tick.
- **Empty:** "No runs in this range." plus " The next overnight cycle starts {HH:MM}." when `nextOvernightAt` is set.

**Job-run panel** (loaded by `GET /api/admin/runs/job/{id}`, so a deep link or a batch works):

- **Title:** the `anthropicBatchId` (mono 18px, truncated in the middle) for a batch, else the name.
- **Meta:** "{group label} · job run #{id} · {status} · {HH:MM} → {HH:MM}".
- **Scope** (rows with data only):
  - Locations: "{n} in {r} regions";
  - Dates: "{min} – {max} · {event types}";
  - Model: `modelLabel`;
  - Strategies: mono tags, with `EVALUATE_ALL` shown as `JFDI`;
  - the batch status (batch vocabulary);
  - Completed;
  - App version;
  - Notes (full).
- **Dispositions:** the restyled `DispositionBreakdown`.
  - "{n} candidates", then a 10px stacked bar with 2px gaps.
  - Rows of swatch, label, count and chevron. Opened, a row shows the first 3 entries ("{location} · {date} · {event}",
    with the detail in ink-3), then "+{n} more", which expands the full list in place.
- **Evaluations:** today's Anthropic breakdown table.
- **Calls:** one row per service with the §2.1 figures, then the total cost.
  - "No token data — batch failed before processing" where it applies today.
- **Diagnostics:** shown only when a count is greater than 0. Disclosure rows for failed calls, prompt-cache diagnostics
  and fallback-parsed responses, each opening today's content.
- **Nothing to show:** "No detail available".

**Start a run (R2a legacy body, R-D4):**

- The panel at `#manage/runs/start`, titled "Start a run", with the subtitle "Runs start straight away and appear at the
  top of the list."
- The body is today's admin trigger card as it stands: every button, the batch region dialog, the tide confirm dialogs,
  both aurora modals, `anyRunning`, and the busy and error lines.
- A start that returns a `jobRunId` sets `activeRunId` as today.
- `usePanelEscape` already defers to a visible `ConfirmDialog` or modal.

**Cycle rows in R2a** open `#manage/pipelineruns/{id}` (the old sub-tab) until R2b.

**R2b: live strip:**

- It shows only with `liveCycleId`, and uses `useCycleDetail`.
- **Line 1:** a pulse dot, "{name} is running", "Cycle #{id} · started {HH:MM}", and **Open**.
- **Line 2:** a five-segment phase bar (decorative, `aria-hidden`). Done segments are go, the current one is a tide
  gradient, later segments are the border colour.
- **Line 3:** "{phase label} · waiting on {n} of {m} batches · {elapsed} so far". The waiting clause shows only during
  WAIT.
- Reduced motion stops the pulse.

**R2b: cycle panel:**

- **Title:** the name.
- **Meta:** "Cycle #{id} · {cycleType lower} · {status label} · started {HH:MM} ({relative})".
- **Waiting box** (RUNNING with `waitingOn`, tide tint): "Waiting on {n} of {m} batches", with the still-SUBMITTED ids
  (truncated in the middle), "submitted {HH:MM}" and "Checked {relative}".
- **An amber or coral box** with `failureReason` when DEGRADED or FAILED.
- **"vs this morning's nightly"**, only with a `comparison`:
  - a chip: "Plan changed" when any diff `changed`, else "No change";
  - a card per rank (Plan A, Plan B, then "Plan {n}" beyond) showing the intraday headline, or the stay-home wording
    for `none`;
  - a changed card adds "Was {nightly headline}" struck through and the changed dimensions (`DIMENSION_LABEL`,
    capitalised);
  - "Baseline: nightly cycle #{id} · {HH:MM}".
- **Phases:** a vertical timeline in `sequenceOrder`, with `PHASE_LABEL[p] || p`.
  - Section meta: "{done} of {n}" while running, the total duration when finished (frames 2 and 12).
  - Done is a filled go dot, current a tide dot with a halo, failed coral, and not yet run an outline.
  - Each row shows "{HH:MM} · {detail}" and the duration on the right; the running phase shows "{elapsed} so far" in
    tide.
  - A phase not yet run says "Runs after Wait".
- **Batches:** section meta "{n} · {requests} requests".
  - Rows are at least 48px: the id (mono, truncated in the middle); "{requests} requests · {ok} / {err} ok/err ·
    {HH:MM} ({relative})"; a `RETRY` tag; a status; and a chevron.
  - **The batch vocabulary is as the frames show it:** Submitted (tide, halo), Completed (go), Failed / Expired
    (coral), Cancelled (ink-3).
  - A row with a `jobRunId` is a button to `cycle/{id}/job/{jobRunId}`. A row without one is plain.
  - With no batches: "No batches were submitted. The cycle went straight to the briefing."
- **Footer KV:** "Batch cost so far" / "Batch cost" (GBP, USD small), and Completed.

**R2b: drilling into a batch:**

- The panel shows "‹ Cycle #{id}" at the top on split and overlay. On the phone, `DataSplitLayout`'s back bar gets
  `backLabel="Cycle #{id}"` and `onBack` returns to the cycle, with no second back row.
- Back, Esc and the back control return to the cycle; × closes everything.
- After returning, focus goes to that batch's row.

**Responsive:** columns drop at ≤820px and cards appear at ≤540px. Phone touch targets are at least 44px.

### 5.3 R3: Start a run (frame 4)

**Options:** three groups of radio-style options (`role="radiogroup"`, arrow keys move). Each option has a name and one
line of description.

- **Forecast:**
  - Scheduled batch: "Same triage and stability gates as the overnight cycle."
  - JFDI batch: "All locations, T to T+3, sunrise and sunset, no gates."
  - Briefing: "Rebuilds the briefing from current ratings."
  - Aurora forecast: "Choose the nights to score."
- **Data refresh:**
  - Tide data: "{n} coastal locations from WorldTides." (the count is dropped until the summary loads).
  - Tide history: "12 months for seascapes, in 7-day chunks. Skips dates already stored."
  - Light pollution: "Locations without a Bortle rating."
- **Testing:** Aurora simulation, "Injects fake NOAA data so the aurora UI can be tested.", with its On/Off state and
  **Simulate…** opening `AuroraSimulateModal`.

**Inline scope:** the selected option expands it.

- **Batches:** an "All regions" row with the total, then each region with its count. `null` means all; an empty
  selection disables the start button and is never sent.
- **Tide options:** the location summary grouped by region.
- **Aurora forecast:** the start button opens `AuroraForecastModal`.

**Footer:** the scope ("{n} of {m} regions · {k} locations" / "{k} locations" / nothing), **Cancel**, and **Start
{option, lower case}**.

**Behaviour:**

- **409:** "A run is already in progress. Wait for it to finish."
- **While a run started here is in flight:** every option and the start button are disabled.
- **Success with a `jobRunId`:** set `activeRunId`, reload the head, and open `#manage/runs/job/{id}`. Auto-clear is
  kept.
- **Success without an id:** reload, close the panel, and show a toast with the server's `status` or "Started."

### 5.4 Scheduler and travel-day server pieces

These are in §5.5 and §5.6.

### 5.5 R4: Scheduler (frames 5, 6, 10, 13)

**Server:**

- `lastRun` and `GET /api/admin/scheduler/jobs/{key}/runs?limit=3` (`RunListItem[]`, using R1's rules) cover **exactly
  these jobs**, which can be proved from code:
  - the three cycle jobs, through `allCycleJobs()` / `Job.cycleType()`, to pipeline runs of that type;
  - `tide_refresh` → `TIDE` runs with `triggeredManually = false`;
  - `aurora_batch_evaluation` → `SCHEDULED_BATCH` runs whose batch type is AURORA.
- Every other job gets `lastRun: null` and `[]`. `aurora_polling` isn't attributable (`triggeredManually` is hard-coded
  false).
- **Trigger on a `DISABLED_BY_CONFIG` job** serves 409, not 500.
- **Tests:** the mapping, `lastRun` status, and the 409.

**Frontend:** `components/scheduler/` with `SchedulerListPane`, `SchedulerJobPanel`, `JobSchedule`, `JobNextFires` and
`JobActions`. `SchedulerView` is rewritten.

**Header and chips:**

- **Header:** "Scheduler", then "{n} jobs · UK time ({BST|GMT})" (`new Date()`).
- **Chips:** All · Sun-relative · Cron · Fixed delay (only if any) | Paused · Off in config.

**Columns:**

- **Job** 1.6fr: the name, with the description below.
- **Schedule** 1fr: `describeCron`, `describeFixedDelay` or `describeSolarRule`, with the type below, or "Unrecognised
  schedule type…".
- **Next** .8fr: mono time and relative time. The default sort is ascending, with paused or off rows last.
- **Last run** .9fr, secondary: the `lastRun` time and status; else the `lastFireTime` with "fired"; else "—".
- **Status** .7fr: Active / Paused / Off in config.
- Paused and off rows are dimmed. There are no row actions.

**Hash:** `#manage/scheduler/{jobKey}`.

**Panel:**

- **Schedule:**
  - Rule; Reference (solar: the place and "Computed {time}"); Fallback cron (mono, its reading, "(UTC)"); "never before
    00:05 UK" where today shows it.
  - **Edit** swaps in `SolarEditForm` or today's inline cron/delay field.
  - "Switch to sun-relative…" / "Switch back to a fixed time…" opens `ScheduleSwitchDialog`, with today's rules.
- **Next fires:**
  - **Solar jobs:** a Today / Tomorrow table (Sunrise|Sunset, Fires), with fallback and clamped days marked amber and
    "ran" under a past fire. Then "Expected finish" and "Over the year".
  - **Cycle jobs still on cron:** today's **proposed-rule preview**, under that label.
  - The stale and unavailable states live here.
- **Recent runs** (mapped jobs only): up to 3, "{started} · {duration} · {status}", each opening
  `#manage/runs/{cycle|job}/{id}`. **All in Runs** goes to `#manage/runs?kind={group lower}`.
- **How sun-relative timing works:** a disclosure (solar and cycle jobs) holding the four notes, verbatim.
- **Job actions:**
  - **Run now:** "Starts a cycle straight away. Tonight's still runs." for cycle jobs, "Runs the job straight away." for
    others. It uses `RunNowButton`, with the triggered text "Started. It'll appear in Runs in a moment."
  - **Pause:** "Skips its fires until you resume it." / **Resume:** "Fires again from its next time."
- **Off in config:** "Requires {configSource}" in mono, and every action is disabled.

### 5.6 R5: Travel days (frames 7, 8, 14)

**Server** (`TravelDayService` / `TravelDayController` only):

- **Add `listAll()`** for `GET /api/admin/travel-days?includePast=true`, newest start first. **`list()` is unchanged in
  shape,** because it backs the public endpoint the Plan, Map and Ask read.
- **`PUT /api/admin/travel-days/{id}` `{startDate, endDate, note}`:**
  - shares one validation method with `add()`: both dates are required, end is not before start, and **note is ≤200
    characters**, all returning 400;
  - returns 404 when the range is missing;
  - calls `servedEpoch.bump()`, as add and delete do.
- **"Today" in `list()`** moves to `ForecastHorizon` with the injected `Clock` (the cross-cutting invariant). Its
  results don't change.
- **Tests:** the service tests; `@WebMvcTest` for 403, 400 (including a 201-character note on add and on update) and
  404; and that `includePast` doesn't leak to the public endpoint. `api-endpoints.md` is updated.

**Frontend:** `components/traveldays/` with `TravelDaysCalendar`, `TravelDaysListPane`, `TravelRangePanel` and
`TravelRangeForm`. `TravelDaysView` is rewritten.

**Header and intro:**

- **Header:** "Travel days", then "{n} upcoming", then **Add range** (`btn-secondary`). The invisible `bg-plex-accent`
  button goes.
- **Intro:** "The overnight batch skips forecasts for days you're away. Start and end dates are both included."

**Calendar:**

- Five weeks from this week's Monday (UK, `new Date()`), Monday first.
- Cells are at least 36px (44px on phones), radius 5, mono 12px.
- Away days are tide at 55%, past days are at 45% opacity, and today has an inset ring. The 1st of a month reads "1 Nov".
- A key below the calendar.
- **Cells are buttons** with an `aria-label` such as "Mon 2 Nov, away (London — work)".
  - An away day opens its range.
  - An empty day from today on opens Add with From set to it.
  - Past empty days are inert.

**List:** a `DataGrid` with group rows "Upcoming · {n}" (open) and "Past · {n}" (collapsed).

- **Columns:**
  - **Range:** "Mon 2 Nov → Wed 4 Nov", with the note below; a single day shows one date.
  - **Length:** "3 days" / "1 day".
  - **When** (secondary): "In 2 days" / "Tomorrow" / "Now" / "Ended {d MMM}".
- **Hash:** `#manage/traveldays/{id}`.

**Range panel:**

- **Title:** the range. **Meta:** "{n} days · {when}".
- **Range:** From, To and Note, with **Edit**, which uses the PUT and shows field errors under their fields.
- **Effect:** "The Plan tab shows these as Away. No forecast is generated for them, so nothing is rated."
- **Remove range:** "These days are forecast again from the next run." The coral outline **Remove** needs no
  confirmation (the handoff's call).

**Add panel:**

- **Title:** "Add travel days". **Subtitle:** "No forecasts are generated for these dates."
- From and To in two columns; clicking opens the native picker, as today.
- **Note:** optional, placeholder "London — work", with `maxLength=200`.
- **Summary box:** "{n} days" and "{from} to {to}, both included."
- **Footer:** Cancel, then **Add range** (`btn-primary`).
- **Validation is unchanged.**

**Empty:** "No travel days set. Every date is forecast."

## 6. Tests (per phase, `frontend-test-standards.md` / `test-improvement-standards.md`)

- **R1:** §5.1.
- **R2a:**
  - The served merge renders; range, kind and attention reach the request; chip counts.
  - The `metrics` redirect, and the moved hash tests (§4); the aurora door opens `#manage/runs/start`.
  - The status vocabulary for every served status.
  - The job-run panel: deep link by id; each section shows only with data; JFDI tag; dispositions "+n more"; the
    evaluation breakdown; per-service figures.
  - Cursor Load more, and a head poll that doesn't drop or duplicate rows.
  - Close-on-filter decided from the item.
  - **`activeRunId` progress survives closing Start and switching tab**; Dismiss focuses the heading.
  - **A set rewind doesn't move "Today" or elapsed times.**
  - The breakpoint classes.
- **R2b:**
  - The `pipelineruns` redirect (mount and hashchange).
  - The live strip only with `liveCycleId`, sharing one fetch with the open panel.
  - Phase states, "so far", unknown-phase fallback, and the phase meta.
  - Batch drill-in and back: focus returns to the row; the phone back bar label; a null `jobRunId` row is plain.
  - The comparison only with a `comparison`; rank 3; `none`.
  - The live count tracks `runningCount` and clears.
- **R3:**
  - An empty region selection is never sent; all regions sends `null`.
  - A 409 shows the busy sentence.
  - Options are disabled in flight.
  - A `jobRunId` opens its panel; no id shows a toast.
  - Arrow keys move between options; the simulate modal opens.
- **R4:**
  - Sorted by next fire; no actions in rows.
  - Off in config disables everything.
  - The `RunNowButton` double-click guard.
  - Stale and unavailable states; the cron cycle job's proposed preview.
  - The `lastFireTime` fallback; recent runs open the Runs panel.
  - Server tests (§5.5).
- **R5:**
  - The calendar marks away, past and today across a month boundary; labelled cells.
  - Validation; edit via PUT; Remove.
  - Past is collapsed.
  - A set rewind doesn't move the calendar.
  - Server tests (§5.6).

## 7. Gates and review

- **Gates:**
  - Frontend: `npm run lint && npm test && npm audit --audit-level=high && npm run build`.
  - Backend: the build ladder, ending in `./mvnw clean verify` with the integration tests excluded.
- **Review:** each phase gets an adversarial review of the diff before its PR, then CI, and Codex 👍 on the exact head.
- **Browser check:** owed for the whole series, as for the Data tabs: 1280, 1024, 1000, 820 and 390px.

## 8. Owed by reference docs

- `reference/api-endpoints.md`: R1, R4 and R5.
- `reference/platform.md`: the admin Runs, Scheduler and Travel days sections.
- `reference/pipeline.md`: the run status rules, the stale ceiling, and that the ratio is a display rule.

## 9. Departures from the handoff and deliberate drops (running list)

- R-D2 and R-D3.
- **Running and Degraded are new hues** (as the handoff asks).
- **Cancelled batches are ink-3.** The handoff doesn't list Cancelled.
- **Dropped from today's screens:**
  - the run-type select and the two checkboxes (replaced by the chips);
  - sorting by duration or succeeded (the list is newest first only);
  - `MetricsSummary`'s success rate, evaluations count, slowest service and real-time vs batch cost split (the four
    summary cells replace them).
- **Cycle cost is "Batch cost"**, because the briefing's cost has no cycle link.
- **Stale job runs show Failed with "Did not finish"**, a server rule that is new in R1.
- Builders add to this list as they go.

### Built departures (R1)

Where the code or the wording of §5.1 made the plan's text ambiguous, R1 follows these:

- **`total`** follows range, kind and attention (every filter), independent of the cursor. `summary` follows range
  and kind; `kindCounts` and `attentionCount` follow range only.
- **Spend** is the sum of the served rows' costs after the kind filter (a cycle's is its distinct batch runs'). With
  kind ALL this equals "every job run in range, each once" except for a cycle batch run left over from a cycle
  outside the range, which is not counted.
- **The median** covers NIGHTLY cycles *triggered* in range that finished DONE or DEGRADED, not cycles that merely
  ended in range. It ignores the kind and attention chips.
- **`size`** above 100 is clamped to 100; below 1 is a 400 (as `/api/metrics/job-runs` does). Only `range`, `kind`
  and `before` are 400 on an unknown value.
- **The exclusion queries are bounded by the smallest candidate job run id** (`>= minId`), which is exact because a
  hidden run in the window can never have a smaller id than the window's smallest id. The notes fallback looks up only
  the Anthropic batch ids named by candidate runs' notes, not every batch ever submitted.
- **"Running" job runs outside the range** (open, within the longest stale ceiling) are loaded separately so that
  `runningCount` ignores the range as well as the filters.
- **Group `AURORA`** holds `AURORA_EVALUATION` and aurora batches only; `AURORA_GLOSS` (never written) maps to OTHER,
  as the plan's OTHER rule says.
- **`noteTone`** is `INFO` | `WARN` | `ERROR` (null with no note): a running cycle's waiting-on is INFO, a degraded
  cycle's reason and a PARTIAL run's "{n} requests errored" are WARN, everything FAILED is ERROR.
- **`JFDI batch`** is named only when the run's `active_strategies` contains `EVALUATE_ALL` (today's frontend rule);
  `startBatchRun` records no strategies, so a JFDI batch currently reads "Scheduled batch" unless a writer records it.
- **The job-run detail also serves `noteTone` and `triggeredManually`** (the latter is deliberately absent from
  `JobRunDto`), and the cycle row carries `batchCount` as well as `batchesDone` / `batchesTotal`.
- **The batch scope (`batchSummary`) is derived per scheduled-batch row on the returned page**, which is one extra
  query set per row (up to 100). It matches the existing job-runs endpoint; a batched `deriveAll` is a follow-up.
- **A batch run whose `forecast_batch.job_run_id` was never written** is matched on its `notes`
  (`Anthropic batch: <id>`) for its group, for the detail's `batch`, and for its cycle's Batch cost.
- **`nextOvernightAt`** comes from `DynamicSchedulerService.getAll()` filtered to `ForecastCycleJobs.NIGHTLY_JOB`, then
  `calculateNextFireTime`: null when the job is paused, disabled, unknown, or the call throws.

### Built departures (R2a)

Where the plan's text was ambiguous, or the code made a different call safer, R2a follows these:

- **`useHashSelection` keeps an inbound `?query` only when asked** (`{keepQuery: true}`, which the Runs tab passes), not
  for every tab as §4 says. Locations' `?region=` is a filter the reader can change in the page; writing it back on
  `select` re-applied the old region on the next `hashchange` and broke `LocationManagementView`'s "Back restores the
  filter" test. The default (query dropped) is unchanged, so the Data tabs are untouched. `parse` receives the segments
  after the tab with empty ones removed, and the hook returns the same selection object when the hash moves to an equal
  one.
- **`ManageView` reads `#manage/pipelineruns/{id}`** into the Pipeline Runs detail (it holds `activePipelineRunId`;
  `PipelineRunsView` has no hash of its own), at mount and on `hashchange`, so a cycle row's navigation opens that cycle.
  Closing the detail replaces the entry with the bare `#manage/pipelineruns`.
- **A `#manage/runs/cycle/{id}` hash names no panel in R2a** (it reads as the bare tab); R2b owns that path and the
  `pipelineruns` redirect. A `#manage/runs/job/{id}` the server has no row for (404) replaces itself with the bare tab.
- **`#manage` with no tab, and an unknown tab, land on Runs** (was Pipeline Runs). `#manage/metrics` redirects, keeping a
  path and a query.
- **The Pipeline Runs tab id stays `pipelineruns`** and now sits second in the group, after Runs.
- **The summary's Spend is to the penny** ("£18.41", "$23.31"); the Cost column and the panel keep `formatCostGbp`'s
  four decimals.
- **`DispositionBreakdown`:** "{n} candidates" replaces "{n} candidates considered". FORCE_EVALUATED sits right after
  EVALUATED (go swatch); SKIPPED_TRAVEL_DAY sits after Past date (ink-3 swatch, a skip by the admin's own choice). It
  takes an optional `onLoaded(total)`, so the panel can say "No detail available" only when nothing at all loaded.
- **The panel's Diagnostics shows only the rows with a count above zero** (the handoff's frame shows a zero row; §5.2
  says only when greater than 0), and its disclosures are closed until pressed. The old always-open Failed Calls,
  fallback-parsed and prompt-cache blocks are those disclosures. The old "Anthropic Evaluation Breakdown" is
  **Evaluations** and the "Total Cost" block sits at the foot of **Calls**.
- **Dates read "Thu 1 Jan – Wed 7 Jan · 7 days"** (day count kept from the old Days card, event types after it).
  A batch with no target dates shows its horizon range instead. The manual or scheduled source is a row's line 2, not a
  panel row.
- **A running batch's row Result is "5 / 29"**; the panel's Progress row says "5 / 29 succeeded". The row's line 2 for a
  finished batch with no scope says "No detail available", as the old grid row did.
- **The open job-run panel refetches on the list's poll tick** (`pollCount`, one beat), not on a timer of its own, and
  its calls reload quietly when its own counts or completion change.
- **`PanelShell` gained `titleClassName`**, so a batch id can set in mono 18px; every other panel keeps the serif title.
- **The Start a run panel keeps today's `isAdmin` guard** around the card. Its status line is `start-run-status`.
- **Summary two-column breakpoint is 640px** (the plan's "≤640px"); the phone-only classes elsewhere are 639px.
- **`MetricsSummary`'s per-run-type counts** ("WEATHER 3, TIDE 2 …") now exist only as the group chip counts
  (`kindCounts`); the summary strip no longer lists runs by type.
- **Polling is `useVisiblePoll`** (60 s, 15 s while `runningCount` is above 0), so it also stops while the browser tab
  is hidden. It stands down while the Operations pane is hidden, read from the DOM (`closest('[hidden]')`, as
  `usePanelEscape` does): no existing hook announces that the pane is hidden, because the pane is mounted, not removed.
  A later head response never lands over an earlier one. A head that shares no row with what is loaded (more than a
  page of new runs between polls) replaces the list, and older loaded rows are never refreshed by a poll.
- **A start below 1000px closes the Start panel** (the list, and the one progress panel on it, is then what shows);
  the panel's in-flight flags and message live in `RunsView`, so closing and reopening it mid-request keeps both.
- **The first press of any filter control drops an inbound `?range=&kind=&attention=` in place**, so the address never
  names filters the screen no longer shows. Closing Start a run returns focus to the Start a run button.
- **A pasted old `#manage/metrics/…` address while already on Runs** is re-announced after the redirect, so the Runs
  view sees it. Arriving on Pipeline Runs by a bare address keeps the cycle that was open; picking another cycle
  rewrites a cycle address in place.
- **Tests deleted** (their features are dropped by §9): the run-type select and the two checkboxes, the Date / Duration /
  Succeeded sort buttons, and `MetricsSummary`'s slowest service, success rate, evaluations count, batch-versus-real-time
  cost split and client-side Today / 7-day filter (the server now does that, on UK days). Every other assertion moved.

### Built departures (R2b)

Where the plan's text was ambiguous, or the served data could not carry it, R2b follows these:

- **The cycle detail now serves the cost.** R2b added `costMicroDollars` and `costGbpRate` to
  `GET /api/admin/pipeline-runs/{id}` (a small server change beside the frontend work), computed by the one rule the list
  row uses (`AdminRunsService.cycleCost`, shared, not copied). The panel reads the detail's cost, never the list row; zero
  or absent reads as an em dash.
- **A cycle's name** is the row's served name when the row is loaded, else the same wording keyed by `cycleType`
  (`utils/cycleView.js`); the detail serves no name.
- **"Checked {when}"** is the time of the client's own last read of the cycle (the detail has no server timestamp for when
  `waitingOn` was computed).
- **The waiting box's counts** ("{n} of {m} batches") are the cycle's batches still SUBMITTED over all of its batches. With
  no SUBMITTED batch the box heads with the server's own `waitingOn` sentence instead.
- **Not-yet-run phases are drawn by the client.** The server serves only phases that have started, so while a cycle is
  RUNNING the known phases after the last started one (in the order re-classify, submit, wait, retry, briefing) are
  appended as not yet run ("Runs after {the running phase}"; the retry phase says "Runs if needed", since it runs only
  when something failed). Re-classify is drawn for every cycle type that records it (INTRADAY and EVENING, as
  `CycleType.recordsReclassifyPhase()`), so a NIGHTLY cycle's strip bar is four segments, not five. A finished cycle draws
  exactly what the server served, and a phase left RUNNING in one shows no "so far" clock.
- **A completed retry phase with no detail reads "Nothing to retry"** (the copy sheet's wording).
- **`COMPLETED` reads as Done.** The cycle detail serves `PipelineRunStatus`, not `RunListStatus`; `cycleStatusView` maps
  COMPLETED to the Done look and leaves the rest alone.
- **Going back from a batch is a new history entry** (`select` of the cycle), not `history.back()`, so the control, Esc and
  the phone back bar all land on the cycle whichever way the batch was reached (including a pasted nested address).
- **Small losses from the old Pipeline Runs detail,** accepted with the redesign: the summary's started and completed
  dates are now "HH:MM (relative)" in the meta and the full date only in the footer's Completed; there is no `#` column on
  the phase rows (the order is the timeline's); the total duration shows as the Phases meta once finished, with no
  running total while RUNNING (the live strip and the running phase carry the clocks); and the old intro paragraph
  explaining SUBMIT, WAIT and BRIEFING is gone.
- **The back control sits above the title** on split and overlay (a "‹ Cycle #{id}" row, 44px high), not in the
  header row beside the ×; the panel's Refresh sits in the header beside the ×. On a phone the layout's back bar carries
  both.
- **The panel's accessible name is "Cycle #412"** (the label carries the hash sign).
- **A nested batch the server has no run for (404) returns to its cycle,** replacing the entry, not to the bare tab.
- **On the Runs tab ManageView sends no live-count request of its own;** the Runs view reports the count on each of its
  refreshes. Elsewhere it polls `GET /api/admin/runs?size=1` every minute.
- **A null pick reads "No pick"** (a rank present on one side of the comparison only); a pick with no region is "Stay
  home". A changed card shows "Was {headline}" and that nightly pick's own line (region, rating).
- **The live strip's elapsed time is the running phase's** (the wait, as the frames show), not the cycle's.
- **A pasted `#manage/pipelineruns/abc`** (non-numeric) redirects to the bare `#manage/runs`.
- **A taking-over poll reads once at once.** `useCycleDetail` elects the first reader as the poller; when it leaves, the next
  reader takes over and, as `useVisiblePoll` always does when it starts, reads straight away.
- **`fetchPipelineRuns` and the `PipelineRunsView` list are gone;** `api/pipelineRunApi.js` keeps only the detail read.
- **Tests deleted with `PipelineRunsView`:** its list tests (the table's pills, the DEGRADED orange pill, the empty
  state, the row click, the list error sentence) are the old list's, which `RunsView` now serves (covered by
  `RunsView.test.jsx`); the detail tests moved to `RunsCycles.test.jsx` (see the PR body for where each went). The old
  "expand a batch to show dispositions inline" is replaced by the batch drill-in, since the batch's job-run panel carries
  the dispositions.

### Built departures (R3)

Where the plan's text was ambiguous, or the code made a different call safer, R3 follows these:

- **The footer button's label is "Start JFDI batch"** (not "Start jfdi batch"): "Start {option, lower case}" with JFDI kept in capitals. The other labels are lower case ("Start scheduled batch", "Start tide data").
- **Aurora simulation is not a radio option.** It is the Testing group's row (On/Off text, **Simulate…**), as the handoff's frame shows, so the radio groups are Forecast and Data refresh. Simulate… stays usable while a run is in flight (it is not a run). The old "Simulated" amber button state is the On text in `text-verdict-marginal`.
- **One radiogroup, "Run to start".** Forecast and Data refresh are `role="group"` sub-sections (with their headings) inside it, and the roving tab stop runs over all seven options (the chosen one). Arrow keys move the choice and the focus (wrapping), Home and End go to the first and last. Each radio is named by its name (`aria-labelledby`) and described by its description (`aria-describedby`). The inline scope is a `role="group"` named "{option} scope", a sibling of the radio (not inside it), which the radio names with `aria-controls`.
- **The default choice is Scheduled batch**, as in the frame; choosing another option clears the panel's status line.
- **The toast is `SubRegionToast`** (the app's one existing toast: a polite status, five seconds, portalled), reused rather than a second one. `RunsView` holds it.
- **A start with no `jobRunId` uses the server's `status` or "Started."**, except a batch that returns a `batchId`: "{label} submitted: {requestCount} request(s), batch {batchId}." (the light pollution "(job #n)" line is gone). Tide data answers with a `status` and a `runType` and no run id, so it toasts. The aurora forecast has no id: finishing its modal reloads the list, closes the panel and toasts "Aurora forecast complete: {n} nights, {k} locations scored. Cost: {cost}.", as the old status line said.
- **A start never moves a reader who has moved on.** The handlers outlive the panel (the in-flight flags are lifted), so a finish closes, and a run id opens a panel, only when the Start panel is still the open one (`startingRef`); otherwise the id still goes to the progress panel and the list reloads.
- **Below 1000px a start no longer closes the Start panel to show the list.** It opens the new run's panel (overlay or page). That panel covers the list, so while it is the job panel of `activeRunId` the one `RunProgressPanel` is drawn inside its Progress section ("Following this run live below.") and not above the list (moved, never duplicated). At 1000px and up it stays above the list and the note points there. It is not drawn in the panel while the panel is loading or has failed (it stays on the list then), so its stream opens once; going back to the list from the panel re-opens the stream (the server replays).
- **The status line is two always-mounted live regions** (an empty `role="alert"` for errors, an empty `role="status"` for successes), written into. The wrapper keeps `start-run-status`. **Start uses `aria-disabled` and a guard, not `disabled`**, so pressing it never drops focus to the page.
- **A failed regions load says so** ("Couldn't load regions; every region will run.") and Start still sends `null`, as it does with no regions; a failed locations load says "Couldn't load locations; the run will still start." for both tide options.
- **Small copy and timing rules:** "1 coastal location" singular; the simulation row reads "Off." or "On."; the footer scope is in the sans face; the finish toast is not shown while the Operations pane is hidden (read from the DOM, as the poll does); `SubRegionToast` takes an optional `nonce` so the same line shown again restarts its five seconds.
- **One 409 sentence for every option** ("A run is already in progress. Wait for it to finish."), replacing the briefing, tide and batch wordings. Other failures keep their wording, except the tide data one, now sentence case ("Tide data refresh failed. Check the logs.").
- **An empty explicit region selection disables Start**, and `submitBatch` refuses it too. With no regions served at all, Start sends `null` (all), as the old dialog did; while regions load the scope says "Loading regions…".
- **The region rows' counts are separate figures** ("Cumbria" and "5", "All regions" and "10 locations"), not "Cumbria (5)" and "All regions (10 locations)". The "All regions" box shows a mixed state while some regions are unchecked.
- **The tide scopes show "Loading locations…"** until the location list arrives, and the footer shows no scope until then. Tide history's scope is the enabled coastal SEASCAPE locations (what the old confirm counted).
- **Deleted with the legacy body:** the tide `ConfirmDialog` usage and the batch region `ConfirmDialog` (both inline in `StartRunPanel`; `ConfirmDialog` and `useConfirmDialog` stay, others use them), the `anyRunning`-gated button rows, the emoji labels, and the `indigo-*`, `amber-*`, `green-400` and `red-400` colours (token colours now).
- **`StartRunPanel`'s props are `onRunStarted(jobRunId)` and `onFinished(message)`** (were `onActiveRunChange` and `onStarted`); `useStartRunState` is unchanged.
- **Tests deleted** (their subject is gone): the batch dialog's "Submit (n regions)" confirm button text, "n of m regions selected" line and the dialog's own title and description (the option and footer carry them), the tide `ConfirmDialog`'s "Refresh" confirm click, and the "closes on cancel" dialog test (Cancel is now the footer's, which closes the panel). Every other assertion moved to `StartRunPanel.test.jsx`, `RunsView.test.jsx` or `RunProgressKeptAfterFailures.test.jsx`.

### Built departures (R4)

Where the plan's text was ambiguous, or the code made a different call safer, R4 follows these:

- **The jobs list also serves `runGroup`** (`CYCLES`, `TIDE`, `AURORA` or null), beside `lastRun`. A mapped job with no
  runs yet has a null `lastRun`, so the panel could not tell "no runs yet" from "never attributable" without it; it is
  also what "All in Runs" filters by (`#manage/runs?kind={runGroup lower}`). `lastRun` is on the list only; the pause,
  resume and schedule responses carry `runGroup` but no `lastRun`.
- **`DISABLED_BY_CONFIG` is a 409 for pause and resume as well as trigger.** `rejectIfDisabledByConfig` now throws a
  `JobDisabledByConfigException` (an `IllegalStateException`, so existing catches still hold), which
  `GlobalExceptionHandler` maps to 409. An `IllegalStateException` for any other reason (no registered target) stays 500.
- **The runs are read by `SchedulerRunsService`**, which maps the five jobs once and hands the rows to
  `AdminRunsService.cycleItems` / `jobItems` (new public methods over the Runs list's own private mapping, so a status
  reads the same in both places). The jobs list reads the newest run of each attributable job: up to five jobs, each
  costing a few queries (its runs, then a cycle's batches, their cost runs and the unlinked batches' notes), never one
  per job in the list or per row; the runs endpoint reads `limit` of one job's the same way. The batch scope
  (`batchSummary`) is not derived for these items: the Scheduler never shows it.
- **`aurora_batch_evaluation` matches by notes as well as by link.** A `SCHEDULED_BATCH` run whose
  `forecast_batch.job_run_id` was never written is found through its `Anthropic batch: {id}` notes, as the Runs list's
  own fallback does.
- **`limit` below 1 or not a number is 400; above 10 is clamped to 10** (the Runs list's `size` rule).
- **Recent runs read "{started} · {duration}" beside the status dot**, each row an anchor to its Runs panel (a plain
  link, so it works with the keyboard and a middle click); a running run reads "running" where a duration would be.
- **The reference point sits in Schedule for a sun-relative job and in Next fires for a cycle job still on cron**, so it
  is named once, as "Durham, unless configured otherwise · {lat}, {lon}" with "Computed {time}" small under it. The
  server serves coordinates only, no place name, so the place is the plan's default, not a lookup.
- **Next fires shows for the two sun-capable cycle jobs only** (and any job that is sun-relative); the evening refresh,
  not being sun-capable, has none, but it has the "How sun-relative timing works" disclosure, which is on all three
  cycle jobs. The fourth note's "(shown above)" and "the job's card" are kept verbatim.
- **Status chips are a single-select-or-none group** (Paused, Off in config), ANDed with the type chips. A pasted
  `#manage/scheduler/{key}` is not a filter, so the chips are local state and never written to the hash.
- **The Next column sorts, and so does Job** (a header press; a sort chip does it in card mode). Paused, off and
  next-less jobs stay last whichever way Next is sorted.
- **Run now, Pause and Resume state is held per job in `SchedulerView`** (`useSchedulerActions`, keyed by job key, as
  `RunsView` holds `useStartRunState`): the in-flight guard, the "Started" confirmation and a failure survive leaving a
  job's panel and coming back, and a late failure shows only in its own job's panel (cleared by that job's next action).
  The guards are refs set inside the click, so a double press never sends twice. A failure shows inside the panel's Job
  actions, not in the list's banner, which is hidden behind the panel on a phone; the messages name the job
  ("Couldn't pause {name}.", "Couldn't resume {name}.", "Couldn't start {name}.", "Couldn't save {name}'s schedule.").
  A failed **background poll's** banner is in the list pane, so on a phone it is covered by an open panel.
- **Run now and Pause or Resume are `aria-disabled` plus a guard, not `disabled`, while busy, in flight or confirmed**, so
  a press never drops keyboard focus; only a job off in config is truly `disabled`. The Pause or Resume description is
  `aria-live="polite"`.
- **A manual cycle also counts as its cycle job's `lastRun`.** `pipeline_run` has no manual flag, so a cycle started from
  Runs (or a catch-up) is indistinguishable from a scheduled one.
- **"ran" shows only under an active sun-relative job**: never under a proposed rule (a cycle job still on cron), and
  never for a paused job.
- **Next fires is a table plus a `KvList`** ("Expected finish", "Over the year", and "Reference" for a cron cycle job),
  after frame 06. The rule paragraph shows only under a proposed rule ("Proposed rule" label); a sun-relative job's rule is
  in Schedule, so the old "Current rule: this job is sun-relative now." sentence is gone. The Today / Tomorrow table sits in a
  rounded bordered box with a rule under its header, and a fallback reason wraps anywhere.
- **Schedule has no "Next fire" row**: the panel's meta line already says it ("next Sun 02:30 (in 14h)"). No Edit is drawn
  for a job that is off in config (Job actions says what it requires).
- **The fourth timing note differs from the old panel's words**: its last sentence read "Switching back to a fixed time is
  the same control on the job's card."; there is no card now, so it reads "…is the "Switch back to a fixed time…" link in
  Schedule above." The other three notes and the first half of the fourth are verbatim.
- **The "How sun-relative timing works" body is always rendered, hidden until opened**, so `aria-controls` always resolves.
- **The phone card's second line** is "{schedule} · next {time} ({relative})", and "· next —" on a paused or off row. The
  loading lines use the ellipsis character ("Loading scheduler jobs…", "Loading solar preview…").
- **The page-level `<p>Scheduler</p>` title in `ManageView` is gone**: the list pane carries the heading.
- **`SolarSchedulePanel.jsx` is deleted**, its content moved to `JobSchedule` and `JobNextFires`. The three tests files
  that pinned the old cards (`SchedulerView.test.jsx`, `SchedulerViewSolar.test.jsx`,
  `SchedulerViewSolarReview.test.jsx`) were rewritten for the new screen; every assertion about behaviour that survives
  moved with the same values, and only the test ids and the changed copy differ ("Triggered ✓" to "Started ✓", "Disabled
  by Config" to "Off in config", "Switch back to fixed time..." to "Switch back to a fixed time…", "Last run: 3 min ago"
  to "fired · 3 min ago", "Requires:" to "Requires"). Dropped with their subject: the old card layout test ids
  (`scheduler-job-{key}`, `status-pill-{key}`), the always-on notes list (now a disclosure), and the page-level
  "Sun-relative forecast cycles" card with its heading and "Read-only" line.

## 10. Copy sheet (from the handoff, adjusted for §1)

- **Runs**
  - Header: "Runs" / "Updated {time} · refreshes every minute" / "Start a run".
  - Live: "{cycle} is running" — "Cycle #{id} · started {time}" — "{phase} · waiting on {n} of {m} batches · {elapsed}
    so far".
  - Summary: Runs · Need attention · Spend · Median overnight cycle.
  - Chips: Today · 7 days · 30 days | All · Cycles · Batches · Tide · Briefing · Aurora · Other | Need attention.
  - Status: Running / Done / Degraded / Partial / Failed.
  - Row notes: "{n} requests errored" / {failure reason} / {waiting on} / "Did not finish".
  - Empty: "No runs in this range. The next overnight cycle starts {time}."
- **Cycle panel**
  - Sections: vs this morning's nightly · Phases · Batches.
  - Waiting: "Waiting on {n} of {m} batches" — "{ids}, submitted {time}. Checked {when}."
  - Phases: Re-classify (cost-gate) · Submit batches · Wait for completion · Retry failed requests · Briefing (gloss +
    best-bet); "Runs after Wait"; "Nothing to retry".
  - Comparison: Plan changed / No change / Was {pick} / Baseline: nightly cycle #{id} · {time}.
  - No batches: "No batches were submitted. The cycle went straight to the briefing."
- **Start a run**
  - Options: Scheduled batch · JFDI batch · Briefing · Aurora forecast · Tide data · Tide history · Light pollution ·
    Aurora simulation.
  - Button: "Start {option, lower case}".
  - Busy: "A run is already in progress. Wait for it to finish."
- **Scheduler**
  - Status: Active / Paused / Off in config.
  - Panel: Schedule · Next fires · Recent runs · Job actions.
  - Actions: Run now — "Starts a cycle straight away. Tonight's still runs." / Pause — "Skips its fires until you resume
    it." / Resume.
  - Triggered: "Started. It'll appear in Runs in a moment."
- **Travel days**
  - Intro: "The overnight batch skips forecasts for days you're away. Start and end dates are both included."
  - Groups: Upcoming · {n} / Past · {n}.
  - Add: "Add travel days" — "No forecasts are generated for these dates." / "{n} days" — "{from} to {to}, both
    included."
  - Remove: "Remove range" — "These days are forecast again from the next run."
  - Empty: "No travel days set. Every date is forecast."

## 11. Possible follow-ups (not in this series)

- Link a cycle's briefing job run to it (a `pipeline_run_id` on `job_run`), so it nests under the cycle and its cost
  joins the cycle's.
- Store a pipeline run's trigger (schedule / Run now / catch-up), to bring back the Trigger row.
- A startup sweep that closes orphaned job runs, instead of serving them as stale.
- A wait-phase percentile, for the batch-latency sentence.
