# Pass 3 (bluebell extraction) — RESUME at commit 3, on main

## Where we are
Branch `main`. Two commits landed and green:
- C1 `53aa8e8f` — dedicated bluebell prompt + stripped standard prompt/parser (golden-mastered)
- C2 `c5e1f6d1` — BluebellVisitor + exposure rating-role in RatingCombiner

`./mvnw verify` is green (5004 tests + JaCoCo) **when you exclude the pre-existing
broken demo test**: `-Dtest='!*IntegrationTest,!*E2ETest,!*SmokeTest,!LocationFailureTrackingUIDemo'`.
That demo class (`@SpringBootTest @ActiveProfiles("local")`) fails locally on an
`IS_COASTAL_TIDAL` schema mismatch — environmental, unrelated, pre-existing; CI is fine.
If you don't exclude it, surefire fails before jacoco:check runs and MASKS coverage gaps
(this bit us once — a 69% BluebellPromptBuilder gap hid behind it).

Do NOT touch branch `fix/aurora-batch-token-truncation` (commit `dc770582`) — that's
separate aurora work Chris owns.

## Locked architecture decisions (all confirmed by Chris — do not relitigate)
1. **Claude bluebell prompt**, not deterministic scoring. BluebellConditionService is demoted
   to a subject-quality INPUT block feeding the prompt (already done in C1).
2. **WOODLAND in season = bluebell prompt ONLY** (no sky call). Rating = bluebell score.
   No SKY/FIERY_SKY/GOLDEN_HOUR rows for woodland in season.
3. **OPEN_FELL in season = sky prompt + bluebell prompt**, rating = round(avg(sky, bluebell)).
   All of SKY/FIERY/GOLDEN + BLUEBELL rows recorded. **This needs the merge-join** (2 sites:
   Roseberry Topping, Rannerdale Knotts).
4. **Out of season = sky only** (unchanged).
5. Phenology honesty: the prompt scores CONDITIONS GIVEN ASSUMED BLOOM and must not overclaim
   bloom; user-facing copy hedges ("ideal light if they're in bloom"). Known limitation, not a
   regression: a confident in-season score over a not-yet-bloomed/gone-over wood.

## What C2 already wired (so C3 just has to populate it)
- `VisitorContext(eval, tide, bluebell)` — 3-arg, with a 2-arg convenience ctor. `eval` may be
  null (woodland bluebell-only); SkyVisitor abstains on null.
- `BluebellVisitor` produces the BLUEBELL ComponentScore (1-5 + summary) when
  `context.bluebell()` is present; abstains when null (= out of season).
- `RatingCombiner.selectRatingPeers()` already implements the OQ3 rule keyed on
  `location.getBluebellExposure()`. WOODLAND → bluebell is the only rating peer; else all peers.
- Pass 2's dual-write is type-agnostic, so a BLUEBELL ComponentScore persists automatically
  once it reaches buildResult.
- `ClaudeEvaluationStrategy.parseBluebellEvaluation(text, mapper)` → `BluebellEvaluation`
  record already exists (strict JSON + regex salvage).
- `BluebellPromptBuilder` (@Component "bluebellPromptBuilder") exists with getSystemPrompt /
  buildUserMessage(AtmosphericData) / buildOutputConfig. It THROWS if
  data.bluebellConditionScore() is null — collector must only route in-season bluebell sites.

## Remaining commits

### C3a — config window + collector gate + mini-batch bucket + per-site flag
- **Config window**: replace `SeasonalWindow.BLUEBELL` hardcoded MonthDay(4,18)–(5,18) with
  config (`photocast.season.bluebell.start/end`), tunable without deploy. 5 call-sites read it:
  ForecastDtoMapper (lines ~122 & ~288), BluebellHotTopicStrategy (~72,~94), ForecastDataAugmentor
  (~420), BriefingService (~375), PromptBuilder no longer (removed in C1). Keep `isActive(date)`
  shape; inject the window so all sites agree.
- **Per-site flag** (OQ4): add a `bluebell_evaluate_year_round` (or similar) boolean column to
  locations, **DEFAULT true** (= evaluate year-round, current behaviour). SHIP ZERO SITES FLAGGED
  for out-of-season removal — candidacy unchanged at launch. Check `ls db/migration | sort -V | tail`
  first — highest is currently V110 (V109 roseberry, V110 best_bet_status). New = V111+.
- **Mini-batch bucket**: extend `ScheduledBatchTasks` (currently nearInland/nearCoastal/
  farInland/farCoastal) with a bluebell bucket (in-season only; out of season no bucket submitted).
  `ForecastTaskCollector.collectScheduledBatches(...)` (~line 322-462) builds the buckets — add the
  gate: in-season WOODLAND bluebell site → bluebell bucket only (exclude from inland); in-season
  OPEN_FELL → BOTH inland(sky) and bluebell; non-bluebell/out-of-season → unchanged.
  Mirror the `5a818347` homogeneous-prompt-per-batch precedent for cache locality.
- **BatchRequestFactory**: today selectBuilder picks coastal-vs-inland by `data.tide()`. Add a
  bluebell path — the bluebell bucket's requests use BluebellPromptBuilder (its own system prompt
  + output schema). Likely needs the task to carry a "prompt kind" (the EvaluationTask.Forecast or
  a new field) since AtmosphericData alone can't disambiguate "run bluebell prompt here".
- **Result threading**: the bluebell mini-batch responses parse via parseBluebellEvaluation; the
  result handler must route them to a VisitorContext with the bluebell slice populated so buildResult
  writes the BLUEBELL row (+ rating per the combiner rule). For woodland (bluebell-only) eval=null,
  bluebell=present. THIS is the fiddly bit — study ForecastResultHandler.parseBatchResponse/buildResult
  and how custom_id maps a response to (location,date,event) + which prompt produced it.

### C3b — open-fell merge-join (its own commit, golden-mastered alone)
- Only open-fell needs both prompts joined. The sky batch and bluebell mini-batch complete
  independently. Recombine at the cache-merge step (extend the existing `mergeFromBatch` path that
  Pass 2/retry uses for whole-location overlay) so when the bluebell result for an open-fell site
  arrives it recombines rating = round(avg(sky, bluebell)) and writes the BLUEBELL row.
  Mind the ordering (sky element must exist when bluebell merges — handle the race).
- **Golden-master gate**: cache-payload golden master must be byte-identical for NON-bluebell and
  out-of-season before/after the merge extension (CachePayloadGoldenMasterTest). The merge must not
  perturb existing aggregation. A regression must be attributable to prompt-change (C1) OR merge (C3b),
  never ambiguously both.

### C4 — hot-topic re-point + UI to 1-5
- Re-point `BluebellHotTopicStrategy` from `forecast_evaluation.bluebell_score` (via
  `findBluebellEvaluations`) to `forecast_score` BLUEBELL rows. This makes the bluebell hot topic
  fire from the NIGHTLY pipeline for the first time (Pass 0 finding 1: it has only ever seen ~6
  admin-sync rows). Restate the 0-10 thresholds (HOT_TOPIC=6, EXPANDED_DETAIL=5, qualityLabel 9/7)
  to the 1-5 scale — DOCUMENT the mapping (e.g. hot topic at >=3; pick deliberately).
- Frontend: `HotTopicStrip.jsx` `bluebellScoreColour` + `BluebellExpandedCard` — "/10" → "/5",
  9/7 colour thresholds → 1-5. `ForecastDtoMapper` bluebell branch updated for source/scale.
  Also check `MapView.jsx` / `MarkerPopupContent.jsx` (they reference bluebell).
  Pro-gating unchanged. Soften any bloom-overclaiming copy (phenology honesty).
- BluebellGlossService (region headlines for the strip) reads per-location bluebell scores — confirm
  its input scale follows.

### C5 — drop legacy columns (LAST, after a clean reader grep)
- Drop `bluebell_score`/`bluebell_summary` from forecast_evaluation only after C1-C4 migrated every
  reader. `grep -rn "getBluebellScore\|bluebell_score\|getBluebellSummary"` must be clean of readers
  first. SunsetEvaluation's bluebell fields (now always null) drop here too. Migration V11x.

## Step 0 gotchas (already discovered — don't rediscover)
- Inversion boost (PromptBuilder ~161 "MODERATE (7-8) → boost") MUST stay; only bluebell boost
  removed (done in C1). Don't touch inversion anywhere this pass.
- Migration numbering: CLAUDE.md says V1-V68 but actual highest is V110. `ls db/migration | sort -V | tail`.
- Pre-existing scale incoherence: old prompt showed Claude 0-10 but asked for bluebell_score 0-100;
  persisted rows are mixed-scale — NOT a clean baseline. Acceptance is golden-master + replay, not
  prod-data equality (Pass 0: only ~6 prod bluebell rows).
- Tests: per docs/engineering/test-improvement-standards.md (no any() in verify, no lenient(),
  no @SpringBootTest for unit scope).
- Commit discipline: commit locally, DO NOT push. Stage specific files, never `git add -A`
  blindly (working tree has had stray changes). Update CHANGELOG.md each commit.

## Acceptance (deploy is out-of-season-now = dormant; the scary part ships dormant)
1. Prompt goldens reviewed (done for C1).
2. Deterministic replay: simulated in-season cycle → BLUEBELL forecast_score rows land (1-5),
   rating-role produces expected verdict bands, hot topic fires.
3. Out-of-season prod NOW: zero behaviour change — no bluebell bucket, no BLUEBELL rows, cache
   goldens unchanged, Planner/hot-topics identical.
4. Real in-season proof is April 2027; component rows (SKY+BLUEBELL recorded separately) are the
   audit trail. Behaviour change is validated at VERDICT-BAND level (WORTH_IT/MAYBE/STAND_DOWN),
   NOT score equality — the in-season rating change is the POINT of the extraction.
