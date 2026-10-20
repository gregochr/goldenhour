# Plan: an evening refresh of tomorrow's sunrise

**Status: DRAFT, 2026-10-20, not started; §9 (added the same day) cuts the build into Sonnet-delegable briefs. Owner decisions E-D1 to E-D7 (§4) are open, each with a
proposed default.** Written against `main` at `214a49ed`, with both forecast cycles already on their
sun-relative schedules (`docs/engineering/solar-relative-refresh-plan.md`, released v2.27.0; the
afternoon switched 2026-10-13, the overnight 2026-10-20).

## 1. Why

A sunrise decision is made the evening before: set the alarm or not. Today the freshest look at
tomorrow's sunrise before bedtime is the afternoon cycle (`intraday_forecast_refresh`, sunset − 240 min:
about 14:10 UK in October, 11:40 in December, 17:50 in June), so the reader decides on a forecast
that is 15–20 hours old by sunrise. The overnight cycle (sunrise − 255 min, about 03:30 UK) is fresher
but lands while the reader is asleep; it serves the owner, who checks first thing, not anyone deciding
at bedtime.

This is the gap `intraday-settled-refresh-plan.md` §7 named ("what a user needs is delivery before the
*decision*"), and the solar plan's §2 did not close, because both cycles there are timed to the sun, not
to the reader's evening.

**Proposal:** a third scheduled cycle, `EVENING`, at about 21:00 UK, whose only candidates are
**tomorrow's sunrise** slots. Nothing else in the forecast changes.

## 2. How the pipeline takes a third cycle

The orchestrator already runs two cycle types through one path (`PipelineOrchestrator.runCycle`):
`CycleType` + a `CandidateCollectionStrategy` (`includes(date, targetType)`) + an `EligibilityPolicy`.
`pipeline_run.cycle_type` is a plain `VARCHAR(20)` in Postgres (V102), so a new value needs no schema
change. That part is cheap.

Review of the code (2026-10-20, every point cited in the review record, §8) found seven things that
make it more than "a new enum value and a strategy":

1. **The cycle's behaviour hangs off one boolean.** `PipelineOrchestrator.java:547`,
   `boolean intraday = cycleType == CycleType.INTRADAY`, decides whether the cycle records a
   STABILITY_RECLASSIFY phase and whether its stability classification is *ephemeral*. A new type would
   fall into the nightly branch and **publish a tomorrow-sunrise-only stability snapshot over the
   morning's authoritative one** (`GridCellStabilityService.java:196-204`), which every later reader
   (the afternoon's gate, the briefing) then uses.
2. **In summer the evening would skip its own slots as "cached".** The T+1 cache cap is 3 h
   (`FreshnessProperties`), measured from when results *arrive*. In June the afternoon fires about
   16:50 UTC and its tomorrow-sunrise results land around 17:15 UTC; at 20:00 UTC they are under 3 h
   old, so the evening would mark the very slots it exists to refresh `SKIPPED_CACHED`. In October
   (afternoon 13:10 UTC) and winter it would not.
3. **The documented "two cycles never run at once" property breaks.** The 3 h 30 min wait budget was
   sized against the shortest gap between the *two* cycles (solar plan D3). With an evening cycle at
   20:00 UTC the shortest gaps in June are afternoon → evening about 3 h 10 min and evening → overnight
   about 3 h 13 min (overnight fires 23:13 UTC on 18 June).
4. **Failure counting gets stricter.** A place auto-disables after 3 consecutive counted failures
   (`LocationFailureService.AUTO_DISABLE_THRESHOLD`), a figure chosen for two cycles a day. Three cycles
   could disable a place inside one UK day.
5. **Neither schedule type expresses 21:00 UK.** `SOLAR_OFFSET` is "minutes before an event on the same
   UK day", bounded to sunrise fires at 00:05–06:00 (`SolarOffsetTrigger`); CRON is pinned to UTC
   (P1, `DynamicSchedulerService.SCHEDULE_ZONE`), so `0 0 20 * * *` is 21:00 UK in summer and 20:00 UK
   in winter.
6. **The forecast-cycle job list is hard-coded to two.** `SolarForecastJobs` (`keys`, `allowed`,
   `cycleOf`, `proposedRule`) feeds the watchdog, catch-up, the solar preview and, on the client,
   `FORECAST_CYCLE_JOBS`. The watchdog's "did not finish" check assumes a cycle serves *today's* event;
   the evening serves *tomorrow's* sunrise.
7. **A cycle costs more than its batch.** Every finished cycle also rebuilds the whole briefing (the
   gloss and best-bet advisor Claude calls), dispatches an Ask Ready precompute (about 35 Haiku calls,
   counted against `ready.max-cycles-per-day`, 6; today 3 are used: overnight, afternoon, astro) and
   submits cache-primer batches whose cost is recorded nowhere.

## 3. Design

### 3.1 The cycle

- `CycleType.EVENING`. The orchestrator's `intraday` boolean is replaced by properties of the cycle
  type, so no future type can silently inherit the nightly branch:
  - `publishesStabilitySnapshot()` — NIGHTLY only;
  - `recordsReclassifyPhase()` — INTRADAY and EVENING;
  - `countsLocationFailures()` — NIGHTLY and INTRADAY (E-D5);
  - `bypassesCacheGate()` — EVENING only (E-D3);
  - `safetyBudget()` — the configured one for NIGHTLY/INTRADAY, a separate property for EVENING (E-D4).
  An exhaustive `switch` on the enum, so adding a fourth type fails to compile until each answer is
  given.
- `EveningCandidateCollectionStrategy`: `includes(date, t) = date.equals(today + 1) && t == SUNRISE`,
  with `today` the UK civil date fixed when the strategy is built (the intraday strategy's own shape).
- Eligibility: every stability, on the near-term model (E-D2). T+1 lands in the near-term buckets by
  `daysAhead`, exactly as the afternoon's tomorrow-sunrise slots do today.
- The briefing rebuild, the late-batch rebuild, picks persistence, the almanac and (E-D6) the Ask Ready
  precompute run after it exactly as after the other two. `ForceEvalHeadlineSelector` cannot fire (it
  keys on T+2/T+3), and `PipelineRunComparisonService` returns no comparison for it (it compares only
  INTRADAY against NIGHTLY).
- `LocalH2EnumWidener` gains `pipeline_run.cycle_type`, or a developer's existing H2 file refuses the
  new value.

### 3.2 The schedule

- A new job, `evening_sunrise_refresh`, seeded by one migration as **CRON `0 0 20 * * *`, PAUSED**
  (E-D1). It ships dormant; the owner resumes it from the Scheduler screen. Nothing runs until then.
- It is CRON only. The CRON ↔ SOLAR_OFFSET switch stays offered for the two existing cycles alone.
- No startup catch-up: if the app is down at 20:00 UTC, the overnight cycle still evaluates tomorrow's
  sunrise a few hours later.

### 3.3 The job registry and the watchdog

`SolarForecastJobs` becomes `ForecastCycleJobs`, one entry per scheduled forecast cycle, each with its
job key, cycle type, the event it serves **and that event's day offset** (nightly: today's sunrise;
afternoon: today's sunset; evening: tomorrow's sunrise), and whether it may be solar (the evening may
not). The watchdog walks all three:

- **Did not start:** as today, from the job's last scheduled fire (the evening's is a UTC cron).
- **Did not finish:** for the evening, measured against its own budget rather than an event: still
  RUNNING, or a batch outside `TERMINAL`, when its budget has run out (E-D4). A late evening forecast
  has missed bedtime whether or not sunrise is hours away.

The solar preview and panel list only solar-capable jobs; the panel's copy "two forecast cycles can
never be running at once" is reworded.

## 4. Owner decisions (each with a proposed default)

- **E-D1 — When.** (a) **CRON `0 0 20 * * *` UTC**: 21:00 UK in summer, 20:00 UK in winter, no
  scheduler change. (b) A per-job `cron_zone` column (null = UTC, every existing row unchanged) so the
  row can say `0 0 21 * * *` Europe/London all year; it touches the hardened scheduler core
  (`prepare`, `calculateNextFireTime`, the watchdog's cron walk, the solar trigger's cron fallback, the
  frontend's `describeCron` and "(UTC)" labels). **Proposed: (a).** An hour earlier in winter is no
  loss (sunrise is later, bedtime is not), and it adds no risk to the job that schedules every forecast.
  (b) can follow on its own if the drift turns out to matter.
- **E-D2 — Which stabilities.** Every stability, or skip SETTLED cells as the afternoon does for
  tomorrow's sunset? **Proposed: every stability.** The evening exists for one decision; `evaluation_delta_log`
  shows a SETTLED slot re-evaluated after a day moved a full star 63% of the time.
- **E-D3 — The summer cache problem (§2 #2).** (a) **The evening bypasses the CACHED gate** for its own
  slots (it is a deliberate refresh, and it has one event to refresh). (b) Drop tomorrow's sunrise from
  the afternoon cycle instead, so nothing fresh sits in front of the evening. **Proposed: (a) now, and
  (b) as a later, separate decision (E-D7)**: (b) alone would leave tomorrow's sunrise with only the
  overnight's 17-hour-old look whenever the evening is paused or fails.
- **E-D4 — Overlap (§2 #3).** The evening gets its own wait budget, **proposed PT2H** (default, a new
  `photocast.pipeline.evening-safety-timeout`). After two hours the bedtime decision has passed; results
  that land later still go live and trigger the late-batch briefing rebuild. 20:00 + 2 h ends before
  the earliest overnight fire (23:13 UTC), so the property "no two cycles RUNNING at once" holds again.
  The afternoon → evening gap (about 3 h 10 min in June) is already covered: the afternoon's own 3 h 30 min budget,
  counted from its first submit (about 16:55 UTC on the latest June days), runs out about 20:25 UTC, so a
  June overlap of up to about 25 minutes remains possible when the afternoon's batches are very slow. **Accepted**: the failure
  settle then runs RESETS_ONLY (the safe direction) and the two briefing builds queue on their lock.
- **E-D5 — Failure counting (§2 #4).** **Proposed: the evening never counts failures** (its settle is
  always RESETS_ONLY: a success still resets a place's count, a failure adds nothing). The threshold of
  3 keeps its meaning of "about a day and a half of the two main cycles".
- **E-D6 — Ask Ready after the evening.** **Proposed: yes.** It is 4 scheduled precomputes a UK day
  against a ceiling of 6, about 35 Haiku conversations, and it is what makes "best spot tomorrow
  morning?" answer from the 21:00 forecast instead of the afternoon's.
- **E-D7 — Later: drop tomorrow's sunrise from the afternoon?** Once the evening has run cleanly for a
  fortnight, the afternoon's tomorrow-sunrise evaluations duplicate it. Removing them (one strategy
  line, plus the eligibility policy's stale rationale) saves that spend at the price of E-D3's caveat.
  **Proposed: decide after measuring §5.**

## 5. Cost — measure before deciding

No production figures are in the repository for "tomorrow's sunrise alone". Run these on the host
(`ssh dockermacmini`); each is read-only.

How many tomorrow-sunrise slots the afternoon evaluates, against everything it evaluates:

```bash
docker exec -i goldenhour-db psql -U goldenhour -d goldenhour -c "SELECT p.id, to_char(p.trigger_time,'DD Mon') AS day, count(*) FILTER (WHERE d.disposition='EVALUATED' AND d.event_type='SUNRISE' AND d.days_ahead=1) AS t1_sunrise, count(*) FILTER (WHERE d.disposition='EVALUATED') AS all_evaluated FROM pipeline_run p JOIN forecast_run_disposition d ON d.job_run_id = p.disposition_job_run_id WHERE p.cycle_type='INTRADAY' AND p.trigger_time > now() - interval '7 days' GROUP BY 1,2 ORDER BY 1;"
```

What those calls cost (batch sky calls for tomorrow's sunrise, per afternoon cycle):

```bash
docker exec -i goldenhour-db psql -U goldenhour -d goldenhour -c "SELECT p.id, to_char(p.trigger_time,'DD Mon') AS day, count(*) AS calls, round(sum(a.cost_micro_dollars)/1e6, 3) AS usd FROM pipeline_run p JOIN forecast_batch b ON b.pipeline_run_id = p.id JOIN api_call_log a ON a.batch_id = b.anthropic_batch_id WHERE p.cycle_type='INTRADAY' AND p.trigger_time > now() - interval '7 days' AND a.target_type='SUNRISE' AND a.target_date = (p.trigger_time AT TIME ZONE 'Europe/London')::date + 1 GROUP BY 1,2 ORDER BY 1;"
```

The per-cycle overheads every cycle pays on top (briefing rebuild, Ask Ready precompute):

```bash
docker exec -i goldenhour-db psql -U goldenhour -d goldenhour -c "SELECT run_type, count(*) AS runs, round(avg(total_cost_micro_dollars)/1e6, 3) AS avg_usd FROM job_run WHERE started_at > now() - interval '7 days' AND run_type IN ('BRIEFING','ASK_READY') GROUP BY 1;"
```

The evening's daily cost is, roughly, the second query's per-cycle figure plus one briefing rebuild
and one Ask Ready precompute from the third, plus one or two unrecorded primer requests.

## 6. Phases

Each phase is one PR from `main`, with the series' standing rules: the JDK 23 local gate, migrations
proven by CI's Testcontainers job only, an adversarial review before UI work merges, Codex's review
landing before auto-merge.

- **E0 — Owner.** Run §5's queries; take E-D1 to E-D6 (or accept the defaults).
- **E1 — Cycle-type properties, no behaviour change.** Replace the `intraday` boolean with the §3.1
  properties for NIGHTLY and INTRADAY only, each answering exactly what the boolean did. Tests pin that
  both cycles behave identically before and after (phases recorded, snapshot published or not, budget).
- **E2 — The evening cycle, dormant.** `CycleType.EVENING` and its properties, the strategy, the
  eligibility rule, the cache-gate bypass, the RESETS_ONLY settle, the per-cycle budget, the
  `LocalH2EnumWidener` line, the orchestrator target, and a migration seeding
  `evening_sunrise_refresh` CRON `0 0 20 * * *` **PAUSED**. Tests: the strategy's one slot; the bypass
  applies to the evening only; the evening never publishes a snapshot; the settle never counts; the
  2 h budget; an overlap with a RUNNING afternoon settles RESETS_ONLY.
- **E3 — Registry and watchdog.** `ForecastCycleJobs` with the served event's day offset; the watchdog's
  evening checks (did not start; still running at budget end); preview and panel limited to
  solar-capable jobs; the panel copy. UI review cadence for the frontend part.
- **E4 — Owner switch-on.** Resume `evening_sunrise_refresh` after a release containing E1–E3 has run
  cleanly. Watch three evenings: one `EVENING` run at 20:00 UTC, completed well inside 2 h, the Plan
  tab's tomorrow-sunrise card updated after it, no watchdog mail.
- **E5 — Optional (E-D7).** Drop tomorrow's sunrise from the afternoon, after a fortnight of E4 and the
  §5 figures.

Rollback at every stage after E2: pause the job. Nothing else depends on it.

## 7. Stop-points

- E2 ships the job PAUSED; only the owner resumes it.
- E1 must change no behaviour; if a test of the existing cycles has to change, stop and report.
- Migrations are proven only in CI.
- E5 is a product decision, not part of this build.

## 8. Review record

**2026-10-20 (draft).** One read-only code review of `main` at `214a49ed` for every site a third cycle
touches. Findings that shaped the design, with citations:

- The cycle behaviour boolean: `PipelineOrchestrator.java:547-568`; snapshot publishing skipped only
  when ephemeral: `GridCellStabilityService.java:196-204`, `ForecastTaskCollector.java:248-256, 336-338`.
- The intraday strategy and policy (tomorrow's sunrise is evaluated at every stability on the near-term
  model, and the policy's rationale is that nothing later runs): `IntradayCandidateCollectionStrategy.java:47-60`,
  `IntradayEligibilityPolicy.java:23-24, 69-98`.
- Freshness: T+1 cap 3 h from result arrival: `FreshnessResolver.java:86-121`,
  `FreshnessProperties.java:85-108`, `BriefingEvaluationService.java:303-324`,
  `BriefingCandidateCollector.java:199-226`.
- Failure counting: `LocationFailureService.java:158-178, 413-427`.
- Ask Ready ceiling: `AskReadyPrecompute.java:234-242`, `AskProperties.java:276-287`; dispatch after every
  cycle: `PipelineOrchestrator.java:665-666, 699-725`.
- The briefing rebuild after every cycle: `PipelineOrchestrator.java:1003-1038`, `BriefingService.java:571-582`.
- Scheduler: `DynamicSchedulerService.java:46-47, 445-449, 543-560`; `SolarOffsetTrigger.java:64-74, 154, 202-230`;
  no zone column (`SchedulerJobConfigEntity.java:37-128`).
- The two-job registry: `SolarForecastJobs.java:51-89`; `ForecastCycleWatchdog.java:158-166, 208-264`;
  `SolarCatchUpPolicy.java:86-110`; `SolarSchedulePreviewService.java:139-201`;
  `frontend/src/utils/schedulerSolar.js:11, 25-27`, `SolarSchedulePanel.jsx:219, 241-242`.
- `pipeline_run.cycle_type`: `@Enumerated(STRING)` (`PipelineRunEntity.java:32-34`), `VARCHAR(20)` with no
  CHECK (`V102__pipeline_run.sql:24`); `LocalH2EnumWidener.java:74-75` lists only `job_run.run_type`.
- Open-Meteo is called without a `models=` parameter (best match): `OpenMeteoClient.java:721-729`.

Not examined: what the evening's results change on the Map tab beyond what the briefing rebuild
already does; the Ask Ready questions' text at 21:00 (whether "this morning"/"tomorrow" wording reads
right then); and real production costs, which §5 measures.

## 9. Delegation: one Sonnet agent per build phase

Added 2026-10-20 against `main` at `dca621cc`. §6's phases are kept; E1–E3 are re-cut so that each
build piece is one brief a Sonnet agent can finish alone, in its own worktree, without reading this
conversation. The orchestrating session (Opus) writes each brief from the template below, launches the
agent (`isolation: "worktree"`, `model: "sonnet"`), then does the parts a builder should not mark itself:
the adversarial review, the gates re-run from a clean checkout, the PR, and Codex. Agents commit to their
own branch and never push.

### 9.1 What changed from §6, and why

- **Every consumer is wired in E1, not E2.** All five §3.1 properties get their consumer in E1, each
  answering for NIGHTLY and INTRADAY exactly what the code does today. E2 then only adds a constant and
  its answers. This keeps E1 the one risky refactor (proven by "no existing test changed"), and makes E2
  small enough that its tests are about the evening and nothing else.
- **E3 splits into backend (E3a) and frontend (E3b)**, which share no files and can run at once.
- **A docs sweep (E3c)** runs last, because CLAUDE.md's *Dynamic scheduler*, *Command pattern* and
  `CycleType` facts change in three PRs and one pass is cheaper than three conflicting ones.

### 9.2 Order and parallelism

```
E0 (owner: §5 queries, E-D1..E-D6)   ─┐
E1 (cycle-type properties, refactor) ─┴─► E2 (evening cycle, PAUSED) ─┬─► E3a (registry + watchdog) ─┬─► E3c (docs) ─► E4 (owner)
                                                                      └─► E3b (scheduler UI copy)  ──┘
```

- **E0 and E1 run together.** E1 changes no behaviour and depends on no decision.
- **E2 waits for E0.** It encodes E-D2 to E-D5. If the owner has not answered, E2 builds the proposed
  defaults and the PR says so in its first line.
- **E3a and E3b run together** after E2 merges (both need the job key and `CycleType.EVENING` on main).
- Everything in the backend chain is sequential because every piece hangs off one enum; do not try to
  parallelise E1/E2 by stubbing it.

### 9.3 The brief template (every phase)

Each brief carries, in this order: the goal in two sentences; the base (`main` at the merge commit of
the phase before); the files it may touch (anything else is a stop-and-report); the must-nots; the tests
by name; the gate; what to hand back. Standing lines for every brief:

- Read `CLAUDE.md` and `docs/engineering/test-improvement-standards.md` (backend) or
  `frontend-test-standards.md` (frontend) before writing a test. Minimise `any()`/`lenient()`.
- Backend gate, exit code only:
  `cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"`
  (no Docker here; integration classes and the migration run in CI only).
- Frontend gate: `npm run lint && npm test && npm audit --audit-level=high && npm run build`.
- One changelog file, `changelog.d/YYYYMMDD-<slug>.md`. Never `CHANGELOG.md`.
- Never push, tag, or `git add -A`; never `git checkout --` a file you did not change.
- Hand back: branch, commit hash, gate exit codes, the test count delta, and every place the brief was
  wrong or ambiguous.

### 9.4 The briefs

**E1 — Cycle-type properties, every consumer wired, no behaviour change.**
- Add to `CycleType` five methods, each an exhaustive `switch` (no `default`):
  `publishesStabilitySnapshot()` (NIGHTLY true), `recordsReclassifyPhase()` (INTRADAY true),
  `countsLocationFailures()` (both true), `bypassesCacheGate()` (both false),
  `safetyBudget(Duration configured)` (both return `configured`).
- Consumers:
  - `PipelineOrchestrator.submitPhase` (the `boolean intraday` at ~547): first phase and the
    between-steps hook from `recordsReclassifyPhase()`, the `ephemeral` argument to
    `ScheduledBatchEvaluationService.submitForecastBatchForPipelineRun` from
    `!publishesStabilitySnapshot()`. The local `intraday` disappears.
  - `PipelineOrchestrator`'s budget (`budgetDeadline`, `remainingBudget`, the fallback deadline and
    the RETRY_FAILED skip message, all reading the `safetyTimeout` field): read the run's own
    `cycleType.safetyBudget(safetyTimeout)`. The run's cycle type is on `pipeline_run`; load it once
    per wait, not once per poll.
  - `LocationFailureService`'s mode choice (~415–427): a run whose cycle type does not count failures
    settles `RESETS_ONLY` with the reason "this cycle type never counts failures", checked before the
    trigger-order rule. Unreachable for both existing types.
  - `BriefingCandidateCollector`'s CACHED skip (~202): skipped when the cycle bypasses the gate. Thread
    the cycle type (or the one boolean) through the existing `ScheduledBatchEvaluationService` →
    collector path; do not read it from a static.
- Tests: one per property per constant; `PipelineOrchestratorTest` asserting both cycles' phase lists
  and `ephemeral` values are what they were; a budget test per cycle; the settle and cache-gate tests
  for NIGHTLY and INTRADAY unchanged.
- **Stop-point:** if any existing test's assertion has to change, stop and report it. A changed
  constructor or method signature in test setup is fine; a changed expectation is not.

**E2 — The evening cycle, dormant.** (After E0, or on the defaults.)
- `CycleType.EVENING` with its answers: snapshot false, reclassify true, failures false (E-D5),
  cache bypass true (E-D3), budget = a new `photocast.pipeline.evening-safety-timeout` (ISO-8601,
  default PT2H, E-D4). The compiler finds every `switch`; answer each.
- `EveningCandidateCollectionStrategy` in `service/batch/`, the shape of
  `IntradayCandidateCollectionStrategy` (built with the orchestrator's `Clock`, `today` the UK civil
  date fixed at construction): `date.equals(today.plusDays(1)) && targetType == SUNRISE`.
- `EveningEligibilityPolicy`: include every stability on the near-term model (E-D2), with a javadoc
  saying why. Do not reuse `IntradayEligibilityPolicy`: it happens to give the same answer for this
  slot, but its rationale ("tomorrow's sunrise has only the next morning's look") is exactly what the
  evening makes false, and E5 will edit it.
- `PipelineOrchestrator.runEveningCycle()`, registered as `evening_sunrise_refresh` beside the other
  two in `registerJobTarget()`.
- Migration `V184__seed_evening_sunrise_refresh.sql` (re-read the latest number on main first): one
  `scheduler_job_config` row, CRON `0 0 20 * * *`, **PAUSED**, description naming tomorrow's sunrise.
  Copy V172's column list. Testcontainers test `EveningSunriseRefreshSeedMigrationTest`.
- `LocalH2EnumWidener`'s `TARGETS` gains `pipeline_run.cycle_type`.
- `application-example.yml` lists the new property with its range.
- Tests (from §6 E2): the strategy admits exactly one (date, type) and is stable across midnight;
  the policy includes all three stabilities; the evening's collection does not skip a slot whose cache
  entry is 1 h old while an INTRADAY collection does; the evening never publishes a snapshot; its
  settle is RESETS_ONLY even when it is the newest cycle with failures; its wait gives up at 2 h from
  first submit; `PipelineRunComparisonService` returns nothing for it.
- Must not: touch `SolarForecastJobs`, the watchdog or the frontend (E3); resume the job anywhere.

**E3a — Registry and watchdog (backend).**
- Rename `SolarForecastJobs` → `ForecastCycleJobs`: one record per job (key, `CycleType`, served
  event, event day offset 0/0/+1, `solarCapable`). Keep the existing static answers for the two solar
  jobs byte for byte; `allowed`/`proposedRule` return nothing for the evening.
- `ForecastCycleWatchdog`: walk all three. *Did not start* unchanged in shape (the evening's last fire
  from its UTC cron). *Did not finish* for the evening: RUNNING, or a FORECAST batch outside
  `TERMINAL`, once `EVENING.safetyBudget(...)` from its trigger has passed; one mail per (job, run).
  The two existing jobs' checks unchanged.
- `SolarCatchUpPolicy` and `SolarSchedulePreviewService` iterate solar-capable jobs only. The
  `PUT …/schedule-type` endpoint refuses the evening with the existing 400.
- Tests: watchdog cases for the evening (on time; not started; overran; batch left polling), and the
  existing watchdog tests unchanged; the preview lists two jobs; switching the evening to solar is 400.

**E3b — Scheduler UI (frontend).** Runs beside E3a.
- `schedulerSolar.js`: keep `FORECAST_CYCLE_JOBS` as the solar-capable list (the evening stays out, so
  it renders as a plain CRON row with no switch); add a test that `evening_sunrise_refresh` gets no
  CRON ↔ SOLAR control.
- `SolarSchedulePanel.jsx` ~242: reword "two forecast cycles can never be running at once" to the
  E-D4 truth (no two cycles at once except a possible short summer overlap of afternoon and evening).
  The panel's fixed text comes from this plan; quote §4 E-D4, do not invent figures.
- `schedulerApi.js`'s "two forecast cycles" comment.
- UI review cadence applies (CLAUDE.md *UI Work*): the orchestrator runs the review, not the builder.

**E3c — Docs sweep.** CLAUDE.md: the `CycleType`/orchestrator facts, the *Dynamic scheduler* bullet
(the evening job, PAUSED, CRON-only, its watchdog checks, its budget property), *Job metrics*
(EVENING never counts failures), *Ask PhotoCast* (a fourth scheduled precompute against the ceiling of
6, if E-D6 is yes), and the *Configuration* line for `evening-safety-timeout`. This plan's status line
and §6. No code.

### 9.5 What the orchestrator does between phases

1. Before launching: confirm the previous phase is on `main`, re-read the latest migration number,
   and correct the brief's line numbers against the tree.
2. After the agent hands back: read the diff whole; re-run the gate from a fresh checkout of the branch
   (an agent's own "exit 0" is a claim); run an adversarial review (three lenses for backend — runtime
   behaviour, the two existing cycles unchanged, test quality — plus accessibility and CSS for E3b),
   read-only, mutation probes only in their own worktree.
3. Fix or send back what survives; open the PR; read CI's Backend job (the migration's only proof);
   wait for Codex before any merge.

### 9.6 Model choice

Sonnet builds E1, E2, E3a, E3b and E3c. Keep E1 on Sonnet only with the stop-point enforced: it is
the one phase where a "small" test edit can hide a behaviour change, and the review's second lens
exists for it. If E1's agent reports a stop, the orchestrator decides, not a second agent.
