# Plan: an evening refresh of tomorrow's sunrise

**Status: E1–E3 BUILT 2026-10-20** (E1 #1254, E2 #1255, E3a #1256, E3b #1257, E3c this docs sweep); the job ships PAUSED and **E4, the switch-on, is the owner's**. E5 was widened by the owner the same day (§4 E-D7). Written against `main` at `214a49ed`; revised the same day
against `dca621cc` after an adversarial review of the plan (§8), which changed the failure-counting
design (E-D5), added a decision the first draft missed (E-D8), corrected the timing figures, and
rewrote the build briefs (§9) so each phase can be handed to a Sonnet agent. The owner accepted the
review's recommendations for E-D5, E-D8 and the watchdog rule on 2026-10-20; E-D1 to E-D4 and E-D6
stand as proposed defaults until E0. Both forecast cycles are already on their sun-relative schedules
(`docs/engineering/solar-relative-refresh-plan.md`, released v2.27.0; the afternoon switched
2026-10-13, the overnight 2026-10-20).

## 1. Why

A sunrise decision is made the evening before: set the alarm or not. Today the freshest look at
tomorrow's sunrise before bedtime is the afternoon cycle (`intraday_forecast_refresh`, sunset − 240 min:
about 14:00 UK in mid-October, 11:40 in December, 17:47 in June). By sunrise that forecast is about
**10.7 h old in June, 17.5 h in October and 20.8 h in December**. The overnight cycle (sunrise − 255 min:
00:13 UK in June, 03:30 in October, 04:12 in December) is fresher but lands while the reader is asleep;
it serves the owner, who checks first thing, not anyone deciding at bedtime.

This is the gap `intraday-settled-refresh-plan.md` §7 named ("what a user needs is delivery before the
*decision*"), and the solar plan's §2 did not close, because both cycles there are timed to the sun, not
to the reader's evening.

**Proposal:** a third scheduled cycle, `EVENING`, at 20:00 UTC (21:00 UK in summer, 20:00 in winter),
whose only candidates are **tomorrow's sunrise** slots.

**What it gains, by season** (computed at the Durham reference point, §8): the evening is about
**8 h 19 min** fresher than the afternoon's look in December and only about **3 h 13 min** fresher in
June, when the afternoon already runs late. The case is strongest in winter; that is also where it is
cheapest to run (no summer cache or overlap problems, §2).

## 2. How the pipeline takes a third cycle

The orchestrator already runs two cycle types through one path (`PipelineOrchestrator.runCycle`):
`CycleType` + a `CandidateCollectionStrategy` (`includes(date, targetType)`) + an `EligibilityPolicy`.
`pipeline_run.cycle_type` is a plain `VARCHAR(20)` in Postgres (V102), so a new value needs no schema
change. That part is cheap.

Review of the code found these things that make it more than "a new enum value and a strategy":

1. **The cycle's behaviour hangs off one boolean.** `PipelineOrchestrator.java:547`,
   `boolean intraday = cycleType == CycleType.INTRADAY`, decides whether the cycle records a
   STABILITY_RECLASSIFY phase and whether its stability classification is *ephemeral* (passed on at
   `:568`). A new type would fall into the nightly branch and **publish a tomorrow-sunrise-only stability
   snapshot over the morning's authoritative one** (`GridCellStabilityService.java:196-204`). There is no
   `switch` on `CycleType` anywhere today; every branch is an equality test (`:547`,
   `PipelineRunComparisonService.java:73`, `SolarForecastJobs.cycleOf`).
2. **In summer the evening would skip its own slots as "cached".** The T+1 cache cap is 3 h
   (`FreshnessProperties`), measured from the region's **last** result arrival
   (`CachedEvaluation.evaluatedAt` is reset on every merge, `BriefingEvaluationService` ~330/387/540/583,
   and read against `Instant.now()`, not the injected clock). Whether the evening finds its slots cached
   depends on how long the afternoon's batch took: with results 15 min after the afternoon fires, 14 Jun
   to 3 Jul; with 30 min, 30 May to 18 Jul; with 60 min, 12 May to 5 Aug. Never in autumn or winter.
3. **The evening makes the summer overnight skip tomorrow's sunrise (E-D8).** In June the overnight fires
   about 23:13 UTC (00:13 UK), when tomorrow's sunrise has become *today's*: T+0, cap 2 h
   (`FreshnessProperties` `t0Hours`). Any evening region whose results land after about 21:13 UTC is then
   `SKIPPED_CACHED` by the overnight. `FreshnessProperties`' own derivation (a cap below the shortest gap
   between two cycles minus the slowest batch) was made for two cycles; a third breaks it at T+0 in summer.
4. **"Two cycles never run at once" breaks in summer.** The 3 h 30 min wait budget was sized against the
   shortest gap between the *two* cycles (solar plan D3). With the evening at 20:00 UTC: afternoon →
   evening is at least 3 h 11 min (24 Jun), evening → overnight at least 3 h 13 min (17 Jun). An afternoon
   can still be RUNNING at 20:00 UTC from about 25 May to 23 Jul, for up to about 25 minutes of WAIT
   **plus its briefing build**, which has no time bound and runs after WAIT.
5. **Failure counting is shared by every cycle.** A place auto-disables after 3 consecutive counted
   failures (`LocationFailureService.AUTO_DISABLE_THRESHOLD`). Beyond the counting itself, a cycle takes
   part in two ordering rules: an unsettled older cycle (still RUNNING, or with a FORECAST batch still
   polling, `:394-403`) forces every newer tail settle to RESETS_ONLY (`:333-350`, `:417-418`), and a
   settled cycle moves `findNewestSettledTriggerTime`, so an older cycle settling after it is
   RESETS_ONLY (`:419-423`). RESETS_ONLY still applies resets (`:465-470`). See E-D5.
6. **Neither schedule type expresses 21:00 UK.** `SOLAR_OFFSET` is "minutes before an event on the same
   UK day", bounded to sunrise fires at 00:05–06:00 (`SolarOffsetTrigger`); CRON is pinned to UTC
   (`DynamicSchedulerService.SCHEDULE_ZONE`), so `0 0 20 * * *` is 21:00 UK in summer and 20:00 UK in
   winter.
7. **The forecast-cycle job list is hard-coded to two.** `SolarForecastJobs` (`service/`: `keys`,
   `allowed`, `cycleOf`, `proposedRule`) feeds the watchdog, the solar preview and the schedule-type
   endpoint's 400. The watchdog takes the served event from `proposedRule(...).event()`
   (`ForecastCycleWatchdog.java:176, 260, 323`), so a job with no solar rule cannot be watched as it
   stands. The client's `FORECAST_CYCLE_JOBS` (`schedulerSolar.js:11`) is an independent copy.
8. **A cycle costs more than its batch.** Every finished cycle also rebuilds the whole briefing (the
   gloss and best-bet advisor Claude calls), dispatches an Ask Ready precompute (about 35 Haiku calls,
   counted against `ready.max-cycles-per-day`, 6; today 3 are used: overnight, afternoon, astro), submits
   cache-primer batches whose cost is recorded nowhere, and spends Open-Meteo quota (prefetch, cloud
   points, pressure levels, the briefing's weather fetch) through the shared rate limiter.

## 3. Design

### 3.1 The cycle

- `CycleType.EVENING`. The orchestrator's `intraday` boolean is replaced by properties of the cycle
  type, each an exhaustive `switch` with no `default`, so a fourth type fails to compile until each
  answer is given:
  - `publishesStabilitySnapshot()` — NIGHTLY only;
  - `recordsReclassifyPhase()` — INTRADAY and EVENING;
  - `failureEvidence()` — `COUNTED` for NIGHTLY and INTRADAY, `NONE` for EVENING (E-D5).
- **The cache-gate bypass is a property of the candidate strategy, not the cycle type**:
  `CandidateCollectionStrategy.bypassesCacheGate()`, a default method returning false, overridden true
  by the evening's strategy (E-D3). The strategy object already reaches the CACHED gate
  (`BriefingCandidateCollector.java:107, 178`), so no signature changes; the admin region-filtered path
  always passes the nightly strategy and is unaffected.
- **The wait budget is chosen by the orchestrator**, from a `Map<CycleType, Duration>` built from
  `photocast.pipeline.safety-timeout` (NIGHTLY, INTRADAY) and a new
  `photocast.pipeline.evening-safety-timeout` (EVENING, E-D4). An enum constant cannot read
  configuration, so the budget is not an enum method.
- `EveningCandidateCollectionStrategy`: `includes(date, t) = date.equals(today + 1) && t == SUNRISE`,
  with `today` the UK civil date fixed when the strategy is built (the intraday strategy's own shape).
- `EveningEligibilityPolicy`: every stability, on the near-term model (E-D2). Not
  `IntradayEligibilityPolicy`, which happens to give the same answer for this slot but whose rationale
  ("tomorrow's sunrise has only the next morning's look") is what the evening makes false.
- The briefing rebuild, the late-batch rebuild, picks persistence, the almanac and (E-D6) the Ask Ready
  precompute run after it exactly as after the other two. `ForceEvalHeadlineSelector` cannot fire (it
  covers T+2/T+3), and `PipelineRunComparisonService` returns no comparison for it (it compares only
  INTRADAY against NIGHTLY).
- `LocalH2EnumWidener` gains `pipeline_run.cycle_type`, or a developer's existing H2 file refuses the
  new value.

### 3.2 Failure counting: the evening gives no evidence (E-D5)

A cycle whose `failureEvidence()` is `NONE` takes no part in `LocationFailureService` at all:

- Its tail settle (and any sweep that reaches it) **claims** the run (`failures_settled_at` set, so it is
  never retried) and applies **nothing**: no failure counts, no resets. It is never DEFERRED, even with
  a FORECAST batch still polling, because nothing it could later learn would be applied.
- `sweepLocked` never lists it as an unresolved older cycle, so a slow or timed-out evening cannot force
  the next overnight's settle to RESETS_ONLY.
- `findNewestSettledTriggerTime` ignores it, so a June afternoon whose tail settles after the evening's
  is still settled FULL.

Why not RESETS_ONLY: a reset from the evening is evidence about tomorrow's sunrise lane only, and it
would break the threshold — a place failing every overnight and afternoon but succeeding each evening
would run 1 → 2 → 0 and never disable. With no evidence, the threshold of 3 keeps its meaning (about a
day and a half of the two main cycles), and the two main cycles' settles behave exactly as they do today.

### 3.3 The schedule

- A new job, `evening_sunrise_refresh`, seeded by one migration as **CRON `0 0 20 * * *`, PAUSED**
  (E-D1). It ships dormant; the owner resumes it from the Scheduler screen. Nothing runs until then.
- It is CRON only. The CRON ↔ SOLAR_OFFSET switch stays offered for the two existing cycles alone (the
  schedule-type endpoint already refuses any job not in `SolarForecastJobs.allowed`, which stays two).
- No startup catch-up (`SolarCatchUpPolicy` already returns nothing for a CRON row): if the app is down
  at 20:00 UTC, the overnight cycle still evaluates tomorrow's sunrise a few hours later.

### 3.4 The job registry and the watchdog

`SolarForecastJobs` becomes `ForecastCycleJobs`, one record per scheduled forecast cycle: job key,
cycle type, and either a solar rule (the two existing jobs, unchanged) or none (the evening). Two
accessors: `allCycleJobs()` (the watchdog) and `solarCapableJobs()` (the preview, the schedule-type
endpoint, catch-up). The watchdog walks all three:

- **Did not start:** as today, from the job's last scheduled fire (the evening's from its UTC cron).
- **Did not finish**, for the evening: **not** "still RUNNING at budget end" (a healthy run is RUNNING
  through its briefing build after WAIT ends, and the budget runs from the first batch submit, minutes
  after the trigger). Instead: the run ended FAILED by the safety timeout, or a FORECAST batch of the run
  is still outside `TERMINAL` once `min(forecast_batch.submitted_at) + evening budget` (falling back to
  the trigger when no batch was submitted) has passed. One mail per (job, run). The served event for the
  two existing jobs still comes from their solar rule; the evening has no event window.

## 4. Owner decisions

- **E-D1 — When.** (a) **CRON `0 0 20 * * *` UTC**: 21:00 UK in summer, 20:00 UK in winter, no
  scheduler change. (b) A per-job `cron_zone` column (null = UTC) so the row can say `0 0 21 * * *`
  Europe/London all year; it touches the hardened scheduler core (`prepare`, `calculateNextFireTime`, the
  watchdog's cron walk, the solar trigger's cron fallback, the frontend's `describeCron` and "(UTC)"
  labels). **Proposed: (a).** An hour earlier in winter is no loss (sunrise is later, bedtime is not).
- **E-D2 — Which stabilities.** **Proposed: every stability.** The evening exists for one decision;
  `evaluation_delta_log` shows a SETTLED slot re-evaluated after a day moved a full star 63% of the time.
- **E-D3 — The summer cache problem (§2 #2).** (a) **The evening's strategy bypasses the CACHED gate**
  (it is a deliberate refresh of one event). (b) Drop tomorrow's sunrise from the afternoon instead.
  **Proposed: (a) now; (b) only as E-D7**: (b) alone would leave tomorrow's sunrise with only the
  overnight's look whenever the evening is paused or fails.
- **E-D4 — Overlap (§2 #4).** The evening gets its own wait budget, **PT2H** (property
  `photocast.pipeline.evening-safety-timeout`, bounds PT30M..PT3H30M, an out-of-range value fails
  startup). After two hours the bedtime decision has passed; later results still go live through the
  late-batch briefing rebuild (which rebuilds the briefing only: no picks, no Ask Ready precompute, so
  the afternoon's Ready answers stay withheld by their freshness check until the overnight's precompute).
  The evening's budget ends by about 22:05 UTC, an hour before the earliest overnight fire. **Accepted**:
  from late May to late July an afternoon may still be RUNNING at 20:00 UTC (WAIT up to about 25 min,
  then its briefing build); the two briefing builds queue on their lock, and with E-D5's design the
  afternoon's failure settle is unaffected.
- **E-D5 — Failure counting (§2 #5). Owner accepted 2026-10-20: the evening gives no evidence** (§3.2).
  The first draft's "always RESETS_ONLY" was rejected by the review: a deferred evening would have
  blocked the overnight's counting, the evening's settle would have demoted a late afternoon to
  RESETS_ONLY, and its resets would have stopped places that fail every main cycle from ever disabling.
- **E-D6 — Ask Ready after the evening.** **Proposed: yes.** It is 4 scheduled precomputes a UK day
  against a ceiling of 6, about 35 Haiku conversations, and makes "best spot tomorrow morning?" answer
  from the evening's forecast instead of the afternoon's.
- **E-D7 — Later: the afternoon evaluates today's sunset only. Widened by the owner, 2026-10-20.** Once
  the evening has run cleanly, drop BOTH of the afternoon's tomorrow slots. Tomorrow's sunrise moves to
  the evening, which re-pays it, so that is a shift, not a saving. Tomorrow's sunset is the real saving:
  the next overnight and the next afternoon still cover it. Gate it on E4 (a fortnight of clean evenings;
  never while the evening is PAUSED, or tomorrow's sunrise would rest on an overnight look about 18 h
  old) and on §5's figures. Side benefits: the evening's cache bypass (E-D3 (a)) becomes unnecessary, the
  June afternoon/evening overlap (E-D4) mostly goes, and Open-Meteo use falls. The change is the intraday
  strategy plus `IntradayEligibilityPolicy`'s tomorrow rule. Perhaps seasonal.
- **E-D8 — The summer overnight skips tomorrow's sunrise (§2 #3). Owner accepted 2026-10-20: accept it.**
  From roughly May to August an evening result landing after about 21:13 UTC is under the overnight's
  2 h T+0 cap, so the overnight marks that slot `SKIPPED_CACHED` and the morning look *is* the evening's.
  That is what the 2 h cap already says is fresh enough, and it saves the spend. The alternative (the
  overnight ignores entries an EVENING cycle wrote) was rejected as cost for little gain. The plan no
  longer claims "nothing else in the forecast changes"; `FreshnessProperties`' javadoc derivation must be
  updated to name the third cycle.

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

Tomorrow's sunset, the second half of E5 (the first query with `d.event_type='SUNSET'`):

```bash
docker exec -i goldenhour-db psql -U goldenhour -d goldenhour -c "SELECT p.id, to_char(p.trigger_time,'DD Mon') AS day, count(*) FILTER (WHERE d.disposition='EVALUATED' AND d.event_type='SUNSET' AND d.days_ahead=1) AS t1_sunset, count(*) FILTER (WHERE d.disposition='EVALUATED') AS all_evaluated FROM pipeline_run p JOIN forecast_run_disposition d ON d.job_run_id = p.disposition_job_run_id WHERE p.cycle_type='INTRADAY' AND p.trigger_time > now() - interval '7 days' GROUP BY 1,2 ORDER BY 1;"
```

The per-cycle overheads every cycle pays on top (briefing rebuild, Ask Ready precompute):

```bash
docker exec -i goldenhour-db psql -U goldenhour -d goldenhour -c "SELECT run_type, count(*) AS runs, round(avg(total_cost_micro_dollars)/1e6, 3) AS avg_usd FROM job_run WHERE started_at > now() - interval '7 days' AND run_type IN ('BRIEFING','ASK_READY') GROUP BY 1;"
```

The evening's daily cost is, roughly, the second query's per-cycle figure plus one briefing rebuild
and one Ask Ready precompute from the third, plus one or two unrecorded primer requests. Not measured
here: its Open-Meteo quota use. Not verified by the review: that every column these queries name exists
(the facts reviewer found them on the entities; nobody ran them).

## 6. Phases

Each phase is one PR from `main`. §9 holds the brief for each build phase.

- **E0 — Owner.** Run §5's queries; confirm E-D1 to E-D4 and E-D6 (or leave the defaults).
- **E1 — Cycle-type properties, no behaviour change.** §3.1's properties for NIGHTLY and INTRADAY, the
  strategy's `bypassesCacheGate()`, the orchestrator's budget map and the `failureEvidence()` property, each
  consumer wired and answering exactly what the code does today.
- **E2 — The evening cycle, dormant.** `CycleType.EVENING`, its strategy and policy, the no-evidence
  settle (wired in `LocationFailureService`), the evening budget property, the orchestrator target, the `LocalH2EnumWidener` line, and a
  migration seeding `evening_sunrise_refresh` CRON `0 0 20 * * *` **PAUSED**.
- **E3a — Registry and watchdog (backend).** `ForecastCycleJobs`; the watchdog's evening checks.
- **E3b — Scheduler panel copy (frontend).** Beside E3a.
- **E3c — Docs.** CLAUDE.md and this plan.
- **E4 — Owner switch-on.** Resume `evening_sunrise_refresh` after a release containing E1–E3 has run
  cleanly. Watch three evenings: one `EVENING` run at 20:00 UTC, completed well inside 2 h, the Plan
  tab's tomorrow-sunrise card updated after it, no watchdog mail, and the next overnight's failure settle
  FULL.
- **E5 — Optional (E-D7).** The afternoon evaluates today's sunset only: drop tomorrow's sunrise (moved
  to the evening) and tomorrow's sunset (the saving), after a fortnight of clean evenings (E4) and the §5
  figures. Never while the evening is paused.

Rollback at every stage after E2: pause the job. Nothing else depends on it.

## 7. Stop-points

- E2 ships the job PAUSED; only the owner resumes it.
- E1 must change no behaviour; if an existing test's expected value has to change, stop and report.
- Migrations are proven only in CI.
- E5 (the afternoon evaluates today's sunset only) is a product decision gated on E4, not part of this build.

## 8. Review record

**2026-10-20 (draft).** One read-only code review of `main` at `214a49ed` for every site a third cycle
touches. Citations:

- The cycle behaviour boolean: `PipelineOrchestrator.java:547-568`; snapshot publishing skipped only
  when ephemeral: `GridCellStabilityService.java:196-204`, `ForecastTaskCollector.java:248-256, 336-338`.
- The intraday strategy and policy: `IntradayCandidateCollectionStrategy.java:47-60`,
  `IntradayEligibilityPolicy.java:23-24, 69-98`.
- Freshness: `FreshnessResolver.java:86-121`, `FreshnessProperties.java:85-108`,
  `BriefingEvaluationService.java:303-324`, `BriefingCandidateCollector.java:199-226`.
- Failure counting: `LocationFailureService.java:158-178, 413-427`.
- Ask Ready ceiling: `AskReadyPrecompute.java:234-242`, `AskProperties.java:276-287`; dispatch after every
  cycle: `PipelineOrchestrator.java:665-666, 699-725`.
- The briefing rebuild after every cycle: `refreshBriefing()` at `PipelineOrchestrator.java:655`
  (`:1003-1038` is `persistPicksForCycle`, mis-cited in the draft); `BriefingService.java:571-582`.
- Scheduler: `DynamicSchedulerService.java:46-47, 279, 445-449, 543-560`;
  `SolarOffsetTrigger.java:64-74, 154, 202-230`; no zone column (`SchedulerJobConfigEntity.java:37-128`).
- The two-job registry: `SolarForecastJobs.java:51-89`; `ForecastCycleWatchdog.java:158-176, 208-264, 323`;
  `SolarCatchUpPolicy.java:86-110`; `SolarSchedulePreviewService.java:139-201`;
  `frontend/src/utils/schedulerSolar.js:11, 25-27`, `SolarSchedulePanel.jsx:219, 240-244`.
- `pipeline_run.cycle_type`: `@Enumerated(STRING)` (`PipelineRunEntity.java:32-34`), `VARCHAR(20)` with no
  CHECK (`V102__pipeline_run.sql:24`); `LocalH2EnumWidener.java:74-75` lists only `job_run.run_type`.
- Open-Meteo is called without a `models=` parameter (best match): `OpenMeteoClient.java:721-729`.

**2026-10-20 (adversarial review of the plan, against `88afd508`).** Four read-only reviewers: facts
(every citation and feasibility claim), timing (NOAA sunrise/sunset at 54.776N −1.575E for every day of
2026, through `SolarOffsetTrigger`'s real rules), pipeline interactions, and whether a Sonnet agent
could follow each brief. Charges that changed the design were found independently by two to four
reviewers and checked in the code by the orchestrator; there was no separate refutation pass. What
changed:

- **E-D5 redesigned (§3.2).** "Always RESETS_ONLY" left the evening a blocker (a deferred run with a
  polling batch, `LocationFailureService.java:394-403`, listed as unresolved older at `:333-350`, forcing
  the overnight's tail to RESETS_ONLY at `:417-418`), moved `findNewestSettledTriggerTime` (`:419-423`)
  and applied resets (`:465-470`) that defeat the threshold.
- **E-D8 added.** The overnight's T+0 cap of 2 h (`FreshnessProperties` `t0Hours`) against an evening
  landing after ~21:13 UTC in summer.
- **Watchdog rule rewritten (§3.4).** The draft measured from the trigger (the orchestrator measures from
  the first submit, `PipelineOrchestrator.java:1079-1084`) and alarmed on RUNNING (a healthy run is
  RUNNING through its briefing). It also missed that the watchdog reads the served event from
  `proposedRule` (`ForecastCycleWatchdog.java:176, 260, 323`), so a job with no solar rule would throw,
  be caught (`:160-163`), and never be watched.
- **The cache bypass moved onto the strategy.** Threading a cycle type to the gate would have changed six
  signatures across three classes (`ScheduledBatchEvaluationService` `:210/229/275`, `:368`;
  `ForecastTaskCollector` `:220/236/265`, `:643`; `BriefingCandidateCollector:107`) and about 180 test
  references; the strategy already reaches the gate.
- **The budget moved off the enum** (an enum constant cannot read the evening's property).
- **Timing corrected** (§1, §2 #2, §2 #4): afternoon-to-sunrise age 10.7 h (June) to 20.8 h (December);
  the cache problem runs mid-May to early August depending on batch latency; the overlap season is late
  May to late July and includes the afternoon's briefing build. Verified unchanged: afternoon fires
  16:48 UTC at the latest (24 Jun); the overnight's earliest fire 23:13 UTC on 17 June (00:13 UK, 8 min
  clear of the 00:05 clamp, never clamped in 2026); the shortest gaps 3 h 11 min and 3 h 13 min.
- **Briefs**: the local gate needs the JDK 23 recipe; V118, not V172, is the template for a CRON PAUSED
  seed; `LocalH2EnumWidener` compiles only under `-Plocal-dev`; `SolarCatchUpPolicy` and the schedule-type
  400 need no change; E1 needs seam tests that drive a non-default answer, or its tests pass with nothing
  wired.

Refuted: a RUNNING afternoon does not drop the evening's submission (the guard,
`ScheduledBatchEvaluationService.java:284-294`, is held only through collect and submit); E1's
`ephemeral = !publishesStabilitySnapshot()` matches today's behaviour exactly; 4 precomputes fit the
ceiling of 6; a late afternoon result cannot overwrite the evening's newer one (submission-order rules,
CLAUDE.md *Where a rating lives*); the evening writes no `SKIPPED_STABILITY`, so no new retraction.

Not examined: `BestBetFallbackService` with evening picks; the honesty filter and verdict sample gate when
an evening submission fails (its `SUBMISSION_FAILED` rows become the latest disposition for tomorrow's
sunrise and may hide the afternoon's triage evidence); bluebell/OPEN_FELL pairing in the evening;
`BatchCachePrimer` with near buckets only; `PipelineRunService`'s startup resume and the frontend
`PipelineRunsView` for a third cycle type; DST-transition nights; Ask question wording at 21:00; real
batch latencies and costs (§5).

## 9. Delegation: one Sonnet agent per build phase

Each build phase is one brief a Sonnet agent can finish alone, in its own worktree, without reading the
conversation that produced this plan. The orchestrating session (Opus) launches it
(`isolation: "worktree"`, `model: "sonnet"`) and keeps the parts a builder should not mark itself: the
adversarial review, the gate re-run from a clean checkout of the branch, the PR, CI and Codex. Agents
commit to their own branch and never push; fixes from the review are new commits, never amends.

### 9.1 Order and parallelism

```
E0 (owner) ─────────────────────────┐
E1 (properties, no behaviour change) ┴─► E2 (evening, PAUSED) ─┬─► E3a (registry + watchdog) ─┬─► E3c (docs) ─► E4 (owner)
                                                               └─► E3b (panel copy)          ──┘
```

- **E0 and E1 run together.** E1 changes no behaviour and depends on no open decision (E-D5's shape is
  settled; only E2 encodes values).
- **E2 waits for E1 to merge and for E0.** If the owner has not answered, E2 builds the proposed
  defaults and its PR says so in the first line.
- **E3a and E3b run together** after E2 merges; they share no files, and E3b needs only the job key.
- The backend chain is sequential because every piece hangs off one enum; do not stub it to parallelise.

### 9.2 Standing lines (every brief)

- Read `CLAUDE.md` and `docs/engineering/test-improvement-standards.md` (backend) or
  `frontend-test-standards.md` (frontend) before writing a test. Minimise `any()`/`lenient()`.
- **Stay inside the brief's file list.** Touching any other file in `src/main` or `frontend/src` (not
  tests) is a stop: report what and why. Test files of the listed classes may change; adding a parameter
  to an existing `verify(...)`/stub is allowed provided every existing argument value is unchanged.
- Backend gate (this Mac has JDK 23, not the project's 25; exit code only):
  ```bash
  cd backend && export JAVA_HOME=/Users/chrisgregory/Library/Java/JavaVirtualMachines/openjdk-23.0.2/Contents/Home MAVEN_OPTS=--enable-preview && ./mvnw clean verify --batch-mode --no-transfer-progress -Djava.version=23 -Dmaven.compiler.enablePreview=true -Dtest='!**/integration/**,!EvaluationModelTest' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
  ```
  A green run is one JDK behind CI, skips one test and runs no migration; CI's Backend job is the proof.
- Frontend gate: `cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build`.
- One changelog file, `changelog.d/YYYYMMDD-<slug>.md`, `### Category — title`. Never `CHANGELOG.md`.
- Never push, tag, `git add -A`, or `git checkout --` a file you did not change.
- Hand back: branch, commit hash, gate exit codes, the test count before and after, and every place the
  brief was wrong, ambiguous or contradicted by the code.

### 9.3 E1 — cycle-type properties, every consumer wired, no behaviour change

**Goal.** Replace the orchestrator's `intraday` boolean and the scattered cycle assumptions with explicit
properties, each answering for NIGHTLY and INTRADAY exactly what the code does today. Nothing observable
changes: phases, `ephemeral` values, budgets, dispositions, log text.

**Files (main):** `entity/CycleType.java`, `service/pipeline/PipelineOrchestrator.java`,
`service/batch/CandidateCollectionStrategy.java`, `service/batch/BriefingCandidateCollector.java`,
and nothing else. (`LocationFailureService` waits for E2: no existing cycle type gives no evidence, so
its new path could not be tested here.)

**Build.**
1. `CycleType`: `publishesStabilitySnapshot()` (NIGHTLY true, INTRADAY false),
   `recordsReclassifyPhase()` (NIGHTLY false, INTRADAY true), and `failureEvidence()` returning a new
   nested enum `FailureEvidence { COUNTED, NONE }` (both COUNTED). Exhaustive `switch`, no `default`.
2. `PipelineOrchestrator.submitPhase` (~547): first phase and between-steps hook from
   `recordsReclassifyPhase()`; the `ephemeral` argument at ~568 from `!publishesStabilitySnapshot()`.
   The `intraday` local disappears.
3. Budget: a `Map<CycleType, Duration>` field built in the constructors from `safetyTimeout` for both
   types (E2 adds EVENING's own property). Take the cycle type from the run `waitAndBriefPhase` already
   loads (~617) and pass the chosen `Duration` down to `waitForBatchSetComplete`, `remainingBudget` and
   `budgetDeadline`; never call `findById` again. A null cycle type falls back to `safetyTimeout`. Every
   `safetyTimeout` read on the wait path uses the chosen value: `:937` (RETRY skip text), `:1053`
   (fallback deadline), `:1065` (exception message), `:1084` and `:1098`. `runCycleSynchronously` (~437)
   is nightly-only and uses the NIGHTLY entry.
4. `CandidateCollectionStrategy`: `default boolean bypassesCacheGate() { return false; }`.
   `BriefingCandidateCollector`'s CACHED skip (~202) is skipped when `strategy.bypassesCacheGate()`.
   Bypassed slots must flow exactly as uncached ones do (counters included).

**Tests.** One per property per constant. **Seam tests that drive a non-default answer**, so a property
nobody reads fails: a test strategy with `bypassesCacheGate() = true` skips no cached region; an
orchestrator built (package-private constructor) with a budget for INTRADAY different from NIGHTLY's
times out on the INTRADAY value. `PipelineOrchestratorTest`: both cycles' phase lists and `ephemeral` values
unchanged.

**Stop-point.** If any existing test's expected value has to change, stop and report. Signature changes in
test setup are fine.

**Must not.** Add EVENING; touch `ScheduledBatchEvaluationService`, `ForecastTaskCollector` or
`SolarForecastJobs`.

### 9.4 E2 — the evening cycle, dormant

**Goal.** Add the EVENING cycle end to end, seeded PAUSED. Encodes E-D2 to E-D5 (defaults if E0 has not
answered; say so in the PR's first line).

**Files (main):** `entity/CycleType.java`, `service/pipeline/PipelineOrchestrator.java`, new
`service/batch/EveningCandidateCollectionStrategy.java`, new `service/batch/EveningEligibilityPolicy.java`,
`service/LocationFailureService.java` (and `PipelineRunRepository` only if the newest-settled query
cannot be filtered in the service), a new
`db/migration/V<next>__seed_evening_sunrise_refresh.sql` (re-read the latest number on `main`; V184 at
the time of writing), `backend/src/local/java/.../LocalH2EnumWidener.java`,
`application-example.yml`, the config holder for the new property (a `@Value` on the orchestrator's
constructors at ~178/223/262/304, or a validated properties class like `AskProperties`),
`FreshnessProperties.java` (javadoc only: the T+1/T+0 derivation now names three cycles and E-D8),
`PipelinePhase.java` and `AdminAlertService.java` (javadoc lists of "NIGHTLY or INTRADAY" only).

**Build.**
1. `CycleType.EVENING`: snapshot false, reclassify true, failure evidence NONE. The compiler finds E1's
   switches; there are no others.
2. `EveningCandidateCollectionStrategy`, shaped like `IntradayCandidateCollectionStrategy` (built with the
   orchestrator's `Clock`, `today = ForecastHorizon.today(clock)` fixed at construction):
   `includes(date, t)` is `date.equals(today.plusDays(1)) && t == TargetType.SUNRISE`;
   `bypassesCacheGate()` true.
3. `EveningEligibilityPolicy`: include every stability on the near-term model; javadoc says why it is not
   `IntradayEligibilityPolicy` (§3.1).
4. `photocast.pipeline.evening-safety-timeout`, ISO-8601, default PT2H, bounds PT30M..PT3H30M; out of range
   fails startup. Feeds E1's budget map for EVENING. Listed in `application-example.yml` with its range.
5. `PipelineOrchestrator.runEveningCycle()`, registered in `registerJobTarget()` (~339) as
   `"evening_sunrise_refresh"` (a literal here; E3a moves it into the registry).
6. Migration: one `scheduler_job_config` row modelled on
   `V118__seed_sky_rating_eval_scheduler_job.sql` (`job_key, display_name, description, schedule_type,
   cron_expression, status`): `'CRON', '0 0 20 * * *', 'PAUSED'`, description naming tomorrow's sunrise
   at 20:00 UTC. Testcontainers test `EveningSunriseRefreshSeedMigrationTest` (CI only).
7. `LocalH2EnumWidener.TARGETS` gains `pipeline_run.cycle_type`.
8. `LocationFailureService` (§3.2): in `settleClaimed`, **before** the polling-batch deferral (~394), a run
   whose type's `failureEvidence()` is NONE is claimed with nothing applied and logged at INFO ("cycle
   type EVENING gives no failure evidence"); in `sweepLocked` (~333-350) such a run is never added to the
   unresolved-older list; the newest-settled trigger used at ~419-423 ignores such runs.

**Tests.** The strategy admits exactly (today+1, SUNRISE) and keeps the `today` it was built with when
the clock moves on; the policy includes all three stabilities; the evening's collection does not skip a
region whose cache entry is fresh, while an INTRADAY collection does (back-date the stored
`evaluatedAt`; `hasFreshEvaluation` reads `Instant.now()`, not the clock); the evening runs a
STABILITY_RECLASSIFY phase and submits with `ephemeral = true`; its wait gives up at the evening budget
from first submit; its settle claims the run with no count and no reset, even with a polling batch; a
deferred or RUNNING older EVENING does not stop a newer NIGHTLY tail settling FULL; an EVENING settled
after an INTRADAY does not make that INTRADAY's later tail RESETS_ONLY; out-of-range budget fails startup.

**Extra gate:** `cd backend && ./mvnw -Plocal-dev test -Dtest='LocalH2EnumWidener*'` (with the JDK 23
exports), because the standing gate never compiles `src/local`.

**Must not.** Touch `SolarForecastJobs`, the watchdog or the frontend; resume the job anywhere.

### 9.5 E3a — registry and watchdog (backend)

**Goal.** One registry of scheduled forecast cycles, and a watchdog that watches all three without false
alarms.

**Files (main):** `service/SolarForecastJobs.java` → `service/ForecastCycleJobs.java`,
`service/ForecastCycleWatchdog.java`, `service/SolarSchedulePreviewService.java`,
`service/DynamicSchedulerService.java` and `service/SolarCatchUpPolicy.java` (reference updates only),
`service/pipeline/PipelineOrchestrator.java` (use the registry's constant for the evening key),
`service/AdminAlertService.java` (only if the evening's mail needs its own wording).

**Build.**
1. `ForecastCycleJobs`: a record per job (key, `CycleType`, optional solar rule). `allCycleJobs()` (three)
   and `solarCapableJobs()` (the existing two); `allowed`, `proposedRule` and `cycleOf` keep their
   behaviour for the two existing keys; `allowed`/`proposedRule` are empty for the evening.
2. Watchdog: walk `allCycleJobs()`. The served event and its window come from the record's solar rule,
   never from `proposedRule` for a job without one — `checkJob` (~176) must not call `eventInstant` for
   the evening. *Did not start*: unchanged rule, from the evening's UTC cron. *Did not finish* for the
   evening, per §3.4: FAILED by the safety timeout, or a FORECAST batch outside `TERMINAL` after
   `min(submitted_at) + evening budget` (trigger when no batch), within the run query's existing UK day;
   one mail per (job, run). The evening budget is injected from E2's property.
3. Preview, catch-up and the schedule-type 400 iterate `solarCapableJobs()`; `SolarCatchUpPolicy` and the
   400 already exclude a CRON evening, so they need only the rename and a test each.

**Tests.** Evening: on time (no mail); not started; timed out; batch still polling past budget; RUNNING
past budget with all batches terminal (no mail). The existing watchdog tests unchanged apart from
renamed references. Preview lists two jobs; switching the evening to solar is a 400; catch-up never
queues the evening.

**Must not.** Touch the frontend or add a migration.

### 9.6 E3b — scheduler panel copy (frontend)

**Goal.** The panel stops claiming two cycles can never overlap, and the evening's row stays a plain CRON
row.

**Files:** `frontend/src/components/SolarSchedulePanel.jsx`, its test, a test in
`frontend/src/utils/schedulerSolar` (no change to `schedulerSolar.js` itself: `FORECAST_CYCLE_JOBS` and
`isForecastCycleJob` already exclude the evening; do not rename them).

**Build.** Replace the safety-budget bullet's sentence (~240-244) with exactly: "Each cycle's budget runs
from its first batch submit: 3 h 30 min for the overnight and afternoon cycles, 2 h for the evening
sunrise refresh. From late May to late July the afternoon cycle can still be finishing when the evening
one starts; their briefing builds then run one after the other." Keep "3 h 30 min" (asserted at
`SchedulerViewSolar.test.jsx:508`). Add a test that `evening_sunrise_refresh` renders no CRON ↔ SOLAR
control.

**Review.** UI cadence (CLAUDE.md *UI Work*): the orchestrator runs the review, not the builder.

### 9.7 E3c — docs sweep

**Goal.** CLAUDE.md and this plan describe what E1–E3 built. No code. A changelog file is not needed for
a docs-only PR unless E1–E3's entries left something unsaid.

**CLAUDE.md:** *Dynamic scheduler* (the evening job, PAUSED, CRON-only, its watchdog rule, its budget
property, `ForecastCycleJobs`); *Job metrics* (a cycle type that gives no failure evidence: claimed with
nothing applied, never a blocker, ignored by the newest-settled rule); *Ask PhotoCast* (a fourth scheduled
precompute against the ceiling of 6, if E-D6 is yes); *Configuration* (`evening-safety-timeout`); the
*Database Migrations* table (V-row for the seed, "Proven by CI only"); every sentence saying
`LocalH2EnumWidener` lists only `job_run.run_type` (the *Command pattern* bullet, the Haiku 5.5 note,
*Dev Setup*); every "two forecast cycles" phrase in the *Dynamic scheduler* and *Daily Briefing* bullets;
the freshness caps' three-cycle derivation (E-D8). This plan: status line and §6.

### 9.8 What the orchestrator does between phases

1. Before launching: the previous phase is on `main`; re-read the latest migration number; correct the
   brief's line numbers against the tree.
2. After the agent hands back: read the diff whole; check each listed consumer actually reads its
   property (E1's main risk); re-run the gate from a fresh checkout of the branch; run a read-only
   adversarial review (backend lenses: runtime behaviour, the two existing cycles unchanged, test quality;
   E3b adds accessibility and CSS), mutation probes only in their own worktree.
3. Fix or send back what survives; open the PR; read CI's Backend job (the migration's only proof); wait
   for Codex before any merge.

### 9.9 Model choice

Sonnet builds E1, E2, E3a, E3b and E3c. E1 stays on Sonnet only with its stop-point and seam tests
enforced: it is the phase where a "small" test edit can hide a behaviour change, or an unread property can
pass every test. If an agent reports a stop, the orchestrator decides, not a second agent.
