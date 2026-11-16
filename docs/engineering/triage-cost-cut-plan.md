# Triage cost cut: a 60% line for today and tomorrow, and one call per shared cell

**Status:** planned 2026-11-15, not built. **Owner decisions:** the 60% line for T+0/1 (2026-11-15); plan both
changes (2026-11-15). Evidence: `claude-cost-ledger.md`, "Open: triage headroom" and "Cost per call and
shared cells". Queries: `cost-queries/triage-headroom.sql`, `cost-queries/per-call-and-shared-cells.sql`,
`cost-queries/triage-60-thin-strip.sql`.

## Why

Sonnet 5.5 rating for T+0/1 is ~$77 per 30 days, most of the bill. Two measured cuts:

| Phase | Saving per 30 days | Harm measured |
|---|---|---|
| P1: stand down T+0/1 above 60% sun-side low cloud (not 80%), thin strips exempt | ~$9 | 8 of ~5.4k skipped calls were 4★+ (0.15%); 87 were 3★ |
| P2: one request per set of equivalent places in a grid cell | up to $14; P2a measures the real figure | identical weather inputs, but the place name is an input too; gated on a comparison week |

Neither changes the model or the cadence. P1 does not change the prompt; P2 changes it only if P2a shows that
sharing needs the event time printed to the minute (an owner decision).

---

## P1: the 60% line for T+0/1

### Rule

`WeatherTriageEvaluator` rule 1 today: solar-horizon low cloud over 80% (observer low cloud when there is no
directional data) stands the slot down. New rule:

- `daysAhead <= 1`: over **60%** stands down, **unless the horizon reads as a thin strip**
  (`isThinStrip`: near ≥ 50% and near − far ≥ 30 points), which keeps the 80% line. The prompt already tells
  the model to soften the low-cloud penalty for a thin strip, the one case where light gets under the cloud.
- `daysAhead >= 2`: unchanged at 80%. Far-term ratings stay 2–5% 4★+ up to 80% and cost ~$2/month.
- Observer fallback (no directional data): 60% for T+0/1, 80% beyond. No thin-strip test is possible.
- Rules 2 and 3 (rain over 2 mm, visibility under 5 km with the mist exemption) are unchanged.
- The reason text keeps its format (`Solar horizon low cloud 65% — sun blocked`), so every reader of
  `forecast_run_disposition.detail` and the honesty filter's wording is unchanged.

**Pre-build check: PASSED (2026-11-15, production, 45 days).** `triage-60-thin-strip.sql` split the 61–80%
T+0/1 ratings:

| Shape | Requests | $ / 45 days | 4★+ | 3★ |
|---|---|---|---|---|
| Blanket (stood down at 60) | 5,347 | 13.55 | 7 (0.13%) | 77 (1.4%) |
| Thin strip (exempt, stays at 80) | 2,831 | 7.89 | 9 | 1,430 (51%) |
| No directional data (60% on observer cloud) | 33 | 0.09 | 1 | 10 |

The exemption keeps 9 of the 17 good ratings and almost all the "Maybe" ratings, so P1 hides 8 good and
~87 Maybe ratings in 45 days. **The saving is ~$9/30 days, not ~$14**: the thin-strip third stays rated.

### Code

- `WeatherTriageEvaluator.evaluate(AtmosphericData)` gains `int daysAhead`:
  `evaluate(AtmosphericData data, int daysAhead)`. Constants: `NEAR_TERM_MAX_DAYS = 1`,
  `NEAR_TERM_CLOUD_THRESHOLD = 60`, `CLOUD_THRESHOLD = 80` kept for the far horizon.
- Its one caller, `ForecastService.fetchWeatherAndTriage` (`ForecastService.java:362`), already holds
  `daysAhead` (`:259`, `ForecastHorizon.daysAhead`, UK civil date). The scheduled collector
  (`ForecastTaskCollector.java:986`), ForceSubmit (`ForceSubmitBatchService.java:121`) and retry
  (`BatchRetryService.java:290`) all go through it. A negative `daysAhead` (a retried past slot) falls under
  `<= 1`, which is harmless.
- **Retry fix (existing gap, review finding).** `BatchRetryService.java:292` skips a slot only when
  `pre.atmosphericData() == null`. A triaged result keeps its data (`ForecastService.java:378`), so a retried
  slot that triages is recorded as triaged *and then submitted and rated anyway*. Change the test to
  `pre.atmosphericData() == null || pre.triaged()` (recording the triage as now when `triaged()`). Replace the
  `BatchRetryServiceTest` mock that models "triaged" as null data (`:269-270`) with a real-shaped triaged
  result (data attached, `triaged() == true`) and assert no submission.
- `isThinStrip` gets one definition: a static `isThinStrip(int nearLowPct, Integer farLowPct)` with
  `SOLAR_LOW_CLOUD_SIGNIFICANT_PERCENT` (50) and `THIN_STRIP_DROP_POINTS` (30), beside `DirectionalCloudData`
  (plus an instance `isThinStrip()` that delegates). Three callers use it: `PromptBuilder` (`:1013`, keep a
  delegating package-private method so `PromptBuilderTest.java:1084` still compiles), the triage exemption,
  and `CloudVerificationService.thinStripPreconditionsHeld` (`:1098`, today a second copy on pair integers).
  The prompt label and the triage exemption read the same `forecastData`, so with one definition they cannot
  disagree.
- `CloudVerificationService` mirrors rule 1 (`TRIAGE_SOLAR_LOW_CLOUD_MAX_PCT = 80`, `:205`). The real cut is
  `forecastGapUnderTriageCut` (`:1075`); every `underTriageCut` bucket (`:845`, `:894-901`) and `promptable()`
  (`:973`) share it. `CloudVerificationPair` already carries `daysAhead`. **The report runs over stored
  history**, and pairs from before the deploy at T+0/1 with 61–80% did reach Claude, so the mirror applies the
  60% line only to pairs with `forecast_run_at` at or after a rollout date constant
  (`TRIAGE_60_ROLLOUT`, set to the release date in the PR), and 80% before it. Bucket labels that say `<=80`
  are renamed to say "under the triage cut".
- Comments and Javadoc that state 80: `TriageRule.java:8`, `ForecastTaskCollector.java:457`,
  `ForecastScoreWriter.java:69`, `CloudVerificationReport.java:45`, `WeatherTriageEvaluator.java:21`.
- **Not changed:** `BriefingVerdictEvaluator` (`CLOUD_STANDDOWN = 80`, `HORIZON_CLOUD_STANDDOWN = 70`). It is
  the briefing's own heuristic verdict, not the gate in front of Claude. Its Javadoc says it "mirrors"
  triage, so the Javadoc is corrected to say what it does not mirror. `Verdict.java`'s Javadoc likewise.

### Knock-on effects (reviewers: check each)

- **A small, mostly-triaged region would read "Unrated" instead of "Stand down" (review finding, fixed in
  P1).** Below `VerdictSampleGate.MIN_RATED` (5) rated places, the rollup falls back to the region's
  heuristic verdict (`BriefingRegionEvaluationRollup.java:~297`, `DisplayVerdict.resolve(null, …)`), where
  GO and MARGINAL become UNRATED and only STANDDOWN becomes STAND_DOWN (`DisplayVerdict.java:71-74`). The
  heuristic stands down horizon cloud only at 70% or more (`BriefingVerdictEvaluator.java:226`), so a region
  whose places are now triaged at 61–69% would say "Unrated", which wrongly implies nobody looked. **Fix:** in
  that fallback, when the region's voting slots this cycle are at least half examined (the gate's existing
  `examinedCount`) and none of the examined ones was rated, resolve STAND_DOWN, because the pipeline looked and
  the weather ruled it out. Rollup tests: 4 places triaged at 65% and 0 rated read STAND_DOWN; 2 triaged and 2
  never examined still read UNRATED; 4 rated and 1 triaged keep today's behaviour.
- **Other effects of more stood-down places, accepted.** `VerdictSampleGate`'s coverage half counts triaged
  slots as examined, so it is unaffected. `ConfidenceDeriver` thin coverage counts *scored* places against
  the scoreable roster (`ConfidenceDeriver.java:122-123`), so more regions read one band less confident on
  mixed days. A fully triaged region is blanked with "Too unsettled to forecast"
  (`BriefingHonestyFilter.java:93`); more cloud-blocked (not unsettled) regions will now get that wording,
  which already predates P1. **Owner look, not part of P1.**
- Retraction: a later triage supersedes `cached_evaluation` and `forecast_evaluation` through the freshness
  gate (`EvaluationViewService.java:~1307`, `:~1544`) but never `forecast_score` (`ratings-stores.md`;
  `ForecastScoreWriter.java:69`: a stale row can outlive its event). P1 widens that existing gap slightly;
  accepted, as for every triage today. In-season bluebell sites stay exempt from the skip
  (`ForecastTaskCollector.java:460`).
- Failure tracking: triaged places are "triage-only, not evidence" and reset counters, as today.
- Hot topics and Coming up are never silenced by triage (the two-question rule): no change.

### Tests

- `WeatherTriageEvaluatorTest`: T+0 and T+1 at 60 (passes) and 61 (stands down); T+2 and T+3 at 80 and 81;
  T+0 at 70 with a thin strip (passes) and with far cloud too (stands down); observer fallback at 61 for T+0
  (stands down) and T+2 (passes); reason text unchanged; rules 2 and 3 unchanged at T+0.
- `WeatherTriageEvaluatorMistTest`: the mist-candidate log line still fires for a rule-1 refusal at 65%, T+0.
- `ForecastServiceTest`: the caller passes the `ForecastHorizon` days-ahead, proved with a fixed `Clock` near
  UK midnight (a UTC/UK date split must not change the threshold).
- `PromptBuilder` tests that assert the THIN STRIP label still pass unchanged after the move.
- `CloudVerificationServiceTest`: the mirror at T+0 and T+2, before and after `TRIAGE_60_ROLLOUT`.
- `BatchRetryServiceTest`: a retried slot that triages with data attached is recorded and not submitted.
- The rollup tests listed under the knock-on effects.
- Stub churn: every `evaluate(any())` stub becomes `evaluate(any(), eq(<days>))` with the days pinned
  (Codex rejects loose `any()` stubs): `ForecastServiceTest` (`:174`, `:203`, `:349` and more),
  `ForecastServiceInversionProfileTest`, `ForecastTaskCollectorRegionFilteredReadingsTest`,
  `FarCloudCaptureInvarianceTest`.

### Docs

`reference/pipeline.md` and `reference/architecture-decisions.md` wherever triage's 80% is described; the
ledger entry moves from "decision pending" to built, with a re-measure note (`triage-headroom.sql` after
14 days: T+0/1 requests in the 61–80% band should be about the thin-strip count only).

---

## P2: one request per set of equivalent places in a cell

### Idea

54 of 266 enabled places share an Open-Meteo cell with another. When two sky-lane places in one cell would
send the **same request apart from the place name**, send one request and give every place its answer.

Measured (30 days, T+0/1): 5,058 of ~28.2k Sonnet calls would be saved if every same-cell place shared; 74% of
groups already got identical stars, 0.5% spread 2★+. **That is an upper bound.** It groups by cell only, and
the real message also carries each place's own event time (`solarService.sunriseUtc(lat, lon, …)`,
`ForecastService.java:263`, printed with `%s` at `PromptBuilder.java:513`; the precision comes from the
external `solar-utils` library and is unverified), orientation and directional samples taken from its own
coordinates. If the event time carries seconds, strict sharing may be near zero. **P2a measures the real
figure, and it replaces the $8–14 estimate.**

### Equivalence

Two candidates are equivalent when all of these are equal:

- Sky lane only: never woodland, bluebell, a forced (`-f`) task or a task with a null `evalRowId`.
- Same target date and event.
- **Explicitly in the key, not trusted to the bucket** (`BatchRequestFactory.java:191`, `:229-247`):
  `model.getModelId()`, `maxTokens`, the thinking and effort settings, and `cachePrefixKey` (which carries the
  system prompt and the coastal or inland suffix).
- **The rendered user message (`built.userMessage()`, `EvaluationServiceImpl.java:189-191`) with only its
  `Location: <name>.` prefix replaced by a fixed token.** Nothing else in the message is touched. This covers
  every other place-specific input (event time, orientation, directional samples, approach risk, mist,
  inversion, coastal mist, stability, surge) without a hand-kept list. The system prompt is static and holds
  no name, and the cache primer's prefix key holds no name (checked in review).

The group's representative is the place with the lowest location id. Its request is unchanged, name included.

**The name is an input.** The model can use what it knows about a named place (a pier, a castle, a summit),
and a sibling's written why can carry the representative's details. So the claim is "identical weather
inputs; the name may shift the answer", not "no harm by construction". P2b is gated on a direct comparison.

### P2a: shadow measurement (one PR, no behaviour change, deployed after P1)

- In `submitForecast`, after each request is built, compute two keys per eligible task: the **strict** key
  above, and a **minute** key that is the same except the event time is truncated to the minute. Computing them
  has no side effects (the message already exists).
- One INFO line per `submitForecast` call: `[BATCH SHARE] trigger=<…> retry=<bool> bucket=<inland|coastal
  × near|far> tasks=N strictShareable=S minuteShareable=M`, with each group of 2+ at DEBUG. `submitForecast`
  has no bucket label today: derive it from the builder and horizon, or pass it in.
- The region-filtered and manual path (`ForecastTaskCollector.java:826`, one model for all) and retries also
  call `submitForecast`. They are logged with their trigger and left out of the decision.
- **The 7 days start after P1 has deployed**, because P1 changes which tasks get past triage.
- **Decision rule:** build P2b only if strict sharing averages at least 8% of SCHEDULED T+0/1 sky requests
  (post-P1 base ~$63/30 days, so ~$5/month). If only the minute key passes, P2b also needs the event time
  printed to the minute in every prompt: a prompt change that must clear the Sky Eval and the prompt regression
  suite first, and an owner decision. Record the result in the ledger either way.

### P2b: the fan-out (only if P2a passes, then the comparison week)

**Comparison week first.** The switch's middle value, `verify`, still submits every sibling and records the
shared answer beside each sibling's own, serving only the sibling's own. Turn `on` only if shared stars differ
by 2★ or more in under 1% of sibling pairs and by 1★ at no more than the noise floor (~8.5%, `noise-floor.sql`).

**Persistence.** Every place keeps its own PENDING `forecast_evaluation` row, as today. New nullable column
`forecast_evaluation.shared_eval_row_id BIGINT` (next free migration, indexed), set on each sibling's row to the
representative's row id. Only the representative's request is submitted, so `custom_id` and `api_call_log`
stay one-to-one with requests.

**Prompt store and hash.** Each sibling row gets its representative's system-prompt `prompt_hash` and its own
`forecast_evaluation_prompt` row holding its own rendered message (already in hand from the key). Otherwise the
calibration report (`ForecastEvaluationRepository.java:271`) sees null hashes. Calibration then counts siblings
as separate samples of one request; say so in `ratings-stores.md`.

**The fan-out lives in `BatchResultProcessor`.** `ForecastResultHandler.parseBatchResponse` returns one
`Optional<BatchSuccess>` (`:247`); the processor routes it into one region sink (`:378-395`), flushes per key
(`:891`), and alone holds `locationsById` (`:200`). For each representative success the processor loads the
sibling rows by `shared_eval_row_id` **whatever their state**, and runs the existing per-location `buildResult`
path for each with the shared parsed response: the sibling's own location, tide context, rating combiner,
region sink, `ForecastScoreWriter.write` with the same `pipelineRunId`, the superseding check (per sibling,
`:818`) and `scoreEvaluationRow` on its own row, whose first-instant guard (`:690-697`) makes a re-poll or
restart safe. `succeeded` and `requestCount` stay counts of requests, or
`completeBatchRun(…, succeeded, requestCount - succeeded)` (`:479-480`) goes negative.

**Name in the written why.** Replace only the representative's exact name with the sibling's: literal,
case-sensitive, with explicit boundary characters (not `\b`, which breaks on "St.", apostrophes and hyphens).
A shortened or re-cased name stays unreplaced; accepted.

**Abandonment.** `abandonPendingForBatch` (`EvaluationAbandonmentService.java:85-98`) builds `rowIds` from
`api_call_log`. It also abandons PENDING rows whose `shared_eval_row_id` is in `rowIds` (every representative in
the batch, scored or not), so a sibling whose own scoring threw is closed rather than left to the 48 h backstop.
A lost `api_call_log` write still leaves both to the backstop, as today for a single row.

**Retry.** Siblings are never re-pointed at a retried row: the retry fetches fresh weather, so its answer is to
a different prompt. `BatchRetryService.selectFailures` (`:140`) expands a failed representative into itself and
its siblings, reconstructs each separately (fresh weather, new PENDING row, precursor abandoned), and the retry
batch's own `submitForecast` regroups them. Places count towards `failureCap` (`:419`); expanded siblings past
the cap stay failed.

**Failure evidence.** Left alone, siblings would add duplicate "through" votes to `LikeEvidence`
(`LocationFailureService.java:666-672`, `mostlyThrough` `:701`) and never any failure, which inflates the ratio
and makes other places' failures easier to count towards auto-disable. In `CycleLocationOutcomeResolver`, a
sibling takes its representative's outcome, failure included (through `shared_eval_row_id`), and a group counts
once in the like tally.

**Dispositions.** No change: siblings stay in their bucket's task list (`BucketOutcome.tasks`,
`ScheduledBatchEvaluationService.java:769`; `SlotKey` matching `:779-790`), so a failed bucket already marks
them. Test it.

**Readers that change meaning.** `BatchSummaryDeriver` counts custom ids for `locationCount` (`:117`) and
`computeRegionCount` (`:134-150`); both add siblings through `shared_eval_row_id`, or siblings in other regions
vanish. `RunCostProfileService` per-call cost becomes per request: correct for money; say so in its Javadoc.

**Switch.** `photocast.batch.share-equivalent-requests`: `off` (default), `verify`, `on`. The owner turns it on
after merge.

### P2b tests

- Key: two places in one cell with identical weather and different names share; a different orientation,
  event time, coastal flag, mist band, model, max tokens or bucket does not; forced, null-row, woodland and
  bluebell never do; only the `Location:` prefix is neutralised (a name repeated elsewhere breaks equivalence).
- Fan-out: one result writes a SCORED row, a `forecast_score` row, a `cached_evaluation` entry, a prompt row and a
  prompt hash for each sibling, under its own region and name; the written why names the sibling; `succeeded`
  counts requests.
- Processing the same batch twice changes nothing.
- Abandonment: a sibling whose scoring threw is abandoned with the batch.
- Retry: a failed representative expands to its siblings, each reconstructed with fresh weather; the cap holds.
- Settle: a sibling takes its representative's success and failure; the like tally counts the group once.
- `BatchSummaryDeriver` counts siblings in places and regions.
- Switch `off`: byte-identical requests and custom ids to today. `verify`: siblings still submitted, nothing
  served from the shared answer.
- Migration: a Testcontainers test for the column (CI only; there is no Docker locally).

---

## Order and delivery

One PR per phase, each from the updated `main`, built by a Sonnet subagent in its own worktree under the
CLAUDE.md workflow (gates, changelog entry, CI, Codex 👍 on the head commit):

1. **P1** after the owner runs `triage-60-thin-strip.sql` and the blanket row passes.
2. **P2a** after P1 has deployed (its 7 days must measure the post-P1 mix). It touches different files, so it
   can be built while P1 is in review.
3. **P2b** only if P2a passes its decision rule; it gets its own adversarial plan review first, sized for a
   cross-cutting change (result path, abandonment, retry, settle evidence), then ships in `verify` for the
   comparison week before `on`.

## Re-measure

After 14 days of P1, re-run `triage-headroom.sql` and `measured-costs.sql`; after P2b, compare the T+0/1 Sonnet
request count per cycle with the 30 days before. Record both in the ledger.
