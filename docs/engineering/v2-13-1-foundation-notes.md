# v2.13.1 — Visitor foundation: implementation notes

Companion to `v2-13-visitor-investigation.md` (Pass 0) and
`photogenic-evaluator-architecture.md` (hypothesis). Records the ground facts, the
discrepancies found, and the design reconciliations made while implementing the
relocate-only visitor foundation. Where the prompt/hypothesis and the code disagreed, the
code won and the disagreement is noted here.

## A.0 — rating-shape finding (the fact the visitor depends on)

The sky/forecast Claude call returns the star **`rating` (1–5) directly and optionally** —
not 0–100 potentials mapped to stars downstream.

- Output schema declares `rating` as integer enum `[1..5]`, required
  (`PromptBuilder.buildOutputConfig`).
- `ClaudeEvaluationStrategy.parseEvaluation` reads
  `node.has("rating") ? node.get("rating").asInt() : null` — straight through, **nullable**.
- `SunsetEvaluation.rating` is that `Integer`, persisted directly. `fiery_sky` / `golden_hour`
  (0–100) are separate fields and do **not** feed the star rating.

Consequence: `SkyVisitor.evaluate` returns the parsed rating as-is. Because the rating is
nullable, the `Visitor` interface returns `OptionalInt` (empty = "no number this time"), not the
bare `int` the original prompt sketched — `OptionalInt.empty()` preserves today's behaviour where
an absent rating persists as `null`, not a fabricated star value. This is absence, not a veto.

## Discrepancy: triaged-out rating is NULL, not 1

CLAUDE.md and `ForecastService`'s own Javadoc (`:242`) say a triaged-out location gets
`rating=1`. The **code sets `rating=null`** — `ForecastService:367` / `:399` build the canned
triage entity with `new SunsetEvaluation(null, null, null, null)`. The downstream view branches
on this: `EvaluationViewService:233-241` renders `rating != null` as a *scored* result and
`triageReason != null` (with null rating) as a *triaged* result. So persisting a `1` for a
triaged location would reroute it into the scored branch and **drop the triage reason** — an
observable, non-equivalent change. This is why the triage-fail path must preserve `null`.
(Not fixed here — flagged for review; the Javadoc/CLAUDE.md wording is wrong, the code is right.)

## Discrepancy (load-bearing): there is no tide star-scorer to relocate

The prompt's Part B `TideVisitor` (B.3) assumed an existing deterministic tide scorer returning
1/4/5. **None exists.** The only tide logic in the scoring path is `TideAlignmentEvaluator`, a
**triage gate** returning `Optional<TriageResult>` (misaligned → skip, SEASCAPE-only) — it
produces no rating. Tide's effect on the rating today is **baked into the single Claude call**
via `CoastalPromptBuilder`'s prompt text ("tide boost +1 if sky ≥3, aligned king tide = 4★").

Therefore a `TideVisitor` + averaging is **new scoring, not a relocation**, and wiring it in
v2.13.1 would either double-count tide (coastal ratings change → fails equivalence) or require
making the sky prompt sky-only (prompt decomposition → v2.13.2, breaks the Part A golden masters).
**Resolution (agreed):** v2.13.1 ships **`SkyVisitor` only**; `TideVisitor` + averaging defer to
v2.13.2 alongside sky-prompt decomposition. With one applied visitor the combiner's output equals
the existing Claude rating for inland *and* coastal — provably equivalent.

## Coastal discriminator (recorded for v2.13.2's TideVisitor.appliesTo)

The live coastal/inland selection is `AtmosphericData.tide() != null`
(`BatchRequestFactory.selectBuilder:94`). At the `LocationEntity` level the underlying fact is
`location.toCoastalParameters().isCoastalTidal()` (consumed in `ForecastDataAugmentor:308` to
decide whether to attach tide data). v2.13.1's `SkyVisitor.appliesTo` ignores this (sky is
universal); v2.13.2's `TideVisitor.appliesTo` should key on the coastal-tidal discriminator so it
never applies to inland locations.

## Design reconciliations (where the prompt and the code's architecture collided)

1. **Triage stays a pre-visitor gate; it is NOT moved into `SkyVisitor`.** The chosen option was
   "triage in SkyVisitor, fail → null", but planning the wiring showed a Claude-owning,
   synchronous `SkyVisitor.evaluate` cannot serve the **asynchronous** batch path (the rating only
   exists later, in `ForecastResultHandler` after polling). Moving triage into the visitor would
   force the visitor to be sync-only → it could serve `evaluateNow` but not batch → a fork, which
   the pass forbids. Keeping triage as the existing gate (unchanged null+reason behaviour) lets the
   visitor operate at **result** time, which both transports share. Triage relocation (with a
   reusable `SkyTriage` collaborator) is deferred to v2.13.2. *(Code beat the prompt here; noted.)*

2. **The single seam is the result handler, not `BatchRequestFactory.selectBuilder`.**
   `selectBuilder` is the single *prompt-build* seam, but the visitor is about the *rating*, which
   does not exist at prompt-build time on the async path. The single point both batch
   (`parseBatchResponse`) and sync (`handleSyncResult`) converge with a rating in hand is
   **`ForecastResultHandler`** — that is where the `RatingCombiner` is wired. No fork: both
   transports flow through the same combiner call.

3. **`Visitor.evaluate` consumes the produced `SunsetEvaluation`, not raw forecast data.**
   v2.13.1 is relocate-only: the rating is already computed by the existing async/sync Claude
   machinery, so the visitor *re-expresses* it rather than recomputing (recomputing would require
   re-calling Claude — impossible on the batch path). v2.13.2 changes the signature when
   `SkyVisitor` owns its own sky-only call.

## What was built

- `service/evaluation/visitor/Visitor` — `appliesTo(LocationEntity)` + `evaluate(LocationEntity,
  SunsetEvaluation) → OptionalInt`. No triage on the interface.
- `service/evaluation/visitor/SkyVisitor` — `appliesTo` → true; `evaluate` returns the rating
  (empty when null). The existing whole-rating call relocated into a visitor.
- `service/evaluation/visitor/RatingCombiner` — plain average of applied visitors' scores,
  rounded half-up; `null` when no applicable visitor produced a score (preserves today's null).
- `SunsetEvaluation.withRating(Integer)` — lets the combiner-derived rating flow into the
  persisted payload.
- Wiring: `ForecastResultHandler` derives the persisted rating via `RatingCombiner.combine(...)`
  in both `parseBatchResponse` (batch → `cached_evaluation`) and `handleSyncResult` (sync →
  `cached_evaluation` and, via the returned `Scored(eval.withRating(...))`, → `forecast_evaluation`).

## Equivalence proof

- **Part A golden masters** (5 archetypes) stay green — the assembled prompt is unchanged.
- **`RatingCombinerTest`** — with the single `SkyVisitor`, combined rating equals the evaluation
  rating across 1–5 for inland *and* coastal, and is `null` when the rating is absent. Plus
  combiner mechanics (averaging, half-up rounding, non-applicable/empty exclusion, empty case)
  with stub visitors.
- **`SkyVisitorTest`** — universal applicability; faithful rating pass-through incl. the null case.
- **Boundary (no phantom tide, reframed):** exactly one visitor (`SkyVisitor`) applies to an
  inland location today — documenting that an inland rating is sky-only and tide joins in v2.13.2.
- **Touched suites green:** `ForecastResultHandlerTest`, `BatchResultProcessorTest`,
  `EvaluationServiceImplTest`, `ForecastServiceTest`, full-context `GoldenHourApplicationTests`.

## Briefing layer left untouched (confirmed)

`BriefingService`, `BriefingBestBetAdvisor`, and the best-bet / also-good logic were **not**
touched. `ForecastResultHandler` still calls `briefingEvaluationService.writeFromBatch(...)`
unchanged; the briefing layer receives the same `BriefingEvaluationResult` shape as before.

## Empirical acceptance (post-merge — green tests are not proof)

After a nightly cycle, per-location ratings in the Planner and `forecast_run_disposition` must be
indistinguishable from pre-v2.13.1: the batch path's `cached_evaluation` ratings flow through the
combiner but the combiner returns the same value (one visitor), and `forecast_evaluation` ratings
likewise. Check a sample of inland and coastal locations' ratings match expectation. Any shift
means the relocate was not equivalence-preserving and must be fixed before v2.13.2.

## Deferred to v2.13.2 (explicitly NOT in this pass)

- `TideVisitor` (rule-based) + non-trivial averaging.
- Sky-prompt decomposition (sky-only system prompt) — at which point the golden masters are
  regenerated intentionally and `SkyVisitor` narrows to sky-only.
- Relocating triage into `SkyVisitor` as a reusable `SkyTriage` collaborator (and a mist visitor).
- Routing the visitor's input from `SunsetEvaluation` to forecast inputs once `SkyVisitor` owns
  its own Claude call.
