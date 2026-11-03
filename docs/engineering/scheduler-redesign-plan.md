# Scheduler redesign (Operations → Runs → Scheduler)

Status: **plan, reviewed once (3 lenses), revised** (2026-11-02). Source: the owner's design handoff
(`PhotoCast Scheduler Screens.zip` → `design_handoff_scheduler/`: README, three prototype files, seven screenshots).
The handoff is a high-fidelity HTML/JS reference, **not** production code; its run outcomes, durations and costs
are synthetic. This plan rebuilds it in the React + Tailwind codebase on real data.

## 1. What the owner asked for

1. **A picture of the day**: when every job fires, how long it takes, how the jobs connect ("Day's jobs" tab).
2. **Plain-English descriptions** for every job ("All jobs" tab and the job panel).
3. **The outcome of every run, day by day** (succeeded / partial / failed, a reason, a route into Runs).
4. Fix "Daily at Invalid Date GMT" for hourly crons.

## 2. What exists today

- Frontend: `SchedulerView.jsx` on the Data list-and-panel shell (`DataSplitLayout`, `SchedulerListPane`,
  `SchedulerJobPanel`, `JobSchedule`, `JobNextFires`, `JobActions`, `useSchedulerActions`), hash
  `#manage/scheduler[/{jobKey}]` (`useHashSelection`; `SCHEDULER_HREF` in `utils/runsTiming.js` links here), a 30 s
  `useVisiblePoll`, helpers in `utils/schedulerView.js`. Real clock throughout (R-D7), never `appNow()`.
- Backend: `SchedulerController` (`/api/admin/scheduler/*`), `DynamicSchedulerService`. Every fire (scheduled,
  `triggerNow`, `catchUpIfMissed`) goes through `wrapTarget`, which today does `findByJobKey` + whole-entity
  `save` twice per fire and records no outcome; an exception propagates and is only logged. `firesBetween` steps
  cron and solar schedules (empty for FIXED_DELAY and for paused jobs; capped at 100). Only 5 jobs have attributable
  runs (`SchedulerRunsService`: three cycles, `tide_refresh`, `aurora_batch_evaluation`); `sky_rating_eval` is
  attributable through `trigger_source=SCHEDULED`.
- 27 jobs (30 seeded, 3 retired). Tailwind 4.3 (native `@container`). `DataGrid` already gives container-query
  columns at 820/540.

### Review findings that change the plan

- **Existing lost-update race** in `wrapTarget` (`DynamicSchedulerService.java:794-809`): the detached entity's
  whole-row `save` in the `finally` can revert a Pause or schedule edit committed during the fire (the DB row
  says ACTIVE again; the job resumes on restart). Fixed in S1 with scoped updates.
- The three **forecast cycles return from the scheduler thread after the submit phase**; the wait-and-brief tail
  runs on `backgroundExecutor` (`PipelineOrchestrator.java:518-539`), and `runCycle` swallows failures
  (`:525-536`). Fire `completed_at` alone is meaningless for them → link to `pipeline_run`.
- Many fires deliberately do nothing (concurrency guards, flags off) → a SKIPPED status.
- `describeCron` also mis-renders `*/5` / list minutes as `:00` and `MON-FRI` as a day name.

## 3. Decisions

### Data

- **D1: a fire log, written by the scheduler wrapper.** Table `scheduler_fire_log`:
  `id, job_key, fired_at, completed_at, status, summary VARCHAR(200), trigger VARCHAR(12), pipeline_run_id NULL`.
  - `status`: `RUNNING | SUCCEEDED | PARTIAL | FAILED | SKIPPED` (stored as strings; if mapped `@Enumerated`, add
    the `LocalH2EnumWidener` line). `MISSED` is **served only**, never stored (D6).
  - `trigger`: `SCHEDULED | MANUAL | CATCH_UP` (catch-up is currently passed as `manual=false`, `:362`).
  - Indexes: `(job_key, fired_at DESC)` and `(fired_at)`.
  - Writes per fire: one insert (RUNNING) at start, one scoped `UPDATE … SET completed_at, status, summary,
    pipeline_run_id WHERE id = ?` at the end. Log-entity columns other than those four are `updatable = false`.
  - An uncaught exception → FAILED with a sanitised (`LogSanitizer`), truncated reason, then **re-thrown**
    (behaviour unchanged). A logging failure never stops or fails a job (catch, WARN, carry on).
  - `wrapTarget`'s two config saves become scoped `@Modifying` updates (`setLastFireTime(jobKey, t)`,
    `setLastCompletionTime(jobKey, t)`): fixes the lost-update race. Net per fire: 1 insert + 3 updates, 0 selects.
  - **Orphans**: on startup, before `initSchedules`, `RUNNING` rows with `fired_at < now` become FAILED,
    "Interrupted by a restart".
  - Admin routes that run job bodies off-scheduler (`startTideRefresh`, the `@Async` cloud backfill) are **not**
    logged; documented as such.
- **D2: jobs report their outcome through a scoped reporter**, not a signature change.
  `SchedulerFireOutcome.report(status, summary)` and `SchedulerFireOutcome.linkPipelineRun(id)`, bound by
  `wrapTarget` with a JDK 25 `ScopedValue` holding a mutable per-fire carrier (cannot leak across pooled
  threads). Last report wins. A call with no fire bound is a no-op logged at DEBUG. Default with no report:
  SUCCEEDED, no summary. Work on other threads (the cycle tail, the astro Ask precompute) cannot report — by
  design; the cycles use the `pipeline_run` link instead.
- **D3: retention 14 days**, by a new daily job `scheduler_fire_log_cleanup` (03:57 UTC), seeded in the same
  migration as the table (precedent: `AskLogCleanupJob`, V167). One `DELETE … WHERE fired_at < ?` (≈4k rows/day,
  ≈53k retained). That makes **28 jobs**; every count on screen is served, never hard-coded.
- **D4: plain-English copy in a backend static catalogue** (`SchedulerJobCatalogue`, keyed by job_key): alias,
  what, why, purpose tag, external services, `usesClaude`, and dependency edges (collects-results-from,
  watches, backstop, stagger, follows). The required config key is **not** duplicated: it is the served
  `configSource` column. A row without an entry serves `catalogue: null` (prod rows are hand-editable), never a
  500. CI Testcontainers test: every seeded row has an entry and no entry is orphaned; cycle and astro edges
  agree with `ForecastCycleJobs` / `ScheduledFiresLister`. Copy is taken as-is from `prototype/scheduler-jobs.js`
  (owner, 2026-11-02: no PR review; they check it on the prod build). The one edit: any `why` that hard-codes a
  clock time is reworded to name the rule, since an edited schedule would make it stale.
  The stored `description` stays as the "Technical" note.
- **D5: UK civil time, labelled per instant.** The label "GMT"/"BST" comes from the instant, so clock-change days
  label each half correctly. The day axis is the UK civil day built from real instants (23 or 25 hours). The
  02:00–05:00 close-up is defined in instants. `calculateNextFireTime`'s FIXED_DELAY branch switches from
  `Instant.now()` to the injected `clock` (`:524`).
- **D6: `GET /api/admin/scheduler/day?date=YYYY-MM-DD`** (UK date; 400 outside today ± 7). Serves:
  - `date`, `isToday`, `now` (real clock; `/api/admin` is never rewound), sunrise/sunset for the solar preview's
    reference point, axis start/end instants.
  - Per scheduled job: fires. Past from the log (status, summary, start, end, trigger; for linked cycle fires,
    end and status from `pipeline_run.completed_at` / `status` / `failure_reason`). Future from `firesBetween`
    (`SCHEDULED`), not listed for paused jobs. A per-fire `fallback` flag when a solar fire used its fallback cron.
  - **MISSED**: expected past fires (`firesBetween(dayStart, min(now − 2 min, dayEnd))`) with no log row within a
    tolerance. Suppressed before a new `schedule_changed_at` column (set by schedule edit, type switch, pause,
    resume), because there is no schedule history.
  - **Repeating jobs** (FIXED_DELAY, and crons firing > 6 times a day): compressed shape. `expectedCount` from the
    schedule (not `firesBetween`, which is empty for FIXED_DELAY and capped for crons), counts by status from one
    GROUP BY, and only the non-SUCCEEDED fires individually. Day-level tally "n failed runs across m jobs".
  - **Watchdog windows**, from a new public `ForecastCycleWatchdog.deadlines(job, date)` (no copied constants):
    for NIGHTLY and INTRADAY cycles, `{event, opensAt (event − 20 min), closesAt (event + 60 min)}` whatever their
    schedule type; for the evening job, `startGrace` and `budgetMinutes` instead.
  - `notOnThisDay`: weekly jobs that do not fire that date.
- **D7: job detail split by cost.**
  - `/jobs` (polled 30 s) gains only cheap fields: `catalogue`, `lastFire` (newest log row), `nextFires` (next 3).
    The existing `lastRun` stays for "Open in Runs →".
  - New `GET /jobs/summary`: counts by status, type, usesClaude, last-fire-failed; `nextScheduled {jobKey, at}`.
    (A separate endpoint, so `/jobs` keeps its list shape.)
  - New `GET /jobs/{key}/detail` (panel only): `typicalDurationSeconds` (for the five attributable jobs and the
    cycles, the existing run-based medians — `SolarSchedulePreviewService` / `RunStats` — so the app shows one
    "typical"; for others the fire log, min 3 samples, null below, so weekly jobs are null), and
    `claudeCost30d {gbp, complete, runs, includes, inclManual}` computed from `RunsLoader` DeepRuns (which already
    convert with `RunFx` and set `costComplete`). Cycles: member batches **plus** chained BRIEFING and ASK_READY
    children, stated in `includes`. Aurora batch via `SchedulerRunsService`'s AURORA mapping. Sky eval via
    `trigger_source=SCHEDULED`. `astro_conditions_refresh` and `aurora_polling`: `null` with a reason. Manual
    cycle runs cannot be told apart in `pipeline_run`, so `inclManual: true` for cycles. Plus `perRun`.
- **D8: schedule preview is served.** `POST /api/admin/scheduler/jobs/{key}/schedule-preview` (dry run, no save,
  ADMIN): takes a proposed schedule, validates through `prepare()`, returns next 3 fires, the year min/max for
  solar, neighbours within 30 min (other ACTIVE jobs over the next 24 h), and the resulting cron and time zone.
  The client never converts UK times to cron. `PUT /schedule` gains an optional `timeZone` (validated as
  `cronZone` is; written by a scoped update because `time_zone` is `updatable = false`). The cron editor writes
  `Europe/London` and accepts times sharing one minute value only (mixed minutes → a clear message).

### Screen

- **D9: tab and date live in the query, not the path.** `#manage/scheduler[/{jobKey}]?view=day|all&date=YYYY-MM-DD`
  following `utils/runsQuery.js`; `parseSchedulerHash` unchanged, `keepQuery: true`, `replaceState` for tab/date,
  `date` only when not today. Old `#manage/scheduler/{jobKey}` links keep working. Hash wins over the
  `localStorage` tab (try/catch); invalid or out-of-range `date` is replaced with today client-side.
- **D10: drawer.** An opt-in `mode="overlay"` prop on `DataSplitLayout` (additive; default and the other seven
  users unchanged, with a test) makes the Scheduler's panel a 520 px drawer with a scrim at every desktop width,
  so the list/picture keeps full width. Phone keeps the existing full-page panel.
- **D11: All jobs on `DataGrid`**, accepting its 820/540 house breakpoints instead of the design's 760/480
  (recorded deviation). Grouped by schedule type with subtitles; sorted by next fire within a group.
- **D12: the day picture** is React SVG under `components/scheduler/day/`; geometry (time → x, 6 px diamond and
  3 px tick/band thresholds, arrow paths, night shading) in pure `utils/schedulerDay.js`. It sizes from its
  container (`ResizeObserver`, tested minimum). Colours from `--color-plex-*` plus two new tokens (sun-relative
  `oklch(.8 .13 75)`, repeating ink-50%).
  - Accessibility: each row label is `role="button"`, `tabIndex=0`, Enter/Space opens the panel, `aria-label` with
    name, status, time, duration, alias; tooltips on focus as well as hover, not positioned via `getBBox`;
    decorative marks `aria-hidden`; repeating failures are reachable by keyboard through "Needs a look". Arrows
    chip has `aria-pressed`; Whole day / close-up uses `data/Segmented.jsx`.
  - testids: `scheduler-day-now|sunrise|sunset`, each fire, each arrow, `scheduler-row-{jobKey}` (focus return),
    distinct ids for "Needs a look" rows. Only the active tab is mounted.
  - Phone (`useIsMobile`): picture in a horizontal scroller (min ≈ 720 px, sticky label column), wrapped controls
    and legend. Phone test plus a desktop-unchanged test.
- **D13: clock.** "Today", nav bounds and the Now line come from the served `date`/`isToday`/`now`, advanced by
  `new Date()` between polls (fallback `ukDateStr(new Date())`). Poll `/day` every 30 s only when the date is
  today, with the hidden-pane skip; handle midnight rollover. Never `appNow()`; a Rewind test proves it.
- **D14: links into Runs** via a new `runsDayHref(date)` (and a timing href) in `runsQuery.js`, as plain hrefs
  (`writeRunsQuery` rewrites the *current* path). Labels "today" / "yesterday" / "Fri 30 Oct"; hidden for future
  days. Includes "Choose the offset from real run times (Runs → Timing)".
- **D15: role gating.** ADMIN only; every new endpoint `hasRole('ADMIN')` and listed in `api-endpoints.md`. Not
  personal, not ETag-shared.
- **D16: actions** reuse `JobActions` / `useSchedulerActions` and their existing inline copy; the prototype's
  "X paused." toasts are not adopted. The save toast "Saved. Next fire {Tomorrow 12:27}." reuses an existing toast
  pattern (e.g. `SubRegionToast`), reading the reloaded job's next fire.

### Design items to carry explicitly

Page meta line (`N jobs · UK time (GMT) · n paused · next scheduled: X at HH:MM`), tab sub-labels; per fire a
status dot and time, future at 30 % (35 % repeating), dashed outline for paused, gold £ after Claude jobs;
gridlines 3 h (30 min close-up); arrow kinds (solid "results collected by", dashed stagger), selected arrows gold,
unrelated rows 25 %; tooltip fields (name, status, time, duration, ~£/run, note, alias; watchdog "Watchdog window
HH:MM–HH:MM / {event}"); "Run detail →" in Needs a look (≤ 8, "and n more"); filters Off in config, Uses Claude,
Last run failed; panel copy "Individual runs of this job aren't listed in Runs yet.", "None. This job doesn't call
Claude" (+ "; batch costs are counted against the job that submitted them" for pollers), "Nothing outside
PhotoCast. Database only."

**Recorded deviations (S5b):** the tooltip omits the design's "~£ per run": that figure exists only in
`/jobs/{key}/detail` (the panel), not in the `/jobs` and `/day` data the picture holds, and loading it per mark
would add a request per hover for a number the panel already shows. The repeating band is one band at about 45 %
(not split at Now), as in the prototype.


## 4. Phases (one PR each; each branched from updated `main` after the previous merged)

| Phase | Scope | Side |
|---|---|---|
| **S0** | `describeCron`: `hour === '*'` with one numeric minute → "Every hour at :20"; any other non-numeric minute/hour or a non-single day-of-week → the raw cron. Tests incl. `0 0 2 * * MON`, `*/2`, ranges, minute lists. | frontend |
| **S1** | V215: `scheduler_fire_log` + indexes, `schedule_changed_at` on `scheduler_job_config`, cleanup job seed. Entity, repository, scoped updates (incl. replacing `wrapTarget`'s saves), `SchedulerFireOutcome` (`ScopedValue`), trigger kinds, orphan sweep at startup, cleanup job. Testcontainers migration test; unit tests (success, exception → FAILED + rethrow, PARTIAL/SKIPPED reports, logging failure harmless, trigger kinds, 200-char sanitised summary, orphan sweep, the Pause-during-fire race no longer reverts). Reference docs updated. | backend |
| **S2** | Every job reports outcome + one-line summary per the review's job table; guards → SKIPPED; quiet failures → FAILED/PARTIAL; cycles `linkPipelineRun` and report FAILED/SKIPPED for their swallowed paths. A test per job, and one end-to-end through the real wrapper per threading shape (sync, submit-then-async, manual-aware). | backend |
| **S3a** | `SchedulerJobCatalogue` + CI test; `/jobs` cheap fields; `/jobs/summary`; `/jobs/{key}/detail` (durations, cost). | backend |
| **S3b** | `/day` (MISSED, compressed repeaters, watchdog windows, fallback flag); `ForecastCycleWatchdog.deadlines`; `schedule-preview`; `PUT /schedule` `timeZone`; clock fix in `calculateNextFireTime`. | backend |
| **S4a** | Tabs + query hash (D9), `DataSplitLayout` overlay mode (D10), header meta line, **All jobs** on `DataGrid` (D11), the read-only job panel sections. Rewrites `SchedulerView.test.jsx`, `SchedulerViewSolar*.test.jsx`, `schedulerView.test.js`; retires `SchedulerListPane`. | frontend |
| **S4b** | The three editors via `schedule-preview` (slider debounced), neighbour warning, Runs → Timing link, save toast. | frontend |
| **S5a** | `schedulerDay.js` geometry; day nav, Whole day; summary bar; legend; scheduled rows; sunrise/sunset/now lines; night shading; Not on this day; Needs a look; phone scroller; clock rules (D13). | frontend |
| **S5b** | Repeating section, watchdog windows, dependency arrows + chip, selection dimming, tooltips, close-up. | frontend |

S0 is independent and can run alongside S1. S4a/S5a must not merge before S2 has merged, or the screen paints
green for jobs that quietly fail. Each phase: Sonnet builds in its own worktree to the gates (backend ladder to
`clean verify`; frontend lint → test → audit → build), a `changelog.d/` entry, conventional commit; the planner
reviews the diff; UI phases get the adversarial review and a browser check on local backend 8083 with fire-log
rows seeded by SQL (no forecast runs, no Claude spend).

## 5. Risks

- Missed-fire detection is only as good as "schedule unchanged since `schedule_changed_at`".
- Cycle cost includes manual runs (no flag in `pipeline_run`); labelled.
- `ScopedValue` must be final (not preview) in JDK 25 for the build; if not, fall back to a `ThreadLocal` set and
  cleared in `try/finally` (safe: the scheduler pool is platform threads).
