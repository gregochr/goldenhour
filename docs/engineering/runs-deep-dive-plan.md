# Runs deep dive: Day, Cost trends and Timing

**Status:** planned 2026-11-02. Phases D1 → D4, one PR each. Follows `runs-redesign-plan.md` (R1–R5), whose rules
(R-D1 backend-heavy, R-D7 real clock, rows carry no buttons, panels for detail) still hold unless this plan says
otherwise.

**Source:** the owner's Claude Design export "PhotoCast Runs" (`design_handoff_runs_deep_dive`: `README.md`,
`prototype/Runs Deep Dive.html`, `prototype/runs-deep.js`, seven screenshots). It lives with the owner, not in the
repo. For the life of the planning session it is unpacked at
`/private/tmp/claude-501/-Users-chrisgregory-IdeaProjects-goldenhour--claude-worktrees-runs-screen-changes-e9f855/db940306-1cae-48f9-a8bf-047db613b612/scratchpad/design/design_handoff_runs_deep_dive/`.
The handoff's README is the copy and token sheet; where the handoff and the codebase disagree, **the codebase wins**.

**Owner note (2026-11-02):** the running-cycle banner (`RunsLiveStrip`: "Afternoon refresh is running · Cycle #330 ·
started 12:29", phase bars, "Submit batches · 48s so far", Open) is liked and **stays**, above the view tabs, on every
view.

## 1. What changes

The Runs sub-tab's list (one reverse-chronological list with range chips and Load more) is replaced by three views
under the Runs heading: **Day** (default), **Cost trends**, **Timing**. The header (Runs, Updated · refreshes every
minute, Refresh, Start a run), the progress panel, the live banner, the Start a run panel and the cycle / job-run
panels all stay. "Open run detail →" in an expanded Day row opens the existing panel (`#manage/runs/cycle/{id}` or
`#manage/runs/job/{id}`).

## 2. Server facts this plan rests on (checked 2026-11-02)

- **A cycle** is a `pipeline_run` (`cycle_type` NIGHTLY / INTRADAY / EVENING → "Overnight cycle" / "Afternoon refresh"
  / "Evening sunrise refresh", `RunClassifier.cycleName`). Its batch job runs (`forecast_batch.pipeline_run_id` →
  `job_run_id`, plus the `notes` fallback in `AdminRunsService`) and its `disposition_job_run_id` belong to it and are
  **never separate Day rows**. Cycle cost = sum over its distinct linked job runs (`AdminRunsService` ~413–439).
- **The briefing is a phase of the cycle** (`PipelineOrchestrator` ~764–774) and writes its own `BRIEFING` job run.
  **ASK_READY** runs after `finishRun` (`dispatchAfterRun`, ~816–841). `job_run` has **no pipeline column**; the
  ASK_READY's cycle id is only on `ask_ready_answer.pipeline_run_id`.
- **Run types written:** SCHEDULED_BATCH, TIDE, BRIEFING, AURORA_EVALUATION, LIGHT_POLLUTION (manual), WEATHER
  (Wildlife comfort **and** Astro conditions, told apart by `evaluation_model` WILDLIFE vs null), ASK, ASK_READY.
- **Sky eval** writes no `job_run`: `sky_rating_eval_run` has `cost_micro_dollars`, `started_at`/`completed_at` (UTC
  `LocalDateTime`), `status`, `trigger_source`, `model`, no FX rate, no `api_call_log` rows.
- **Cost:** `job_run.total_cost_micro_dollars` + `exchange_rate_gbp_per_usd` (null rate possible); a running batch has
  a null total. Open-Meteo calls cost 0 (`cost.open-meteo-micro-dollars`). FX history: `ExchangeRateService.getRate`.
- **`api_call_log`** rows carry `job_run_id` (indexed), `service`, `duration_ms`, `succeeded`, `status_code`,
  `error_message`, `error_type`, `cost_micro_dollars`, `is_batch`, `batch_id`. No retention job on either table.
- **Times:** `job_run` and `api_call_log` are UTC `LocalDateTime`; `pipeline_run` is `Instant`. UK days come only from
  `ForecastHorizon` (`today(clock)`, `ukDayStartUtc`, `civilDate`).
- **Scheduler:** `scheduler_job_config` (CRON / FIXED_DELAY / SOLAR_OFFSET). `SolarOffsetTrigger.fireFor(ukDay)`
  resolves a solar rule for a day; `SolarTriggerFactory.create(...)`; `CronExpression` for cron. Nothing lists
  "everything still due today".
- **Solar:** `SolarService.sunriseUtc/sunsetUtc(lat, lon, date)` at `SolarReferencePoint`.
- **Latest migration:** V213.

## 3. Decisions

- **DD-1. Backend-heavy.** The server serves each view's rows, statuses, per-type medians and p90s, estimates, the
  "slow · top 10%" flag, the cost verdict sentence parts and the per-run sunset. The client lays out, scales and draws.
  Two client derivations are **licensed** by this plan and added to the named list in
  `reference/architecture-decisions.md` ("Backend-heavy"):
  1. **Timing chart geometry**: histogram binning, the Gaussian KDE curve and the 7-run rolling median line, all over
     the served duration list. Presentation of served numbers, not a verdict.
  2. **The sunset planner replay**: for a slider offset and buffer, `ready = −offset + duration` per served run, the
     in-time count, and "latest start that lands 9 in 10" = `ceil5(buffer + p90)` with p90 **served**. It is a what-if
     over served numbers; the server cannot pre-serve every slider position.
  The 7-day rolling average on the cost chart is the third member (presentation of served daily totals).
- **DD-2. Type taxonomy (codebase wins).** Seven display types, served as a `type` key:
  `OVERNIGHT` (NIGHTLY cycle), `AFTERNOON` (INTRADAY cycle), `EVENING` (EVENING cycle), `ASK_READY`, `BRIEFING`,
  `SKY_EVAL` (`sky_rating_eval_run`), `OTHER` (TIDE, WEATHER, AURORA_EVALUATION, LIGHT_POLLUTION, ASK, and any
  SCHEDULED_BATCH not owned by a cycle: aurora and JFDI batches). One `RunDeepType.of(...)` mapping with no default
  branch, beside `RunClassifier`. Display names come from the server.
- **DD-3. Link chained runs to their cycle (V214).** Add nullable `job_run.pipeline_run_id BIGINT` + index. Written
  from now on by the cycle's BRIEFING run and by `AskReadyPrecompute` (which already has the run id). History falls
  back to: BRIEFING = the BRIEFING run that starts inside the cycle's BRIEFING phase window; ASK_READY = the
  `ask_ready_answer.pipeline_run_id` rows, else the first ASK_READY run starting within 30 minutes after the cycle's
  `completed_at`. The column is **written by a scoped repository update only** (`updatable = false` on the entity,
  per the column-scoped-writes invariant). This is what "Until answers ready" (cycle start → its ASK_READY end) and
  the Day list's "Runs after Afternoon refresh" sublines read.
- **DD-4. Estimates** are the median (duration, cost) of the last 30 **non-manual, finished** runs of that type; p90 is
  the 90th percentile of duration over the same set. "slow · top 10%" = duration > p90 of the 30 runs before it.
- **DD-5. Still to run today** is a server list built from `scheduler_job_config`:
  - ACTIVE CRON and SOLAR_OFFSET jobs whose run maps to a display type (the three cycle jobs, `tide_refresh`, the
    wildlife and astro refresh jobs, `aurora_batch_evaluation`, `sky_rating_eval`), with every fire between now and
    the end of the UK day. CRON via `CronExpression` in the job's `time_zone`; solar via `SolarOffsetTrigger.fireFor`.
  - FIXED_DELAY jobs (pollers) are left out: they would flood the list and most write no run.
  - Each cycle fire adds its chained children: BRIEFING at `~fire + median cycle duration − median briefing
    duration` labelled "Runs at the end of {cycle}", and ASK_READY at `~fire + median cycle duration + median gap`
    labelled "Runs after {cycle}". Their start is served with `estimated: true` (the `~`).
  - The cycle's subline is its trigger, served: "Sun-relative · 4h 10m before sunset (16:49)" or "Fixed time · 21:00".
- **DD-6. External calls** are an aggregate of `api_call_log` per run (for a cycle: over all its linked job runs, plus
  its BRIEFING and ASK_READY children are **not** included, they have their own rows), grouped by `service` and
  `is_batch`: calls, sum of `duration_ms` (for Anthropic batch rows: batch wall time from `forecast_batch`
  submitted→ended instead, and count served as "n batches · m requests"), sum of cost, error count and the commonest
  `error_message` (truncated 120). Purpose text is a server-side static map per `(service, runType)` ("Forecast ·
  cloud, visibility, wind", "Air quality · aerosols", "Message Batches · sky scoring", "Tide times · coastal spots",
  "Messages · ready answers" …). Status: OK (no errors), ERRORS (some), FAILED (all failed). Sky eval has no call log,
  so its expanded row shows "Calls are not logged for Sky eval runs" instead of the table. **Dropped:** the "Will call"
  table on scheduled rows (no truthful source). Admiralty "cached" in the design is WorldTides in this codebase, and it
  appears only when calls were logged.
- **DD-7. The 3-dollar figure.** USD is served beside GBP: GBP uses the run's own rate, falling back to
  `ExchangeRateService.getRate(runDate)` (Sky eval always uses that).
- **DD-8. Real clock (R-D7).** "Today", "Now", "n days ago", "in 3h 14m" use `new Date()` on the client and the injected
  `Clock` on the server, never `appNow()`. A test pins that a set Rewind moves none of them.
- **DD-9. Address.** The view and day ride the hash query: `#manage/runs?view=day&date=2026-11-01`,
  `?view=cost&range=30&type=ASK_READY`, `?view=timing&type=AFTERNOON&range=60&measure=ready`, written with
  `replaceState` (no history entry), so a reload or a shared link restores it; panel paths (`/cycle/{id}`, `/job/{id}`,
  `/start`) keep working with the query beside them (`keepQuery: true`, already set). The old `?range=&kind=&attention=`
  inbound links map: `kind` → the Day filter chip (CYCLES → Cycles, BRIEFING → Briefing, others → Other),
  `attention=true` → Need attention; `range` is ignored. localStorage remembers the last view only (wrapped in
  try/catch).
- **DD-10. Retention bound.** The earliest selectable day is the earliest `job_run.started_at` UK day (served as
  `earliestDay`), capped at 400 days back.
- **DD-11. The old list endpoint stays** (`GET /api/admin/runs`): the Scheduler and `onLiveCountChange` read its
  `runningCount` / `liveCycleId` / `RunListItem`. Removing it is a follow-up only once nothing reads it. The new view
  keeps polling it for the live banner and the nav count (one call a minute, unchanged).
- **DD-12. Apply in Scheduler** navigates to the Scheduler sub-tab with the `intraday_forecast_refresh` job's panel open
  and its solar offset edit prefilled to the slider value, if the Scheduler panel can take a prefill through its hash;
  otherwise it opens the job's panel and the button reads "Open in Scheduler". Never saves on its own.
- **DD-13. Responsive.** The design's breakpoints apply only inside the Runs view: < 900px hides the Cost and the
  right-hand columns, < 700px summary strips go to 2 columns, < 1000px Timing two-up cards stack. On a phone
  (`useIsMobile`) the day strip scrolls horizontally inside its card (no page scroll) and the calendar popover is a
  full-width sheet. ManageView's shell (sidebar, group bars) is untouched.
- **DD-14. Charts** are hand-rolled SVG, as in the prototype (the frontend has no chart library on this screen); colours
  from the README's tokens, added as Tailwind theme values or CSS variables, never inline styles (SVG presentation
  attributes on chart marks are allowed, as elsewhere in the codebase's SVG).

## 4. Phases

### D1 — Day endpoints (backend)

- `V214__job_run_pipeline_run_id.sql`: nullable `pipeline_run_id BIGINT` on `job_run`, FK-less, index. Testcontainers
  migration test.
- Write it: `JobRunRepository.linkPipelineRun(jobRunId, pipelineRunId)` (scoped `@Modifying` update) called from the
  orchestrator's BRIEFING phase (after `refreshBriefing` returns its run id; if `refreshBriefing` does not expose the
  id, return it) and from `AskReadyPrecompute.precompute(runId)` after `startRun`. Best effort, logged on failure,
  never fails the cycle.
- `RunDeepType` + mapping (DD-2), `RunTypeStats` (DD-4, one query per type over the last 30 finished runs, cached for
  the request).
- `GET /api/admin/runs/day?date=YYYY-MM-DD` (ADMIN; default today UK; 400 on a bad or future date or before
  `earliestDay`). Response `RunDayResponse`:
  - `date`, `isToday`, `earliestDay`, `sunrise`, `sunset` (UK `HH:mm` and ISO instants), `now` (ISO, only when today).
  - `runs[]`: `id` (string: `cycle-12`, `job-34`, `eval-5`, `sched-<jobKey>-<epoch>`), `kind` (CYCLE / JOB / SKY_EVAL /
    SCHEDULED), `type`, `typeLabel`, `name`, `subline`, `startedAt`, `endedAt`, `estimated` (bool), `durationSeconds`,
    `costMicroDollars`, `costGbp` (pence-precision decimal), `costUsd`, `status` (DONE / PARTIAL / FAILED / RUNNING /
    SCHEDULED; DEGRADED → PARTIAL with the note), `note`, `noteTone`, `slow` (bool), `parentCycleId`, `cycleId`,
    `jobRunId`, `facts` (finished: `startedAt`, `finishedAt`, `fasterThanPercent` of last 30, `batches`,
    `requests`, `answersReadyAt` for cycles; scheduled: `expectedStart`, `expectedFinish`).
    Sorted ascending by start.
  - `summary`: today → `finished`, `stillToRun`, `needAttention`, `attentionNames[]`, `spendGbp`, `projectedGbp`,
    `avg30Gbp`, `nextUp {name, at}`; past → `runs`, `cycles`, `jobs`, `needAttention`, `partial`, `failed`,
    `spendGbp`, `vsAvgPercent`, `slowestCycle {name, durationSeconds, medianSeconds}`.
  - `filterCounts` per chip (ALL, CYCLES, ASK_READY, BRIEFING, SKY_EVAL, OTHER, ATTENTION).
- `GET /api/admin/runs/calendar?month=YYYY-MM` (ADMIN): per UK day `date`, `runs`, `spendGbp`, `attention` count, plus
  `maxSpendGbp`, `earliestDay`.
- `GET /api/admin/runs/calls?runId=cycle-12|job-34` (ADMIN): `services[]` per DD-6. 404 unknown.
- Reuse `RunClassifier` for statuses and notes. No business logic in the controller.
- Tests: service unit tests for the type mapping (every RunType), UK-day boundaries across the 25 Oct DST change, a
  run at 23:30 UTC on 24 Oct (00:30 BST on 25 Oct) landing on 25 Oct, cycle folding (batch runs not top-level), the chained-children fallback, scheduled fires for cron and
  solar jobs with a fixed `Clock`, estimates and `slow`, attention counts, calls aggregation (batch vs sync, error
  status), controller tests for 400/404/403. Update `reference/api-endpoints.md` and `database-migrations.md`.

### D2 — Day view (frontend)

- `RunsView` keeps the header, progress panel, live banner, Start a run and the panels; the `RunsListPane` body is
  replaced by a `RunsViewTabs` (Day · Cost trends · Timing; Cost and Timing render "Coming soon" placeholders until
  D4) and `RunsDayView`.
- New components under `components/runs/day/`: `DayDateBar` (‹ › date button, Today, "n days ago", keyboard ←/→/T,
  Esc closes the popover; keys ignored while focus is in an input or a panel is open), `DayCalendar` (popover per the
  README), `DaySummary`, `DayStrip` (SVG, 5 lanes, night shading, sunrise/sunset/now lines, dashed scheduled blocks,
  hover tooltip, click → expands that row and scrolls it into view), `DayRunList` (filter chips, FINISHED / STILL TO
  RUN TODAY groups on today, rows, the expanded row with facts and `ExternalCallsTable` fetched on expand).
- Hooks: `useRunDay(date)` (polls every minute only when the date is today and the pane is visible, as `useRunsList`
  does), `useRunCalendar(month)`, `useRunCalls(runId)`.
- Expanded row: click toggles; one open at a time; Enter/Space on a focused row; `aria-expanded`. "Open run detail →"
  opens the existing panel. A scheduled row has no detail link.
- Address and storage per DD-9; real clock per DD-8.
- Delete what nothing uses any more (`RunsListPane`, `RunsSummary`, `useRunsList`'s paging, if unused), keeping
  `useRunsList`'s live-count poll or replacing it with a slim hook.
- Tests (Vitest + Testing Library, `frontend-test-standards.md`): date stepping and clamping, keyboard, calendar
  dots/bars/disabled future, summary for today vs past, groups and `~` estimates, expand/collapse and the calls
  table states (loading, OK, errors, Sky eval note), filter chips and counts, hash restore, the live banner still
  shows, Rewind does not move "Today".

### D3 — Cost and Timing endpoints (backend)

- `GET /api/admin/runs/cost?days=14|30|60` (ADMIN; 400 otherwise). Excludes today. Response:
  - `days[]` for the last 2N UK days: `date`, `byType {TYPE: gbp}`, `totalGbp`.
  - `types[]` sorted by £ delta desc: `type`, `label`, `lastGbp`, `priorGbp`, `deltaGbp`, `deltaPercent`, `lastRuns`,
    `priorRuns`, `runsChangePercent`, `perRunChangePercent`, `avgLocationsLast`, `avgLocationsPrior` (ASK_READY only,
    from `locations_processed`).
  - `totals`: `lastGbp`, `priorGbp`, `deltaGbp`, `deltaPercent`, `lastUsd`, `fxRate` (the latest rate), `perDayLast`,
    `perDayPrior`.
  - `verdict`: `headline` (the three README sentences), and the body parts the client bolds: `topType`,
    `topDeltaGbp`, `runsAsOften` (bool, |run change| < 10%), `perRunMultiple`, the ASK_READY locations pair, the Sky
    eval share sentence parts when its share > 4%. Composed server-side as structured fields; the client only joins
    them into the README's sentence template.
- `GET /api/admin/runs/timing?type=OVERNIGHT|…&days=30|60|120&measure=cycle|ready` (ADMIN; `measure=ready` only for
  cycle types, 400 otherwise). Response:
  - `runs[]`: `id`, `startedAt`, `date`, `durationSeconds` (per measure), `sunsetAt` (the actual sunset on that run's
    UK date at the reference point), `dayRunId` for click-through.
  - `stats`: `medianSeconds`, `p90Seconds`, `slowest {seconds, date}`, `fastest`, `count`, `firstHalfMedian`,
    `secondHalfMedian`, `changePercent`, `skewed` (p90/median > 1.35).
  - `typeMedians {TYPE: seconds}` for the chips.
  - For AFTERNOON: `trigger {offsetMinutes, solarEvent}` from the job's config and `todaySunset`. For fixed-time
    cycles: `startsAt` (cron time), `typicalReadyAt`, `p90ReadyAt`, `worstReadyAt`, and for OVERNIGHT `sunriseToday`
    and `slackAtP90Minutes`.
- Manual runs are excluded from timing (they are not scheduled behaviour). Tests as D1's shape; update reference docs.

### D4 — Cost trends and Timing views (frontend)

- `RunsCostView`: verdict card, `DailySpendChart` (stacked SVG bars per README order, legend chips (multi-select since 2026-11-11), 7-day line,
  hover tooltip, click a day → Day view for that date), `CostChangeTable` (rows toggle a type on click, sparklines, total
  row with USD at the served rate).
- `RunsTimingView`: type chips with served medians, range and measure segmented controls, stat strip,
  `DurationHistogram` (binning + KDE + staggered markers + skew note), `SunsetPlanner` (AFTERNOON only; slider
  20m–5h step 5m defaulting to the served trigger, buffer segmented, strip chart, result box, suggestion bar with Try
  it / Reset / Apply in Scheduler per DD-12), `ReadyByCard` for fixed-time cycles and the explainer for non-cycle
  types, `SlowerScatter` (dots, p90 amber, rolling median, click → Day).
- Pure helpers in `utils/runsTiming.js` (binning, KDE, rolling median, planner replay) with their own unit tests;
  these are the DD-1 licensed derivations and carry a comment saying so.
- Tests: verdict template, isolate behaviour, range switching, click-through to Day, planner maths at the edges
  (0 runs, all late, buffer = sunset), skew note, measure toggle hidden for non-cycles.

## 5. Gates and review

Each phase: the build ladder and frontend four commands green; UI phases (D2, D4) get the six-lens adversarial review
(correctness, accessibility/keyboard, responsive/phone, backend-heavy and data honesty, state/address, regression of
the kept pieces: banner, progress panel, panels, Start a run) with refuters, and browser verification against the
local backend on 8083. Each phase adds a `changelog.d/` entry and updates the reference docs it touches
(`api-endpoints.md`, `database-migrations.md`, `platform.md` admin section, `architecture-decisions.md` licensed list).

## 6. Departures from the handoff

- Admiralty Tides → WorldTides; NOAA SWPC / AuroraWatch UK appear only if the aurora job logs them (it does not today:
  the aurora run's table shows what `api_call_log` holds).
- No "Will call" table on scheduled rows (DD-6).
- Sky eval rows have no external-calls table.
- The briefing is "Runs at the end of {cycle}", not "after", because it is the cycle's last phase.
- FX is the stored per-run rate, not a fixed 1.34.
- The sunrise/sunset come from `SolarService`, not the prototype's cosine approximation.

## 7. Review corrections (frontend review, 2026-11-02) — these override §3–§4 where they differ

- **F1. Keys.** The ←/→/T listener does nothing when the Runs root is inside `[hidden]` (the Operations pane stays
  mounted behind Map/Plan, whose `WindowFirstShell` also uses document-level arrows), when `event.defaultPrevented`,
  when a modifier is held, when focus is in an input/select/textarea, or when `visibleDialogOpen()` (export it from
  `usePanelEscape.js`) is true. The calendar grid's own arrow keys `preventDefault`. Test: Map tab with a window open,
  → does not move the Runs date.
- **F2. The calendar is a disclosure, never `role="dialog"`/`aria-modal`** (DataSplitLayout and `mapForeignModal.js`
  would count it as a foreign modal). Button with `aria-expanded`/`aria-controls` + a plain region. Closes on Esc
  (handled first, `stopPropagation` + `preventDefault`, before the panel's Esc), outside press, a pick, and when the
  pane is hidden. On a phone it renders inline, full width, under the date bar (not a BottomSheet). Reuse
  `utils/travelDays.js` (`monthCells`, `shiftMonth`) and the `TravelDaysCalendar` key pattern.
- **F3. Extract before deleting.** D2 first extracts `RunsHeader` (the `h2` heading + `headingRef`, Updated · refreshes
  every minute, Refresh, `start-run-btn` + `startButtonRef`) and `RunsTopStack` (progress → error → live banner order)
  from `RunsListPane.jsx` (~233–275) with the same test ids, then deletes the pane. Updated/Refresh follow the active
  view's fetch.
- **F4. One query helper.** Replace `filtersFromHash` + `dropInboundQuery` with one `runsQuery` read/merge/write
  helper (`replaceState`). Legacy `kind`/`attention` are mapped once and rewritten out. `date` is written only when it
  is not today. Test: open a panel → close → reload keeps `view` and `date`.
- **F5. One refresh.** `refreshAll()` (live hook + active view) replaces every `list.refresh` call
  (`onComplete`, `handleRunStarted`, `handleStartFinished`), and one shared `pollCount` feeds `useJobRunDetail`.
- **F6. Live hook cadence.** The slim live hook (`GET /api/admin/runs?size=1`) keeps `useRunsList`'s 15s-while-running /
  60s cadence, `isPaused`, and the `pollCount`-keyed `onLiveCountChange` (ManageView skips its own poll on Runs).
  `cycleRow(liveId)` returning null falls back to `CYCLE_NAME`; test the banner title.
- **F7. No close-when-filtered-out.** Neither a date change nor a Day chip closes an open panel (it was opened
  deliberately). Remove that hook use and its `needsAttentionStatus` wiring here.
- **F8. Focus return.** The expanded row's "Open run detail" link carries `data-testid="runs-row-cycle-12"` /
  `runs-row-job-34` (the served `id` minus nothing: D1's ids are `cycle-N` / `job-N`), so `DataSplitLayout`'s focus
  return finds it; `rowTestIdPrefix="runs"` stays.
- **F9. Container queries.** DD-13's breakpoints are `@container` widths of the list column (it shrinks by 440px when a
  panel is split open), not viewport media queries. Charts are `viewBox` SVGs at width 100%. Phone-only behaviours
  (strip horizontal scroll, inline calendar) sit behind `useIsMobile`.
- **F10. DD-12 is "Open in Scheduler"** (`#manage/scheduler/intraday_forecast_refresh`) in D4. A prefilled solar edit
  (`SchedulerView` keeps `editingJob` in local state; `SolarEditForm` seeds from the job) is a named follow-up.
- **F11. Test budget.** `test/RunsView.test.jsx` (~139 list test ids), `RunsCycles.test.jsx`,
  `RunProgressKeptAfterFailures.test.jsx` (mocks `fetchRuns`) are ported to the Day view's rows (panel open, focus
  return, batch return, progress placement) before the old pane is deleted.
- **F12. UK today on the client** comes from the server's `date`/`isToday` and an `Intl` Europe/London formatter, never
  the browser's local day. When a poll returns `isToday: false` (midnight passed), the `~` rows go. A future or
  pre-`earliestDay` hash date is clamped with `replaceState`, not shown as a 400.
- **F13. States.** Day: loading skeleton, error + Retry, empty day, zero-row filter, calendar fetch error. Calls: fetch
  error distinct from "run had errors". Timing: n = 0 and 1 (no KDE, null p90), AFTERNOON with no solar trigger.
  Cost: all zeros. Each is in the phase's tests.
- **F14. Charts.** DD-14 is corrected: `recharts` is a dependency and used in Operations (`SkyRatingEvalView.jsx`). Use
  Recharts for the stacked daily-spend bars and the scatter where it fits; the day strip, histogram + KDE + staggered
  markers and the planner strip stay hand-rolled SVG (Recharts cannot express them cleanly). No `style={{…}}`:
  tooltips are positioned inside the SVG (`<g transform>` / `foreignObject`) or via Recharts' own tooltip. Series
  colours go in `index.css` `@theme` as `--color-run-*` and are used as `fill-run-*` / `fill="var(--color-run-*)"`.
- **F15. Reuse** `Chips.jsx`, `Segmented`, `DataTools`, `ErrorBanner`.
- **F16. Inbound links.** `SchedulerJobPanel.jsx` (~39, "All in Runs") and `platform.md` (~94) are updated to
  `?view=day&filter=…`.

## 8. Review corrections (backend review, 2026-11-02) — these override §3–§4 where they differ

- **B1. Link at insert, not by a later update (replaces DD-3's write path).** `job_run.pipeline_run_id` is mapped
  `insertable = true, updatable = false`. Add a `JobRunService.startRun(..., Long pipelineRunId)` overload.
  `BriefingService.refreshBriefing()` returns void and creates its run inside the private `doRefreshBriefing` (~604)
  behind a lock, so add `refreshBriefing(Long pipelineRunId)` (the no-arg form delegates with null) and pass the id down;
  `PipelineOrchestrator`'s BRIEFING phase (~758–773) calls it with the cycle id. `AskReadyPrecompute.runExclusively`
  already holds `pipelineRunId` where it starts the run (~257). No `@Modifying` link, no best-effort path. A cycle
  resumed at BRIEFING can have more than one BRIEFING run: the Day view folds to the **latest** linked one.
- **B2. History fallback (replaces DD-3's).** `ask_ready_answer` is an upsert cache (V165 unique key), not history, so
  it is **not** used. For cycles before V214: ASK_READY = a non-manual ASK_READY run starting within 2 minutes after
  the cycle's `completed_at`; BRIEFING = a BRIEFING run starting inside the cycle's BRIEFING phase window. No match →
  "Until answers ready" is null for that cycle and it is left out of that measure. `AstroConditionsRefreshJob` starts
  its own ASK_READY (NIGHT_ONLY, 15:30): such runs are top-level rows typed ASK_READY, subline "After Astro
  conditions".
- **B3. FX is read-only in a request.** Never call `ExchangeRateService.getRate` from these endpoints (it fetches
  Frankfurter and writes on a miss). Load the `exchange_rate` rows covering the range once, use the nearest earlier
  date, then `JobRunRepository.findLatestExchangeRates`, then the service's 0.79 constant. GBP is converted **run by
  run** (each run's own rate first) before any sum.
- **B4. Stats populations (replaces DD-4's "non-manual").** `pipeline_run` has no manual flag and BRIEFING always
  records `triggeredManually=false`. Cycles: last 30 COMPLETED or DEGRADED of that `cycle_type`. BRIEFING: last 30
  cycle-linked (or B2-matched) BRIEFING runs. ASK_READY: last 30 non-manual finished. OTHER: stats per `(RunType,
  evaluationModel)`, never across the mixed bucket. Timing (D3) excludes manual runs only where a flag exists, and says
  so in the response (`manualExcluded: bool`).
- **B5. No N+1.** `slow` and the timing series load, per type, one projection of the last 30+k finished runs and slide
  the 30-run window in memory. Day, Calendar and Cost share one package-level loader (move `AdminRunsService.topLevel`'s
  top-level rule and the notes fallback, ~262–318, into it) that loads a small projection (id, run type, model,
  started/completed, cost, rate, manual, cycle link, locations) with set queries. **No `SCAN_CAP` truncation** on these
  paths. Calendar never calls the day builder per date.
- **B6. Cost source.** All spend is `job_run.total_cost_micro_dollars` (cycle-linked runs folded into their cycle) plus
  `sky_rating_eval_run.cost_micro_dollars`. **Never sum `api_call_log`** for cost (one row per batch request, no
  retention); avoid `ApiCallLogRepository.findCostRowsSince`. DD-6's calls aggregate is by indexed `job_run_id` with a
  SQL `GROUP BY service, is_batch`; the commonest error message is a second grouped query, not entity loading.
- **B7. Unknown is not zero.** A run with a null total (a running batch can stay open up to 26 h) serves
  `costMicroDollars: null`, `costComplete: false`; a day or cycle containing one serves `costComplete: false` and the UI
  marks it ("so far"). Sky eval's primitive `long` cost is treated as unknown while its status is not finished.
- **B8. Every RunType is mapped.** `RunDeepType.of` lists all of RunType, including the never-written
  BRIEFING_BEST_BET, BRIEFING_GLOSS, AURORA_GLOSS, BLUEBELL_GLOSS, BATCH_NEAR_TERM, BATCH_FAR_TERM (→ OTHER). A BRIEFING
  with no cycle link (an admin build) is a top-level BRIEFING row.
- **B9. Scheduled fires.** Step `DynamicSchedulerService`'s existing next-fire logic (`calculateNextFireTime` / the
  trigger's `nextAfter`, which applies the stored-cron fallback when `fireFor` is unusable) from now until the UK
  day's end; a null `time_zone` is UTC, as there. `astro_conditions_refresh` fires chain an ASK_READY child too.
- **B10. DST.** Serve `dayStartUtc` / `dayEndUtc` (25 Oct 2026 is a 25-hour day); the strip's x axis spans them.
  Calendar day bounds are `ukDayStartUtc(d)` → `ukDayStartUtc(d+1)`.
- **B11. The Afternoon trigger may be CRON** (`switchScheduleType`). D3's `trigger` is nullable and the planner hides
  when it is null (the card then says the refresh runs at a fixed time).
- **B12. Small ones.** Sky eval is queried by its indexed `run_timestamp`. `earliestDay` is the minimum over
  `job_run`, `pipeline_run` and `sky_rating_eval_run`. DD-6 reads "excluding its BRIEFING and ASK_READY children".
  New public types carry Javadoc; every "now" comes from the injected `Clock`.
