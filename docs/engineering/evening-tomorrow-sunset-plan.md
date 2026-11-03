# The evening refresh takes tomorrow's sunset; the afternoon keeps today's sunset

**Status: PLANNED and reviewed 2026-11-03; E-D2 decided by the owner the same day (*include*); P1 not started.** Owner's proposal, 3 November 2026. This replaces E5 of
`evening-sunrise-refresh-plan.md` (§9.10), which would have *dropped* the afternoon look at tomorrow's
sunset. Here that look *moves* to 21:00 instead. Written against `main` at `d319c606`.

## 1. Why

The owner plans the next day in the evening. Today the freshest look at tomorrow's sunset before then is
the afternoon cycle's (`intraday_forecast_refresh`, sunset − 240 min, about 13:00 UK in November). The
21:00 evening refresh (`evening_sunrise_refresh`) already re-rates tomorrow's sunrise for the alarm
decision. It can re-rate tomorrow's sunset in the same run, about **8 hours later** than the afternoon
look.

**Which weather the 8 hours buys (checked 2026-11-03).** `OpenMeteoClient` sends no `models=` (best
match). At Durham, best match's low/mid/high cloud matched `ukmo_uk_deterministic_2km` (Met Office UKV)
value for value, and none of ECMWF, ICON or GFS. Open-Meteo's run metadata
(`https://api.open-meteo.com/data/<model>/static/meta.json`, fields `last_run_initialisation_time` and
`last_run_availability_time`) gave UKV a new run **every hour, available about 4.5 h after its start**,
against 6-hourly runs for ECMWF IFS (~8 h delay), GFS (~6.3 h) and UKMO global (~7 h). So every fetch
sees a UKV run about 4.5–5.5 h old, and there is no model-cycle cliff to time a job around: 21:00 gets
roughly the 16Z run, 13:00 the 08Z run, the overnight at ~02:50 the 22Z run. The gain is the 8 hours,
in proportion. Caveats: one point checked (every location is inside the UKV domain, so the same is
expected elsewhere, unproven); UKV runs about 2 days ahead, so T+2 and T+3 fall back to the 6-hourly
global model, where a cycle does matter.

The afternoon then has nothing left to do for tomorrow, and becomes the last look at **today's sunset**,
which is what it is timed for.

**The number of looks does not change; one moves.** Wednesday's sunset, as an example:

| Look | Today | After this plan |
|---|---|---|
| Overnight Tue ~03:00 (T+1, ~37 h ahead) | ✓ | ✓ |
| Afternoon Tue ~13:00 (T+1, ~27 h ahead) | ✓ | — (deferred to 21:00) |
| **Evening Tue 21:00 (T+1, ~19.5 h ahead)** | — | **✓** |
| Overnight Wed ~03:00 (T+0, ~13.5 h ahead) | ✓ | ✓ |
| Afternoon Wed ~13:00 (T+0, ~3.5 h ahead) | ✓ | ✓ |

**Cost: about neutral.** The afternoon's T+1 sunset share (~104 calls, ~$0.29 a cycle when measured in
October, SETTLED already skipped) moves to the evening. Neutral only if the evening keeps the afternoon's
SETTLED rule for that slot; under E-D2's recommended *include* it costs the SETTLED share on top
(unmeasured; see E-D2). Neutrality also needs §2's change 4, without which nothing is deferred. Measure
after shipping with `cost-queries/measured-costs.sql`.

**What it gives up:** during the day, tomorrow's sunset shows the overnight's ~37 h rating instead of a
13:00 one, the same position tomorrow's sunrise has been in since #1601. It does not reverse the ledger's
"cutting cadence further: rejected" decision, because no look is removed.

## 2. How it works (the design)

The pieces already exist, from the evening refresh series (E1–E3) and #1601. Three changes:

1. **`EveningCandidateCollectionStrategy.includes`** admits tomorrow's sunset as well as tomorrow's
   sunrise. `bypassesCacheGate()` stays `true` and now covers both (E-D4).
2. **`EveningEligibilityPolicy`**: under E-D2 *include* (the recommendation), `resolve` is unchanged,
   every stability on the near-term model for both events, and only its Javadoc changes. Under *skip*,
   SETTLED tomorrow's sunset is skipped with `SKIPPED_NO_REFRESH_NEEDED`, which does **not** retract
   (`ForecastRunDispositionRepository.java:263,295`: only `SKIPPED_STABILITY` and `SKIPPED_TRIAGED` do),
   so the overnight's rating keeps serving.
3. **`IntradayCandidateCollectionStrategy.defersRatedSlot`** defers tomorrow's sunset as well as
   tomorrow's sunrise when the evening refresh is due tonight, under exactly today's rules: only a slot
   that already holds a live rating, only after weather fetch, triage and the eligibility policy (so a
   triage stand-down still records `SKIPPED_TRIAGED` and retracts), only in the sky lane, and only when
   `DynamicSchedulerService.firesLaterTodayUk(EVENING_JOB)` is true (E-D1).
4. **`ForecastTaskCollector.LiveRatings.load()`** (`ForecastTaskCollector.java:~708-714`) loads live
   ratings for `Set.of(TargetType.SUNRISE)` only, so `hasLiveRating` is always false for a sunset and
   change 3 would never fire: the afternoon would go on rating tomorrow's sunset and the evening would
   rate it again (found in review; a test stubbed with `any()` would not notice). Widen it to
   `Set.of(SUNRISE, SUNSET)`. This is the one change allowed inside `ForecastTaskCollector`; the
   deferral block itself (`:505-528`) stays as it is.

**Canopy lanes are not deferred.** Woodland and in-season bluebell slots are excluded from the deferral
(`ForecastTaskCollector.java:511`), as they are for the sunrise today, so a non-SETTLED canopy T+1 sunset
is rated at 13:00 and again at 21:00. Accepted as a small double look; "the afternoon rates today's
sunset only" is true of the sky lane.

**Why deferral and not removal (E-D1).** The old E5 removed T+1 from the afternoon's
`includes`. Deferral is the same thing on a normal day (every T+1 slot is already rated by the overnight,
so the afternoon rates today's sunset only), and it degrades safely: with the evening PAUSED, missing or
throwing, the afternoon rates tomorrow's sunrise and sunset as it does today, and a slot with no rating
(a new location, a failed overnight) is rated at 13:00 rather than waiting for 21:00. No new config
switch is needed: pausing `evening_sunrise_refresh` restores today's afternoon behaviour.

**Accepted risk, extended (owner to confirm).** If the evening run fails after the afternoon has deferred
to it, tomorrow's sunset keeps the overnight rating (~37 h ahead) until the next overnight (~13.5 h
ahead). This is the risk #1601 accepted for tomorrow's sunrise; it now covers tomorrow's sunset too. The
watchdog's evening check mails the admins when the run hits its safety timeout or a batch is still out
past its budget (`ForecastCycleWatchdog.java:59-64`); a failure in the submit step or an aborted weather
pre-fetch is not covered by that check. `CycleType.EVENING.failureEvidence()` is NONE, so per-location
failure counting also loses the afternoon's T+1 sunset evidence (accepted, as for the sunrise).

**Summer: the overnight can lose today's sunset for a whole region (found in review; owner to accept).**
The CACHED gate is per region and event (`BriefingCandidateCollector.java:191-203`, key
`region|date|event`), and any merge restamps `evaluatedAt` (`BriefingEvaluationService.java:298,377`). In
June the overnight fires about 00:13 BST, when tomorrow's sunset has become today's (T+0, the 2 h
`t0Hours` cap), so an evening sunset result landing after about 22:13 BST makes the overnight treat that
region's sunset as cached. With E-D2 "skip", one non-SETTLED location re-rated at 21:00 stamps the
region and its SETTLED neighbours get neither the evening look nor the overnight one, keeping a rating
made about 45 h ahead until the afternoon. Before this plan the last T+1 sunset write was the
afternoon's (~17:30 BST), more than 2 h before the overnight, so it never happened. In winter the
evening result lands about 6 h before the overnight and there is no issue. With E-D2 "include", every
location gets the 21:00 look and this shrinks to E-D8's accepted case (the morning look *is* the
evening's). P1 updates the `FreshnessProperties` three-cycle note (`:112-121`) to say so.

## 3. Decisions

- **E-D1 — Defer, not remove.** As §2. Recommended; the old E5 brief (§9.10 of the evening plan) is
  superseded and gets a pointer here.
- **E-D2 — SETTLED tomorrow's sunset at 21:00: include (recommended after review) or skip.**
  - *Skip* is cost-neutral and keeps today's looks for SETTLED cells (overnight T+1, overnight T+0,
    afternoon T+0) in winter. But "two further looks are guaranteed" is false in summer (§2's summer
    note): a SETTLED cell in a mixed region can lose the overnight look as well and keep a ~45 h rating
    until the afternoon.
  - *Include* makes 21:00 the planning look for every cell and removes the summer gap down to E-D8's
    accepted case. It costs the SETTLED share of tomorrow's sunset, which was never measured (the
    October ~104 calls are the non-SETTLED share). The afternoon runs' STABILITY_RECLASSIFY detail
    already counts it ("N settled-skipped", nearly all tomorrow's sunset): read a week of those before
    deciding.
  - **Decided 2026-11-03 by the owner: include.** The brief is written for *include* (the policy stays "every stability, never
    skips" for both events, so `EveningEligibilityPolicy.resolve` does not change, only its Javadoc);
    *skip* is the SETTLED-sunset rule of `IntradayEligibilityPolicy` copied in, with its test.
- **E-D3 — Keep the job key `evening_sunrise_refresh`.** It is in the database, the scheduler hash routes
  (`#manage/scheduler/<key>`), `ForecastCycleJobs`, the watchdog latch keys and many fixtures. Renaming it
  buys nothing a reader sees. The *names people read* change instead (§4).
- **E-D4 — One cache-gate bypass for both slots.** The evening is a deliberate refresh; the reason it
  bypasses the CACHED gate for the sunrise (the afternoon's batch can land inside the T+1 cap of the
  evening's start in summer) applies to the sunset identically.
- **E-D5 — No change to the overnight cycle's code, the watchdog's logic, the budget or the schedule.**
  (The overnight's *behaviour* can change in summer through the CACHED gate: §2's summer note.) The evening
  budget (`photocast.pipeline.evening-safety-timeout`, PT2H) is ample: the first evening run took 14
  minutes for ~170 slots, and this adds ~100.

## 4. The narrative: every place that describes the jobs

The owner asked for the Scheduler page to describe the jobs accurately. These are all the places a
person reads a description of the three cycles, found by grep on `main` at `d319c606`:

**Scheduler page (Operations → Scheduler), served by the backend:**
- `SchedulerJobCatalogue.declare()`:
  - `intraday_forecast_refresh`, "The afternoon refresh". Today's what-text also carries an existing
    error: "skips places that are settled" is true only of tomorrow's sunset
    (`IntradayEligibilityPolicy.java:81,110`); tonight's sunset is rated at every stability. New
    what-text: *"The last look before tonight's sunset. Re-rates tonight's sunset everywhere, then
    rebuilds the briefing. Tomorrow's sunrise and sunset are left to the evening refresh."* New
    when-text: *"Fires a set time before sunset, so there's still time to change plans. If the evening
    refresh is paused, or a place has no rating yet for tomorrow, it rates tomorrow too, skipping a
    settled sunset."*
  - `evening_sunrise_refresh`, "Tomorrow's sunrise, at bedtime": new title **"Tomorrow, at bedtime"**;
    new what-text: *"Re-rates tomorrow's sunrise and sunset at bedtime, so tonight's decisions (an early
    alarm, tomorrow evening's plan) rest on a fresh forecast."* (Under E-D2 *skip*, add: *"The sunrise is
    re-rated everywhere; the sunset skips places that are settled."*) When-text unchanged.
  - `near_term_batch_evaluation`, "The overnight forecast cycle": unchanged (still accurate).
  - The watchdog's catalogue text (`SchedulerJobCatalogue.java:322-325`) is still accurate.
- **Which text is prominent.** `display_name` is shown widely: the panel title and All-jobs row
  (`SchedulerJobPanel.jsx:96`, `AllJobsTab.jsx:143`), the day picture (`SchedulerDayService`), neighbour
  warnings (`SchedulePreviewService:235`) and the overview (`SchedulerJobOverviewService:189`). The
  catalogue texts are the panel's main description. The DB `description` shows only as "Stored text" in
  the panel's Technical section (`JobPanelSections.jsx:331`), because these jobs have a catalogue entry.
- **The watchdog alert email** (`ForecastCycleWatchdog.java:305-307`): "…so tomorrow morning's sunrise
  may not carry the evening look" becomes "…so tomorrow's sunrise and sunset may not carry the evening
  look". The message string is in scope; the watchdog's logic is not.
- `scheduler_job_config.description` and `display_name` (the row's own text in the job panel): one new
  migration, `V217__evening_refresh_takes_tomorrow_sunset.sql` (re-read the latest number on `main`),
  that updates:
  - `intraday_forecast_refresh.description`, guarded on V212's exact text;
  - `evening_sunrise_refresh.description`, guarded on V204's exact text
    (`Re-evaluates tomorrow''s sunrise at 21:00 UK time all year, so the bedtime decision rests on a fresh look`);
  - `evening_sunrise_refresh.display_name` from `Evening Sunrise Refresh` to `Evening Refresh`, guarded
    on the old value.
  Same shape as V141, V174 and V212 (an applied migration is never edited; a hand-edited row is left
  alone). Neither column is `updatable = false` (`SchedulerJobConfigEntity.java:44-49`). The intraday
  text gets the same correction as the catalogue (tonight's sunset at every stability) and must stay
  under the 480 characters `IntradayJobDescriptionMigrationTest:146` enforces (the column is
  `varchar(500)`); `display_name` is at most 100. Proved by a Testcontainers test,
  `EveningRefreshDescriptionMigrationTest`, on `IntradayJobDescriptionMigrationTest`'s pattern
  (`flyway(target)` from V216 to V217, exactly one column on one row changed per update): three
  updates, each with its own guard and its own hand-edited-row case.
- `frontend/src/components/scheduler/JobNextFires.jsx` (~line 283, the safety-budget note): "2 h for the
  evening sunrise refresh" → "2 h for the evening refresh".

**Cycle names on the Runs and Scheduler views** ("Evening sunrise refresh" → "Evening refresh"):
- backend `RunClassifier.cycleName` (`case EVENING`), `RunDeepType.EVENING`'s label;
- frontend `utils/runsCost.js`, `utils/cycleView.js` (both key on the type `EVENING`, never the label,
  and prefer the served backend label, so the rename is safe);
- tests that pin it: backend `RunClassifierTest:81`, `SchedulerRunsServiceTest:124`,
  `ScheduledFiresListerTest:82`, `RunsDayServiceTest:270` and `:624` ("Runs at the end of …"); frontend
  `test/runsCostTimingFixtures.js:13` (the title at `SchedulerViewSolar.test.jsx:228` is cosmetic).

**The STABILITY_RECLASSIFY phase detail** (Runs → a run's phases): `ReclassSummary` counts deferrals by
matching `IntradayCandidateCollectionStrategy.EVENING_DEFERRAL_REASON` exactly. The reason text becomes
event-neutral, **"tonight's evening refresh re-rates it"**, one constant for both slots, so the counting
stays a single equality and the detail ("N left for tonight's evening refresh") stays true. Old
dispositions in `forecast_run_disposition` keep the old text; they are only counted for the run that
wrote them, so no backfill is needed. Checked by review: the only writer is `ForecastTaskCollector`
(`:519,524`), the only reader `ReclassSummary`; the stored `detail` is read at serve time only by
`WeatherTriageEvaluator.isLowVisibilityReason` (`EvaluationViewService.java:367`), a triage-prefix check;
no SQL under `cost-queries/` and no frontend code matches it. `ForecastTaskCollectorEveningDeferralTest:124`
pins the literal and is updated. The constant's Javadoc (`IntradayCandidateCollectionStrategy.java:38`)
and the constructor `@param` (`:60`) are reworded.

**Javadoc and comments that say "tomorrow's sunrise alone"** (accuracy, no behaviour):
`EveningCandidateCollectionStrategy`, `EveningEligibilityPolicy` (including "this policy must never learn
to skip", which becomes "never skips tomorrow's sunrise"), `IntradayCandidateCollectionStrategy` (class
note and `defersRatedSlot`), `IntradayEligibilityPolicy` (the evening-refresh note), `CycleType`,
`PipelinePhase`, `PipelineOrchestrator.runEveningCycle`/`runIntradayCycle`, `FreshnessProperties` (the
three-cycle note), `ForecastCycleWatchdog` class note, `ForecastCycleJobs.java:29`,
`DynamicSchedulerService.java:~823` (check), `application-example.yml`'s `evening-safety-timeout`
comment (lines 351-352; the "bedtime decision has passed" line at 354 is still accurate),
`frontend/src/utils/schedulerSolar.js:13` comment.

**Reference docs (same PR as the behaviour, per CLAUDE.md):** `docs/engineering/reference/platform.md`'s
long scheduler paragraph: "Its candidates are tomorrow's sunrise alone", the deferral reason quoted
verbatim, and the accepted risk "overnight rating (up to about 20 h old)", which for the sunset becomes
about 37 h (§2). `reference/pipeline.md` and `reference/configuration.md` have no sunrise-only wording.
`claude-cost-ledger.md` (a "Done" row once shipped), and a pointer at the top of
`evening-sunrise-refresh-plan.md` §9.10 to this plan.

## 5. Phases

One phase, one PR. The behaviour is three small changes that must land together (an evening that rates
the sunset without the afternoon deferring it double-spends; an afternoon that defers without the evening
rating it leaves a gap), and the narrative must change in the same PR or the Scheduler page describes the
wrong jobs.

**P1 — `feature/evening-tomorrow-sunset`.** Built by one Sonnet agent in its own worktree; the planner
reviews the diff read-only, then adversarially (backend lenses: runtime behaviour, the overnight cycle
unchanged, retraction semantics, test quality; plus a copy and accessibility lens on the two frontend
strings), and sends fixes back.

## 6. Build brief (P1)

**Goal:** §2 and §4, nothing else.

**Main files:** `service/batch/EveningCandidateCollectionStrategy.java`,
`service/batch/EveningEligibilityPolicy.java`, `service/batch/IntradayCandidateCollectionStrategy.java`,
`service/batch/ReclassSummary.java` (only if the constant's use needs it),
`service/batch/ForecastTaskCollector.java` (`LiveRatings.load()` only, §2 change 4),
`service/SchedulerJobCatalogue.java`, `service/runs/RunClassifier.java`, `model/runs/RunDeepType.java`,
the Javadoc-only files of §4, `db/migration/V<next>__evening_refresh_takes_tomorrow_sunset.sql`,
`frontend/src/components/scheduler/JobNextFires.jsx`, `frontend/src/utils/runsCost.js`,
`frontend/src/utils/cycleView.js`, `frontend/src/utils/schedulerSolar.js` (comment), the reference docs
of §4, `changelog.d/YYYYMMDD-evening-refresh-tomorrow-sunset.md` (`### Changed — …`).

**Do not touch:** `NightlyEligibilityPolicy`, `NightlyCandidateCollectionStrategy`,
`IntradayEligibilityPolicy.resolve` (behaviour), `ForecastTaskCollector` beyond `LiveRatings.load()`'s
event set (the deferral block already asks the strategy; it must not grow an event check of its own), the watchdog's logic (its alert
message text is in scope, §4), the job key, the
cron, the budget, prompt-regression tests.

**Tests to write or change (read `docs/engineering/test-improvement-standards.md` first):**
- `EveningCandidateCollectionStrategyTest`: tomorrow's sunrise and sunset in; today's sunset, today's
  sunrise, T+2 either event, HOURLY out; `bypassesCacheGate()` true. Pinned clock either side of UK
  midnight and on a clock-change day.
- `EveningEligibilityPolicyTest` (E-D2 *include*): both events included at every stability on the
  near-term model, never the far-term one. (Under *skip*: sunset SETTLED skipped with
  `SKIPPED_NO_REFRESH_NEEDED` and the intraday policy's reason text.)
- `IntradayCandidateCollectionStrategyTest`: with the evening due, defers tomorrow's sunrise **and**
  sunset, never today's sunset; with it not due, defers nothing; `includes` unchanged.
- `ForecastTaskCollectorEveningDeferralTest` (existing deferral tests extended, not replaced; its
  literal at `:124` updated to the new reason): a rated T+1 sunset is
  `SKIPPED_NO_REFRESH_NEEDED` with the deferral reason when the evening is due; an unrated one is
  evaluated; a triaged one is `SKIPPED_TRIAGED`; a SETTLED one is skipped by the policy (not counted as a
  deferral); woodland and in-season bluebell slots are not deferred. Stub
  `getLiveScoresForEnrichmentBulk` with the **exact** set `Set.of(SUNRISE, SUNSET)`, never `any()`, and
  update `ForecastTaskCollectorMutationGapsTest:784`, which stubs `Set.of(SUNRISE)`.
- `ReclassSummaryTest`: sunrise and sunset deferrals both counted in `deferredToEvening`.
- `SchedulerJobCatalogueTest`: the new texts, and no catalogue text still says "sunrise only" for the
  evening.
- Migration: a Testcontainers test (under `integration/`, runs in CI only) that the guarded updates
  change the seeded rows and leave a hand-edited row alone.
- Label tests: the backend and frontend tests listed in §4 updated to "Evening refresh".
- Browser check on the local backend (port 8083): the Scheduler panel and All-jobs row for both jobs,
  and the Runs view's cycle name. Say plainly what was seen and what was only tested.
- A mutation check for the three behaviour lines (`includes`, `defersRatedSlot`, `LiveRatings.load()`'s
  event set): break each, see a test
  fail, restore **with `cp`** from a backup, never `git checkout --`.

**Gates (until green, exit codes, not greps):** the build ladder in CLAUDE.md through
`clean verify` with the integration exclusion; frontend `npm run lint && npm test && npm audit
--audit-level=high && npm run build`.

**Stop and report, do not improvise, if:** the deferral needs a change inside `ForecastTaskCollector`
other than `LiveRatings.load()`'s event set;
any reader of the deferral reason string other than `ReclassSummary` (reader) and
`ForecastTaskCollector` (writer) exists; `EveningCandidateCollectionStrategyTest:30` (asserts T+1 sunset excluded) is expected to change; any
other test asserting the evening is sunrise-only is a stop; the
migration's guard text does not match production's seeded text as found in V204/V212.

## 7. After it ships

- The first evening run after deploy: Runs → that run's STABILITY_RECLASSIFY detail shows sunset
  slots considered; the afternoon run before it shows sunset slots "left for tonight's evening refresh".
- A week later: `measured-costs.sql`, and a "Done" row in `claude-cost-ledger.md` with the measured
  afternoon and evening cost against the October figures in §1.

## 8. Follow-up, not in P1: does the overnight still need today's sunset?

Owner's question, 3 November 2026. After P1, today's sunset has three looks: 21:00 the night before
(~19.5 h ahead), the overnight (~13.5 h) and the afternoon (~3.5 h). The afternoon replaces the
overnight's rating anyway, so the overnight's only job for that slot is what readers see for tonight's
sunset from ~03:00 to ~13:30: the morning Plan tab, the briefing and the morning picks. Dropping it would
save the sunset share of Nightly T+0 (about $19 per 30 days for both events; the sunset share is not yet
split, perhaps $8–10) at the cost of a morning rating built on weather about 6 h older.

**Measure first, after P1 has run for about two weeks:** for each slot, compare the evening T+1 sunset
rating with the overnight T+0 rating that replaced it (star change, and crossings of the 3★ line), and
split Nightly T+0 cost by `target_type`. If the overnight rarely moves the stars across 3★, propose the
change as its own plan; if it often does, the morning look earns its cost. Record the result in
`claude-cost-ledger.md` either way.

## 9. Review record

2026-11-03, two read-only reviewers on the first draft.

- **Runtime:** one blocker, folded in as §2 change 4: `LiveRatings.load()` loaded sunrise ratings only,
  so a sunset could never be deferred and would have been rated twice. Should-fixes folded in: the summer
  CACHED-gate gap for today's sunset (§2), which moved E-D2's recommendation from *skip* to *include*;
  the watchdog's coverage and `failureEvidence()` NONE (§2, accepted risk); canopy lanes not deferred
  (§2). Verified: triage, policy and deferral ordering; no retraction from `SKIPPED_NO_REFRESH_NEEDED`;
  the fail-safe when the evening is paused or the lookup throws; nothing else in the evening path
  assumes sunrise; V204's guard text; V217 is the next number.
- **Narrative and brief:** folded in: the watchdog alert email; the afternoon catalogue text's existing
  "skips places that are settled" error; the backend tests that pin the cycle label; the deferral
  string's real readers and writer; the migration test's shape and length limits; missed comments and
  the `platform.md` sentences; a browser check. Confirmed: the label is never a key; `display_name` is
  the prominent text; the single-PR choice.
