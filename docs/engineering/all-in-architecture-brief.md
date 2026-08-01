# All-in architecture — design brief

**Status:** brief, not a design. Written 2026-07-31 as a handover from a long investigation session.
**Audience:** whoever picks up the "put everything through Claude" redesign.

Read §0 and §2 before anything else. §0 frames how to approach the existing code. §2 records findings
from that session that were stated confidently and turned out to be wrong — two of them twice. The
failure mode was always the same shape, and it will be available to you too.

---

## 0. Why the code is the shape it is

**This complexity is accretion, not a design failure.** The app grew: a single-location colour
forecast became multi-location, then multi-subject (wildlife, waterfall, woodland, bluebell), then
gained a briefing layer, an aurora pipeline, a batch engine alongside the original synchronous one,
and a verification harness. Each gate, each cache, each snapshot was a reasonable answer to a real
problem at the time it was added — usually cost, when cost was less well understood than it is now.

Two things follow, and they matter for how you approach this:

1. **Don't read the existing code as mistaken.** The stability subsystem earns 1.7% today; when it
   was written nobody had measured that, and £193/year of guarded spend was a plausible thing to
   guard. The T+3 defect came from V103 retiring a cron for good reasons and not noticing a
   downstream read. These are the ordinary costs of an app that changed shape while running.
2. **The redesign's job is subtraction, not correction.** Most of what follows is "this earned its
   place once and no longer does", not "this was wrong". That distinction should show up in commit
   messages and in what you choose to preserve — several of these subsystems encode real knowledge
   in their comments even where the mechanism should go.

The measurements in §1 exist because this is now measurable in a way it wasn't when the code was
written. That is the actual change — not that the design was bad, but that the evidence arrived.

---

## 1. What was decided, and on what evidence

All figures are from production Postgres over the stated window. Nothing here is modelled or assumed.
The queries are in §6 if anything needs re-deriving.

### 1.1 Don't build a pipeline state machine

Measured over 30 days (`pipeline_run`): **60 cycles, 1 failure.** 30/30 nightly completed, 29/30
intraday, one intraday failure at `FORECAST_BATCH_WAIT`.

Every failure mode a state machine would address measured **zero**: restart abandoning a cycle,
missed fires with no catch-up, all-FAILED batch sets reporting green, two concurrent cycles, orphaned
`RUNNING` rows. The one defect the data cannot rule out is a zero-batch cycle reporting success —
and that is visible in `forecast_run_disposition` anyway.

An earlier analysis claimed ~22 reachable states. That count was inflated: it treated
`PipelinePhaseStatus` as a state dimension, but that field is written in two places
(`PipelineRunService:102`, `:121`) and read in one — an admin DTO (`PipelineRunController:117`).
Nothing branches on it. The honest figure is **~6**: the equivalence classes induced by
`current_phase`, which is the only thing any transition function reads.

`current_phase` is a **high-water mark by design** — `startPhase` writes it, `completePhase`
deliberately leaves it, `completeRun` clears it. Do not "fix" that. Clearing it on completion turns
three resumable states into abandoned cycles, including the one where a clean cycle rests.

### 1.2 Drop the cost gates, keep the intent gates

From `forecast_run_disposition`, 30 days:

| Disposition | Nightly | Intraday | Kind |
|---|---|---|---|
| `SKIPPED_TRAVEL_DAY` | 20.0% | **52.8%** | intent — **keep** |
| `SKIPPED_HARD_CONSTRAINT` | 2.4% | 1.4% | physics — **keep** |
| `SKIPPED_PAST_DATE` | 25.0% | — | mechanical — see §3.7 |
| `SKIPPED_TRIAGED` | 12.5% | 12.0% | **keep** — see §2.3 |
| `SKIPPED_CACHED` (freshness) | 7.0% | 8.7% | cost — **drop** |
| `SKIPPED_NO_REFRESH_NEEDED` (SETTLED) | — | 13.0% | cost — **drop** |
| `SKIPPED_STABILITY` | **1.7%** | — | cost — **drop** |
| `EVALUATED` | 31.1% | 12.1% | |

**Total Anthropic spend: $40.69 over 30 days** (`api_call_log`), averaging $0.00206/call. That window
included roughly 15 travel days; the at-home run rate is closer to **£51/month**.

Priced at the measured rate, **all the cost gates together guard ~£193/year.** The stability
subsystem alone — `ForecastStabilityClassifier`, `GridCellStabilityService`,
`StabilitySnapshotProvider`, `stability_snapshot` (V98), the `ephemeral` seam — earns **£16/year**.

Going all-in adds roughly **+28% nightly evaluations and ~3× intraday**, or **£20–30/month**.

**If stability goes, two things it also does must be re-homed:** model selection (NEAR→Sonnet,
FAR→Haiku) and the freshness TTLs (SETTLED 36h / TRANSITIONAL 12h / UNSETTLED 4h). A design that
deletes stability without answering both is incomplete.

### 1.3 Shipped during the session

- **#367** (merged) — `BRIEFING_WINDOW_DAYS` 4 → 5. The nightly cycle could never reach T+3, because
  the briefing is written at a cycle's *tail* and read by the *next* cycle, by which point its first
  date is yesterday and is dropped as `PAST_DATE`. Confirmed by telemetry: **zero candidates at
  `days_ahead = 3` across 28 cycles, and 5,684 at `days_ahead = -1`.**
- **#370** (open) — popup score bars. Correct *only because* #368 landed first; see its PR body.
- **#371** (open) — cache-floor guard plus three stale doc fixes.

---

## 2. Retractions — read before trusting anything

### 2.1 The ERA5 "calibration curve" does not support recalibration

A 25,730-row ERA5 comparison appeared to show the forecast massively over-predicting low cloud
(forecast 92.5% → observed 60.0%), and a calibration curve was derived from it.

**This had already been adjudicated three days earlier.** Commit `2806d7d4` (#336, 2026-07-28)
measured the same **+24.67pp** offset, tested it against lead time, found it **flat** (T+0 +25.19,
T+1 +25.52, T+2 +21.01), and ruled it a *baseline artefact* — genuine forecast error grows with lead
time; a dataset offset does not. The ruling is carried in `CloudVerificationBucket.java:11-21`. The
commit message ends: *"Acting on it would have weakened the veto on an artefact."*

**Offset-corrected, the current thresholds are roughly right:** `E[true|f] ≈ 37.6 + 0.581f`, so f=60
implies ~72% real cloud and f=80 implies ~84%. Candidate designs built on the curve would have moved
thresholds **20–40 points in the wrong direction.**

### 2.2 "The cloud-approach veto is inverted" measured rows where the veto never ran

Triage fires at `solarLow > 80` (`WeatherTriageEvaluator:42`). The veto-fired buckets had mean
forecast **81.6 and 84.5** — above that cut. Triaged rows persist
`new SunsetEvaluation(null, null, null, null)` (`ForecastService:371`): **`rating` is null and no
prompt was ever built.** The comparison was matched on forecast value, which is why it looked clean —
but matching does not help when the population never reached the mechanism being tested.

### 2.3 Triage should stay at 80

This reverses a claim made earlier in the same session. Two independent reasons:

1. Offset-corrected, f=80 implies ~84% real cloud. The gate is roughly right.
2. **Even if you loosened it, nothing visible would change.** Newly admitted slots have solar low in
   (80, 93] — all above 60 — and `PromptBuilder` reads *">60% = BLOCKED … non-negotiable …
   rating 1-2"*. The Plan-screen outcome is bit-identical to being triaged, at the cost of one Claude
   call each. **This is the sharpest constraint on "put everything through Claude" — see §3.1.**

### 2.4 The verification harness is structurally blind to scored slots

`src/main/java/.../service/batch/` (27 classes) never references `ForecastEvaluationEntity`. Batch
results go to `cached_evaluation` via `ForecastResultHandler`. The only `forecast_evaluation` inserts
on the batch path are **triage rows**.

So of 25,730 verified rows, roughly 22,251 are triaged and 3,520 are not — and that remainder arrives
via non-cloud triage rules or the admin sync engine. **Forecast deciles below 80 therefore contain
zero `HIGH_CLOUD` rows by construction.** The apparent "reversal" at decile 70→80 sits exactly on that
seam: a composition change between two selection regimes, not a physical effect.

**This is directly relevant to §3.4** — a unified forecast table is the natural fix.

### 2.5 The upwind distance cap (D7) is closed

`MAX_UPWIND_DISTANCE_M = 200_000` makes no difference: the veto effect is **−14.0pp both capped and
uncapped**, identical to one decimal place. Capping looked significant three separate times and
dissolved each time — first confounded with lead time, then wind regime, then sample size.

### 2.6 Three doc claims that misdirected the work

Fixed in #371. Note the pattern: every doc claim about this subsystem that was actually checked turned
out to be wrong.

- `BatchRequestFactory` said "~3,600-token system prompt" — it is ~4,320, and 3,600 is *below*
  Haiku's cache floor, so the comment argued the opposite of the truth about its own `cache_control`.
- `CycleType` said INTRADAY was "reserved … do not use yet" — false since V105.
- `CLAUDE.md` described `AuroraStateCache` as a four-state FSM — it is `enum State { IDLE, ACTIVE }`.

### 2.7 The failure mode to watch for

Every retraction above has the same shape: **a statistic computed over a population selected by the
very thing being measured.** If an aggregate excites you, check what selected the rows before you
check anything else.

---

## 3. The architecture ask, with what is already known about each point

### 3.1 "Put everything through Claude"

**Refine this.** Two different kinds of gate are in scope and only one should go:

- **Re-evaluation gates** (stability, freshness/`SKIPPED_CACHED`, SETTLED skip) skip a slot because it
  was *recently scored*. **Drop them.** ~£193/year, and they produced the T+3 defect, the `ephemeral`
  seam, and the freshness collision that blocks retiming the intraday cycle.
- **Hopeless-slot gates** (triage) skip a slot because the sky is blanketed. **Keep.** Per §2.3,
  admitting them produces bit-identical output at one Claude call each.

Cost of the refined version: **£20–30/month.**

### 3.2 Remove redundant code

Deletable once the re-evaluation gates go: `ForecastStabilityClassifier`, `GridCellStabilityService`,
`StabilitySnapshotProvider`, `StabilitySnapshotEntity`/repository, the `ephemeral` seam and the whole
`SnapshotAuthority` question, `FreshnessProperties` plus the `SKIPPED_CACHED` gate in
`BriefingCandidateCollector`, `IntradayEligibilityPolicy`, and much of `NightlyEligibilityPolicy`.

Also worth auditing: `OptimisationSkipEvaluator` and the 7 optimisation strategies (V41/V42/V52/V54)
with their admin UI, and `evaluation_delta_log` (V97 — built to tune the freshness thresholds you
would be deleting).

**Re-home first:** model selection and the freshness TTLs (§1.2).

### 3.3 Drop unneeded database objects

Candidates follow from §3.2: `stability_snapshot` (V98), `evaluation_delta_log` (V97), the
optimisation strategy rows. **Do not drop `forecast_evaluation` history** — see §3.4.

### 3.4 Single table: most recent forecast per location

The most consequential item, and it interacts with §2.4.

**The opportunity.** Batch-scored results never reach `forecast_evaluation`, which is why the ERA5
harness can only see triaged rows. A unified forecast table that *every* engine writes to closes that
blind spot — the highest-value structural fix available.

**The trap.** If "most recent per location" means UPDATE-in-place, you destroy the ability to verify
past forecasts at all. The harness needs history: a row per (location, target date, target type,
forecast run), so ERA5 can be compared against what was actually predicted at the time.

**Suggested shape** — a serving projection over an append-only history, not a replacement for it:

- Keep an append-only evaluation history (verifiable, analytics-capable).
- Add a *latest-per-(location, target date, target type)* projection for serving — materialised table,
  view, or `DISTINCT ON`, as performance dictates.
- Carry `forecast_run_at` on the projection so staleness is expressible (§3.5).

**On forecast type:** aurora currently lives in `aurora_forecast_result` (V57) with its own pipeline
(`AuroraOrchestrator`, `ClaudeAuroraInterpreter`, `AuroraStateCache`). Unifying it with
sunrise/sunset is plausible but is a *second* project — its cadence (5-minute polling, night-only),
its trigger model (Kp/OVATION thresholds) and its state are genuinely different. Consider scoping it
out of v1.

### 3.5 Rows always present, even when stale

Good instinct, and the codebase already has vocabulary for it: `ConfidenceDeriver` /
`BriefingRegion.confidence` (HIGH/MEDIUM/LOW, **null when unknown**) and the `topicCertainty` axis.

Requirements a design should meet:

- The projection carries `forecast_run_at` (or equivalent) so age is queryable, not inferred.
- The UI distinguishes "no forecast yet" from "a forecast from two days ago". `ConfidenceDeriver`
  returns **null** for zero coverage precisely so unknown reads provisional rather than confident —
  preserve that.
- Beware the existing precedent: `getCachedBriefingForApi()` already serves last-known-good on a
  below-threshold refresh, silently. Make staleness visible rather than repeating that.

### 3.6 Trim Manage → Operations

Whatever the gates decision removes takes UI with it: optimisation-strategy toggles, stability
displays, freshness-threshold controls. `SchedulerView` stays.

Worth checking against actual use — several admin surfaces exist to answer questions the telemetry
now answers directly.

### 3.7 The briefing ↔ forecast dependency

**The sharpest architectural finding of the session, and the cause of the T+3 bug.**

The briefing is currently **both an input and an output of the same cycle**:

- **Input:** `ForecastTaskCollector:264` selects batch candidates from
  `briefingService.getCachedBriefing()`.
- **Output:** the cycle's final `BRIEFING` phase calls `briefingService.refreshBriefing()`.

Since V103 retired the standalone `daily_briefing` cron, nothing else refreshes it. So each cycle
reads the briefing the *previous* cycle wrote — and a 4-date window written yesterday afternoon
arrives at the 01:00 nightly with its first date already past. That is #367.

The one-line window extension fixed the symptom. **A redesign should break the circularity**, for
example:

- A cheap structural briefing pass (slots, dates, verdicts — no Claude) at the *head* of a cycle for
  candidate selection, with the full enrichment pass (gloss, best-bet, headline) at the tail; **or**
- Candidate selection derives its own window from the location roster and stops depending on the
  briefing at all.

⚠️ If you take the first option: `refreshBriefing()` calls Claude — `glossService.generateGlosses`
(one call per region × date × event, up to ~56 per refresh) and `bestBetAdvisor` (one ~12k-token
call). A naive head-position refresh **doubles that spend and can serve a gloss-less briefing** for
the 2–4 hours until the tail refresh lands, because `daily_briefing_cache` backs `GET /api/briefing`.

---

## 4. Hard constraints

1. **Never change assertions in `src/test/java/.../regression/`.** Project rule in CLAUDE.md.
   `PromptRegressionTest` pins rating bounds against exact cloud values — including `coptHill_5Mar`
   (solar low **67%** → rating ≤ 2), which sits 7pp above the ">60% BLOCKED" rule. Any threshold
   change above 67 breaks a protected test.
2. **Never push, tag, or open PRs without being asked.**
3. **Copt Hill must stay correct.** Its solar low is **7%**, not 88% — misreading that field inverts
   the whole conclusion. It clears both veto triggers by 10pp (trend rise 30 vs 20; upwind 70 vs 60).
4. **`SystemPromptCacheabilityTest`** pins the system prompt at ≥ 15,500 chars. Haiku 4.5's minimum
   cacheable prefix is **4,096 tokens** and the prompt clears it by ~5%. Shortening it silently
   disables caching on every far-term evaluation. Bluebell (~1,020 tok) and woodland (~1,270 tok) are
   already below the floor — their `cache_control` is inert, deliberately.
5. **Never transform a cloud value at the point of record.** `DirectionalCloudDetails` feeds
   `forecast_evaluation.solar_low_cloud`, which is the verification harness's own input. A transform
   there would make the bias read as zero and destroy the instrument.
6. **ERA5 is a model reconstruction, not measurement.** It says nothing about whether a sunset was
   beautiful.

---

## 5. Open questions worth answering before or during the redesign

Ordered by value per unit effort.

1. **Will you start recording outcomes?** `POST /api/outcome` is already open to every authenticated
   user; `actual_outcome` has **zero rows**. `missedOpportunities` (predicted ≤2, actual ≥4) is the
   only counter that adjudicates wasted-trip-versus-missed-photograph, and it reads 0 forever until
   the table has rows. **Twenty observations would do more than the next twenty thousand ERA5
   comparisons.** Every analytical loop in the session dead-ended here.
2. **Does #336's lead-time flatness survive V130?** Those figures were measured on the old
   single-point sampling that V130 wiped. `daysAhead` is already on `CloudVerificationPair` and the
   report ignores it. One report field; settles §2.1 on current data.
3. **Does `vetoSeparation` survive when restricted to `rating IS NOT NULL`?** One filter. Isolates the
   ~3,520 rows where the veto actually reached a rating. Bucket-difference based, so immune to the
   offset. The only measurement that could ever justify touching the veto.
4. **How many distinct (location, date, targetType) triples and distinct dates lie behind 25,730
   rows?** `findVerifiedPairs` applies no dedup, and one synoptic system covers a UK day. Until this
   is known, no confidence figure from the harness is interpretable.
5. **Pin `models=` on both Open-Meteo clients.** Both take the provider default, so the baseline can
   change without a deploy.
6. **Retime the intraday cycle?** Designed but not built: fire at sunset−5h rather than 14:00 UTC.
   Blocked on the 12h TRANSITIONAL freshness threshold, which the gates decision removes anyway. Note
   batch latency is 98–173 min, so "three hours' warning" needs roughly a 5h lead.

---

## 6. Current state

**Merged:** #367 (T+3 window). Earlier the same day: #356, #357, #363–#366, #368.

**Open:** #370 (popup score bars), #371 (cache-floor guard + doc fixes). Both 1 ahead / 0 behind
`main`, touching disjoint trees.

**Nothing from the gates decision has been built.** Clean slate.

**Production queries.** The database runs on host `dockermacmini` and is not reachable from the dev
Mac — these need running there.

```sql
-- gate breakdown by cycle (hour 01 = nightly, 14 = intraday)
SELECT to_char(created_at,'HH24') AS hr, disposition, count(*)
FROM forecast_run_disposition
WHERE created_at >= now() - interval '30 days'
GROUP BY 1, 2 ORDER BY 1, 3 DESC;

-- cycle health
SELECT cycle_type, status, current_phase, count(*)
FROM pipeline_run
WHERE trigger_time >= now() - interval '30 days'
GROUP BY 1, 2, 3;

-- spend and cache behaviour by run type
SELECT jr.run_type, count(*) AS calls,
       sum(a.cache_creation_input_tokens) AS writes,
       sum(a.cache_read_input_tokens) AS reads,
       round(sum(a.cost_micro_dollars)/1e6::numeric, 2) AS usd
FROM api_call_log a JOIN job_run jr ON jr.id = a.job_run_id
WHERE a.service = 'ANTHROPIC' AND a.called_at >= now() - interval '7 days'
GROUP BY 1;
```
