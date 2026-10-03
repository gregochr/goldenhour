# Plan: stop the two-star rating flips

**Status: DRAFT v2, 2026-10-03, not started.** v1 proposed code-computed rating bands with a
clamp. An adversarial review the same day (six prosecutors, four refuters, §9) kept the idea of
code-owned bounds but overturned v1's evidence, its rules and its seam. v2 puts an experiment
and two cheap fixes ahead of any clamp. Owner decisions needed: §7.

## 1. The problem, as measured

On 2026-10-03 the 14:00 intraday run rated Peel Craggs 4★ (fiery 75) and Sycamore Gap 2★
(fiery 15) for the next sunrise on identical cloud inputs: observer 0/100/100, solar horizon
0/6/1, antisolar 100/100/100, far corridor 0. The two had swapped places since the 01:00 run.
The 2★ summaries blame antisolar cloud, which `PromptBuilder` (l.126–132) says is never a
penalty and must not be mentioned.

Production scores everything with Haiku 4.5 by owner decision. Owner decision 2026-10-03: a
one-star wobble is acceptable, a two-star flip is not.

What the review established, last 14 days, `forecast_evaluation`:

- 18,106 scored rows are 5,628 distinct slots, each re-scored about 3.2 times.
- `forecast_evaluation.rating` is the combined star with tide averaged in
  (`ForecastResultHandler.scoreEvaluationRow` l.709; a missed tide scores 1). Sky rules can
  only be sized on inland rows or on a sky-only column.
- Clear solar horizon with canvas, rated ≤2★, no rain/murk/overhead-low/approach excuse:
  851 rows, 678 inland. They split three ways:
  - **311 "far canvas only"**: mid/high cloud only at the antisolar 113 km sample, clear
    overhead and toward the sun. That cloud sits 2.5–4.6° above the far horizon. The prompt
    says this is not clear sky and rates 4–5; Claude rates it ≤2 in 50–66% of cases.
  - **182 mist-flagged** (dew gap ≤2 °C or humidity ≥90%). About half are excess over the
    ordinary low-rating rate, and 86 of the summaries give a canvas reason, not mist.
  - **185 residual** (177 slots across all runs; 51 where the slot's latest row is still low).
    In a sample of 20, about 16 contradict their own inputs.
- Best estimate of answers that contradict their inputs: about 250 rows in 14 days, plus the
  311 where prompt and physics disagree.
- The ceilings are barely breached once tide is removed. Blocked horizon: 66 inland rows.
  Thick-mid "never 5": zero inland. Building-plus-upwind "max 3": 23 of 29 breaches are thin
  strips, where the user message itself says "rate 3-4".
- Identical-input disagreement on 2026-10-04 sunrise: 3 inland groups, 10 locations (v1 said
  6 groups, 18 locations; three contained tidal spots and ungrouped inputs differed).

Replay on 2026-10-03 (rebuilt prompt, N=10): Haiku 19 of 20 at 4★, one 2★. Sonnet 4.6 stayed
within one star. One in 20 is a 95% interval of about 0.1–25%; it justifies an experiment, not
a build.

## 2. Two defects found on the way

**The output field order is random per backend start.** `PromptBuilder.buildOutputConfig`
(l.684–735) builds the schema's properties with `Map.ofEntries`. Stored responses from 22 Sep
to 3 Oct show the key order changing in blocks: `rating` before `summary` on some days, after
it on others. So whether the model commits to a number before or after writing its reason
changes with each deploy. No rating effect showed on unmatched data (2.37 vs 2.44; 2.57 vs
2.57), which is weak evidence either way.

**No temperature is set**, so every request samples at the default. Setting 0 costs nothing in
caching. The API reference marks the parameter deprecated for models after Opus 4.6, so it
works on Haiku 4.5 and would break on a later model.

## 3. Phases

**P0 — Fix the field order.** Build the schema with a fixed order. Which order (summary before
rating, or as today's most common) is settled by P2; until then pin `rating, fiery_sky,
golden_hour, summary`, the `required` list's order. Small, no migration. The prompt regression
tests call the strategy directly, so run them and report; change no assertion.

**P1 — Keep what the model was asked and what it answered.**
- Persist the user message at submit. Batch `api_call_log` rows are created only at result
  time with no request in hand (`JobRunService.logBatchResult`), so the carrier is a new
  nullable column on the PENDING `forecast_evaluation` row. User message only: the system
  prompt is 15,500 characters and identical per run. State a retention rule.
- Persist Claude's own sky rating before tide, as `sky_rating_raw`, on the same row.
- One migration, proven only by CI. Without P1 no production prompt can be replayed and no
  sky rule can be sized on coastal rows.

**P2 — The experiment. Gates everything after it.** At least 10 stored production prompts
(the Wall case, far-canvas-only, mist-flagged, blocked horizon, thin strip), N ≥ 100 per arm,
on Haiku 4.5:
1. baseline;
2. temperature 0;
3. summary before rating;
4. two short required fields before rating (`light_path`, `canvas`: enums);
5. one added sentence: full antisolar mid/high cloud is the canvas when the solar side is bare;
6. the band stated in the user message;
7. the rating enum restricted to the band per request.

Report per arm: share of answers outside the band, shift of the in-band distribution against
baseline (anchoring to the minimum), summaries that cite the band, and cost. Arm 7 splits the
system-prompt cache into one lineage per band ("Changing the `output_config.format` parameter
will invalidate any prompt cache", structured-outputs docs), so cost it.
Run through `PromptTestService` or the eval harness, key passed inline, never exported.
Stop rule: if an arm among 2–5 brings two-star flips under 1% with no distribution shift,
ship that arm and stop here.

**P3 — Bounds in code, only if P2 leaves a tail.** Design in §4. Shadow first, enforce behind
a flag.

## 4. If bounds are still needed (P3)

- **Calculator.** Pure function from `AtmosphericData` to min, max and a reason code. Not
  named `RatingBand`: `eval/RatingBand.java` exists. Thresholds have one home that the prompt
  text and `CloudVerificationService` (its own 20/60/50 copies) are also built from, with a
  drift test.
- **Rules.** Ceilings as the prompt states them, with the thin-strip exception on the
  building-plus-upwind cap. Drop the thick-mid "never 5" ceiling unless P1 data shows sky-side
  breaches. Floor only where canvas is at the solar horizon or overhead; far-canvas-only
  follows §7 Q1. Where the prompt says "rate 4, not 3", the floor is 4. Mist needs a dew-gap
  or mist-trend exemption. A missing rating (the code substitutes 1, `buildResult` l.610–620)
  is never lifted. State precedence when a floor meets a ceiling, and for the "STRONG
  inversion → 5" rule.
- **Seam.** Clamp `eval.rating()` in `ForecastResultHandler.buildResult` before
  `ratingCombiner.combine` (l.626), so the tide average, `skyRating` and the `forecast_score`
  SKY component all receive the clamped value. Compute the band there from the task's
  atmospheric data on the sync path (no PENDING row exists), and from the stored row on the
  batch path, loaded before combine. No row or no directional data means no clamp, counted in
  a log line.
- **Flag.** `photocast.rating-band.enforce`, default false. Shadow mode stores the band and
  the reason code and clamps nothing.
- **A clamped answer must be coherent.** A lifted 3★ shows as "Maybe" beside Claude's sentence
  and score bars in the location sheet and spot peek. v1's "re-ask through RETRY_FAILED"
  cannot work: that lane selects failed calls only and stops above 5 per cycle. Settle §7 Q2
  before enforcing.
- **Readers to decide, one line each in the P3 brief:** calibration and cloud verification
  read `rating` (point them at the raw column and record the cutover date); LITE gets the
  main star over basic-tier text; sentinel sampling compares the rating with 2; OPEN_FELL
  bluebell blending reads the stored rating; `evaluation_delta_log` gets a break; the test
  harnesses bypass the handler and stay unclamped.
- **Exit numbers, set before the shadow starts:** per-slot counts, split by `days_ahead`,
  humidity and tidal/inland, over at least one unsettled spell; tier and pick changes per
  region-window (simulation says about 9 of 120, none up to "Worth it").

## 5. Stop-points

- Prompt regression tests (`PromptRegressionTest`, `BestBetAuroraPromptRegressionTest`, tag
  `prompt-regression`) and the sky-rating eval harness: run, report, change no assertion.
  `PromptRegressionTest` l.238 asserts exactly 4 for a clear horizon under full mid/high.
- Any prompt change also needs `messages.count_tokens` against Haiku: the cache floor is
  4,096 tokens and the prompt clears it by about 5%.
- Migrations are proven only by CI's Backend job, with a Testcontainers test.
- Per commit: `changelog.d/` entry, Checkstyle javadoc, JaCoCo 80% per class. No push without
  permission. Code goes to Sonnet sub-agents.
- No UI change is planned; if P3's coherence fix touches the frontend, the UI review cadence
  applies.

## 6. Not in this plan

- Changing the scoring model.
- Trimming the system prompt.
- The Sonnet bisect (which input earns Sycamore's fifth star).
- CLAUDE.md corrections ("NEAR=Sonnet" is the V92 default; the `regression/` directory it
  names does not exist). Separate docs commit.

## 7. Owner decisions

- **Q1 — far canvas only.** Cloud only 113 km behind the viewer, clear overhead and toward
  the sun. The prompt rates it 4–5; it sits under 5° above the far horizon. Is that a good
  sunrise? This changes prompt text, so it is the owner's call, and it moves about 300 rows a
  fortnight.
- **Q2 — a clamped answer's text and scores** (only if P3 is reached): replace the sentence
  with the calculator's reason, or build a separate re-ask lane.
- **Q3 — temperature 0** if P2 favours it, knowing a later model would reject the parameter.

## 8. Order and size

P0 is one small session. P1 is one session plus CI. P2 is an experiment the orchestrator
runs, costing a few pounds at N ≥ 100 over 7 arms and 10 prompts. P3 is at least three
sessions (calculator and shadow; clamp and flag; readers).

## 9. Review record, 2026-10-03

Six read-only prosecutors (band rules, sizing, code seams, downstream, model behaviour,
rollout) and four refuters. Refuted or cut down: the direct-save sync path (dead for sky
scoring); notifications (disabled in production); "only 185 genuine flips"; "low ratings on
far-canvas-only skies are correct" (they break the prompt as written). Not examined by
anyone: `CoastalPromptBuilder`'s body, the frontend heat-field arithmetic, a matched-input
test of field order, the SDK's map serialisation, and `cached_evaluation.skyRating` as a
sizing source.
