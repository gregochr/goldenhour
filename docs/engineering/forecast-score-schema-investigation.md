# Forecast-score schema refactor + bluebell extraction — Pass 0 investigation

**Status:** read-only investigation, no code changed. 2026-06-10.
**Scope:** foundation for (a) a normalised `forecast_type` / `forecast_score` component-score
model with strangler-pattern dual-write, and (b) extracting the bluebell evaluation out of the
standard prompt into its own prompt/visitor. Verified against HEAD (`8651f5d6`) and read-only
prod queries on `goldenhour-db`.

---

## Headline findings (read these before anything else)

1. **The nightly pipeline never persists numeric bluebell scores.** The batch path writes
   Claude results only to `cached_evaluation.results_json`, whose element record
   (`BriefingEvaluationResult.java:22-31`) has **no bluebell fields**. `forecast_evaluation`
   — the only table with `bluebell_score`/`bluebell_summary` columns — receives only
   **triage rows** from the nightly pipeline (verified in prod: every row since 2026-06-08
   has `rating IS NULL AND triage_reason IS NOT NULL`). Scored rows land there only via the
   admin sync path. Hence the entire May 2026 season produced **6** bluebell-scored rows,
   all from manual sync runs. `BluebellHotTopicStrategy` reads those columns — it is
   effectively starved in production unless an admin happens to run a sync evaluation
   in season.
2. **The bluebell scale is mixed in prod, exactly as feared.** The prompt instructs Claude
   to output `bluebell_score` 0–100 (`PromptBuilder.java:522-523`) while feeding it a 0–10
   input score and while every consumer (hot topic thresholds, frontend "score/10"
   rendering) assumes 0–10. The 6 prod rows are `{3, 3, 30, 60, 75, 90}` — Claude sometimes
   echoed the 0–10 input, sometimes scaled to 0–100. Already documented in
   `bluebell-scoring-deferred-findings.md`; confirmed empirically here.
3. **LITE premium gating is only half-applied today.** Entity-backed endpoints gate LITE
   users onto `basic_*` scores (`ForecastDtoMapper.java:73-80`). But the Map-tab sparse path
   (`toSparseDto`, `ForecastDtoMapper.java:229-231`) declares its `isLiteUser` parameter
   *"reserved for future use"* (`@SuppressWarnings("PMD.UnusedFormalParameter")`) and serves
   the **enhanced 0–100 sub-scores to LITE users ungated** from the cache. The future
   row-filtering model is being compared against inconsistent current behaviour, not a
   clean baseline.
4. **The design brief omits the `basic_*` score family entirely.** `basic_fiery_sky`,
   `basic_golden_hour`, `basic_summary` are first-class response fields (the LITE product),
   persisted on `forecast_evaluation` but **not** in `results_json`. The `forecast_type`
   seed list (FIERY_SKY / GOLDEN_HOUR / TIDAL / BLUEBELL [/ SKY]) has no home for them.
   This needs Chris's call before Pass 1 (see Open Questions).
5. **The "Tidal Foreccast" typo does not exist** in goldenhour or goldenhour-lite code, nor
   in any obvious prod table. Nothing to fix, or it lives in a design doc outside the repo.

---

## Section 1 — The standard prompt's actual output contract

Source: `PromptBuilder.buildOutputConfig()` (`PromptBuilder.java:561-604`), parsed by
`ClaudeEvaluationStrategy.parseEvaluationWithMetadata()` (`ClaudeEvaluationStrategy.java:218-256`)
into `SunsetEvaluation` (`SunsetEvaluation.java:26-39`). Schema has
`additionalProperties: false`; commit `7f221cbf` (restore fields to schema) is the current
state, not `8d155648` (schema-out, message-only) — the fields live in the schema **always**,
with a conditional user-message instruction controlling whether Claude populates them.

**Grain: one Claude call per (location, date, event).** Events are `SUNRISE` / `SUNSET`
(`HOURLY` exists for wildlife comfort runs only and is not colour-evaluated). The response is
a single flat object — no per-location arrays.

| Field | Type | Scale | Required | Populated when |
|---|---|---|---|---|
| `rating` | int | 1–5 (enum) | yes | always |
| `fiery_sky` | int | 0–100 | yes | always |
| `golden_hour` | int | 0–100 | yes | always |
| `summary` | string | one sentence | yes | always |
| `basic_fiery_sky` | int | 0–100 | no | directional cloud data present (observer-point-only inference, the LITE product) |
| `basic_golden_hour` | int | 0–100 | no | as above |
| `basic_summary` | string | — | no | as above |
| `inversion_score` | int | 0–10 | no | water-overlooking locations, deterministic pre-score ≥ 7 triggers prompt block |
| `inversion_potential` | enum | NONE/MODERATE/STRONG | no | as above |
| `bluebell_score` | int | **0–100 per prompt; 0–10 per everything else** | no | bluebell site + in season (see §5) |
| `bluebell_summary` | string | — | no | as above |
| `headline` | string | 4–9 words | no | requested since Gate 2 redesign (V100); optional per prompt |

Notable prompt-side coupling: the system prompt tells Claude to **boost `rating` by 1** when
bluebell score is 8–10 and rating < 5 (`PromptBuilder.java:165-177`), and similarly for
inversion 7–10. The 1–5 sky rating is therefore *not purely a sky product* today — bluebell
and inversion leak into it inside the prompt. Extracting bluebell to a combiner peer
(Pass 3) is a **behaviour change** to the sky rating during season, not a pure refactor.

**Coastal variant** (`CoastalPromptBuilder.java:22-70`, post fc05b371 decomposition): same
schema, same fields. It only appends a system-prompt suffix telling Claude to score *sky
exactly as inland* and to treat a storm-surge block as foreground/safety context. Claude no
longer rates the tide; `TideVisitor` does (deterministic, no API call).

**Premium-intent confirmation:** the 0–100 `fiery_sky`/`golden_hour` are display products.
Nothing combines them; the combiner consumes only 1–5 values. This matches the design
intent (0–100 = premium display granularity, 1–5 = combiner peers).

---

## Section 2 — Where every score persists today

Two structurally different stores, written by two different paths, with **no overlap in the
nightly pipeline**:

### Write path A — nightly batch (the live path)
`BatchResultProcessor` → `ForecastResultHandler.parseBatchResponse()` (:168-208) →
`buildResult()` applies `RatingCombiner.combine()` (:305-324) → flush per cache key →
`BriefingEvaluationService.persistToDb()` (:504-541) → **`cached_evaluation.results_json`**.

- Key: `region|date|targetType` (one row per region-event-day; `CacheKeyFactory`).
- Element shape: `{locationName, rating, fierySkyPotential, goldenHourPotential, summary,
  triageReason?, triageMessage?, headline?}` — triage fields mutually exclusive with scores.
- **Dropped on this path:** `basic_*`, `inversion_*`, `bluebell_*` (verified in prod: 0 rows
  contain a `bluebellScore` key; 174 rows mention bluebells only in summary/headline prose).
- All locations are kept, not just rating ≥ 3 (prod sample shows rating 1–2 entries with
  full scores). The "rating>=3 only" memory is **wrong for the cache**; it conflates the
  briefing's display filtering with persistence.
- `evaluation_delta_log` (V97) written alongside on cache refresh: old/new rating deltas only.

### Write path B — sync/admin (`ForecastCommandExecutor` → `ForecastService`)
`evaluateAndPersist()` / `runForecasts()` → `buildEntity()` (`ForecastService.java:593-682`)
→ **`forecast_evaluation`** columns: `rating` (post-combiner), `fiery_sky_potential`,
`golden_hour_potential`, `summary`, `headline`, `basic_*` (3 cols), `inversion_score`,
`inversion_potential`, `bluebell_score`, `bluebell_summary`, plus ~50 weather-input columns.
This is the **only** path that persists `basic_*`, `inversion_*`, `bluebell_*` numerically.
It does not write `cached_evaluation` (WriteTarget.NONE).

### Write path C — triage (both pipelines)
`ForecastService.fetchWeatherAndTriage()` persists a scoreless `forecast_evaluation` row
(`rating=null` + `triage_reason/message`, :363-377 weather triage, :383-410 tide triage) for
every triaged candidate, **including during nightly collection**
(`ForecastTaskCollector.java:341-359`). This is why `forecast_evaluation` grows by hundreds
of rows daily despite the batch path never writing scored rows to it. Nightly:
`forecast_evaluation` is a *triage ledger*; `cached_evaluation` is the *score store*.

### Tide
`TideVisitor` (R1 rules: king/spring aligned=5, regular aligned=4, widened=3, misaligned=1,
underivable=abstain; `TideVisitor.java:43`) produces its 1–5 score **transiently** inside
`RatingCombiner.combine()` (`RatingCombiner.java:57-69`, plain rounded average of applicable
visitor scores). **The tide sub-score is persisted nowhere.** Only the combined rating
survives. Deterministic tide *facts* (`tide_state`, `tide_aligned`, surge columns) persist on
`forecast_evaluation` as weather inputs, derived independently of the visitor.
→ Dual-writing a TIDAL row in Pass 2 requires capturing the visitor's score at the
`buildResult()` seam before combination — the seam exists and serves both transports
(`ForecastResultHandler` owns batch and sync parsing).

### Everything else
- `api_call_log` (V99): raw response body only for diagnostics (regex-fallback / errors);
  **NULL on the happy path**. The design's "raw Claude responses stay in api_call_log" is
  only true for unhappy paths — worth knowing before relying on it for reconciliation.
- `pipeline_run_pick` (V104): `claude_average_rating` snapshot per pick.
- `forecast_run_disposition` (V101): category + detail, no scores.
- `stability_snapshot` (V98): stability enums, no scores.

### Field → persistence map (the dual-write replication target)

| Response field | results_json (nightly) | forecast_evaluation (sync only) |
|---|---|---|
| rating (combined) | ✅ `rating` | ✅ `rating` |
| fiery_sky | ✅ `fierySkyPotential` | ✅ `fiery_sky_potential` |
| golden_hour | ✅ `goldenHourPotential` | ✅ `golden_hour_potential` |
| summary | ✅ `summary` | ✅ `summary` |
| headline | ✅ `headline` | ✅ `headline` |
| basic_fiery_sky / basic_golden_hour / basic_summary | ❌ | ✅ |
| inversion_score / inversion_potential | ❌ | ✅ |
| bluebell_score / bluebell_summary | ❌ | ✅ |
| tide visitor score (1–5) | ❌ (folded into rating) | ❌ (folded into rating) |
| sky visitor score (pre-combine 1–5) | ❌ inland ≡ rating; coastal lost | ❌ same |

Note the last row: for coastal locations the **pure sky rating is also lost** post-combine.
A SKY type row in `forecast_score` would be the first place it's ever recorded.

---

## Section 3 — Every reader of every score (safety-critical)

### 3.1 Serving API / frontend
- **Planner/heatmap + briefing (primary serving):** `cached_evaluation` →
  `BriefingEvaluationService` (:246-248 deserialise) → `EvaluationViewService` merge
  (:150-200, latest-wins merge with `forecast_evaluation`) → `BriefingService.enrichWithCachedScores`
  (:474-476) and `BriefingEvaluationController` (`/api/briefing/evaluate/scores`, :61-67).
  Frontend: `HeatmapGrid.jsx:233,238,342-348,412,700`, `DailyBriefing.jsx:415-429`,
  `StarRating.jsx`, `markerUtils.js:59-131` (marker arcs render the two 0–100 sub-scores —
  **the premium sub-scores already render**, see gating below).
- **Map tab sparse DTOs:** `ForecastController.java:167` → `toSparseDto` — **no LITE gating**
  (parameter unused). Enhanced 0–100 scores + summary served to all tiers from cache.
- **Entity-backed endpoints:** `ForecastController.java:142,208,408` → `toDto(entity, lite)`
  — LITE swapped onto `basic_*` (`ForecastDtoMapper.java:73-80`). Bluebell fields seasonal-
  and type-gated in the same mapper (:119-129). `MarkerPopupContent.jsx:199-210` renders the
  sub-score rows; `TRIAGE_REASON_LABELS` (:18-24) renders triage fallbacks.
- **Not tier-gated anywhere:** `rating`, `headline`, `bluebellScore`, tide fields, triage fields.

### 3.2 Premium gating mechanism today
Single mechanism: role check `ROLE_LITE_USER` (`ForecastController.java:511`) → field
*substitution* (enhanced → basic) in `ForecastDtoMapper`. No field redaction, no component
hiding, no frontend gating. And it is bypassed on the cached path (headline finding 3).
Future row-filtering by `forecast_type` must decide what LITE receives **instead** — today
LITE receives a degraded *variant* (basic_*), not nothing.

### 3.3 Hot topics
- `BluebellHotTopicStrategy.java`: reads `forecast_evaluation.bluebell_score/summary` via
  `findBluebellEvaluations()` (`ForecastEvaluationRepository.java:88`). Thresholds **on the
  0–10 scale**: hot topic ≥ 6 (:40), expanded detail ≥ 5 (:43), quality labels ≥9/≥7
  (:222-229). (The "assumed 0-10" in the brief is correct; the *prompt* is the outlier.)
  Production-starved per headline finding 1.
- `KingTide/SpringTideHotTopicStrategy`: read `forecast_evaluation.tide_aligned` counts +
  deterministic lunar classification. No Claude scores.
- `AuroraHotTopicStrategy`: space-weather cache only. No coupling to these tables.
- Frontend: `HotTopicStrip.jsx:36-40` (`bluebellScoreColour`, 0–10 thresholds 9/7),
  `:88-91`, `:290-391` (`BluebellExpandedCard`).

### 3.4 Briefing / best-bet advisor
- `BriefingRatingStats.compute()` (:76-92): averages `rating` from cached results; verdict
  bands ≥3.5 WORTH_IT / ≥2.5 MAYBE (:110-121).
- `BriefingBestBetAdvisor`: `computeRegionStats()` (:893-906) → emits `claudeRatedCount`,
  `claudeHighRatedCount` (≥4), `claudeMediumRatedCount` (=3), `claudeAverageRating` into the
  rollup JSON Claude sees (:1026-1051); prompt guidance thresholds 3.5/4.0 (:186-203);
  `MIN_HEADLINE_CLAUDE_COVERAGE = 3` (:100). **Source: `cached_evaluation` via
  `BriefingEvaluationService.getCachedScores()` — never `forecast_evaluation`.**
- `BriefingGlossService.appendClaudeScores()` (:304-329): same source, same stats, calibrates
  gloss language (3.5/2.5 bands at :80-85).
- `BluebellGlossService` (:174-186): best/avg bluebell score from **in-memory hot-topic
  detail** (`bluebellLocationMetrics`), i.e. transitively from `forecast_evaluation`.

### 3.5 Cross-run machinery
- `PipelineRunPickService.lookupAverageRating()` (:191-209): recomputes region average from
  cached scores at persist time → `pipeline_run_pick.claude_average_rating` (:151). Used by
  `PipelineRunComparisonService` (admin run-comparison UI, also reads `headline` :157).
- `evaluation_delta_log`: written from cache refresh (`BriefingEvaluationService:320`);
  old/new rating only.
- `ForecastDispositionService`, `StabilitySnapshotProvider`: no score reads.
- `OptimisationSkipEvaluator` + sentinel phase (`ForecastCommandExecutor.java:535-560`):
  reads fresh `entity.getRating()` against a threshold for region early-stop — sync path only.

### 3.6 Notifications
`PushoverNotificationService.java:68-85`, `EmailNotificationService.java:77-106`,
`MacOsToastNotificationService.java:64-70`: read `rating`, `fierySkyPotential`,
`goldenHourPotential` from the in-memory `ForecastEvaluationResult` (not from either table).

### 3.7 Reader-map summary

| Field | Readers (production) |
|---|---|
| rating | BriefingRatingStats, BestBetAdvisor, BriefingGlossService, PipelineRunPickService, EvaluationViewService→Heatmap/Briefing/markers, ForecastDtoMapper, sentinel early-stop, notifications, delta log |
| fiery_sky / golden_hour (0–100) | ForecastDtoMapper (both paths), marker arcs + popup, BriefingService slot enrichment, notifications |
| basic_* | ForecastDtoMapper LITE branch only |
| summary / headline | DTO mappers, Heatmap/DailyBriefing, PipelineRunComparison (headline), gloss/advisor prose context |
| bluebell_score/summary | BluebellHotTopicStrategy (+ frontend strip/card), BluebellGlossService, ForecastDtoMapper seasonal branch |
| inversion_* | ForecastDtoMapper passthrough only (no pipeline consumer) |
| tide_aligned / lunar / surge | King/SpringTide strategies, BestBetAdvisor rollup counts, Heatmap tier logic |
| tide visitor 1–5 | nobody — transient, fused into rating |

Anything not listed above did not show up in wide greps of `results_json` key access,
`ForecastEvaluation` getters, or frontend score-field names. Test files mirror the same
fields and are not separately listed.

---

## Section 4 — Q1: should the overall 1–5 sky rating be a `forecast_type` row?

Findings that bear on it:

- The combiner consumes **in-memory visitor values** (`VisitorContext(evaluation, tide)`),
  never table rows. A `forecast_score` table is purely a *record* in Pass 2 either way; no
  reader needs SKY rows for the system to function.
- But: the **pure sky score for coastal locations is currently lost** post-combine
  (Section 2). Without a SKY row, the component-score record is incomplete for exactly the
  locations where decomposition matters, and Pass 4's read migration (hot topics → advisor →
  serving consume component rows) has nothing to migrate the rating readers onto.
- The advisor/hot-topic layer consumes the **combined** rating. If only sub-products are
  stored, Pass 4 would need the combiner re-run at read time or a second "combined" row
  anyway.

**Recommendation: seed `SKY` as a type row (scale_max 5) and write it per evaluation.**
Store the *pre-combine* sky visitor score. The combined rating stays a serving-path product
(results_json today; derivable as avg of 1–5 peers tomorrow). Trade: one more row per
(location, date, event) — at current volume (~600–800 evaluations/night, ≤4 rows each)
that's trivial for Postgres; the win is a complete component record and a clean Pass 4
target. The alternative (serving-only) saves rows but leaves the coastal sky score
unrecorded and forces Pass 4 to re-derive it — a worse trade.

**Aurora is not precluded:** `forecast_type` is a lookup row + writer; aurora results live in
their own tables (`aurora_forecast_result`) with their own batch path, and nothing in the
proposed schema constrains type codes or writers. Folding AURORA in later is additive.
Same goes for a future INVERSION type (0–10 today, persisted but unconsumed downstream).

**Lookup naming:** `(id, code, display_name, scale_max)` is sufficient. Seed
`SKY(5), FIERY_SKY(100), GOLDEN_HOUR(100), TIDAL(5), BLUEBELL(?)` — bluebell scale is an
open question (§5/OQ2). No "Tidal Foreccast" typo exists to fix (headline finding 5).

---

## Section 5 — Bluebell extraction specifics

### 5.1 What removal from the standard prompt touches
- Prompt assembly: conditional block `PromptBuilder.java:505-523`; scoring guidance + the
  rating-boost rule :165-177; output schema fields :590-592.
- Parsing: `ClaudeEvaluationStrategy.java:100-105` (regex fallback patterns), :240-243
  (JSON extraction); `SunsetEvaluation` fields.
- Persistence: `ForecastService.buildEntity()` :677-680 (sync path only — the nightly path
  already drops the fields, headline finding 1).
- Readers: `BluebellHotTopicStrategy`, `BluebellGlossService`, `ForecastDtoMapper:119-129`,
  `HotTopicStrip.jsx` (incl. `BluebellExpandedCard`).
- Golden masters: `prompt-golden/woodland-bluebell.txt` fixture pins the current folded
  prompt — regenerating it is the reviewed diff for the contract change.

### 5.2 Seasonal machinery today
- `SeasonalWindow.BLUEBELL` = **April 18 – May 18, hardcoded** (`SeasonalWindow.java:24`).
  Not config-driven. Gates (a) the prompt block (`PromptBuilder.java:506-507`) and (b) DTO
  exposure (`ForecastDtoMapper`). The target config-driven window is new build.
- `BluebellConditionService` is **deterministic** (no API calls): scores 0–10 from
  pre-fetched weather with exposure-weighted flags — WOODLAND favours mist/soft light/calm;
  OPEN_FELL favours calm + golden-hour light. Wired in via
  `ForecastDataAugmentor.augmentWithBluebellConditions` (`ForecastService.java:359-360`).
- **Collector level: no gating at all.** `NightlyCandidateCollectionStrategy.includes()`
  returns true unconditionally (:33-36). Out of season, bluebell sites are evaluated as
  ordinary landscape locations (which they are — see 5.3), with normal dispositions.
  The "disposition spam" framing only applies if these sites are bluebell-*only*; today the
  type system says none are.

### 5.3 The 16/2 split and Roseberry
Confirmed in prod and V84: 18 sites, 16 WOODLAND + 2 OPEN_FELL (Rannerdale Knotts,
Loughrigg Terrace). **Roseberry Topping (id 87) is WOODLAND** — the planned reclass to
OPEN_FELL is a one-row data fix but changes `BluebellConditionService` weighting (calm +3,
golden-hour light +2 instead of soft-light weighting) and the UI exposure label.
**Correction to the brief:** all 18 sites are typed `LANDSCAPE,BLUEBELL` — there is no
"bluebells-only" location type. Removing sites from out-of-season candidacy therefore needs
a product decision about which sites are *worth evaluating as landscape* year-round (OQ4),
not just a collector gate.

### 5.4 Combined (OPEN_FELL) evaluation shape
The coastal pattern is the right template and is fully in place: a `BluebellVisitor` would
implement `Visitor` (appliesTo: bluebell site + in season [+ exposure policy]), return a 1–5
score, and `RatingCombiner` would average it as a third peer — `avg(sky, tide?, bluebell)`.
No combiner changes needed beyond registering the bean; the combiner is already N-ary.
Two design wrinkles the brief should absorb:
- Today bluebell influences the rating *via the prompt boost rule* (+1 if 8–10). Replacing
  that with a combiner peer changes in-season ratings — e.g. sky 2 + bluebell 5 averages to
  4 (round-half-up), where today Claude might output 3. Acceptance must compare verdict-band
  behaviour, not expect identical numbers.
- Whether WOODLAND sites also get the combiner peer or keep bluebell as a pure side-product
  is unstated in the brief (it says OPEN_FELL gets "combined" shape). Needs a call (OQ3).

### 5.5 Batch split precedent + golden-master strategy
- **Cache-locality split precedent (5a818347):** the admin batch path already splits
  submissions by prompt type so identical system prompts batch together; the collector also
  buckets near/far × inland/coastal. A bluebell mini-batch (own system prompt, 18 locations,
  seasonal) follows the same mechanism — extend the bucketing, don't invent a new path.
- **Golden masters (8d97b067 precedent):** two harnesses exist and both apply:
  `PromptGoldenMasterTest` (byte-for-byte prompt fixtures, `prompt-golden/`, regenerate flag)
  and `CachePayloadGoldenMasterTest` (cache JSON shape with `<<SUMMARY>>` placeholders,
  `cache-golden/`). Pass 3 = regenerate prompt goldens (reviewed diff shows exactly the
  bluebell block leaving the standard prompt + the new bluebell prompt fixture) + cache
  goldens must show **no change out of season**.
- **May 2026 prod data is NOT a sufficient golden master for the extracted prompt.** In the
  Apr 18–May 18 window: 1,576 triage rows vs **111 scored rows**, of which **6** have
  bluebell scores — on a mixed scale. The season's `cached_evaluation` rows (174 with
  bluebell prose) validate *rating/prose* behaviour but contain no bluebell numbers.
  Realistic strategy: treat the deterministic input side (`BluebellConditionService` is
  replayable from weather data) + prompt goldens + a short in-season or simulated
  side-by-side run as acceptance, and treat the 6 prod rows as anecdotes, not a baseline.

---

## Section 6 — Pass structure (validated, with amendments) + risk register

The proposed phasing survives contact with the code, with **two amendments** (bold below).

### Pass 1 — schema
Lookup + `forecast_score` + Roseberry reclass. **Amendment 1: do NOT drop the legacy
bluebell columns in Pass 1.** `bluebell_score`/`bluebell_summary` are live-mapped in the JPA
entity and read by `BluebellHotTopicStrategy`, `BluebellGlossService`, `ForecastDtoMapper`,
and the frontend expanded card. Dropping them before those readers are re-pointed (Pass 3/4)
breaks the app at startup. The drop is the *last* step of Pass 3 at the earliest.
- Riskiest element: getting the unique key wrong. `(forecast_type_id, location_id,
  evaluation_date, event_type)` is correct for the current grain, but note the nightly
  pipeline re-evaluates the same (location, date, event) across cycles (delta log exists
  because of this) — dual-write must **upsert**, and if per-run history is wanted later,
  `pipeline_run_id` is the discriminator, not the unique key.
- Acceptance: migration applies cleanly; app boots; zero behaviour change (additive only).
- Rollback: drop the two tables; revert Roseberry row.

### Pass 2 — dual-write (sky composite + tide)
Hook point: `ForecastResultHandler.buildResult()` (:305-324) — the single seam both
transports share, where the parsed `SunsetEvaluation` and the visitor scores coexist before
combination. Write SKY (pre-combine sky score), FIERY_SKY, GOLDEN_HOUR (+ summary on SKY
row only, per design), TIDAL (visitor score, when applicable). `results_json` path untouched.
- Riskiest element: the tide score is currently computed *inside* `RatingCombiner.combine()`
  and discarded; exposing per-visitor scores means touching the combiner's return shape.
  Mitigate with the existing cache-payload golden master (combined ratings must be
  byte-identical) before/after.
- Plumbing gap found: `pipeline_run_id` is not currently threaded into
  `ForecastResultHandler` (it has `jobRun` context via `ResultContext` for logging). Small,
  but do it deliberately; nullable column makes it non-blocking.
- Triage rows produce no `forecast_score` rows — the reconciliation query must join
  against non-triaged results_json entries only.
- Acceptance (empirical, over N nightly cycles): reconciliation query —
  every non-triaged `results_json` element has matching FIERY_SKY/GOLDEN_HOUR rows, and
  `rating` equals the rounded average of that location's 1–5 peer rows. Inland: SKY row
  rating == results_json rating exactly.
- Rollback: feature-flag the dual-write off. Serving never touched.

### Pass 3 — bluebell extraction (the one prompt-contract change)
Standard prompt loses bluebell fields **and the bluebell rating-boost rule** (golden-mastered
diff); new bluebell prompt + `BluebellVisitor` + seasonal mini-batch (5a818347 bucketing
precedent) + config-driven season window + collector gate + UI seasonal removal; bluebell
rows dual-written to `forecast_score` as type BLUEBELL.
- Riskiest element: in-season sky ratings change semantics (boost rule removed, combiner
  peer added). This is intended but must be *declared* as a behaviour change and validated
  at verdict-band level (WORTH_IT/MAYBE/STAND_DOWN flips), not score equality.
- Second risk: the hot-topic path. Re-point `BluebellHotTopicStrategy` from
  `forecast_evaluation` columns to `forecast_score` rows **in this pass** — that's what
  finally makes the bluebell hot topic work from the nightly pipeline (it never has; see
  headline finding 1). Thresholds must be restated for whatever scale OQ2 lands on.
- Acceptance: prompt goldens (reviewed diff); cache goldens unchanged out of season;
  simulated season side-by-side (deterministic inputs are replayable); hot topic fires from
  a nightly cycle in (simulated) season.
- Rollback: revert prompt + visitor registration behind a flag; the folded-path code remains
  until the legacy column drop, which is why Amendment 1 sequences the drop last.
- **Amendment 2: fix the 0–100/0–10 prompt instruction lie as part of this pass's new
  prompt, not before** — touching the standard prompt twice (once to fix the scale, once to
  extract) doubles the golden-master churn for no benefit. The deferred-findings doc's
  "standardise on 0–10" recommendation is superseded by OQ2 (the forecast_score scale).

### Pass 4 — read migration (later, separately gated, not this work)
Consumer-by-consumer: hot topics (done early, in Pass 3, for bluebell) → advisor/stats
(`getCachedScores` call sites are the choke point: BestBetAdvisor, GlossService,
PipelineRunPickService, BriefingRatingStats inputs) → serving (`EvaluationViewService`
merge). Feasible — every reader funnels through 2–3 service methods, none read the JSON
directly except `BriefingEvaluationService`. Nothing found that entangles a reader with the
write path in a way that breaks dual-write. Not this work.

---

## What surprised me most

1. **The nightly pipeline discards every score the refactor wants to normalise** except the
   four cache fields. Bluebell, inversion, and basic_* numbers survive only via manual admin
   sync runs. The `forecast_score` table isn't just normalising existing persistence — for
   bluebell/tide/coastal-sky it will be the *first* persistence.
2. The Map tab serves enhanced premium sub-scores to LITE users today (`toSparseDto`'s lite
   parameter is decorative). The row-filtering model would be a tightening, not a port.
3. The 1–5 "sky" rating is already contaminated by bluebell and inversion boost rules inside
   the prompt — the clean "SKY component" the schema imagines doesn't quite exist yet.
4. Only 111 scored rows landed in `forecast_evaluation` across the entire bluebell season;
   the golden-master plan for the extraction has to lean on deterministic replays and prompt
   fixtures, not prod data.

## Open questions needing Chris's call before Pass 1

1. **(OQ1) SKY type row** — recommended yes (Section 4): store the pre-combine sky score,
   keep the combined rating a derived/serving product. Confirm.
2. **(OQ2) Bluebell native scale in `forecast_score`** — the brief seeds BLUEBELL(5); all
   existing machinery (condition service input, hot-topic thresholds 6/5, UI "/10" and 9/7
   colours) is 0–10. Options: (a) scale_max=10, thresholds untouched, combiner maps 10→5 at
   read; (b) scale_max=5 as designed, restate thresholds (≥3 hot topic, etc.) and UI. (b) is
   cleaner for the combiner ("1–5 peers" stays literally true) but touches more code.
3. **(OQ3) Does WOODLAND get the combiner peer too**, or is bluebell a pure side-product for
   WOODLAND and a rating peer only for OPEN_FELL? The brief implies the latter; the visitor
   pattern supports either with a one-line `appliesTo` difference.
4. **(OQ4) Which sites are "bluebells-only"?** All 18 are typed LANDSCAPE+BLUEBELL today.
   Out-of-season removal from candidacy (and UI) needs a per-site decision — e.g. Plessey
   Woods probably isn't a year-round landscape candidate, Rannerdale certainly is.
5. **(OQ5) Where do `basic_*` scores live in the taxonomy?** They're the current LITE
   product, absent from the seed list. Options: their own type rows (BASIC_FIERY_SKY…),
   a `variant` column on `forecast_score`, or an explicit decision that row-filtered LITE
   replaces the basic_* product (in which case Pass 2 needn't dual-write them, but the
   LITE experience changes at Pass 4). This decision shapes the unique key.
6. **(OQ6) `summary` on which rows?** The response has four prose fields (summary,
   basic_summary, bluebell_summary, headline). The schema has one nullable `summary` per
   row. Proposed: summary→SKY row, bluebell_summary→BLUEBELL row, headline stays
   serving-path-only (it's a card label, not a score product), basic_summary per OQ5.
   Confirm.

---

*Prod queries used (read-only, `goldenhour-db` via dockermacmini): bluebell row sample,
row counts/date ranges per table, recent-row triage characterisation, job_run types,
location exposure listing, results_json key vs prose checks. All reproducible with psql.*
