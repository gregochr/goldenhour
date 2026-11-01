# Stop asking Claude for the basic-tier scores

**Status:** planned 2026-11-01 · one phase, one PR · backend only

## Why

Every sky evaluation asks Claude for three "basic" fields (`basic_fiery_sky`, `basic_golden_hour`,
`basic_summary`): what it would score from the observer-point cloud alone, without the
directional cone. They were the LITE product. Since the Pro features gate
(`pro-features-gate-plan.md` §6, 2026-10-25) LITE gets no Fiery Sky / Golden Hour number at all, and
that plan recorded that the basic fields "stay in the database; they are simply never served".

We still pay for them on every call. Measured on production batch job run #2353 (561 Sonnet 5.5
calls, 2026-11-01): 92% of output tokens are the JSON answer, thinking is about 19 tokens a call,
and the three basic fields with their keys are about a third of each answer, roughly **30% of the
batch's output tokens** (~$0.19 of that batch's $0.65 output). `basic_summary` alone averages
23 words. The prompt's DUAL-TIER paragraph also rides in the cached prefix on every call.

## What reads them today (verified 2026-11-01)

| Place | Use | After this change |
|---|---|---|
| `PromptBuilder` system prompt (DUAL-TIER SCORING paragraph) and `buildOutputConfig()` schema | asks for them | removed |
| `SunsetEvaluationParser` (Jackson path + regex fallback, `BASIC_*_PATTERN`s) | parses them | removed; an old-shape reply carrying them still parses (extras ignored) |
| `SunsetEvaluation` record (3 components, overloads, `withRating`) | carries them | components removed |
| `ForecastResultHandler.scoreEvaluationRow` (batch) and `ForecastService` builder (sync) | write `forecast_evaluation.basic_*` | writes removed |
| `ForecastEvaluationEntity` + columns `basic_fiery_sky_potential`, `basic_golden_hour_potential`, `basic_summary` (V48) | stores them | **kept**, no longer written; new rows hold null |
| `ForecastDtoMapper` / `ForecastEvaluationDto` / `ForecastListDto` | none: the DTOs have no basic fields (`ForecastDtoMapperTest.toDto_basicFieldsNotInDto`) | unchanged |
| Frontend (`frontend/src`) | none | unchanged |
| Ask, briefing, calibration, Map, Plan | none | unchanged |
| `scripts/far-cloud-p1/export_forecasts.sql`, `premise.py` | export the columns for an offline study | unchanged; new rows export null |
| Prompt-regression tests (`@Tag("prompt-regression")`, resources in `src/test/resources/prompt-regression/`) | none | untouched (owner rule) |

Nothing in `ForecastController` serialises the entity directly; both forecast endpoints go through
`ForecastDtoMapper`.

## Decisions

1. **Keep the three columns and the entity fields.** Dropping them needs a migration proven only in
   CI and buys nothing; the history and the far-cloud scripts can still read old rows. Add a
   javadoc line on each entity field: no longer written since this change, kept for history. A
   later migration may drop them; that is not this PR.
2. **Remove the fields from the request, the parser and `SunsetEvaluation`**, not just from the
   schema, so no dead always-null components linger.
3. **No feature flag.** The fields are unread, so there is no behaviour to switch back to.
4. **Do not touch `inversion_*` or `mist_potential`.** They are already emitted only when their
   block is present (0 and 117 of 561 answers in the measured batch).

## The change

### `PromptBuilder`
- Delete the `DUAL-TIER SCORING: …` paragraph from the system prompt (the text from
  `"DUAL-TIER SCORING:` through `omit the basic_* fields entirely.\n\n"`). Leave the adjacent
  paragraphs byte-identical.
- In `buildOutputConfig()` delete the `basic_fiery_sky`, `basic_golden_hour` and `basic_summary`
  entries. Keep every other property and the existing order. Update the method javadoc's
  property-order list.
- Check `getPromptSuffix()` and the rest of the file for any other basic mention (none expected).

### `SunsetEvaluationParser`
- Delete `BASIC_FIERY_SKY_PATTERN`, `BASIC_GOLDEN_HOUR_PATTERN`, `BASIC_SUMMARY_PATTERN`,
  `BASIC_SUMMARY_PATTERN_SALVAGE` and the extraction in both the Jackson path (~L237–246) and the
  regex path (~L293–306).
- A reply that still carries `basic_*` (a batch submitted by the previous binary and collected
  after deploy: batches can take up to 24 h) must parse the same for every other field. Both paths
  already ignore unknown keys (`readTree` + `node.has`, no strict binding; every regex is anchored
  on a leading quote); prove it with a test. One benign difference: an old reply whose
  `basic_summary` is not a string used to drop to the regex fallback and now parses on the Jackson
  path.

### `SunsetEvaluation` — ⚠️ the one hazard in this PR
Removing three positional components changes overload arities, and an old call can silently bind
to a different overload. Concretely: today's 7-arg overload is
`(rating, fiery, golden, summary, basicFiery, basicGolden, basicSummary)`; after removal the
"no mist" convenience overload would be
`(rating, fiery, golden, summary, inversionScore, inversionPotential, headline)`. A test call
`new SunsetEvaluation(4, 70, 60, "s", null, null, "basic text")` would still compile and turn the
basic summary into the headline.

So:
- New canonical record: `(rating, fierySkyPotential, goldenHourPotential, summary,
  inversionScore, inversionPotential, headline, mistPotential)`.
- **Delete every convenience constructor except the 4-arg `(rating, fiery, golden, summary)`.**
  Do not re-add any other overload. Every call site that used one moves to the canonical 8-arg
  form (or the 4-arg form when everything after `summary` is null). With no 5-, 6- or 7-arg
  overload left, every old call fails to compile and must be rewritten by hand; none can bind
  silently.
- While rewriting each call, delete exactly the three basic arguments and keep the others in
  order. Before committing, list every `new SunsetEvaluation(` call in `main` and `test` (78 calls
  in 17 files today) and confirm each one's inversion/headline/mist arguments are what the old
  call passed.
- Update `withRating` and the class javadoc (drop the "served to LITE users" sentence).

### Writers
- `ForecastResultHandler.scoreEvaluationRow`: delete the three `setBasic…` lines and the javadoc's
  basic-tier wording.
- `ForecastService` (sync path builder, ~L592–594): delete the three `.basic…(…)` builder calls.
- `NoOpEvaluationStrategy`: uses the 4-arg form; expect no change.

### Entity
- `ForecastEvaluationEntity`: keep the three fields and columns; **replace** their existing
  "(LITE tier)" javadocs (L214, L218, L222) with: "No longer written (basic-tier-removal-plan.md);
  kept for history."
- Also fix the `SunsetEvaluation` 4-arg constructor javadoc ("without basic-tier or inversion
  fields") and trim the record's `@param` tags with the components (Checkstyle).

### Cache and hash side effects (expected, no action)
- The system prompt changes, so the cached prefix changes once. `BatchCachePrimer` primes from the
  same `buildOutputConfig()`/system prompt as the real requests, so the first run after deploy
  writes the new prefix as any run does.
- The prompt stays cacheable: `SystemPromptCacheabilityTest` requires at least
  `MIN_CACHEABLE_SYSTEM_PROMPT_CHARS = 15_500` (`PromptBuilder:79`). Measured from the golden
  fixtures, the inland system prompt is 17,920 characters and coastal 18,179; the paragraph is 434,
  leaving 17,486 / 17,745 (~1,990 headroom; inland ~4,610 tokens, still above Haiku's 4,096
  floor). Do not lower the constant.
- `cached_evaluation.results_json` stores `BriefingEvaluationResult`, which never embedded
  `SunsetEvaluation`, so no stored JSON depends on the record's components.
- `prompt_hash` changes for every row from the deploy on. It is used only for calibration
  bucketing (`ForecastCalibrationService`), where a new bucket for a new prompt is correct.

## Tests

- `SunsetEvaluationParserTest`: delete the basic-field tests; add (a) a structured-output reply
  carrying all three basic fields parses to the same rating, scores, summary, headline, inversion
  and mist as the same reply without them, on both the Jackson path and the regex fallback path;
  (b) the parsed `SunsetEvaluation` has no basic accessor (compile-enforced, no test needed).
- `OutputSchemaOrderTest`: expected order without the three basic properties.
- `PromptBuilderTest`: rewrite the assertions that expect the basic fields (L100–102 prompt
  contains the basic names; L370–371 `basic_summary` placeholder wording; L387–394 "Output config
  JSON contains dual-tier fields"; L440 loop over `basic_fiery_sky`/`basic_golden_hour`) into the
  inverse: the schema's `properties` has no key starting `basic_`, and the system prompt contains
  neither `DUAL-TIER` nor `basic_`.
- `ClaudeEvaluationStrategyTest` L259–265 ("parses dual-tier response with basic fields"): keep it
  as an old-shape-still-parses test (the reply carries basic fields; every other field parses) and
  fix the class javadoc at L53.
- `CachePayloadGoldenMasterTest` L158, L178 (10-arg calls), `ForecastResultHandlerTest`,
  `ForecastDtoMapperTest`: update constructor calls / drop basic assertions.
  (`ClaudeResponseParseResilienceTest` needs no change.)
  `ForecastDtoMapperTest`'s tests that set basic fields on the *entity* stay (the setters remain)
  and keep proving they are never served.
- `ForecastResultHandlerTest`: a scored row leaves `basic_*` null.
- `SunsetEvaluationParserTest`: convert L304 (bounded `basic_summary`) and L444 ("all fields
  present") into old-shape-still-parses tests rather than deleting them.
- Prompt golden masters: from `backend/`, regenerate with
  `./mvnw test -Dtest='PromptGoldenMasterTest,BluebellPromptGoldenMasterTest' -Dprompt.golden.regenerate=true`
  (regenerate mode writes without asserting), then re-run the same command **without** the flag.
  Six of the eight `src/test/resources/prompt-golden/*.txt` files change (`coastal-mist-light`,
  `coastal-surge`, `coastal-tidal`, `darksky-inversion`, `inland-landscape`, `woodland-bluebell`),
  each by exactly the removed DUAL-TIER paragraph; the two `bluebell-*` files must show no diff.
- Do **not** touch the `@Tag("prompt-regression")` tests (none references the basic fields).
  Running `-Pprompt-regression` (real API, costs money) is the owner's call; it is the check that
  removing the paragraph did not shift the main scores.

## Gates

Backend build ladder through the full `clean verify` (with the integration exclusion), exit code 0.
No frontend files change, so the frontend gates are not needed; say so in the PR.

## Docs and changelog

- `docs/engineering/reference/pipeline.md` line 8: replace "Dual-tier: enhanced (directional cloud)
  for PRO/ADMIN, basic (observer-point) for LITE" with a note that Claude scores with directional
  cloud only, and the basic tier is no longer requested (this plan).
- `model/ForecastEvaluationDto.java:17` javadoc ("LITE users get basic scores, PRO/ADMIN get
  enhanced directional scores") is already false since the Pro gate; correct it.
- `changelog.d/20261101-stop-basic-tier-scores.md`: `### Changed — Evaluations no longer ask
  Claude for the unused basic-tier scores`, one paragraph with the ~30% output-token saving.

## How we will know it worked

Re-run the measurement (`scratch/output-split`, uncommitted) on the first nightly batch after
deploy: mean answer tokens per call should fall from ~213 to ~145, and `basic_*` columns on new
rows should be null.
