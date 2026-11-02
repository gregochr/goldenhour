# Stop re-rating sunrise twice overnight

**Status:** planned 2026-11-01, owner approved cuts A and B; reviewed once (findings folded in) ·
**two phases, one PR each**: phase 1 = cut B (intraday), phase 2 = cut A (nightly) · backend only

## Why

A sunrise is rated up to four times in its last day: nightly (T+1), intraday (T+1), evening at 21:00
(T+1), nightly (T+0). Production, last 30 days, consecutive ratings of the same slot:

| Step | Ratings | Cost | Same stars | Moved ≥ 2 |
|---|---|---|---|---|
| SUNRISE nightly → intraday (T+1) | 3,858 | $11.42 | 51.7% | 5.5% |
| SUNRISE evening → nightly (T+0), ~4 h apart | 1,666 | $4.83 | 54.1% | 4.2% |
| SUNRISE intraday → nightly (T+0), nights without an evening run | 1,770 | $4.56 | 52.4% | 6.6% |
| *for contrast:* SUNRISE nightly → nightly (T+1), 24 h apart | 1,554 | $4.40 | 34.3% | 18.3% |

Every same-day step sits at the same level (about half unchanged, about 5% moving two stars or more)
whatever the gap; only the 24 h step moves materially. Same-day sunrise re-ratings behave like the
model's run-to-run noise. Expected saving: phase 1 about $11, phase 2 about $5–9 a month.

Production runs the evening refresh: the 30-day data has 1,897 EVENING ratings and the Runs page shows
"Evening sunrise refresh" cycles. `platform.md` still says the switch-on (E4) "is not done"; correct
that line in phase 1.

## Shared facts (verified by review)

- `IntradayEligibilityPolicy.INSTANCE` is used only at `PipelineOrchestrator.java:481`
  (`runIntradayCycle`); `resolve` runs per candidate at `ForecastTaskCollector.java:461`, after
  weather fetch and triage (:448).
- Evening: CRON `0 0 21 * * *` Europe/London (V204), same collector, triage, travel-day and
  woodland/bluebell lanes as intraday; bypasses the cache gate
  (`EveningCandidateCollectionStrategy.java:45`).
- Force-eval covers T+2/T+3 only (`ForceEvalHeadlineSelector.java:85-91`); neither cut touches it.
- A `SKIPPED_NO_REFRESH_NEEDED` skip never retracts a rating (`ForecastRunDispositionRepository.java:79-85,97-99`,
  `SupersedingDispositionService.java:72-73`); skipped slots keep serving their stored rating.
- "Need attention" counts only DEGRADED/PARTIAL/FAILED runs; skips do not raise it.
- Scheduler status enum: `SchedulerJobStatus` (ACTIVE, PAUSED, DISABLED_BY_CONFIG);
  `SchedulerJobConfigRepository.findByJobKey` (:25); `DynamicSchedulerService.calculateNextFireTime(config)`
  (:490).

## Phase 1 — cut B: intraday stops re-rating tomorrow's sunrise when tonight's evening refresh will

`IntradayEligibilityPolicy` re-rates T+1 sunrise because "its only later run is the next morning's
cycle, which lands while the reader sleeps". That predates the 21:00 evening refresh, which re-rates
every T+1 sunrise at every stability.

**Rule.** In the intraday cycle, skip a T+1 SUNRISE candidate (any stability) only when **both**:

1. **Tonight's evening refresh is due.** Resolved once per cycle in `runIntradayCycle`, not inside the
   policy: the `evening_sunrise_refresh` row exists, its status is ACTIVE, and
   `calculateNextFireTime(config)` is after now and before 23:59 Europe/London today (so a re-cron or
   a sun-relative switch that moves it past bedtime or off today disables the skip). PAUSED,
   DISABLED_BY_CONFIG, a missing row, or any exception reading it → keep rating (log the reason once
   per cycle at INFO).
2. **The location already has a rating for that slot** (from the region's cached evaluation, per
   location). A slot with no rating is rated as today, so no region shows "Not scored" until 21:00
   (`CoverageReasons.of` maps an unrated region's `SKIPPED_NO_REFRESH_NEEDED` to UNKNOWN,
   `CoverageReasons.java:108-140`; `VerdictSampleGate`'s examined-slot count reads the same,
   `EvaluationViewService.java:254-270`). Find the cleanest place to know this per candidate
   (the collector already reads the region cache for the freshness gate).

Every other slot keeps its current behaviour byte-for-byte (T+0 sunset include; T+1 sunset SETTLED
skip; TRANSITIONAL/UNSETTLED T+1 sunset include).

**Shape.** Keep the policy stateless: either a second instance (e.g. `withEveningRefreshDue()`) or a
policy built per cycle with the boolean, chosen in `runIntradayCycle`. The per-location "already
rated" check belongs where the collector has the cache, not in the policy; pass whatever the policy
needs through the existing `resolve` call path or filter in the collector, whichever keeps
`EligibilityPolicy`'s interface simplest. Disposition `SKIPPED_NO_REFRESH_NEEDED`, reason "tonight's
evening refresh re-rates tomorrow's sunrise".

**Labels.** `ReclassSummary` calls every no-refresh skip "settled-skipped" (`ReclassSummary.java:38,54`);
these skips can be TRANSITIONAL or UNSETTLED. Give them their own count or a neutral label.

**What readers see.** An afternoon reader sees tomorrow's sunrise as the overnight rating until 21:00.
If tonight's evening run then fails (`PipelineOrchestrator.java:679-696`; `CycleType.java:75` notes
EVENING has no failure evidence), that rating stays up to ~20 h old until the next overnight run. Say
this in the policy javadoc and `platform.md`; it is the accepted risk of this cut.

**Text that becomes wrong (fix in this PR):**
- `IntradayEligibilityPolicy` javadoc table and `hasGuaranteedLaterLook`.
- `EveningEligibilityPolicy.java:15-18` ("happens to give the same answer").
- `FreshnessProperties.java:85-108` and `application-example.yml` :386-389 where they describe which
  slots intraday covers.
- The intraday job's `scheduler_job_config.description` (V174 says SETTLED T+1 sunrise is
  evaluated): a new Flyway migration updating that description (latest number: read the tree). Write
  it for Postgres; it is proven only in CI.
- `docs/engineering/reference/platform.md`: the evening/intraday text and the stale E4 line.

**Tests (phase 1):**
- Policy: table-driven, 3 slots × 3 stabilities × evening-due true/false; only T+1 sunrise with
  evening due skips.
- Evening-due resolution: ACTIVE with next fire tonight → due; ACTIVE with next fire tomorrow or
  after 23:59 → not due; PAUSED; DISABLED_BY_CONFIG; missing row; repository exception → not due.
- Collector: T+1 sunrise with an existing rating is skipped; without one is collected (coverage stays
  rated, not UNKNOWN).
- `ReclassSummary` label for the new skip.
- Migration: covered by CI's Testcontainers Flyway run.
- `changelog.d/20261101-intraday-skips-tomorrows-sunrise.md`: `### Changed — The afternoon refresh no
  longer re-rates tomorrow's sunrise when the evening refresh will`.

## Phase 2 — cut A: nightly reuses tonight's evening rating of today's sunrise

Do **not** loosen `FreshnessResolver`'s region gate: `hasFreshEvaluation` reads the region's single
`CachedEvaluation.evaluatedAt` (`BriefingEvaluationService.java:293-299`), which every merge resets
(:320, :377, :530, :573), so one evening-rated location would make a whole region look fresh and
leave its other locations on a ~24 h-old rating.

**Rule.** In the nightly cycle, skip a T+0 SUNRISE candidate when **that location's own** stored
result was written by an EVENING cycle for this date within the last 8 h (provenance, not age alone:
on a sun-relative schedule an intraday rating can be only 5–6 h old at the overnight run). Needs the
per-location result's provenance: check whether `BriefingEvaluationResult` already records the
producing cycle or `submittedAt`/pipeline trigger time; if not, add it (it rides `cached_evaluation`
JSON, so it must default safely for old entries → treat unknown as "not evening" → rate).

**Open items to settle in the phase 2 plan review:**
- The admin region-filtered batch uses the nightly strategy (`ForecastTaskCollector.java:746-749`);
  an admin re-run of today's sunrise must not be skipped (bypass for admin-triggered runs).
- A skipped slot skips `fetchWeatherAndTriage`; confirm whether that drops the 01:00
  `slot_atmosphere` write that live inversion/mist hot topics read (`SlotSignals.java:54-60`). If it
  does, fetch weather for skipped T+0 sunrise slots anyway (cheap) and skip only the Claude call.
- Triage flips: a location stood down at 21:00 has no evening result, so it is rated at 01:00 (safe).
- Callers of `FreshnessResolver.maxAgeFor`: none change in this design (the 2-arg gate at
  `BriefingCandidateCollector.java:199` and the 1-arg stamps at `BriefingEvaluationService.java:737`,
  `BriefingCandidateCollector.java:274,289` stay as they are).

Phase 2 gets its own plan revision and review before build.

## Gates

Backend build ladder through `clean verify` (integration exclusion) for each phase. No frontend
change. Each phase: one PR, CI green and Codex 👍 on the head commit before merge.

## How we will know it worked

After a week, re-run the cycle-step query: SUNRISE `NIGHTLY -> INTRADAY (T+1)` falls to near zero
after phase 1; SUNRISE `EVENING -> NIGHTLY (T+0)` after phase 2; batch sky spend falls accordingly.
