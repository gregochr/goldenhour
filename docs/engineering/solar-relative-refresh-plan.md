# Plan: run the two forecast cycles relative to the sun, with one safety net

**Status: v3, 2026-10-13, not started. Every owner question is answered (§2).** v1 and v2 were
written and reviewed on 2026-10-05; v2 merged unchanged as #1127. v3 follows a second three-lens
code-fit review on 2026-10-13 (§10). That review found that v2's P1 could itself stop every scheduled
job, that four timing assumptions were missing from v2's list, and that v2's Part B could not work.
v3 is written to be handed to implementing agents one phase at a time: each phase in §6 names its
files, the behaviour each case must have, its tests, its acceptance checks and its stop-points.

**These cycles are the product.** A day with no forecast is the worst outcome this work can produce,
so every phase is held to one rule above all others: **no change may leave a forecast job
unscheduled, and no failure in the new code may stop the old code from running.** Where a phase must
choose between a solar fire time and *some* fire time, it chooses some fire time.

## 1. Why

The two scheduled cycles fire at fixed UTC times: `near_term_batch_evaluation` at 01:00 and
`intraday_forecast_refresh` at 14:00. The events they serve move by about five hours over the year
(computed at 54.9N 1.9W for 2026, cross-checked against `forecast_evaluation.solar_event_time`, and
re-computed independently in review at the reference point, §2 D5):

| | Event (UK local) | Lead after today's fixed run |
|---|---|---|
| Sunset, midsummer | ~21:49 | ~6.7 h after 15:00 BST |
| Sunset, early October | ~18:30 | ~3.5 h |
| Sunset, midwinter | ~15:40 | ~1 h 40 min |
| Sunrise, midsummer | ~04:28 | ~2.5 h after 02:00 BST |
| Sunrise, midwinter | ~08:32 (latest, 29 Dec) | ~7.5 h |

A long lead is a stale forecast; a short one lets a slow batch miss the event. Production, last 30
days at v2's writing, 49 cycles with batches: the wait from first submit to the last batch ending is
normally under 20 minutes, but 5 cycles took 32, 45, 60, 60 and 181 minutes. The delay is on
Anthropic's side (`ended_at` is stamped by the 60-second poller). On 30 September results landed
about 40 minutes before sunset.

## 2. Owner decisions

Taken 2026-10-05 (v2):

1. Both cycles run at an offset from the sun, all year. No cap and no seasonal switch.
2. Morning cycle: **sunrise − 255 minutes**, sized so the latest sunrise of the year still finishes
   by 05:00 UK; the owner checks the app first thing. Accepted: the midsummer run fires ~00:13.
3. Afternoon cycle: **sunset − 240 minutes**. Accepted: the midsummer afternoon refresh fires
   ~17:50 instead of 15:00.
4. An admin screen that documents all of this (§5).

Taken 2026-10-13 (v3), each answering a review finding:

- **D1 — A missed firing fires late, inside a window.** If a trigger passes while the app is down,
  on startup the cycle fires once immediately provided useful lead remains: the morning cycle if it
  is before **05:00 UK**, the afternoon cycle if it is at least **90 minutes before sunset**. Otherwise
  it waits for the next trigger. (v2 said "never fire late", which turns a restart just after the
  trigger into a day with no forecast.)
- **D2 — The T+1 cache cap drops from 8 h to 6 h.** The shortest new gap (midwinter morning →
  afternoon, ~7.4 h, less ~10 min of morning batch latency) is under 8 h, and the cache check runs
  before eligibility, so from late November to late January the afternoon would skip every SETTLED
  and TRANSITIONAL T+1 region as cached. 6 h leaves about an hour's margin all year.
- **D3 — WAIT and RETRY_FAILED share one budget of 3 h 30 min measured from the cycle's first batch
  submit.** Today each waits up to 4 h with a fresh deadline, so one cycle can stay RUNNING for 8 h+,
  longer than the new minimum gaps (6.4 h summer afternoon → morning, 7.4 h winter morning →
  afternoon). One 3 h 30 min budget means two forecast cycles can never be RUNNING at once.
- **D4 — Part B is replaced by a late-completion briefing rebuild and a watchdog** (§4). v2's
  direct-scoring fallback is dropped: the late batch answer would overwrite it in all three rating
  stores (§10). The sync-scoring alternative (score tonight's sunset synchronously at submit) is
  recorded as a possible later step, not planned.
- **D5 — The reference point is Durham, 54.776 N, −1.575 E**, the point `AuroraPollingJob`,
  `BriefingAuroraSummaryBuilder` and `AstroConditionsService` already use. It is configurable
  (`photocast.scheduler.solar-reference.lat|lon`) with Durham as the default, so a missing property
  can never prevent scheduling.

v2's open questions, closed by the review rather than by a decision:

- *v2 Q2 (does the afternoon still skip slots the next morning "guarantees")* — the rule
  (`IntradayEligibilityPolicy`: skip SETTLED only for T+1 **sunset**) still holds under the new
  times, because the next morning includes T+0 at every stability and the next afternoon includes
  T+0 sunset. Only its written rationale ("the 01:00 run, which nobody is awake to read") needs
  rewording. No behaviour change.
- *v2 Q4 (may the 03:30 aurora batch and 03:45/03:50 cleanups overlap the morning cycle)* — yes.
  The aurora batch is PAUSED, and the cleanups only delete rows by age (slot_atmosphere 180 d,
  forecast prompts 30 d, ask log 90 d, dispositions 30 d), none inside a cycle's working set. The
  only cost is database contention; the 5-thread scheduler pool has room (§3 A6).
- *v2 Q1 (order)* — scheduling first, with the scheduler hardened before anything else (§6).

## 3. Part A — sun-relative scheduling

### A1 — The scheduler must be made safe before it learns a new type

Review found the scheduler fragile in ways that turn any new schedule type into an outage. All
citations are `backend/src/main/java/com/gregochr/goldenhour/service/DynamicSchedulerService.java`
unless stated.

- `initSchedules()` (`:122-133`) loads every row in one `findAllByOrderByIdAsc()` and schedules them
  in a loop with **no per-row isolation**. One row that throws stops every row after it.
  `near_term_batch_evaluation` (V73) has a low id, so most jobs come after it.
- `schedule_type` is `@Enumerated(STRING)` (`SchedulerJobConfigEntity.java:52-54`). An unknown value
  throws inside Hibernate's load, so **one row with a value this JAR does not know fails the whole
  load**: `DynamicSchedulerBootstrap.onApplicationReady` throws, which very probably stops the app,
  and `GET /api/admin/scheduler/jobs` returns 500. This is also the rollback hazard: once any row
  says `SOLAR_OFFSET`, every older JAR fails the same way.
- `scheduleJob` (`:299-305`) is `if CRON … else` and treats **everything else as FIXED_DELAY**:
  `Duration.ofMillis(config.getFixedDelayMs())` NPEs on a null delay.
- `updateSchedule` (`:169-172`) cancels the job **before** rescheduling, so a reschedule that throws
  leaves the job off until the next restart. `updateSchedule` can also not change a job's type.
- `SchedulerController.toDto` builds with `Map.ofEntries`, which rejects nulls, so a new nullable
  column needs a default in the DTO.
- `SchedulerView.jsx` (`:282, 294-296, 371, 380-386`) treats every non-CRON type as FIXED_DELAY: a
  `SOLAR_OFFSET` row shows an empty schedule, and Save sends `{fixedDelayMs: 0}` — straight into the
  NPE above.
- `new CronTrigger(expr)` (`:300`) uses the **JVM default zone**, which is UTC in production only
  because nothing sets `TZ`; `calculateNextFireTime` (`:259-262`) assumes UTC regardless. On a
  London-zoned Mac the two disagree today.
- Missed firings are never caught up: a `CronTrigger` computes from startup.

P1 (§6) fixes every one of these before any new type exists.

### A2 — The trigger

`SolarOffsetTrigger implements org.springframework.scheduling.Trigger`, passed to the existing
`taskScheduler.schedule(Runnable, Trigger)` at `:300`. No custom Trigger exists in the codebase
today. Its contract, each line a test:

1. **Instants, not wall-clock times.** For UK civil date D: `fire(D) = eventInstant(D) − offset`,
   where `eventInstant` is `SolarCalculator.sunrise|sunset(refLat, refLon, D, ZoneOffset.UTC)`
   converted to an `Instant`. Computing in instants makes DST irrelevant: no nonexistent or doubled
   local hour can arise. (v2 said "computed in local civil time", which invites exactly that bug.)
2. **Never before UK midnight of D.** `fire(D) = max(fire(D), D at 00:05 Europe/London)`. The batch
   path has no instant-level past-event gate, only a date gate (`BriefingCandidateCollector.java:132`),
   and "today" is the UK civil date (`ForecastHorizon.today`). A morning cycle firing at 23:59 on D−1
   would treat D−1 as today and evaluate events that have already happened, and would shift the
   briefing window (`BriefingService.java:476-478`), the intraday baseline
   (`PipelineRunComparisonService.java:77-81`) and the Ask Ready day count (`AskReadyPrecompute.java:236`).
   At Durham the margin is 13 minutes (18 June, 00:13). The clamp is what keeps it safe.
3. **Never null.** A Spring `Trigger` that returns null stops its job for good, silently. If the
   solar calculation fails for any reason (exception, NaN, the midnight sentinel — `SolarCalculator`
   returns midnight-UTC-of-the-date when an event does not occur), the trigger returns the job's
   stored **cron** fire time instead and logs WARN. A solar job always keeps its cron expression
   (A3), so there is always a fallback.
4. **Strictly after the last scheduled execution.** `nextExecution` returns the first `fire(D)` for
   D = today, today+1, … that is strictly after both `now` and `lastScheduledExecution()` (if any).
   This prevents a double fire when the clamp, the cron fallback or a clock adjustment makes two
   candidates coincide.
5. **Sanity bounds.** A computed morning fire must fall in [00:05, 06:00] UK and an afternoon fire in
   [10:00, 20:00] UK; outside that, fall back to cron (WARN). At Durham the real ranges are
   00:13–04:15 and 11:39–17:48, so the bounds only ever catch a bug.
6. **Logs.** Each computed fire logs one INFO line: job, event, event instant, offset, fire instant,
   and whether it was clamped or fell back.

### A3 — Schema and switching

- `ScheduleType` gains `SOLAR_OFFSET`. Two nullable columns on `scheduler_job_config`:
  `solar_event VARCHAR(10)` (`SUNRISE`/`SUNSET`) and `solar_offset_minutes INTEGER` (minutes
  *before* the event, 0–720, check constraint). One migration, next free number (read it from the
  tree on main; at v3's writing the latest is V170). **The migration adds columns only. It does not
  touch any row** — production's `near_term_batch_evaluation` cron (01:00) was hand-edited and
  differs from its seed (V73 seeds `0 0 3,15 * * *` PAUSED), so no migration may assume its value.
- A solar row **keeps its cron expression**: it is the trigger's fallback (A2.3) and the revert target.
- Switching type is its own endpoint, `PUT /api/admin/scheduler/jobs/{jobKey}/schedule-type`
  (ADMIN), body `{scheduleType, solarEvent?, solarOffsetMinutes?}`. Validated before anything is
  cancelled (a SOLAR_OFFSET body needs both solar fields; a CRON switch needs a stored, valid cron).
  Only the two forecast cycle jobs may be switched to SOLAR_OFFSET (an allow-list); any other job
  key is 400.
- The switch is how a phase is rolled back: P3 and P4 are reverted by one call, no deploy.

### A4 — Timing assumptions that change, and what each phase does about them

| Assumption | Where | What changes | Handled in |
|---|---|---|---|
| T+1 cache cap 8 h "survives every cycle boundary" | `FreshnessProperties.java:87-94`, `application-example.yml:334-341` | winter gap ~7.4 h, so T+1 skipped as cached | P3 (6 h, D2) |
| WAIT and RETRY each get a fresh 4 h | `PipelineOrchestrator.java:1010`, `:917` | cycles could overlap | P2b (D3) |
| Afternoon offset (240 min) equals the 4 h timeout | `PipelineOrchestrator.java:111` | a timed-out afternoon run fails at sunset | P2b (3 h 30 min budget, from first submit) |
| `IntradayEligibilityPolicy` rationale ("01:00, nobody awake", "tomorrow's 14:00") | `:24, 43, 84-85` | text only | P3 |
| Stability snapshot stale after exactly 24 h | `StabilitySnapshotProvider.java:67` (`SNAPSHOT_STALENESS_HOURS = 24`) | morning→morning drifts ±2–3 min/day; after a restart a snapshot 24 h 3 min old reads as missing, every region is treated UNSETTLED (4 h threshold, more spend) | P4 (raise to 30 h) |
| Batch summary dates its horizon by the **UTC** date of `job_run.started_at` | `BatchSummaryDeriver.java:89-95` | midsummer morning runs (23:13 UTC) show T+1..T+4 in admin views | P4 (use `ForecastHorizon` UK date) |
| `briefing_model_comparison` at 05:00 UTC reads the briefing the nightly built | V79, `BriefingModelTestService.java:100` | winter morning trigger 04:17 UK leaves ~43 min | P4 (move the job to 06:30 UTC) |
| Astro refresh at 15:30 UTC runs "after the 14:00 intraday" | `AstroConditionsRefreshJob.java:69-71`, V170 | summer intraday (~16:50 UTC) runs after it; its NIGHT_SKY precompute becomes redundant, harmless | P3 (comment only) |
| "> 12 h since last run = recovered after restart" log label | `BriefingCandidateCollector.java:291-293` | mislabels the summer intraday | P3 (comment/label) |
| Ask Ready `BEST_NEXT` text is fixed at precompute | `ReadyQuestion.java:20-23, 318-324` | a summer 00:20 precompute says "this morning", so it is withheld from ~04:30 until the evening precompute (~19:00–21:00), not ~16:00 as today. Winter improves | accepted, stated in the admin panel's notes |
| Batch cache primer's 1 h lifetime | `BatchCachePrimer` | a summer morning trigger (23:13 UTC) is US afternoon, higher latency; winter afternoon improves | accepted; watch the primer INFO line after P4 |
| Intraday→nightly comparison takes the nightly "between London midnight and the intraday trigger" | `PipelineRunComparisonService.java:72-81` | safe only because of A2.2's clamp | A2.2 |
| Scheduler display assumes UTC, cron uses JVM zone | `DynamicSchedulerService.java:259-262, 300-301` | already inconsistent locally | P1 (pin both to UTC explicitly) |
| Tests pinning 01:00 / 14:00 | `DynamicSchedulerServiceIntegrationTest:60`, `PipelineRunComparisonServiceTest:34-35`, `IntradayCandidateCollectionStrategyTest:26-30`, `FreshnessResolverTest:116`, `IntradayEligibilityPolicyTest:37-60`, `ForecastTaskCollectorHorizonBasisTest:180`, `ForecastHorizonTest:74` | fixtures and display names; none fails on the move itself | the phase that changes the matching code |
| Stale timing text | `PipelineOrchestrator.java:100-111, 356`, `V141` job description, `BriefingService.java:93-96`, `V151` comments, `application-example.yml:305-310` | text only | P3/P4 (Java and YAML text; migration comments are left as history) |

Verified as **not** affected: the eligibility policies' date handling (every pipeline caller uses
`ForecastHorizon.today`, UK date, so a 23:13 UTC fire is correctly day D); the nightly at 04:17
midwinter still evaluates today's 08:32 sunrise as T+0; the force-eval cap (per cycle, T+2/T+3
only); `max-cycles-per-day` for Ask Ready (3 scheduled sources a UK day against a limit of 6; the
23:13 UTC fire counts against D); `LocationFailureService` (trigger order, 7-day window);
`BriefingAuroraSummaryBuilder`'s hour ≥ 6 UTC switch; `topic_daily_log` at 04:40 (reads yesterday).

### A5 — Year at a glance (trigger times, UK local, at Durham)

| | Morning cycle | Afternoon cycle |
|---|---|---|
| Midsummer | ~00:13 (18 Jun, earliest) | ~17:48 (25 Jun, latest) |
| Early October | ~03:00 | ~14:30 |
| Midwinter | ~04:15 (29 Dec, latest) | ~11:39 (14 Dec, earliest) |

Computed by `SolarOffsetTrigger` at the reference point (P2a); §1's figures were taken at 54.9N 1.9W, which puts the two latest triggers about two minutes later (04:17, 17:50). In UTC the morning trigger crosses midnight (23:13 UTC on the previous UTC date in summer); the
afternoon trigger runs 11:39–16:50 UTC. Over 2026 no computed trigger falls in a DST-ambiguous hour,
but A2.1 makes that irrelevant anyway.

### A6 — Fixed-time neighbours

The morning cycle now runs among the 00:00–05:00 UTC jobs (tide refresh Mon 02:00, drive times
02:40, refresh-token cleanup 03:00, region drive times and grid backfill 03:10, disposition cleanup
03:30, slot-atmosphere 03:45, prompt 03:50, ask-log 03:55, topic log 04:40). SUBMIT holds one of the
pool's five threads for minutes; at most four of the others fire in any one minute. Accepted. In
summer the morning cycle runs *before* the tide refresh and grid backfill rather than after; both
have multi-day data windows, so this is harmless.

## 4. Part B — the safety net (replaces v2's direct-scoring fallback)

**What a slow batch costs today, precisely.** Stars, verdicts, the sample gate and confidence are
re-enriched from `cached_evaluation` on every `GET /api/briefing` (`ServedBriefingAssembler`,
`EvaluationViewService.getScoresForEnrichmentBulk`), and `BatchPollingService` keeps processing
every SUBMITTED batch every 60 s whatever the orchestrator is doing. So a late batch's **ratings go
live the moment it lands**. What does not refresh is everything built once per briefing: the gloss
prose, best-bet picks, Ask Ready answers and the almanac — because the only `refreshBriefing()` call
is the orchestrator's (`PipelineOrchestrator.java:638`), and a cycle that timed out never reaches it.

**B1 — Late-completion rebuild.** When `BatchResultProcessor.processResults` finishes a FORECAST
batch whose `pipeline_run` is no longer RUNNING (COMPLETED after an earlier briefing, or FAILED on
the safety budget), and no other FORECAST batch of that run is still outside
`ForecastBatchEntity.BatchStatus.TERMINAL`, it calls `briefingService.refreshBriefingIfIdle()` on
the background executor. Rules:
- Never inside the poller thread's own call stack (the rebuild takes minutes).
- `refreshBriefingIfIdle` already refuses while another build runs; a refused rebuild is logged at
  INFO and not retried (the next cycle rebuilds anyway).
- A batch with no `pipeline_run_id` (ad-hoc, force-submit) triggers nothing.
- Picks: the rebuild does **not** call `persistPicksForCycle` (picks belong to the cycle's own tail).
- It never changes `pipeline_run` state.

**B2 — Watchdog.** A scheduled check every 10 minutes (`forecast_cycle_watchdog`, seeded ACTIVE):
- **Did not start:** a forecast cycle job is ACTIVE, its last computed fire time is more than
  30 minutes ago, and no `pipeline_run` of that cycle type has `trigger_time` at or after it.
- **Did not finish:** a forecast cycle's `pipeline_run` is still RUNNING, or its last batch is still
  non-terminal, 20 minutes before the event it serves (morning: sunrise; afternoon: sunset).
- Each fires `AdminAlertService` once per cycle (latched in memory per run/trigger), naming the job,
  the trigger, the event and what is missing. `notifications.admin-alerts.enabled` and working mail
  are part of the feature, as for Ask's spend cap.
- The watchdog reads only; it never starts, stops or retries anything.

**Not planned (D4):** sync-scoring the afternoon's T+0 sunset at submit time. If B2's alerts show
late sunsets in practice, it is the next step — a slot never batched cannot race a late answer —
and it needs its own design review (cost ~2× batch on a few dozen calls a day; bluebell, retry and
failure-evidence interactions).

## 5. The admin screen

One read-only panel on the Operations tab's Scheduler screen. For each forecast cycle:
- the rule in words ("4 h 15 min before sunrise at Durham, never before 00:05");
- today's and tomorrow's event time, trigger time, and expected finish (trigger + median duration of
  the last 14 cycles of that type);
- the year at a glance (A5, computed by the backend, with the earliest and latest trigger and their
  dates);
- the safety budget (3 h 30 min), the catch-up window (D1), and when the watchdog last alerted;
- the reference point, and the fixed-time jobs that share the morning window (A6);
- the stated accepted consequences (A4's "accepted" rows).

All figures come from one backend endpoint, `GET /api/admin/scheduler/solar-preview` (ADMIN), which
uses the trigger's own calculation so the panel can never disagree with what will fire. The
switch control (A3) lives on the existing job row. UI review cadence applies.

## 6. Phases — each a self-contained brief for one implementing agent

Every phase: a branch from main, the local gate (`./mvnw clean verify --batch-mode
--no-transfer-progress -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false`, exit code, and
for the frontend `npm run lint && npm test && npm audit --audit-level=high && npm run build`), a
`changelog.d/` entry, a PR, CI's Backend job read before merge (migrations are proven only there),
and an adversarial review of the diff before the PR. **No phase changes a production schedule row;
the owner does that through the admin endpoint.** Local dev has no `scheduler_job_config` rows
(Flyway is off locally), so scheduler behaviour is proven by unit tests and the Testcontainers
integration tests, not by clicking.

### P1 — Harden the scheduler (no new type yet)

*Goal:* no single job's configuration can stop another job from being scheduled, and no reschedule
can leave a job unscheduled. Independently valuable; ship and release it alone.

*Change:*
- `initSchedules`: isolate each row — catch per row, log ERROR with the job key, continue.
- `scheduleJob`: an explicit `switch` on `ScheduleType`; an unrecognised or incomplete row
  (null cron for CRON, null/≤0 delay for FIXED_DELAY) logs ERROR and is left unscheduled **alone**.
- `updateSchedule` (and P2's type switch): build and validate the new trigger first, then swap —
  never cancel before the replacement exists. On failure the old schedule stays and the call is 400.
- Make `schedule_type` tolerant of values this JAR does not know: replace `@Enumerated` with an
  `AttributeConverter` that maps an unknown string to a sentinel (`UNKNOWN`) the scheduler skips
  with ERROR, and that writes the original string back unchanged. This is what makes P2+'s new
  value rollback-safe: a P1+ JAR that meets `SOLAR_OFFSET` skips that one job instead of failing.
- Pin zones: `new CronTrigger(expr, ZoneOffset.UTC)` and `calculateNextFireTime` in UTC, so behaviour
  no longer depends on the JVM's zone.
- `LocalH2EnumWidener`: not needed once the column is a converter-mapped string; if an `@Enumerated`
  remains anywhere on this table, add its `Target` line.

*Tests:* one row with a null cron among three valid rows → the other two scheduled; an unknown
`schedule_type` string in the table → load succeeds, that job skipped, the rest scheduled (repository
test with a raw SQL insert); the converter round-trips an unknown string unchanged; `updateSchedule`
with an invalid cron leaves the previous future in place; cron evaluated in UTC with the JVM default
zone set to Europe/London.

*Acceptance:* all existing scheduler tests pass; the new ones fail on main and pass on the branch.

*Stop:* release P1 before P2 merges (the converter must be in production before any row can say
`SOLAR_OFFSET`).

### P2a — The trigger, the schema and the preview (no job uses it)

*Change:* `SOLAR_OFFSET`, the migration (A3), `SolarOffsetTrigger` (A2, every numbered rule), the
switch endpoint with its allow-list and validation, `SchedulerController.toDto` defaults for the new
columns, the reference-point properties defaulting to Durham (D5), and `GET
/api/admin/scheduler/solar-preview`. Catch-up (D1): on scheduler start, for each ACTIVE solar job,
if today's fire time has passed, no `pipeline_run` of that `cycle_type` has `trigger_time` ≥ that fire
time, and the D1 window is still open (morning: now < 05:00 UK; afternoon: now ≤ sunset − 90 min),
schedule one immediate run (INFO log naming why). The check reads `pipeline_run`, so it survives
restarts and never double-fires.

*Tests (unit, fixed `Clock`):* every A2 rule — instants across both DST changes; the 00:05 clamp
(a reference point far enough north that sunrise − 255 lands before midnight); null-free fallback
on a thrown calculator, on NaN and on the midnight sentinel; strictly-after-last-execution; sanity
bounds; a year's sweep at Durham asserting every fire lies in A5's ranges. Catch-up: inside the
window fires once, outside does not, a cycle already run does not. Endpoint: allow-list, validation
before cancel, CRON ↔ SOLAR_OFFSET round trip keeps the cron. Migration: Testcontainers test (CI).

*Acceptance:* the preview endpoint at Durham reproduces A5 within a minute.

### P2b — One safety budget, late rebuild, watchdog

*Change:* D3 — `waitForBatchSetComplete` takes its deadline from `min(forecast_batch.submitted_at)`
for the run + `photocast.pipeline.safety-timeout` (default **PT3H30M**), shared by WAIT and
RETRY_FAILED; a restart no longer extends it. B1 and B2 (§4), with the watchdog's migration seed.

*Tests:* the retry wait inherits the remaining budget, not a fresh one; a restart mid-wait keeps the
original deadline; B1 fires once when the last non-terminal batch of a finished run lands, not for a
RUNNING run, not for an ad-hoc batch, not while a build runs; B2 alerts once each for "did not start"
and "did not finish", and not otherwise.

*Acceptance:* works with the CRON schedule unchanged — P2b is valuable before any job moves.

### P2c — The admin panel

*Change:* §5's panel, and `SchedulerView.jsx` given an explicit branch per schedule type (a solar row
shows its rule and next fire, edit opens the solar fields, never `{fixedDelayMs: 0}`).

*Tests:* the view renders all three types; an unknown type renders as "unrecognised" with no edit;
the panel's figures come from the preview endpoint. UI review cadence; browser check by the owner.

### P3 — Move the afternoon cycle

*Change (code, before the switch):* `FreshnessProperties.t1Hours` 8 → 6 with its rationale rewritten
(D2); the `IntradayEligibilityPolicy`, `AstroConditionsRefreshJob`, `BriefingCandidateCollector`
label and `PipelineOrchestrator` timing text (A4); their tests' fixtures and display names.

*Then (owner):* `PUT …/intraday_forecast_refresh/schedule-type` → `SOLAR_OFFSET, SUNSET, 240`.
Watch the first three cycles: trigger time in the log, batch duration, the briefing rebuild, and no
watchdog alert. Revert = the same call back to CRON.

### P4 — Move the morning cycle

*Change (code, before the switch):* `StabilitySnapshotProvider` staleness 24 h → 30 h;
`BatchSummaryDeriver` horizon from the UK date via `ForecastHorizon`; `briefing_model_comparison`
moved to 06:30 UTC (a migration updating that one row's cron only if it still holds the seeded
value); the remaining timing text.

*Then (owner):* switch `near_term_batch_evaluation` → `SOLAR_OFFSET, SUNRISE, 255`. Watch three
cycles, including the primer's INFO line (summer US-afternoon latency).

## 7. Stop-points

- P1 is released before P2a merges.
- Migrations are proven only in CI (no Docker locally).
- P3 and P4 each move production spend and what users see first. Each switch is the owner's, made
  through the endpoint, and is reversible by one call.
- The Scheduler screen changes go through the UI review cadence.
- Sync-scoring tonight's sunset (§4, not planned) gets its own design review before any code.

## 8. Remaining risk, stated

- A batch that takes longer than 3 h 30 min still delivers its ratings late; B1 makes the prose and
  picks follow as soon as it lands, and B2 makes sure someone knows. On the measured record (5 slow
  cycles in 49, the slowest 181 min) the budget would have cut none of them.
- The watchdog depends on admin mail working.
- Catch-up cannot help a restart that lasts past the window (morning after 05:00, afternoon within
  90 min of sunset).
- The clamp at 00:05 is never reached at Durham (the earliest fire is 00:13); it exists for a
  reference point further north.

## 9. Cost

Unchanged for Part A: the same cycles, at different times. B1 adds briefing rebuilds after late
batches (the gloss and advisor calls: a few pence per rebuild, only on slow cycles). B2 is free.

## 10. Review record

**2026-10-05 (v1 → v2).** Code-fit lens: v1's claim that a late batch cannot overwrite a direct
answer held for one store of three; the wait step had no inputs to score from; the 04:25 start
arithmetic failed once pre-submit time and the briefing were counted; freshness and failure counting
assumed the old times; reverting a schedule type had no route. Timing lens: 20 minutes fires on the
same cycles as 30; 180 minutes before sunset left no decision time for the furthest tenth of
locations (drive times p50 81, p90 117, max 161 minutes).

**2026-10-13 (v2 → v3).** Three read-only lenses (scheduler core; pipeline timing assumptions;
safety-net feasibility) plus an independent solar recomputation, which confirmed A5. Findings that
changed the plan:
- *Scheduler:* one unknown or incomplete row stops every later job and probably the app; an older JAR
  meeting `SOLAR_OFFSET` fails to load any job; reschedule cancels first; the UI sends
  `{fixedDelayMs: 0}` for an unknown type; cron runs in the JVM zone; misses are never caught up; a
  null from a `Trigger` stops its job silently. → A1, A2, P1.
- *Timing:* the 13-minute margin before UK midnight against a date-only past gate; overlap from the
  restarting 4 h timeout; winter T+1 lost to the cache check; the 24 h snapshot guard; the UTC-dated
  batch summary; the 05:00 model comparison; Ask Ready's summer gap. → A2.2, A4, D2, D3.
- *Safety net:* the stored prompt is the user message only, sky only, best-effort; the late batch
  overwrites a same-cycle direct answer in `cached_evaluation` (equal `submittedAt`), `forecast_score`
  (equal run id) and `forecast_evaluation` (scored by primary key); bluebell recombination needs the
  direct answer's instant to equal the cycle's while late-rejection needs it later; RETRY_FAILED
  would re-score answered slots; `mergeFromBatch` is an unlocked read-modify-write safe only because
  one poller thread calls it; 190 calls queued on the 6-permit `claude` bulkhead with a 120 s wait
  would fail the later ones. And ratings already go live as late batches land. → D4, §4.

Not examined: what `GET /api/forecast` serves while a cycle is mid-flight at the new times, direct-
call latency on Sonnet, batch latency at the new submission times (it will be measured by P3/P4's
first cycles), and anything in `frontend/` beyond `SchedulerView.jsx`.
