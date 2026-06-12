# Changelog

All notable changes to PhotoCast are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Changed — Forecast-score re-architecture Pass 2, commit 1: expose per-visitor component scores from the combiner
- **Why.** Pass 0 flagged this as the riskiest line of Pass 2: `RatingCombiner.combine()` computed each visitor's score internally and returned only the averaged rating — the per-visitor SKY and TIDAL scores were discarded. The Pass 2 dual-write (commit 2) needs them, so this commit surfaces them while provably not moving any persisted rating.
- **The change.** `combine()` now returns a small `CombinedRating(rating, components)` record: the headline rating is computed exactly as before (half-up mean of applied visitors that returned a value), plus an additive `List<ComponentScore>` of `(type, score, summary)`. `Visitor` gains `type()` (SkyVisitor → `SKY`, TideVisitor → `TIDAL`) and a default `summary()`; `SkyVisitor` re-exposes Claude's prose, and `TideVisitor` authors a deterministic one-line clause per alignment state (king/spring/aligned/widened/misaligned) from a single `classify()` band shared by score and clause so the two can never disagree. The sole caller (`ForecastResultHandler.buildResult`) reads `.rating()` and gets the identical value.
- **Proof.** The `CachePayloadGoldenMasterTest` cache-payload golden master passes byte-for-byte unchanged (combined ratings identical before/after). Combiner unit tests extended to assert the exposed components — single SKY component inland, SKY+TIDAL coastal, an abstaining tide absent from the components — plus `type()`/`summary()` clause coverage on both visitors. No dual-write in this commit: the seam exists, nothing uses it yet.

### Fixed — React/react-dom version mismatch + `eslint-plugin-react-hooks` 7 upgrade (Dependabot batch)
- **react-dom realigned to 19.2.7.** Dependabot's react bump (PR #94) rebased ahead of its react-dom bump (PR #93), so `main` briefly carried `react@19.2.7` with `react-dom@19.2.6`. React requires the two packages to be the *exact* same version and otherwise throws `Incompatible React versions` at runtime, which failed the frontend Vitest job. Bumped `react-dom` to `19.2.7` to match.
- **`eslint-plugin-react-hooks` 5.1.0 → 7.0.1 (PR #22).** v7's `recommended` config enables the React Compiler lint rules (`purity`, `set-state-in-effect`, `refs`, `immutability`), which surfaced 11 errors in existing code. Resolved with real refactors where cleanly behaviour-preserving — moved `TurnstileWidget`'s latest-ref writes into an effect; derived `useAuroraViewline`'s disabled state instead of clearing it via `setState` in an effect; dropped redundant mount-time `setState` resets in `AuroraForecastModal`/`TideManagementView` (state already initialises to the pending values) — and targeted `eslint-disable` lines, each with a justification, for the five intentional patterns the rules flag overzealously (`Date.now()` snapshots for "logged in ago"/"days remaining" display, the post-auth verify-token cleanup coupled to a `history.replaceState`, the auto-dismissing run banner, and the `window.location.hash` tab-sync navigation).
- **No behaviour change.** 1563 frontend tests and the production build pass; lint is clean (0 errors).

### Fixed — Pipeline safety timeout lengthened to 4h and made configurable (afternoon Anthropic batch latency)
- **Why.** Intraday cycles (14:00 UTC) failed ~100% of the time on `Safety timeout: Batch set did not reach terminal status within PT1H30M`, while nightly cycles (01:00 UTC) always succeeded. Instrumented prod data shows why: Anthropic batch processing is load-dependent — nightly batches reach terminal in 2–5 min, but afternoon batches (peak US-morning/EU-afternoon demand) took 98–173 min, with **zero** request failures in `api_call_log` and the poller detecting terminal within seconds of `ended_at`. Every request succeeded; the batch simply completed after the 90-min backstop had already failed the run. The orchestrator and poller were working correctly; the timeout was mis-calibrated for the afternoon reality.
- **The change.** `PipelineOrchestrator`'s safety timeout default goes from 90 min to **4 hours** (clears the worst observed 173 min with margin) and is now injected via `photocast.pipeline.safety-timeout` (ISO-8601 duration, default `PT4H`) so it can be tuned without a deploy as the seasonal latency pattern becomes clearer. The timeout's character is unchanged: a FAILURE BACKSTOP for genuinely-stuck batch sets, not a coordination mechanism — 4h still bounds a dead cycle. The failure message already interpolates the configured value, so no message change was needed.
- **Shared benefit.** The RETRY_FAILED phase (PR #129) waits under this same timeout, so afternoon retry batches are unblocked too.
- **Deferred (recorded, not built).** A 4h timeout on a 14:00 UTC fire means completion as late as ~18:00 UTC — fine in summer, but in winter (sunset ~15:30 UTC) a 3h intraday run completes after the event it exists to inform. The proper fix is event-relative intraday scheduling (fire at soonest-event minus ~4h); this latency data is its concrete justification. See `docs/product/backlog.md`.
- **Empirical acceptance.** The next 14:00 UTC intraday cycle should COMPLETE (assuming afternoon latency ≤4h), visible in the Pipeline Runs view with real phase timings.

### Docs — `pipeline_run_pick` empty since run #21: confirmed honest decline-to-crown, not a persistence gap
- **The question.** `pipeline_run_pick` stopped gaining rows after run #20 — including for #24, a COMPLETED nightly whose briefing the Planner displays. Was that best-bet C honestly declining to crown a flat week (nothing broken), or a regression where computed picks are silently not persisted?
- **Verdict: honest decline; no code fix.** The Planner's "No standout recommendations" headline is frontend empty-state copy rendered only when `bestBets` is empty, and `BriefingHonestyFilter` passes `bestBets` through unchanged — so the #24 briefing demonstrably contains zero picks. The persistence hook writes exactly that same list (empty → documented `[PICK] No picks to persist` no-op), so display and table agree end-to-end. Best-bet C's coverage gate is reorder-only (it can never drop a pick) and returns the same `List<BestBet>` shape the hook always consumed — no mismatch is possible. Full chain, the prod log/SQL checks that distinguish honest-zero from a silent advisor failure, and the cross-run comparison view's already-graceful no-pick handling (`PickCell` renders "none"; both-empty hides the card) are in `docs/engineering/pipeline-run-pick-empty-investigation.md`.
- **Flagged for product decision (not built):** an explicit "declined to crown" record per run, and whether Planner copy should distinguish honest-flat from failure — see `docs/product/backlog.md`.

### Changed — Single tide-fact derivation seam (`TideFactDeriver`)
- **Why.** Two paths derived "the same" tide facts independently and could drift: the scoring path (`ForecastDataAugmentor.buildTideSnapshot`) and the hot-topic/briefing path (`BriefingSlotBuilder.calculateTideData`). Both bottomed out in the same library calls (`TideService.deriveTideData` + `LunarPhaseService.classifyTide` + the statistical height comparison) but assembled them separately. This is the technical debt: two assemblies of the same facts will eventually disagree. See `docs/engineering/hot-topics-tide-observer-refactor-design.md` (verdict ii: shared derivation, separate scopes).
- **The change.** New `TideFactDeriver.derive(...)` performs that derivation **once** and returns one `TideDerivation` carrying the complete union of facts. Both consumers now adapt their own existing presentation type from it (`TideSnapshot` for scoring, `TideResult`/`TideInfo` for briefing) and derive nothing themselves. Grep proof: `deriveDualWindowTideData` + `classifyTide` are each called from exactly one place — the new seam — and neither consumer calls `deriveTideData` at all.
- **Single fetch, both alignment windows.** The scoring path's 3★ "widened window" alignment flag is derived from the *same* tide curve as the tight flag — the extremes query depends only on location + event time, never on the window. So `TideService.deriveDualWindowTideData(...)` fetches the extremes **once** and classifies them at both windows; `TideDerivation` carries both `tideAligned` and `widenedAligned`. This removes the second `deriveTideData` fetch the combine seam used to make (scoring goes 2 fetches → 1; the briefing path, which ignores `widenedAligned`, stays at 1) and makes the seam the literal single tide call site.
- **Lossless statistical signals.** `TideDerivation` carries the two independent height booleans (`heightAboveP95`, `heightAboveSpringThreshold`) rather than only the collapsed `TideStatisticalSize` enum, because the briefing path needs both flags independently and the enum's P95-first collapse is only lossless when `p95 >= springThreshold` (not guaranteed). Scoring collapses to its enum via `TideDerivation.statisticalSize()`; briefing applies its own HIGH-tide ±90-minute gate around the raw booleans.
- **Pure refactor.** No behaviour change — produced hot-topic pills and scoring tide facts are identical before/after, proven by the existing consumer tests passing with unchanged assertions (a real `TideFactDeriver` is injected from the same mocks, so every stub and assertion drives behaviour through the new seam). `RatingCombiner`, the `HotTopic` model, the frontend, the simulation harness, and the two tide hot-topic strategies are untouched. New `TideFactDeriverTest` covers the king/spring height thresholds at value−1 / value / value+1.

### Added — Batch retry-on-failure: recover transient parse/API failures (RETRY_FAILED phase)
- **Why.** A real prod case: a 325-request batch had 1 parse failure — Haiku returned a garbled response (off-distribution text mixed with valid schema fragments) that failed JSON parse; 324/325 succeeded, the 1 failure was dropped, and that location went unevaluated for the cycle. This is a *transient* failure — asked again the model almost certainly returns valid JSON. A capped, single retry recovers these cheaply. Foundational batch-correctness work (robustness against transient failures), not a feature add.
- **The critical distinction (commit 1).** Retry ONLY genuinely-failed requests (attempted and came back unusable — parse failures / API errors), NEVER deliberate skips. The guarantee is **structural**: deliberate skips (`SKIPPED_*`) live in `forecast_run_disposition` and are never sent to the model, so they have **no `api_call_log` row**; the retry set is derived purely from `api_call_log` failures (`is_batch = true AND succeeded = false`) within the cycle's precursor batches, so a skip cannot enter it. `BatchRetryService.selectFailures(pipelineRunId)` parses each failed `custom_id` back to (location, date, event), de-duplicates, and drops any unparseable / non-forecast id (cannot be reconstructed → not retried blindly).
- **Cap is budget AND tripwire (commit 1).** `photocast.batch.retry-failure-cap` (default **5**). ≤ cap genuine failures → retry once; > cap → NOT retried, recorded loudly as a **systematic** failure to investigate (a prompt regression / model issue / bad input would fail again on retry and cost far more). Single pass, no retry loops — bounded per-cycle cost ≤ `cap × 1 retry`, a few pence at Haiku rates against ~£2.50/night.
- **Schema + phase enum (commit 1).** V106 adds `forecast_batch.is_retry` (a boolean, not a precursor FK: a cycle has up to four forecast batches and one retry may aggregate failures across several — tie-back is the shared `pipeline_run_id` + the flag). New `PipelinePhase.RETRY_FAILED` (conditional, between WAIT and BRIEFING) and `BatchTriggerSource.RETRY`.
- **Tests (commit 1)**: selection returns exactly the attempted-failures (skips, having no log row, cannot appear); cap boundary at N and N+1 (RETRY vs SYSTEMATIC); clean cycle and no-precursor-batches → no-op NONE; malformed / non-forecast custom ids dropped; duplicate failure rows de-duplicated.
- **Retry submission + RETRY_FAILED phase (commit 2).** `BatchRetryService.submitRetry` reconstructs each failed request into the SAME (location, date, event) request the precursor sent — re-load location, re-derive model tier by horizon, re-assemble atmospheric data via the same `ForecastService` path — and submits them as ONE retry batch tagged `is_retry`. A request that cannot be reconstructed (location gone, or weather has since turned unsuitable) is left failed and falls back to the next cycle. The orchestrator runs the conditional `RETRY_FAILED` phase inside the shared `waitAndBriefPhase` (both NIGHTLY and INTRADAY — single code path) between WAIT and BRIEFING: NONE → no phase recorded; SYSTEMATIC → phase records "not retried"; RETRY → submit, wait (reusing the existing `waitForBatchSetComplete`, which already polls by `pipeline_run_id`, so the retry batch is waited on and ingested through the normal `BatchPollingService → BatchResultProcessor` path — and shares the safety timeout), then record `"N failed, M retried, K recovered, J still-failed"`. Idempotent + single-pass: submission is skipped if a retry batch already exists, so a restart mid-phase resumes safely and a retry's own failures are never retried.
- **Recovery ingestion MERGES, never replaces (the data-loss guard).** `cached_evaluation` is keyed by region and holds the whole region's locations in one `results_json`; a retry batch carries only the recovered handful. Routing those through the normal replace-write (`writeFromBatch`) would wipe the region's originally-successful locations. New `BriefingEvaluationService.mergeFromBatch` overlays the recovered locations onto the existing entry (in-memory, falling back to the persisted `results_json` if the process restarted), and `BatchResultProcessor` selects merge-vs-replace on the batch's `is_retry` flag. Parsing / `api_call_log` / token accounting / status are identical for both — only the terminal cache write differs.
- **Tests (commit 2)**: `submitRetry` reconstructs + submits one `is_retry` batch with only the failed location; idempotent (no second submit when a retry exists); ignores non-RETRY selections; leaves unreconstructable requests failed; recovery summary formatting. `mergeFromBatch` preserves prior in-memory locations and overlays the recovered one, persists the combined set, and falls back to the DB prior when in-memory is absent (post-restart) so a retry can never shrink a region. `BatchResultProcessor` routes a retry batch through `mergeCacheKey` and never `flushCacheKey`. Orchestrator: clean cycle records no RETRY_FAILED phase; within-cap submits + records recovery; over-cap records systematic without submitting; INTRADAY exercises the same shared retry path.
- **Pipeline Run view surfacing (commit 3).** `PipelineRunBatch` gains `retry`, so the Pipeline Run detail view (Manage → Operations → Pipeline Runs) flags the retry batch with a "Retry" badge in "Batches in this cycle" — tied to its precursor(s) via the shared cycle id — while the precursor rows are unflagged. The `RETRY_FAILED` phase appears in the timeline (label "Retry failed requests") with its `"N failed, M retried, K recovered, J still-failed"` detail. Frontend test asserts the badge appears only on the retry batch and the phase row + recovery detail render.

### Added — `cached_evaluation` defensive instrumentation (tripwire for the 2026-06-06 empty-Planner failure mode)
- **Why.** On 2026-06-06 the batch demonstrably *wrote* 21 cache keys (logs prove it) yet the rows later vanished, leaving the Planner empty. The investigation established it was not a Postgres restore and not a manual delete, and the root cause is likely unknowable from existing evidence (`log_statement = none`, so no DELETE was ever recorded). The incident has not recurred. This is proportionate hardening — make any recurrence visible within one cycle and remove a latent footgun — **not** a root-cause fix (none is confirmed). See `docs/engineering/cached-evaluation-clear-investigation.md`.
- **Backwards-jump heartbeat.** `BriefingEvaluationService.recordCacheHealthHeartbeat()` runs on each briefing refresh (wired into the existing `onBriefingRefreshed` event hook — the natural cadence, no new scheduler). It logs `cached_evaluation health: rows=…, maxEvaluatedAt=…, distinctKeys=…` at INFO and compares against an in-memory last-seen baseline; if `maxEvaluatedAt` moves **backwards** or the row count **drops** by more than a small tolerance (with no intervening admin clear, which resets the baseline), it logs a **WARN** — turning the silent, weeks-cold 6-June mystery into a same-cycle alert. Side-effect-free: reads counts only, never mutates, never throws into the refresh path (failures are swallowed and logged).
- **Delete visibility.** `clearCache()` now logs a **WARN** before deleting — `cached_evaluation DELETE: deleteAll requested by {principal}, removing {n} rows` — so a genuine admin wipe is unmistakable in the app logs and a *silent* disappearance (no such line) positively points at a data-layer cause instead. (Operator follow-up, not code: enabling Postgres `log_statement='mod'` or a low `log_min_duration_statement` on `goldenhour-db` would add a second, infra-level record of any DELETE.)
- **Footgun removed.** `CachedEvaluationRepository.deleteByEvaluationDateBefore(...)` was unwired dead code (confirmed: zero callers in main and tests) — a date-scoped delete is exactly the shape that could produce a "drop recent, keep old" wipe if ever wired carelessly. Removed. Two read-only aggregate queries (`findMaxEvaluatedAt`, `countDistinctCacheKeys`) added for the heartbeat.
- **Admin wipe guarded.** `DELETE /api/briefing/evaluate/cache` now requires an explicit `?confirm=true` (400 otherwise) so the full-table wipe cannot be triggered accidentally; auth is unchanged (ADMIN-only) and the WARN audit always fires.
- **Tests**: heartbeat backwards-timestamp and row-count-drop both WARN; normal forward movement and a within-tolerance drop do not; first call establishes a baseline silently; a repository failure is swallowed (never throws) and logs `heartbeat failed`; an admin clear resets the baseline so the next heartbeat does not false-warn; `clearCache` logs the audit WARN with the row count before `deleteAll`; the admin endpoint rejects without `confirm` and proceeds (with the WARN) with it.

### Changed — Sky/tide decomposition: Claude scores the sky alone, a `TideVisitor` re-adds the tide (v2.13.2 step 3)
- **The monolithic coastal rating is now two separately-scored concerns averaged together.** Before: `CoastalPromptBuilder` folded the tide *into* Claude's single `rating` (a "+1 boost for an aligned tide"). After: the coastal prompt asks Claude to score the **sky alone**, and a deterministic, rule-based **`TideVisitor`** contributes a separate 1–5 tide score that `RatingCombiner` averages in (plain mean, round half-up — no gate, no cap, no weakest-link). This is the goal of the v2.13.x arc (option B from the investigation: decompose the prompt, re-derive tide post-batch). **This is the one intentional behavioural change in the arc — coastal ratings shift.**
- **`TideVisitor` — rule R1 (a misaligned tide PENALISES, it does not abstain), two concentric windows, derivability-gated.** `appliesTo` = a *tidal location* (non-empty `tideType`, a property of the location not the forecast). `evaluate`: tide **un-derivable** (data gap — no stored extremes) → `OptionalInt.empty()` (abstain → sky alone; a data gap must never penalise); **king/spring aligned within the tight golden/blue-hour window** → 5; **aligned within the tight window** (regular tide) → 4 (this tier is the existing `TideService.calculateTideAligned`, reused unchanged); **aligned only within the window widened ±60 min beyond each edge** → 3 ("imperfect tide, but a great sky could work an average foreground"); **outside even the widened window** → 1 (no foreground — drags the rating, the St Mary's Lighthouse case)
- **The widened window is the existing rule + 60 min, not a reimplementation.** `ForecastDataAugmentor.deriveTideContext` runs `calculateTideAligned` twice — once over tide derived with the existing golden/blue half-width window (tight, the snapshot's `tideAligned`), once with that window extended by `WIDENED_ALIGNMENT_EXTENSION_MINUTES = 60` (widened). The 3★ band is exactly "widened-aligned AND NOT tight-aligned". The extra widened derivation runs **only at the combine seam**, never in the forecast prompt path
- **Tide re-derived at the shared combine seam (option B), single path for batch + sync.** `ForecastResultHandler` injects `ForecastDataAugmentor` and re-derives the `TideContext` via `deriveTideContext(location, date, targetType)` (event time re-computed from `SolarService` — a pure, deterministic instant; no new persistence or per-request side-channel needed). Both `parseBatchResponse` and `handleSyncResult` flow through one shared `buildResult(...)` helper, so the two transports behave identically. A new `VisitorContext(SunsetEvaluation, TideContext)` threads the sky + tide inputs through `Visitor`/`SkyVisitor`/`TideVisitor`/`RatingCombiner`. The widened flag is carried on `TideContext` (not added as a field to the heavily-constructed `TideSnapshot` record — that would have churned 38 test sites including the untouchable prompt-regression suite)
- **Sky-not-forecast is now an honest, visible 1★ rather than a silent null.** When Claude returns a parseable response but omits the rating (`eval.rating() == null`), `buildResult` substitutes — *before* combining — `rating = 1`, a code-injected summary "Claude did not forecast the fiery sky and golden hour for this location", a null headline, and **no** triage fields ("evaluated but unscoreable", distinct from "triaged out"). Applies to all locations, coastal and inland. Branching here, ahead of the combine, also structurally prevents a coastal sky-empty location from ever being scored on tide alone (which would average to a misleading 4/5) — the tide is never reached
- **Sky-only coastal prompt.** `COASTAL_SYSTEM_PROMPT_SUFFIX` is now surge-only guidance (the tide-boost / king-tide rating instructions removed); the `Tide:` data line is gone from the coastal user message. The **storm-surge block is kept** — it is a foreground/safety concern, not a tide-rating lever
- **Golden masters regenerated intentionally** (the safety nets the earlier steps put in place did their job): prompt goldens `coastal-tidal` and `coastal-surge` (eyeballed: only the tide instruction + `Tide:` line removed, surge block intact); cache goldens `coastal-misaligned` (3★ → **2★** under R1: sky 3 + misaligned tide 1 → avg 2) and a new `sky-not-forecast` fixture (inland-shaped: pins 1★ + the exact not-forecast summary + null headline + no triage). `coastal-aligned` stayed byte-identical (sky 3 + spring tide 5 → avg 4, the value it already held) and **`inland` stayed byte-identical** (no `TideVisitor` applies — the proof nothing leaked). Mutation check still guards
- **Tests**: `TideVisitorTest` (11 — every tier + both abstain cases + both edges of the +60 boundary); `ForecastDataAugmentorTest` deriveTideContext cases (widened = tight + 60 reusing `calculateTideAligned`; outside-widened; inland short-circuit; data-gap abstain; event-time derivation via `SolarService`); `ForecastResultHandlerTest` (coastal aligned/misaligned/un-derivable averaging + sky-not-forecast for **both** inland and coastal, proving the pre-combine branch fires before tide). `RatingCombinerTest`/`SkyVisitorTest`/`CoastalPromptBuilderTest` updated for the new `VisitorContext` and surge-only coastal prompt
- **Untouched, as designed**: triage (the pre-submission gate), `BriefingService`/best-bet/also-good (consume the score unchanged), aurora (separate prompt path), and the `tide_extreme`/`TideSnapshot`/`calculateTideAligned` tight-window rule. Empirical acceptance (comparing nightly `cached_evaluation.results_json`) is a post-deploy human check — deploy this alone for a clean overnight bake

### Added — Cache-payload golden master: pin `results_json` before the v2.13.2 decomposition (v2.13.2 step 2, test-only)
- **`CachePayloadGoldenMasterTest`** + three fixtures (`src/test/resources/cache-golden/{coastal-aligned,coastal-misaligned,inland}.json`) pin the serialised per-region cache payload (`cached_evaluation.results_json` = `List<BriefingEvaluationResult>`) *before* any decomposition. This is the prerequisite the v2.13.2 investigation (§4) named: with no golden master on the cache payload, step 3's intentional change (sky-only coastal prompt drops coastal ratings ~1★, then `TideVisitor` re-adds via averaging) could not be distinguished from unintended drift (a field emptied, order changed, a rating moved unexpectedly). Same role the v2.13.1 prompt golden masters played
- **Pins structure + numerics + rating, tolerates prose.** Asserts the JSON field names + order and the deterministic fields exactly (`locationName`, `rating`, `fierySkyPotential`, `goldenHourPotential`, and the absent triage fields — null and so `NON_NULL`-omitted on the scored path); asserts `summary`/`headline` are present-strings only (headline may be legitimately absent) without pinning their text, because Claude prose is non-deterministic across runs and pinning it would generate false positives that train the team to ignore red. Fixtures store the payload with `summary`/`headline` values replaced by `<<SUMMARY>>`/`<<HEADLINE>>` placeholders; the test normalises the live serialisation the same way before comparing
- **Confirmed field order** (Jackson, record declaration order): `locationName, rating, fierySkyPotential, goldenHourPotential, summary, [triageReason], [triageMessage], [headline]`. **Confirmed serialisation seam**: `BriefingEvaluationService.persistToDb` serialises via the Jackson-2 `AppConfig.objectMapper()` bean (`new ObjectMapper().registerModule(JavaTimeModule)`) — *not* the Jackson-3 `tools.jackson` mapper the handler uses as a parser handle. The test serialises through a Jackson-2 mapper configured identically
- **Real construction seam, no Claude call.** Drives the real `ForecastResultHandler.parseBatchResponse` → real `RatingCombiner` over `SkyVisitor` → the exact `new BriefingEvaluationResult(...)` production builds, from a fixed `SunsetEvaluation` injected via a stubbed parser (the parser is upstream of, and distinct from, the serialisation seam). No `@SpringBootTest`, no `lenient()`, no `any()` in verify
- **Mutation-verified** (ran locally, reverted): flipping a pinned `rating` → FAIL; reordering/renaming a field → FAIL; changing only `summary` text → PASS (prose tolerance proven). Test-only; no production code, prompt, visitor, combiner, or scoring touched — safe to merge ahead of step 3

### Added — Visitor foundation: relocate the whole-rating Claude result into a `SkyVisitor` (v2.13.1)
- **`service/evaluation/visitor/Visitor`** — foundation interface for the v2.13 photogenic-evaluator architecture: `appliesTo(LocationEntity)` plus `evaluate(LocationEntity, SunsetEvaluation) → OptionalInt`. Two deliberate deviations from the original prompt sketch, code-grounded: (1) `OptionalInt` not `int` because the Claude `rating` field is nullable in the schema and parser, and `OptionalInt.empty()` preserves today's "no number this time" behaviour rather than fabricating a value; (2) the second arg is the produced `SunsetEvaluation` not raw forecast data because the rating only exists at result time on the asynchronous batch path — recomputing from inputs would require re-calling Claude, which a batch result handler cannot do. Both class Javadocs spell this out so the contract is honest about what v2.13.1 is and what changes in v2.13.2
- **`SkyVisitor`** — the only visitor in v2.13.1. `appliesTo` → true (sky is universal); `evaluate` returns the parsed rating as-is, empty when null. This is the existing whole-rating Claude result relocated, not a new evaluator
- **`RatingCombiner`** — plain arithmetic average of applicable visitors that produced a value, rounded to the nearest integer; `null` when no visitor produced a score. No weakest-link, no veto: a bad condition is just a low score that averages in. With only `SkyVisitor` applying today the "average" is over a single value and equals it exactly — combined rating is byte-equivalent to the pre-visitor rating for inland *and* coastal locations. Package-private `appliedVisitors()` accessor lets the boundary test assert "exactly one visitor applies to an inland location today" so future regressions are caught
- **`SunsetEvaluation.withRating(Integer)`** — record copy-with helper that lets the combiner-derived rating flow into the persisted payload, including the path where `ForecastService` later derives the `forecast_evaluation` row from the returned `Scored(eval)`
- **`ForecastResultHandler`** — wired the combiner into both convergence points. `parseBatchResponse` (batch → `cached_evaluation`) and `handleSyncResult` (sync → `cached_evaluation` and, via `Scored(eval.withRating(combinedRating))`, → `forecast_evaluation`) now go through `RatingCombiner.combine(...)`. This was chosen as the seam after planning showed it is the single result-time point both transports converge with a rating in hand; `BatchRequestFactory.selectBuilder` is the prompt-build seam, not the result seam
- **Triage stays a pre-visitor gate** — not on the `Visitor` interface, not moved into `SkyVisitor`. The "triage in SkyVisitor, fail → null" option from the prompt was rejected once the wiring was planned: a Claude-owning, synchronous SkyVisitor would force a sync-only visitor, fork the async batch path, and violate the pass's "no fork" rule. Triage relocation defers to v2.13.2 alongside sky-prompt decomposition
- **Equivalence proof, not just green tests**: the Part A golden-master prompt fixtures (added in commit `4ec6fe8`) stay green — the assembled prompt is unchanged. `RatingCombinerTest` (17 cases): with the single `SkyVisitor`, combined rating equals `eval.rating()` across 1–5 for inland *and* coastal, `null` when the rating is absent; plus mechanics — averaging, half-up rounding, non-applicable exclusion, empty case — via stub visitors. `SkyVisitorTest` (7 cases): universal applicability, faithful pass-through including the null case. Boundary: exactly one visitor applies to an inland location today (no phantom tide). Touched suites green: `ForecastResultHandlerTest`, `BatchResultProcessorTest`, `EvaluationServiceImplTest`, `ForecastServiceTest`
- **Discrepancy recorded (not fixed in this pass)**: CLAUDE.md and `ForecastService:242` Javadoc say a triaged-out location gets `rating=1`; the **code sets `rating=null`** (`ForecastService:367` builds `new SunsetEvaluation(null, null, null, null)`) and `EvaluationViewService:233-241` distinguishes scored (`rating != null`) from triaged (`triageReason != null` with null rating). The docs are wrong, the code is right; flagged for follow-up
- **Briefing layer untouched**: confirmed. `BriefingService`, `BriefingBestBetAdvisor`, best-bet / also-good logic unchanged; `ForecastResultHandler` still calls `briefingEvaluationService.writeFromBatch(...)` with the same `BriefingEvaluationResult` shape
- **Investigation doc**: `docs/engineering/v2-13-1-foundation-notes.md` records the ground facts, the discrepancies found, and the three design reconciliations where the prompt and the code's architecture collided. Companion to the Pass 0 investigation in `chore/v2-13-visitor-investigation`
- **What's deferred to v2.13.2 (explicitly NOT in this pass)**: a rule-based `TideVisitor` + non-trivial averaging; sky-prompt decomposition (regenerates golden masters intentionally); relocating triage into `SkyVisitor` as a `SkyTriage` collaborator; switching `Visitor.evaluate`'s second arg from `SunsetEvaluation` to forecast inputs once `SkyVisitor` owns its own Claude call

### Added — Cross-run comparison: did Plan A or Plan B change since this morning? (intraday refresh, commit 3 of 3)
- **The Pipeline Run detail view now shows an intraday-vs-nightly best-bet comparison** — the value signal the whole intraday refresh exists to produce. For an INTRADAY run it finds the same-day NIGHTLY baseline (latest nightly run on the same Europe/London day, at or before the intraday trigger) and surfaces, per pick rank (Plan A / Plan B), whether the pick changed and on which dimensions: region, event date, event type, or snapshotted Claude rating
- **A headline verdict — "plan changed" (amber) or "no change" (green)** — answers the "do I need to replan tonight's sunset / set an alarm for tomorrow?" question at a glance, with the now-vs-this-morning slots shown side by side. This is how the keep/tune/kill-intraday decision gets made without SSH
- **`PipelineRunComparisonService`** computes it from the existing `pipeline_run_pick` rows (no new persistence): rating drift below half a star is treated as forecast noise (not flagged), event-type matching is case-insensitive, and a pick present in one run but not the other is flagged by presence. Returns nothing for nightly runs (they are the baseline) or intraday runs with no same-day baseline
- Surfaced on `GET /api/admin/pipeline-runs/{id}` as a nullable `comparison` field on `PipelineRunDetail`; the `STABILITY_RECLASSIFY` phase's cost-gate detail now also renders in the phase timeline

### Added — Intraday forecast refresh runs as an INTRADAY cycle through the shared orchestrator (commit 2 of 3)
- **A mid-afternoon intraday refresh now runs daily at 14:00 UTC** (`intraday_forecast_refresh`, V105), re-evaluating the decision-window events — tonight's sunset, tomorrow's sunrise + sunset (the next ~36h of actionable events) — to catch within-day forecast movement before the morning's nightly plan goes stale. It is a *plan-change detector*, not a generic cache refresh
- **It reuses the exact same `PipelineOrchestrator.runCycle` path as nightly** — no parallel orchestrator, collector, briefing, or persistence. `runIntradayCycle()` is a thin peer of `runNightlyCycle()` differing only in: an `IntradayCandidateCollectionStrategy` (the decision window, clock-pinned to Europe/London "today"), an `IntradayEligibilityPolicy` (the skip-settled cost-gate), and an ephemeral re-classification. Wait, briefing, best-bet/also-good persistence and disposition recording are byte-for-byte the nightly code
- **Cost is bounded by stability.** SETTLED decision-window locations are skipped with no Claude call (recorded as `SKIPPED_NO_REFRESH_NEEDED` — a new skip category surfaced through `EligibilityDecision`, distinct from nightly's `SKIPPED_STABILITY`); only TRANSITIONAL/UNSETTLED locations are re-evaluated, on the near-term model. A typical cycle costs a fraction of nightly's
- **The re-classification is ephemeral.** It computes fresh stability for its own gating but does NOT call `stabilitySnapshotProvider.update()` — the morning's snapshot stays authoritative for every other consumer (other run types, the admin stability view). This is the one real new seam, threaded as a single `ephemeral` boolean
- **Intraday records four phases** — `STABILITY_RECLASSIFY → FORECAST_BATCH_SUBMIT → FORECAST_BATCH_WAIT → BRIEFING`. The collect→submit logic stays one shared implementation; a between-collect-and-submit hook (fired inside the batch service's concurrency guard) lets the orchestrator close `STABILITY_RECLASSIFY` (recording the cost-gate summary "N considered, M settled-skipped, K unsettled-evaluated") and open `FORECAST_BATCH_SUBMIT` between the two steps — so both have real, separate durations. Nightly keeps its single SUBMIT phase via a no-op hook
- Best-bet/also-good picks persist for the intraday run via the unchanged PR #113 hook, so the cross-run "did Plan A change vs this morning?" comparison (commit 3) is a query over existing data

### Added — Ephemeral stability re-classification + STABILITY_RECLASSIFY phase (intraday refresh, commit 1 of 3)
- **`ForecastTaskCollector.collectScheduledBatches` gained an `ephemeral` overload.** When `ephemeral=true`, the stability classification is still computed and returned in-memory (so it can drive the cycle's eligibility cost-gate) but the snapshot is **not** written through to `stabilitySnapshotProvider.update()`. This is the one real seam the intraday refresh needs: intraday re-classifies the decision-window cells with fresh afternoon weather purely for its own gating, then discards — the morning's nightly snapshot stays authoritative for everything else (other run types, the admin stability view). Nightly continues to pass `ephemeral=false` (classify + publish); behaviour byte-for-byte unchanged
- **`PipelinePhase.STABILITY_RECLASSIFY` added.** Intraday will record this as a distinct phase before `FORECAST_BATCH_SUBMIT` so the cost-gate decision ("N reclassified, M settled-skipped, K unsettled-evaluated") is visible in the Pipeline Runs UX rather than buried in logs. Nightly's phase set is unchanged
- Pure foundation commit — nothing yet invokes the ephemeral path; wiring follows in commit 2

### Added — Targeted force-evaluation backs far-out headline picks with real evidence (Option C commit 2 of 2)
- **`ForecastTaskCollector` now force-evaluates a small, capped set of far-out best-bet headline contenders** that the Gate 4 stability gate would otherwise drop. Without this, the coverage-aware selection (commit 1) would make best bet structurally near-term-biased — far-out cells are rarely Claude-evaluated, so a genuinely clear "the weekend looks special" day could never clear the coverage floor. Force-evaluation guarantees the would-be-crowned region actually reaches the floor, so a clear far-out day stays promotable *with real Claude evidence behind it*
- **It is a rule WITHIN the single eligibility loop, not a parallel path.** At the existing `!decision.eligible()` skip branch, a far-out (T+2/T+3) GO candidate that is a ranked headline contender is force-included on the far-term model instead of skipped. The forced eval flows through the same near/far × inland/coastal bucketing → same `EvaluationService.submit` → same Anthropic Batch API as every other evaluation
- **`selectForceEvalKeys`** ranks far-out region/event cells with evaluation-eligible GO slots by GO count, then tide-aligned count, then horizon (sooner first), and accumulates their slots up to the cap — so the highest-merit contenders get the budget. New `FORCE_EVALUATED` disposition category records each one for observability (`[BATCH DIAG] FORCE-EVAL` log line + `forced=` in the triage summary)
- **Capped by `photocast.batch.force-eval-cap` (default 6).** Per-cycle cost increase ≈ 6 far-term batch (Haiku) evals ≈ £0.005, ~0.2% of a ~£2.50 night — noise. Cap 0 disables the feature. The cap bounds the cost regardless of how many GO candidates the briefing contains
- Gate 4's general stability economics are unchanged for non-headline candidates — force-evaluation is the capped exception for headline contenders only

### Fixed — Best bet can no longer be crowned on cheap thresholds (coverage-aware selection, Option C commit 1 of 2)
- **`BriefingBestBetAdvisor` now enforces a headline Claude-coverage floor.** The Planner could crown a headline BEST BET on a large triage GO count while only a couple of that region's locations were actually Claude-evaluated — confidently promoting a barely-assessed, further-out day over a nearer, better-evidenced one (observed: Northumberland T+2, 2 Claude-rated, crowned over North Yorkshire Coast T+1, 5 Claude-rated). `GO` is a cheap threshold verdict, not an evaluation; only `claudeRatedCount` measures what was assessed
- **`applyCoverageAwareRanking`** runs after pick validation: a region cannot hold rank 1 when its `claudeRatedCount` is below `MIN_HEADLINE_CLAUDE_COVERAGE` (3, calibrated to sit between the 2-rated and 5-rated regions of the observed case) **and** a pick that clears the floor exists to crown instead. The gate is deliberately comparative — it only demotes a thin headline by promoting a genuinely better-evidenced pick; when no covered alternative exists the order is left as Claude ranked it. Extends `BriefingHonestyFilter`'s "rewrite at zero coverage" principle to the insufficient-coverage case at the crowning decision
- **Coverage is read once per region** from the same `getCachedScores` lookup the rollup JSON already uses (surfaced via `RollupResult.coverageByKey`), so cache call counts are unchanged. On promotion the new headline's `relationship`/`differsBy` are cleared and trailing picks' relationships recomputed so they stay coherent. Stay-home and aurora picks are exempt (aurora has its own clear-sky gate). The system prompt also now tells Claude not to crown thin coverage — the Java gate is the deterministic backstop
- A far-out day remains crownable **with evidence** — the targeted force-evaluation path (commit 2) is what raises headline contenders above the floor in the first place; this commit alone removes the wrong crowning

### Fixed — Stale briefings no longer record carried-forward picks (best-bet persistence follow-up)
- **`PipelineOrchestrator.persistPicksForCycle` now skips persistence when the briefing is stale.** On a below-threshold run, `BriefingService.refreshBriefing()` serves the last-known-good briefing whose `bestBets` are the *previous* cycle's picks carried forward. Persisting those against the current `runId` would have recorded the prior run's picks as this run's, silently corrupting the cross-run "did Plan A change?" comparison the table exists to power. The orchestrator now checks `briefing.stale()` and skips — only genuinely fresh picks are recorded
- **Minor cleanups** from a quality pass on the best-bet-persistence + orchestrator-extraction commits: `NightlyCandidateCollectionStrategy` / `NightlyEligibilityPolicy` constructors made private (all callers use the shared `INSTANCE`)

### Changed — Pipeline orchestrator extracted for cycle reuse (intraday refresh prep, commit 2 of 4)
- **`NightlyPipelineOrchestrator` renamed to `PipelineOrchestrator`** — the class is now the sequencer for any cycle type, not nightly's alone. Its mechanics (start run → submit → wait → brief → persist picks → complete) are shared single code path; cycles differ only in their strategy + policy
- **`runCycle(CycleType, CandidateCollectionStrategy, EligibilityPolicy)`** is the new generic entry point. `runNightlyCycle()` is now a thin caller of `runCycle(NIGHTLY, NIGHTLY_STRATEGY, NIGHTLY_POLICY)`. The intraday cycle (commit 3) will be another caller of the same `runCycle` with intraday's strategy + policy — no parallel orchestrator
- **`CandidateCollectionStrategy`** interface — per-cycle filter over `(date, targetType)` deciding which event slots enter the candidate set. `NightlyCandidateCollectionStrategy` accepts every future event (the existing behaviour). The intraday cycle's strategy will restrict to the decision window (T sunset, T+1 sunrise, T+1 sunset)
- **`EligibilityPolicy`** interface — per-candidate include/skip decision function. `NightlyEligibilityPolicy` encodes the Gate 4 horizon-depth table verbatim. The intraday cycle's policy will skip SETTLED with `SKIPPED_NO_REFRESH_NEEDED` and include only TRANSITIONAL/UNSETTLED
- **`ForecastTaskCollector.collectScheduledBatches(strategy, policy)`** is the new fully-parameterised entry point. The zero-arg overload is preserved as a convenience that uses nightly defaults — keeps the admin `submitForecastBatch()` path and any test harnesses working unchanged. The internal `collectForecastCandidates` gained a third parameter (the strategy) so the iteration's filter is the cycle's, not a hard-coded "accept everything"
- **`ScheduledBatchEvaluationService.submitForecastBatchForPipelineRun(runId, strategy, policy)`** — fully-parameterised cycle-aware variant. The single-arg overload remains as a convenience wrapping nightly defaults
- **Pure refactor — nightly behaviour identical.** All 17 existing `ScheduledBatchEvaluationServiceTest` cases, 39 `ForecastTaskCollectorTest` cases, 8 `CollectForecastTasksCachedGateTest` cases, and 15 existing `PipelineOrchestratorTest` cases pass with no behaviour assertion changes — only the stub / verify call shapes were updated to match the new method signatures. Verified Checkstyle clean
- The "single code path, parameterised" hard constraint is now structurally enforced: intraday wiring (commit 3) only needs to add two concrete impls + a scheduled job + a STABILITY_RECLASSIFY phase, without touching the orchestrator's coordination mechanics

### Added — Per-cycle best-bet / also-good persistence (intraday refresh prep, commit 1 of 4)
- **New `pipeline_run_pick` table (V104)** — one row per Plan A / Plan B pick the briefing phase emits. Captures the qualitative pick from the advisor (headline, detail, event id, region, confidence, relationship, differs_by) plus a numeric snapshot of the region's `claudeAverageRating` at persist time. The numeric rating is the cross-run comparison primitive: a future intraday run's Plan A rating can be compared directly to the same morning's nightly Plan A rating to detect "did anything actionable move?" — the value-proving signal intraday will exist for. Headline prose alone is too noisy to compare run-to-run; the rating snapshot makes the comparison crisp
- **`PipelineRunPickEntity` + `PipelineRunPickRepository`** — JPA entity mirrors the schema; finder returns picks ordered by `pick_rank` so the Pipeline Runs view renders Plan A then Plan B
- **`PipelineRunPickService.persist(pipelineRunId, picks)`** — maps `BestBet` → entity, parses the composite event id, snapshots `claudeAverageRating` by mirroring `BriefingBestBetAdvisor.appendClaudeScores`'s computation exactly (same `BriefingRatingStats.compute` call the advisor itself uses). Robust by construction: null pipelineRunId logs and returns; null/empty picks is a no-op; per-pick DB failures are caught and counted, so one bad row never aborts the others; aurora picks skip the lookup (no region-level rating exists); stay-home picks persist with null event/region/rating
- **`NightlyPipelineOrchestrator` BRIEFING phase now persists the picks** — `persistPicksForCycle(runId)` is called after `briefingService.refreshBriefing()` returns and before `completePhase(BRIEFING)`. Belt-and-braces try/catch around the call so a contract violation in the service (which itself catches internally) still cannot fail a BRIEFING phase whose briefing actually succeeded. Pick persistence is observability, not correctness
- **Tests**: 22 new tests across `PipelineRunPickServiceTest` (mapping, rating-snapshot boundaries, robustness, helper-function boundaries) and `NightlyPipelineOrchestratorTest.PickPersistence` (4 cases — happy path with 2 picks; empty pick list still persisted; null cached briefing skips persist; service contract violation does NOT fail the BRIEFING phase or run). All 15 existing orchestrator tests pass unchanged
- **Why ship this independently of intraday**: nightly runs now start accumulating a per-cycle record of their picks immediately. When intraday lands (commits 3 + 4), there's already a baseline of morning picks to compare against — no warm-up gap

### Added — Pipeline Run observability UX (commit 3 of 3)
- **New Pipeline Runs sub-tab** under Manage → Operations (added as the first sub-tab, before Job Runs). Lists the most recent 50 cycles with status pill, current phase, waiting_on / failure reason, and total duration. The list polls every 60s so a stuck cycle becomes visible without manual refresh
- **Pipeline run detail panel** — click any row to drill in. Renders:
  - Summary card with status, started / completed timestamps, total duration, current phase, and a prominent waiting_on (RUNNING) / failure reason (FAILED) line so the "is it stuck?" / "did the safety timeout fire?" questions answer themselves at a glance
  - Phase timeline (SUBMIT → WAIT → BRIEFING) with per-phase status, started timestamp, duration, and the final detail (WAIT's last waiting_on, or a FAILED phase's reason)
  - Batch list — every `forecast_batch` row tagged with this cycle id, each with a "Show dispositions" toggle that embeds the existing `DispositionBreakdown` view inline so the cycle → phase → batch → disposition hierarchy renders in one place. No SSH, no separate page navigation
  - Auto-refreshes every 15s while the run is RUNNING so waiting_on ticks live
- **Backend API**: `GET /api/admin/pipeline-runs` (recent list) and `GET /api/admin/pipeline-runs/{id}` (detail with phases + batches). ADMIN-only. `PipelineRunSummary` / `PipelinePhaseSummary` / `PipelineRunBatch` / `PipelineRunDetail` records carry pre-computed duration so the frontend doesn't recalculate; `PipelineRunBatch.jobRunId` is the deep-link key the frontend uses to embed `DispositionBreakdown`
- **`PipelineRunController`** (4 MockMvc tests: list shape, detail shape, 404 for unknown id, PRO_USER 403 on ADMIN-only endpoint)
- **`PipelineRunsView.jsx`** (7 vitest cases: list rendering with status pills, empty state, row-click selects, detail panel with phase timeline + batch list, disposition expand-on-toggle, live `waitingOn` surface, FAILED `failureReason` surface)
- **`AbstractControllerTest`** gains `@MockitoBean PipelineRunService` so all controller tests share one context
- The no-SSH bar is now satisfied: where is the pipeline, is it stuck, what's it waiting on, how long did each phase take, did anything fail — every question is answerable in the browser, and the cycle's batch dispositions are one click away

### Changed — Nightly pipeline orchestrator wired; daily_briefing cron retired (commit 2 of 3)
- **`NightlyPipelineOrchestrator` now owns the `near_term_batch_evaluation` cron**. Constructor takes `DynamicSchedulerService`; `@PostConstruct registerJobTarget()` binds the schedule to `runNightlyCycle()`. The orchestrator is the single source of truth for when a nightly cycle runs
- **`ScheduledBatchEvaluationService.registerJobTargets()` no longer registers `near_term_batch_evaluation`** — only `aurora_batch_evaluation` (aurora stays parallel to the orchestrated cycle). The legacy `submitForecastBatch()` entry point is retained for admin invocations and tests but is no longer wired to the cron
- **V103 migration deletes the `daily_briefing` row from `scheduler_job_config`** — the briefing now runs as the orchestrator's BRIEFING phase on actual batch completion (~01:15 in production), not at 04:00 on a ~3h time buffer. The `ScheduledForecastService.registerJobTarget("daily_briefing", ...)` call is retained as a no-op so a one-line revert path stays open
- **Effect in production**: the briefing runs ~2.75 hours earlier on a typical night, and is gated on actual completion of the batch set rather than a fixed clock time. The safety timeout backstop (90 min) fires only if batches genuinely stall — distinguishable from the retired coordination-timeout by the `Safety timeout:` prefix on the run's `failure_reason`
- **Tests**: `NightlyPipelineOrchestratorTest` gains 2 cases under a `Cron wiring` nested class (verifies near-term registration and null-scheduler no-op); `ScheduledBatchEvaluationServiceTest.registerJobTargets_registersExpectedKeys` updated to assert near-term is now NOT self-registered. 57 tests pass across the 4 affected classes (up from 55 in commit 1)

### Added — Nightly pipeline orchestrator spine (commit 1 of 3)
- **V102 migration** — new `pipeline_run` table (cycle identity + lifecycle: cycle_type / status / current_phase / waiting_on / trigger_time / completed_at / failure_reason) and `pipeline_run_phase` table (per-phase status + start/complete timestamps). Adds `forecast_batch.pipeline_run_id` so a cycle's batches are queryable as a set
- **`CycleType` / `PipelinePhase` / `PipelinePhaseStatus` / `PipelineRunStatus`** enums. `CycleType` reserves `INTRADAY` for the intraday refresh follow-on (no intraday phases wired today — those live in code when intraday is built)
- **`PipelineRunEntity` / `PipelineRunPhaseEntity`** + their repositories
- **`PipelineRunService`** — transactional CRUD + phase-lifecycle helpers (startRun / startPhase / completePhase / failPhase / updateWaitingOn / completeRun / failRun). Keeps the orchestrator's main method free of persistence noise so the sequence reads top-to-bottom
- **`NightlyPipelineOrchestrator`** — the sequencer. The one method `runNightlyCycle()` reads as `SUBMIT → WAIT → BRIEFING → COMPLETED`. WAIT is a synchronous DB poll over `forecast_batch.status` for batches tagged with this cycle's id — chosen over callbacks/futures so state is durable across restart and `waiting_on` is a queryable fact. WAIT+BRIEFING runs on a virtual thread so it doesn't tie up a scheduler thread. Safety timeout (default 90 min, expected ~10 min) is a failure backstop, clearly distinguished from the old time-buffer coordination. `ApplicationReadyEvent` listener resumes RUNNING cycles left by a process restart (mid-WAIT or mid-BRIEFING re-enter idempotently; mid-SUBMIT is marked FAILED — partial submission state can't be safely recovered)
- **Cycle-aware batch submission** — `BatchSubmissionService.submit(...)` gains an optional `pipelineRunId` parameter (5-arg overload; existing 4-arg signature retained for aurora batch / admin paths). `EvaluationService.submit(...)` gains a parallel 3-arg overload. `ScheduledBatchEvaluationService.submitForecastBatchForPipelineRun(Long)` is the new cycle-tagging entry point the orchestrator calls. Aurora batch path unchanged (passes null at the bottom)
- **`Clock` bean** added to `AppConfig` so `PipelineRunService` and the orchestrator both run deterministically in tests
- **Tests**: `NightlyPipelineOrchestratorTest` (13 cases — happy path with phase-state recording, zero-batch short-circuit, completion-detection boundary at N-1 vs N, safety timeout, submit/briefing phase failures, restart resume mid-WAIT / mid-BRIEFING / mid-SUBMIT, no-running-runs no-op, structural forecast-only / INTRADAY-enum reserved) and `PipelineRunServiceTest` (6 cases for the CRUD helpers). 19 new tests, 0 lenient stubs, no `@SpringBootTest`
- **Spine is not yet wired** — the scheduler still invokes `submitForecastBatch()` directly, and `daily_briefing` still fires at 04:00. Commit 2 swaps the cron target to `runNightlyCycle()` and retires the briefing cron
- **Aurora is untouched** — no aurora services are injected into the orchestrator; the briefing's read of `AuroraStateCache` remains a non-blocking volatile lookup unrelated to the cycle
- Verification: 19/19 new tests green; full verify green except 3 pre-existing environmental failures (Docker-dependent testcontainers tests `IntegrationTestBaseSmokeTest` + `ForecastBatchPipelineIntegrationTest`, stale-schema `LocationFailureTrackingUIDemo`) confirmed by `git stash` + rerun on main HEAD

### Removed — Stale SSE-path whitelist in JwtAuthenticationFilter (Pass 3.3.3, commit 4 tidy)
- **`JwtAuthenticationFilter.isSsePath`** — dropped the `/api/briefing/evaluate` entry from the SSE token-via-query-param whitelist. The endpoint was deleted in commit 2; the whitelist line was stale. The three surviving SSE endpoints (`/api/forecast/run/{id}/progress`, `/api/forecast/run/notifications`, `/api/status/stream`) remain whitelisted unchanged
- Found during acceptance grep for `/api/briefing/evaluate` references — the only stray hit beyond the controller's `@RequestMapping` base path (which legitimately serves `/scores` and DELETE `/cache`)
- Tests: `JwtAuthenticationFilterTest` passes; covered by integration coverage of the three surviving SSE endpoints

### Removed — SSE service methods + 7 constructor dependencies pruned (Pass 3.3.3, commit 3)
- **`BriefingEvaluationService`** — file shrinks 660 → 348 lines. Deleted the 7 SSE-only methods (`evaluateRegion`, `evaluateSingleLocation`, `getEvaluableLocationNames`, `emitCachedResults`, `completeEmitter`, `sendSafe`, `cancelOutstandingForecastBatches`) plus the orphaned `getCachedEvaluatedAt` (only the deleted `/cache/timestamp` endpoint called it). After this commit no caller exists in the codebase — including admin features — for the Anthropic batch-cancel API; the cancel method body is gone, not just unreachable
- **Constructor dependencies pruned (the riskiest change in this commit)** — 7 of the 12 constructor params dropped: `LocationService`, `BriefingService`, `ForecastService`, `ModelSelectionService`, `JobRunService`, `ForecastBatchRepository`, `AnthropicClient`. Each was confirmed to have zero remaining usage in the class before removal. Surviving deps: `CachedEvaluationRepository`, `EvaluationDeltaLogRepository`, `ObjectMapper`, `FreshnessResolver`, `StabilitySnapshotProvider`. The deletion is in a single deliberate commit so the test-class mock-list fan-out is visible in one diff
- **`BriefingEvaluationServiceTest`** — rewritten from 1643 lines (≈60 SSE-based tests) to 478 lines (23 batch-path tests). Every surviving test exercises a surviving service method: `writeFromBatch` (replace semantics, DB persistence, upsert preserves `evaluatedAt`, multi-result serialisation, DB-failure resilience), `hasEvaluation` / `getCachedScores`, `onBriefingRefreshed` (cache retention), `clearCache` (idempotency, count, DB clear), and `rehydrateCacheOnStartup` (today-or-future filter, rating clamp, corrupt-row resilience, multi-entry round-trip). No SSE-only behaviour assertions remain
- **`BriefingEvaluationServiceCacheFreshnessTest`** + **`EvaluationDeltaLogTest`** — updated to the pruned constructor signature. Test bodies unchanged
- **`AbstractControllerTest`** — unaffected because it uses `@MockitoBean`, which never calls the real constructor
- **Acceptance criterion 1 (final state)**: `grep -rn "batches().cancel" backend/src/main` returns zero hits. Nothing in the codebase can cancel an in-flight FORECAST batch via the Anthropic API, by deliberate intent or otherwise. The "100% reliable batch" objective the retirement was framed around is satisfied
- Verification: 3626 backend tests pass; the 2 Docker-dependent integration tests (`IntegrationTestBaseSmokeTest`, `ForecastBatchPipelineIntegrationTest`) fail on environmental Docker unavailability — confirmed pre-existing across commit 1, 2 and 3 HEADs by stash-and-rerun
- Frontend untouched in this commit

### Removed — Backend SSE endpoints + cancel invocation (Pass 3.3.3, commit 2)
- **`BriefingEvaluationController`** — deleted the SSE evaluation endpoint (`GET /api/briefing/evaluate`, the `SseEmitter` producer), `GET /api/briefing/evaluate/cache` (per-region cache read), and `GET /api/briefing/evaluate/cache/timestamp` (formatted-time read). All three had no surviving callers after commit 1. Pruned the controller's now-unused `forecastExecutor` constructor injection and the `SseEmitter` / `CompletableFuture` / `BriefingEvaluationResult` imports. `GET /scores` and `DELETE /cache` survive — they're the merged-view endpoint (consumed by Plan + Map tabs) and the admin clear-cache escape hatch
- **`BriefingEvaluationService.evaluateRegion`** — removed the `cancelOutstandingForecastBatches()` invocation at line 152. The method body still exists (commit 3 deletes it) but is no longer reachable from any path. **Acceptance criterion 1 is now satisfied**: nothing in the codebase can cancel an in-flight FORECAST batch via the Anthropic API without deliberate intent, because the one and only caller is gone
- **`BriefingEvaluationControllerTest`** — replaced 13 SSE/cache-endpoint tests with the 3 surviving DELETE `/cache` tests (admin allowed + count, PRO_USER 403, unauthenticated 401). Coverage for `/scores` lives elsewhere
- **`BriefingEvaluationServiceTest`** — deleted the 7 `cancelOutstandingForecastBatches_*` test cases that asserted cancel was called as a side-effect of `evaluateRegion` (lines 914–1015) plus `cancel_twoForecastBatches_bothCancelled` and `evaluateRegion_fullCacheHit_stillCancelsBatch` (lines 1084–1135). Once the side-effect was removed they had no subject; the cancel method itself dies in commit 3. Pruned now-unused imports: `MessageService`, `BatchService`, `ForecastBatchEntity`, `BatchType`, `doThrow`
- Verification: 3658 backend tests pass; the 2 failures are testcontainer integration tests (`IntegrationTestBaseSmokeTest`, `ForecastBatchPipelineIntegrationTest`) that need Docker, which isn't running on this dev machine — confirmed pre-existing by stashing and re-running on commit 1's HEAD
- Frontend untouched in this commit

### Removed — Frontend SSE evaluation consumer (Pass 3.3.3, commit 1)
- **`frontend/src/api/briefingEvaluationApi.js`** — removed `subscribeToBriefingEvaluation` (EventSource consumer for `/api/briefing/evaluate`) and the dead `getCachedEvaluationScores` REST helper. The file now exports only `getAllEvaluationScores`, which the Plan and Map tabs use to hydrate batch-written scores on mount
- **`DailyBriefing.jsx`** — removed `handleRunEvaluation` / `handleStopEvaluation` callbacks, the `evaluationProgress` / `evaluationTimestamps` state, the `evalCleanupRef` cleanup, the `getAvailableModels` cost-estimate fetch, and the `activeModelName` state. Renamed the duplicated `canRunEvaluation` / `canSeeBestBets` locals to a single `isPro` flag (they were identical role checks). The `evaluationScores` Map survives, populated only by `getAllEvaluationScores()` on mount
- **`HeatmapGrid.jsx`** — deleted the per-region "Run full forecast" button, its LITE-greyed variant with the Pro pill, the confirmation dialog with cost estimate, the running / error / complete progress UI, and the timestamp display on the mean-score badge. Dropped now-unused imports: `useConfirmDialog`, `ProPill`, `COST_PENCE_PER_CALL`. The drill-down panel still shows location slots with their batch-written ratings; on-demand re-evaluation is no longer available
- **`DailyBriefing.test.jsx`** — deleted the `Evaluation confirmation dialog` describe block (6 tests covering the SSE button, dialog, and subscription); dropped the SSE-related `vi.fn()` mocks
- Motivation: removing the frontend SSE consumer first lets commit 2 delete the backend endpoints + `cancelOutstandingForecastBatches()` invocation without breaking the running app. The cancel call is the only path by which a user interaction can kill an in-flight `FORECAST` batch via the Anthropic API — retiring SSE before intraday refresh ships removes the cost coupling
- Investigation: `docs/engineering/sse-retirement-investigation.md` (untracked at time of this commit; mapped the SSE-ONLY vs SHARED boundary across `BriefingEvaluationService`'s 663 lines, the 7 SSE-only methods, the 7 constructor dependencies that become orphans, the dead REST companions, and the deletion sequence this prompt implements)
- Tests: 1535 frontend tests pass; backend untouched in this commit

### Fixed — Map star-threshold filter hid briefing-rated locations
- **`MapView.getRatingForLocation`** — now consults `briefingScores` before falling back to `forecastsByDate.[type].rating`, matching the precedence the marker render path has used all along (line ~901, `briefingScore?.rating ?? forecast?.rating`). Before this fix, a location whose rating came only via `cached_evaluation` (i.e. the Plan tab's briefing enrichment) would render a 3★ medallion on the marker but get filtered out by the star-threshold filter — `getRatingForLocation` returned `null`, the filter routed it to the `showUnrated` branch (default off), and the marker disappeared. Visible bug: selecting 3★ on a Sunset map with a 3★ briefing-only location (e.g. College Valley) emptied the map
- Test: 1 new `MapViewStarFilter.test.jsx` regression case (`briefingScore.rating survives the star-threshold filter`)

### Fixed — Map tab missing future-date forecasts that Plan tab shows
- **`ForecastController.getForecasts`** — merge cached-only rows so the Map date strip surfaces future dates that the batch pipeline has scored but not yet persisted as full `forecast_evaluation` rows. Before this fix, the Map tab read only `forecast_evaluation` directly, so the date strip cut off at today while the Plan tab (which reads `cached_evaluation` via `BriefingService.enrichWithCachedScores`) correctly showed T+1 through T+3. The two views now share the same source-of-truth boundary that `EvaluationViewService`'s Javadoc has always promised
- **`ForecastDtoMapper.toSparseDto`** — new method that synthesises a sparse `ForecastEvaluationDto` from a `LocationEvaluationView` whose source is `CACHED_EVALUATION`. Atmospheric, tide, surge and inversion fields are left null (cached_evaluation doesn't carry them); solar event time + azimuth, golden/blue hour windows, lunar tide type and lunar phase are computed deterministically from the location's lat/lon so the marker and popup render sensible scaffolding
- **Merge rule** — when the same `(location, date, type)` tuple has both a `forecast_evaluation` row and a `cached_evaluation` entry, the rich row wins; the cached entry is dropped as a duplicate
- Tests: 2 new `ForecastControllerTest` cases (`getForecasts_surfacesCachedOnlyRows`, `getForecasts_prefersForecastRowOverCachedDuplicate`); `EvaluationViewService` added to `AbstractControllerTest` mock superset

### Added — SCHEDULED_BATCH summary line on metrics page (v2.11.18)
- **`BatchSummary` record** (`backend/.../model`) — new read-only enrichment carrying horizon range, event types, evaluation model, location count, region count, and extended-thinking flag for batch job runs
- **`HorizonRangeFormatter`** (`backend/.../util`) — pure utility that turns a set of day-ahead offsets into a compact label (`T`, `T+1`, `T to T+2`, `T, T+2`)
- **`BatchSummaryDeriver`** (`backend/.../service`) — derives `BatchSummary` for `SCHEDULED_BATCH` / `BATCH_NEAR_TERM` / `BATCH_FAR_TERM` rows at read time by inspecting linked `api_call_log` rows. Region count resolved by parsing the `customId` (`fc-{locationId}-…`) and joining `locations.region_id`. No schema change; works for historical batches that have logged result rows
- **`JobMetricsController.getJobRuns`** — populates a new `@Transient batchSummary` field on each row before returning. Non-batch run types are unaffected
- **`JobRunsGrid.jsx`** — renders one extra summary line for batch rows (`T+1 · SUNRISE · 29 locations · HAIKU · 7 regions`), or "No detail available" when the run has no usable call rows
- Tests: 8 `HorizonRangeFormatterTest` cases, 10 `BatchSummaryDeriverTest` cases, 2 new `JobMetricsControllerTest` cases, 6 new `JobRunsGrid.test.jsx` cases

### Fixed — V84 migration tolerant of missing locations (v2.12.2)
- **`V84__add_bluebell_support.sql`** — replaced two `INSERT INTO location_location_type (location_id, location_type) VALUES (40/87, 'BLUEBELL')` statements with `INSERT…SELECT…WHERE EXISTS` against `locations.id`. The original hardcoded IDs 40 (Allen Banks) and 87 (Roseberry Topping) work in production where those rows exist from accumulated seed data, but fail the V8 foreign key against any fresh database — including the Postgres testcontainer introduced in v2.12.2's integration test pyramid. The bug was latent because existing `@SpringBootTest` tests use H2 with `ddl-auto=create-drop` and skip Flyway entirely. Production behaviour is unchanged: where IDs 40 and 87 exist, the new statement produces identical rows. The `NOT EXISTS` predicate also guards against accidental replay
- **`V84__add_bluebell_support.sql`** — additionally added a section-0 backfill seeding regions `id=6 Teesdale` and `id=7 The North York Moors` with `ON CONFLICT (id) DO NOTHING`. V31 only seeds regions 1–5 via Flyway; regions 6 and 7 were added later in production via the API and never made it into a migration. V84's downstream location inserts reference `region_id=6` and `region_id=7`, which failed the `locations.region_id` FK on fresh databases. Production behaviour is unchanged (rows already exist, INSERT is a no-op). A `setval` on the regions identity sequence prevents future name-only INSERTs from colliding with the explicitly-seeded IDs
- Note: production Flyway has `validate-on-migrate: false`, so the changed migration checksum won't block redeploy. No equivalent latent FK bugs found in other migrations (V60 has many hardcoded `UPDATE…WHERE id = N` statements but UPDATE on a non-existent row is a clean no-op, not a constraint violation)

### Added — Test dependencies for integration testing pyramid (v2.12.2 prep)
- **Testcontainers** (`spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql`) — versions managed by Spring Boot 4.0.5 testcontainers-bom (2.0.4); enables Postgres-backed integration tests that run real Flyway migrations, matching production exactly instead of relying on H2 with `ddl-auto=create-drop`
- **WireMock** (`org.wiremock:wiremock-standalone:3.10.0`) — stubs Anthropic API endpoints at the HTTP boundary so the full forecast batch pipeline (BatchRequestFactory → BatchSubmissionService → BatchPollingService → BatchResultProcessor → cache write) can be exercised without burning API cost; standalone variant chosen to avoid classpath conflicts with Spring's Jetty/Jackson
- **Awaitility** — version managed by Spring Boot (4.3.0); replaces `Thread.sleep` for condition-based polling in async integration tests

### Added — Integration test base class with testcontainers Postgres (v2.12.2)
- **`IntegrationTestBase`** — `@SpringBootTest` base for tests that need a production-shaped schema. Spins up a `postgres:17-alpine` container (same image as `docker-compose.yml` production), enables Flyway, switches Hibernate to `ddl-auto=validate`, registers a WireMock JUnit 5 extension on a dynamic port, and exposes its URL as `photocast.test.anthropic-base-url` via `@DynamicPropertySource`
- **`WireMockAnthropicClientTestConfiguration`** — `@TestConfiguration` that supplies a `@Primary` `AnthropicClient` bean built with `AnthropicBackend.builder().baseUrl(wiremockUrl)`, routing all SDK calls (single message and batch) at WireMock without changing production code
- **`DynamicSchedulerBootstrap`** — extracted the `@EventListener(ApplicationReadyEvent.class)` listener out of `DynamicSchedulerService` into its own `@Component` annotated `@Profile("!integration-test")`. The split keeps `DynamicSchedulerService.registerJobTarget()` available to all profiles (so batch services can still register their job targets) while the cron firing itself is suppressed under the `integration-test` profile
- **`IntegrationTestBaseSmokeTest`** — four-assertion smoke test proving the base works: Postgres reachable on v17, V68 seed rows present (Flyway ran), `DynamicSchedulerBootstrap` absent from context (profile gate works), Anthropic client routed to WireMock (SDK base URL override works)
- **`AnthropicWireMockFixtures`** — stub builders for `POST /v1/messages/batches`, `GET /v1/messages/batches/{id}`, and `GET /v1/messages/batches/{id}/results`, plus `BatchResultFixture` records with success and error factory methods that render JSONL stream lines matching the SDK's deserialiser shapes
- **`ForecastBatchPipelineIntegrationTest`** — four-test class (happy path + errored-only + submission failure + multi-region split + mixed batch) exercising the full `BatchSubmissionService → BatchPollingService → BatchResultProcessor → cache write` chain via WireMock-stubbed Anthropic endpoints. Asserts `cached_evaluation` JSON contents, `api_call_log` per-row fields (custom_id, target_date, target_type, error_type, token counts), and `forecast_batch` lifecycle transitions
- **`ForecastBatchPipelineRealApiE2ETest`** — auto-skipped without `ANTHROPIC_API_KEY`; with the key, submits one Haiku request through the production primitives against the real Anthropic API, polls with Awaitility (4-min cap, 5-min `@Timeout`), and asserts the same end-state invariants as the WireMock test. Companion to `BatchSchemaIntegrationTest` from `e91376f` — the schema test is the narrow fast-fail guard, this is the broad full-pipeline contract test
- **`ForecastBatchRepository.findByAnthropicBatchId`** — small additive Spring Data finder that the integration tests use to look up the batch row for assertions; not yet used by production code

### Changed — CI workflow gains a real-API E2E gate (v2.12.2)
- **`.github/workflows/ci.yml`** — new `e2e-real-api` job runs on `main` pushes only (not PRs), depends on the standard `backend` job, sets `ANTHROPIC_API_KEY` from repo secrets, and runs `mvn verify -Dtest='BatchSchemaIntegrationTest,*RealApiE2ETest' -Dsurefire.skipAfterFailureCount=1`. Schema test runs first by alphabetical order; if it fails, the broader pipeline test is skipped to avoid a second wasted Anthropic call. Cost per main push: ~2 Haiku batch inferences (sub-pence each). Prerequisite: `ANTHROPIC_API_KEY` must be set as a repository secret

### Added — Batch observability: per-request api_call_log persistence
- **`api_call_log` persistence for batch results** — every request in a completed Anthropic batch (success or failure) now writes a row to `api_call_log` with `is_batch=true`, `custom_id`, `batch_id`, `error_type`, token counts, and decoded `target_date`/`target_type`. Turns ephemeral log output into durable, queryable forensic data that survives log rotation.
- **`describeFailedResult()` NPE guard** — Anthropic SDK errored results with a null error chain no longer abort the entire processing loop; the error is caught, logged as "unknown", and processing continues to the next request
- **V99 migration** — adds `custom_id` (VARCHAR 64), `error_type` (VARCHAR 100), `batch_id` (VARCHAR 100) columns to `api_call_log`; widens `error_message` from VARCHAR(500) to TEXT; adds partial indexes on `(is_batch, called_at)` and `(custom_id)`
- **`JobRunService.logBatchResult()`** — new method for persisting batch result rows with token-based cost calculation; all DB writes are try/catch-guarded — persistence failures never break batch processing
- **Tests** — 6 new tests: succeeded/errored result persistence, persistence failure resilience, no-jobRunId skip, parse failure persistence, describeFailedResult NPE guard

### Changed — Stability-driven cache freshness for overnight batch

- **`FreshnessProperties`** — new `@ConfigurationProperties("photocast.freshness")` class with per-stability thresholds: SETTLED=36h (blocking persistence), TRANSITIONAL=12h (half synoptic cycle), UNSETTLED=4h (nowcasting window), safety floor=2h
- **`FreshnessResolver`** — shared primitive resolving `ForecastStability` → `Duration` maxAge, used by both the overnight batch CACHED gate and (future) intraday refresh; applies safety floor; logs effective thresholds at startup
- **`ScheduledBatchEvaluationService`** — CACHED gate now uses stability-driven freshness instead of flat 18h; looks up most volatile stability per region from the latest snapshot; DIAG output enriched with stability level, threshold, and per-stability breakdown summary
- **`BriefingEvaluationService`** — `writeFromBatch()` logs rating deltas to `evaluation_delta_log` for empirical threshold refinement; delta logging is try/catch-guarded — failures never break the cache write path
- **V97 migration** — `evaluation_delta_log` table with stability/age/rating-delta columns and an index on `(stability_level, age_hours)` for post-deploy analysis
- **Config** — removed `photocast.batch.cached-gate-freshness-hours`; replaced with `photocast.freshness.{settled,transitional,unsettled,safety-floor}-hours`
- **Tests** — 19 new tests: `FreshnessResolverTest` (7: per-stability returns, safety floor enforcement), `CollectForecastTasksCachedGateTest` rewritten (8: SETTLED/TRANSITIONAL/UNSETTLED skip/refresh, no-snapshot fallback), `EvaluationDeltaLogTest` (3: delta insert, no-prior-entry skip, failure resilience); existing `ScheduledBatchEvaluationServiceTest` updated

### Fixed — Unified score reads for Plan and Map tabs
- **Bug A**: Map tab didn't render batch-evaluated scores — it only read `forecast_evaluation`, while batch results live in `cached_evaluation`
- **Bug B**: Sunset toggle disabled for dates where only `cached_evaluation` had data — availability check only looked at `forecastsByDate`
- **`EvaluationViewService`** — new merge layer combining both data sources with clear precedence: cached evaluation > scored forecast row > triaged forecast row > none
- **`LocationEvaluationView`** — new record carrying merged evaluation state (rating, summary, fiery/golden scores, triage fields, source indicator)
- **`BriefingEvaluationController`** — new `GET /api/briefing/evaluate/scores` endpoint returning all merged scores for the Map tab
- **`BriefingService`** — `enrichWithCachedScores()` now delegates to `EvaluationViewService.getScoresForEnrichment()`, supplementing cache hits with `forecast_evaluation` fallback
- **Frontend** — `DailyBriefing` hydrates `briefingScores` from the new endpoint on mount; `MapView` sunset toggle checks both `forecastsByDate` and `briefingScores`
- **Tests** — `EvaluationViewServiceTest` (10 tests: merge precedence, mixed states, enrichment delegate); `MapViewSunsetToggle.test.jsx` (2 tests: toggle enabled from briefingScores, disabled when empty)

### Fixed — Map popover shows triage reason for briefing-cache stand-downs
- **`MarkerPopupContent`** — new `briefingScore` prop; when `forecast` is null but `briefingScore.triageReason` is set, renders the stand-down badge with `triageMessage` instead of the "no forecast yet" divider + Run Forecast button; marker was already rendering the dashed medallion correctly, only the popover body was wrong
- **`StandDownBadge`** — extracted shared helper; reused by both the scored-forecast triage branch and the new briefing-cache triage branch (removes duplicated dark-red styling)
- **`MapView`** — passes computed `briefingScore` to `MarkerPopupContent` at both call sites (desktop popup and mobile bottom sheet)
- **Tests** — 11 new tests across 2 files. `MarkerPopupContent.test.jsx` (8): triage message rendered when `briefingScore.triageReason` set, no Run Forecast button in that state, empty-state branch unchanged when `briefingScore` null, location header + region sub-row preserved, parameterised label-map check across all 5 `TriageReason` values, no message row when `triageMessage` is null, scored `briefingScore` (no `triageReason`) still falls through to empty state, forecast branch takes precedence over `briefingScore.triageReason`. New `MapViewBriefingScoreWiring.test.jsx` (3): verifies MapView looks up and passes the exact score object reference, passes null when no entry matches, and hands the same object identity to both the cache lookup and the popup prop

### Changed — Also Good surfaces a genuinely distinct opportunity
- **`BestBet`** — new `relationship` (SAME_SLOT / DIFFERENT_SLOT) and `differsBy` (DATE / EVENT / REGION) fields distinguish tier-1 same-slot backups from tier-2 different-slot alternatives
- **`BriefingBestBetAdvisor` system prompt** — tiered "Also Good Selection Rule": Tier 1 picks same-slot region within 0.5 rating and ≥3.5 absolute; Tier 2 picks the best opportunity on a different date/event; no rank 2 emitted when neither tier clears threshold
- **`parseBestBets()`** — parses `relationship` and `differsBy` from Claude response; unrecognised values silently dropped
- **Frontend** — `BestBetBanner` PropTypes extended with `relationship` and `differsBy`; card labels already derive from each pick's own `dayName`/`eventType`/`eventTime` so tier-2 picks naturally show different dates/events

### Added — Drill-down Claude scores in briefing
- **`BriefingSlot`** — 4 new nullable fields: `claudeRating`, `fierySkyPotential`, `goldenHourPotential`, `claudeSummary`; convenience constructor for backward compatibility; `withClaudeScores()` copy method
- **`BriefingService`** — new `@Lazy BriefingEvaluationService` dependency; `enrichWithCachedScores()` walks the day/event/region hierarchy after `buildDays()` and populates each slot's Claude fields from the evaluation cache
- **Frontend drill-down** — `LocationSlotList` in `HeatmapGrid.jsx` merges backend-cached and SSE scores; collapsed rows show first-sentence preview (100 chars); per-row expand/collapse with full summary + Fiery Sky / Golden Hour secondary scores; `HeatmapCell` falls back to backend-cached scores for mean badge

### Fixed — Sonnet token exhaustion and schema compliance
- **`EvaluationModel.getMaxTokens()`** — Sonnet/Sonnet ET now get 1024 output tokens; all other models stay at 512; used in both `ScheduledBatchEvaluationService.buildForecastRequest()` and `ClaudeEvaluationStrategy.invokeClaude()`
- **`PromptBuilder.SYSTEM_PROMPT`** — added `CRITICAL OUTPUT FORMAT RULES` block: first char must be `{`, no reasoning/markdown in output, all 4 required fields mandatory; prevents chain-of-thought leakage into JSON values

### Fixed — Open-Meteo zero-task parse failure
- **`OpenMeteoService.prefetchWeatherBatch()`** — early return with empty map when `coords` list is empty; prevents pointless API call that fails to parse the response

### Added — Briefing gloss and best-bet read real Claude scores
- **`BriefingBestBetAdvisor`** — now consumes cached Claude evaluation scores from `BriefingEvaluationService`; when per-location drill-down scores exist, adds `claudeRatedCount`, `claudeHighRatedCount`, `claudeMediumRatedCount`, and `claudeAverageRating` fields to the rollup JSON sent to Claude; system prompt updated with `CLAUDE EVALUATION SCORES` guidance so the model prefers nuanced scores over triage verdicts; per-date cache coverage logging
- **`BriefingGlossService`** — same cache lookup; appends Claude score distribution to each gloss user message; system prompt updated with `CLAUDE SCORES` calibration guidance; work-item-level cache coverage logging
- **Circular dependency** — `@Lazy` on the `BriefingEvaluationService` constructor parameter in both services breaks the `BriefingService` → `BriefingBestBetAdvisor` → `BriefingEvaluationService` → `BriefingService` cycle
- **Tests** — 6 new tests in `BriefingBestBetAdvisorTest` (cached scores added, omitted when empty, triaged entries filtered, all-triaged omitted, exact cache key verification, system prompt check); 5 new tests in `BriefingGlossServiceTest` (same patterns); regression test updated

### Fixed — Aurora hot topic pill uses consistent clear count
- **`AuroraHotTopicStrategy`** — tonight detail line now reads the cloud-triaged clear count from `BriefingAuroraSummaryBuilder.buildAuroraTonightCached()` (fresh Open-Meteo weather) instead of `AuroraStateCache.getClearLocationCount()` (stale polling-cycle count); falls back to state cache when briefing summary is unavailable
- **Tests** — 2 new tests: briefing summary count preferred when available, state cache fallback when null (32 total)

### Fixed — Persistent logs across deploys
- **Rolling application log** — added `FILE` appender to `logback-spring.xml` writing `goldenhour.log` with size+time rolling (50MB max file, 30 days, 1GB total cap); attached to root logger alongside console
- **Volume mount** — `docker-compose.yml` mounts `/Users/gregochr/goldenhour-data/logs` to `/app/logs` so both `goldenhour.log` and `surge-calibration.log` survive container recreation
- **Deploy workflow** — `mkdir -p` for log directory added to GitHub Actions deploy step before `docker compose up`
- **Docker log rotation** — `json-file` driver with 10MB×3 cap on backend and database services to prevent unbounded Docker stdout logs

### Added — Add Location enrichment
- **`LocationEnrichmentService`** — new service that auto-detects bortle class, sky brightness (SQM), elevation, and Open-Meteo grid cell coordinates via three parallel API calls (lightpollutionmap.info, Open-Meteo elevation, Open-Meteo forecast); each source fails independently, returning null for failed fields
- **`GET /api/locations/enrich`** — new ADMIN-only endpoint on `LocationController` that returns a `LocationEnrichmentResult` record for a given lat/lon
- **`AddLocationRequest` extended** — 7 new fields: `bortleClass`, `skyBrightnessSqm`, `elevationMetres`, `gridLat`, `gridLng`, `overlooksWater`, `coastalTidal`; `LocationService.add()` persists all enrichment fields on the entity
- **Frontend enrichment panel** — after geocoding, the Add Location form shows an "Auto-detected" panel (elevation, bortle, SQM, grid cell) and a "Manual" panel (overlooks water / coastal tidal checkboxes); enrichment data and manual toggles flow through the confirm modal into the save request
- **Tests** — `LocationEnrichmentServiceTest` (19 tests with exact matchers, verify calls, edge cases), enrichment tests in `LocationControllerTest`, `LocationServiceTest` (swap-killing boolean tests), and `LocationManagementView.test.jsx` (7 enrichment tests)

### Added — Light pollution job API call logging
- **`LIGHT_POLLUTION` service name** — added to `ServiceName` enum; `CostCalculator` handles it with zero cost (free API) in both modern micro-dollar and legacy pence switches
- **API call logging for Bortle enrichment** — `BortleEnrichmentService.enrichAll()` now records each `lightpollutionmap.info` call via `jobRunService.logApiCall()` with per-location URL (lon,lat), duration, HTTP status, and success/error flag; the LIGHT_POLLUTION job run panel now shows individual API calls instead of "No API calls recorded"
- **Mutation-killing test coverage** — `BortleEnrichmentServiceTest` verifies exact `logApiCall` arguments (service name, method, URL coordinate order, status code, succeeded flag, error message); `CostCalculatorTest` covers zero-cost entries for `LIGHT_POLLUTION`

### Added — Aurora heatmap grid integration + cloud inversion scoring
- **Aurora grid columns** — aurora promoted from separate banner row to grid columns in Plan tab heatmap with proper day-spanning; aurora data renders alongside sunrise/sunset in the same visual grid
- **Cloud inversion scoring** — `InversionScoreCalculator` produces 0–10 likelihood score from temperature-dew gap, wind speed, humidity, and low cloud; location `elevation_m` and `overlooks_water` metadata (V65); `inversion_score` + `inversion_potential` columns on forecast_evaluation (V66); integrated into PromptBuilder for valley/lake locations
- **Astro conditions API** — `AstroConditionsService` template-scores nightly observing quality for dark-sky locations (cloud cover, visibility, moonlight modifiers); `AstroConditionsController` with `GET /api/astro/conditions` and `GET /api/astro/conditions/available-dates`; `astro_conditions` table (V64)
- **Aurora viewline endpoint** — `GET /api/aurora/viewline` returns OVATION nowcast southernmost visibility boundary; `AuroraViewlineOverlay` with colour-coded zones (green ≤55°N, amber 55–58°N, grey >58°N)
- **Storm surge calculation** — `StormSurgeService` (inverse barometer effect + wind setup) for coastal tidal locations; coastal parameters on locations (V60); surge forecast columns on forecast_evaluation (V61); integrated into forecast pipeline and prompt
- **Lunar tide classification** — spring/king tides derived from lunar cycle (`TideClassificationService`) integrated into PromptBuilder and BriefingBestBetAdvisor; replaces statistical-only thresholds
- **User settings** — `UserSettingsService` + `UserSettingsController` for home location (postcode via `PostcodesIoClient` geocoding, lat/lon) and per-user drive times; `user_drive_time` table (V67); `UserSettingsModal` frontend component; `DriveTimeResolver` abstraction replaces per-location `drive_duration_minutes`
- **Briefing model comparison** — `BriefingModelTestService` calls Haiku/Sonnet/Opus with same rollup; `briefing_model_test_run` + `briefing_model_test_result` tables (V63); `BriefingModelTestView` with agreement highlighting
- **Light pollution API update** — sb_2025 dataset with SQM conversion; `sky_brightness_sqm` column (V62)
- **Quality slider** — heatmap cell visibility tier filtering in Plan tab (`QualitySlider` component)

### Changed
- Drive times moved from per-location (`drive_duration_minutes` on `LocationEntity`) to per-user (`user_drive_time` table); `DriveDurationService` refactored to use `DriveTimeResolver`; `BriefingBestBetAdvisor` simplified to use per-user drive times
- Briefing schedule changed from every 2 hours to 04:00/14:00/22:00; model switched to Opus
- Briefing triage tightened with mid-cloud blanket and building trend checks
- Aurora viewline threshold raised to 10%
- Aurora response matching switched from array index to location name
- Quality slider direction reversed (left=worst, right=best)
- Dark sky chip admin tooltip gated to ADMIN role only
- Night qualifier required when best bet mentions aurora

### Fixed
- NOAA SWPC Kp endpoint format change (array-of-arrays to object)
- Light pollution API content type handling (text/plain, bare number responses)
- PostgreSQL-compatible syntax in V62–V63 migrations
- SSE `IllegalStateException` in `sendSafe` during briefing evaluation
- Health indicator shows red DOWN when backend is unreachable
- Auto-reconnect SSE stream after backend restart
- Docker logs directory created for surge calibration appender

### Database
- V59: `daily_briefing_cache` table
- V60: Storm surge coastal parameters on locations
- V61: Storm surge forecast columns on forecast_evaluation
- V62: `sky_brightness_sqm` column on locations
- V63: `briefing_model_test_run` + `briefing_model_test_result` tables
- V64: `astro_conditions` table
- V65: `elevation_m` and `overlooks_water` columns on locations
- V66: `inversion_score` and `inversion_potential` columns on forecast_evaluation
- V67: User home location + `user_drive_time` table

---

### Added — Briefing evaluation via SSE (Claude scoring from Plan tab)

Wire the "Run full forecast" button in the Plan tab's heatmap drill-down to trigger Claude evaluations for GO/MARGINAL locations, streaming results back via SSE, and propagating scores to the grid cells and map pins.

**Backend:**
- `BriefingEvaluationService` — orchestrates per-region Claude evaluations with `ConcurrentHashMap` cache; filters to GO/MARGINAL slots only; cache cleared via `BriefingRefreshedEvent`
- `BriefingEvaluationController` — SSE endpoint (`GET /api/briefing/evaluate`) streams `location-scored`/`progress`/`evaluation-complete` events; cache endpoint (`GET /api/briefing/evaluate/cache`) returns cached scores; gated to ADMIN/PRO_USER
- `BriefingEvaluationResult` record, `BriefingRefreshedEvent` application event
- `BriefingService` publishes `BriefingRefreshedEvent` after each refresh
- `JwtAuthenticationFilter` — added `/api/briefing/evaluate` to SSE query-param auth paths

**Frontend:**
- `briefingEvaluationApi.js` — EventSource subscription + REST cache fetch
- `DailyBriefing.jsx` — evaluation state, SSE subscription lifecycle, score lift to parent
- `HeatmapGrid.jsx` — stateful "Run full forecast" button (ready/running/complete/error), score badges on location rows with re-sort by rating, mean score pill on heatmap cells
- `App.jsx` — lifts `briefingScores` state to pass from DailyBriefing → MapView
- `MapView.jsx` — overrides forecast scores with briefing evaluation scores when available

### Changed — SSE health status stream

Replaced polling-based `/actuator/health` approach with a Server-Sent Events endpoint (`GET /api/status/stream`) that pushes enriched status every 30 seconds. The frontend `useHealthStatus` hook now uses native `EventSource` with automatic reconnection.

**Backend:**
- `StatusController` — new SSE endpoint using `SseEmitter` with 30-second push interval; assembles `StatusResponse` from `HealthEndpoint` + `GitProperties`
- `StatusResponse` — rich status record: overall status, degraded components, DB health, per-service circuit breaker statuses (with detail like CB state), build/git info, session info
- Soft components (mail) trigger DEGRADED not DOWN; ignored components (rateLimiters) are excluded
- `JwtAuthenticationFilter` — added `/api/status/stream` to SSE query-param token auth paths

**Frontend:**
- `useHealthStatus` — rewritten from `setInterval`/axios polling to `EventSource` SSE consumer
- `HealthIndicator` — enriched tooltip now shows build info (commit, branch, dirty) and service statuses with circuit breaker detail
- `healthApi.js` — orphaned (no longer imported); can be removed

### Changed — Structured best bet banners

Best bet pick banners now display day, event type, time, and drive distance as a structured header line derived server-side from triage data, instead of burying them in Claude's narrative text. Claude's headline and detail now focus on the "why" (conditions, special features) rather than repeating when/where.

**Backend:**
- `BestBet` record — added `dayName`, `eventType`, `eventTime` fields derived from triage data
- `BriefingBestBetAdvisor.enrichWithEventData()` — new enrichment step that parses event IDs, resolves day names ("Today"/"Tomorrow"/weekday), and looks up UK-local event times from the slot hierarchy
- System prompt updated to instruct Claude not to repeat structured fields in headline/detail

**Frontend:**
- `BestBetBanner` — renders structured header line (`Wednesday sunset · 18:48 · 37 min drive`) between rank label and Claude's headline
- Drive time omitted when unavailable; entire structured line omitted for stay-home/aurora picks

### Refactored — Briefing subsystem code quality

Seven targeted refactorings across the briefing pipeline:

1. **BriefingHeadlineGenerator** — extracted shared `appendVerdictCounts()` helper from near-identical `buildVerdictBreakdown()` and `buildNonGoSuffix()`
2. **BriefingVerdictEvaluator** — introduced `WeatherMetrics` and `TideContext` records, reducing `buildFlags()` from 9 positional parameters to 2 named record arguments
3. **BriefingAuroraSummaryBuilder** — extracted `CLEAR_SKY_THRESHOLD` constant (75%), replaced `.mapToInt(s -> 1).sum()` with `.count()`
4. **BriefingSlotBuilder** — extracted 30-line tide calculation into `calculateTideData()` with `TideResult` record, shrinking `buildSlot()` from 95 to 60 lines
5. **BriefingSlot** — split 18-field flat record into `WeatherConditions` + `TideInfo` sub-records with `@JsonUnwrapped` to preserve the flat JSON contract
6. **BestBet** — converted `confidence` from raw String to `Confidence` enum (`HIGH`/`MEDIUM`/`LOW`) with `@JsonValue` for backward-compatible lowercase serialization
7. **BriefingHierarchyBuilder + BriefingAuroraSummaryBuilder** — extracted shared `RegionGroupingUtils.groupByRegion()` utility replacing duplicated `LinkedHashMap + computeIfAbsent` loops

### Added — Daily Briefing ("Go or Movie Night?")

Zero-Claude-cost pre-flight check that runs every 2 hours, fetching live Open-Meteo weather and existing DB tide data for all enabled colour locations, then rolling results up by region per solar event (today + tomorrow, sunrise + sunset).

**Backend:**
- `Verdict` enum (GO / MARGINAL / STANDDOWN) — aligned with WeatherTriageEvaluator thresholds
- `BriefingSlot`, `BriefingRegion`, `BriefingEventSummary`, `BriefingDay`, `DailyBriefingResponse` records — hierarchical briefing model
- `BriefingService` — orchestrates weather fetch (parallel via virtual threads), tide DB lookup, verdict logic, region rollup, headline generation; stores result in `AtomicReference` cache
- `BriefingController` — `GET /api/briefing` (Bearer, all roles) serves cached result; 204 when cache empty
- `RunType.BRIEFING` — new enum value for job run tracking
- `ScheduledForecastService.refreshDailyBriefing()` — `@Scheduled` every 2 hours

**Frontend:**
- `briefingApi.js` — Axios wrapper for `GET /api/briefing`
- `DailyBriefing.jsx` — collapsible card above the map: headline + freshness in collapsed state; per-day sunrise/sunset sections with region rows (verdict pill + summary + tide highlights) and expandable location slot detail
- `App.jsx` — renders `<DailyBriefing />` above DateStrip in map view
- `JobRunsMetricsView.jsx` — BRIEFING run type in filter dropdown; "Show briefing runs" checkbox (hidden by default)

**Tests:**
- `BriefingServiceTest` (18 tests) — verdict logic, region rollup, tide classification, headline generation, colour location filter, cache behaviour
- `BriefingControllerTest` (5 tests) — HTTP status, auth, JSON structure, 204 when cache empty
- `DailyBriefing.test.jsx` (12 tests) — rendering, expand/collapse, region cards, verdict badges, flags, tide highlights, unregioned slots

### Added — Aurora simulation mode (admin-only)

Allows the admin to inject fake NOAA space weather data to test the full aurora UI flow (banner, forecast runs, night selector) without a real geomagnetic storm. No Claude API calls are made on activation — the admin controls spend via the existing Forecast Run flow.

**Backend:**
- `AuroraStateCache` — `SimulatedNoaaData` inner record; `simulated` + `simulatedData` volatile fields; `activateSimulation(AlertLevel, SimulatedNoaaData)` method; `isSimulated()` / `getSimulatedData()` getters; `reset()` clears simulation flag
- `AuroraSimulationRequest` — new record (kp, ovationProbability, bzNanoTesla, gScale)
- `AuroraSimulationResponse` — new record (level, message, eligibleLocations)
- `AuroraStatusResponse` — new `simulated` boolean field
- `AuroraForecastPreview` — new `simulated` boolean field propagated to the night selector
- `AuroraController.getStatus()` — when simulated, returns fake Kp/OVATION/Bz values directly from the state cache; skips live NOAA fetch; sets `simulated: true` in the response
- `AuroraAdminController` — two new ADMIN-only endpoints: `POST /api/aurora/admin/simulate` (activates simulation) and `POST /api/aurora/admin/simulate/clear` (calls `reset()`); injected `LocationRepository` to count eligible locations
- `AuroraForecastRunService` — injected `AuroraStateCache`; `getPreview()` substitutes simulated Kp for all 3 nights when simulation is active; `runForecast()` builds synthetic `SpaceWeatherData` from simulated values instead of calling NOAA; helper methods `buildSimulatedKpForecast()` (72 h of windows at fixed Kp) and `buildSimulatedSpaceWeather()` (KpReading, SolarWindReading, OvationReading, optional G-scale alert)

**Frontend:**
- `auroraApi.js` — `simulateAurora(request)` and `clearSimulation()` functions
- `AuroraSimulateModal.jsx` — new admin-only modal with Kp/OVATION/Bz/G-Scale form fields, three preset buttons (Moderate G2, Strong G3, Extreme G5), disclaimer, and optional "Clear Simulation" button when a simulation is active
- `AuroraBanner.jsx` — when `status.simulated === true`: hatched background + dashed border; 🧪 icon instead of 🌌; "SIMULATED —" prefix; kpText appended with "(SIMULATED)"; click navigates to Manage tab (not map); Bz pulse suppressed
- `AuroraForecastModal.jsx` — per-night 🧪 SIM badge when `preview.simulated === true`; amber warning banner below nights explaining real vs fake data
- `JobRunsMetricsView.jsx` — new "🧪 Simulate" button beside Aurora Forecast button; shows amber "🧪 Simulated" label when a simulation is active; opens `AuroraSimulateModal`; imports `useAuroraStatus` hook to detect live simulation state

**Tests (backend):**
- `AuroraStateCacheTest` — covered by existing reset/lifecycle tests (no new tests needed; `activateSimulation` path exercised by admin controller tests)
- `AuroraAdminControllerTest` — 4 new tests: simulate 403 for PRO, simulate 200 for ADMIN (activates + returns STRONG + eligibleLocations), simulate/clear 403 for PRO, simulate/clear 200 for ADMIN
- `AuroraControllerTest` — 2 new tests: simulated status returns fake Kp/OVATION/Bz + `simulated: true`; normal status returns `simulated: false`; added `stateCache.isSimulated()` stub to `setUp()`
- `AuroraForecastRunServiceTest` — 3 new tests: `getPreview()` uses simulated Kp (all nights Kp 7, `simulated: true`); `getPreview()` returns `simulated: false` normally; `runForecast()` bypasses `noaaClient.fetchAll()` and calls Claude with STRONG alert when simulated
- `AuroraForecastControllerTest` — updated `new AuroraForecastPreview(...)` calls to include the new `simulated` boolean parameter

**Test count:** 1048 backend (↑ from 1043) · 443 frontend (unchanged) · JaCoCo ≥80% maintained

### Fixed — Aurora banner shows trigger Kp, not current Kp

**Backend:**
- `AuroraStateCache` — new `updateTrigger(TriggerType, double kp)` method + `getLastTriggerType()` / `getLastTriggerKp()` getters; `reset()` clears trigger metadata
- `AuroraOrchestrator.scoreAndCache()` — new `triggerKp` param; calls `stateCache.updateTrigger()` on every NOTIFY. `runForecastLookahead()` passes `maxKpTonight`; `run()` passes `latestKp(spaceWeather)` (the most recent real-time Kp reading)
- `AuroraStatusResponse` — two new fields: `Double forecastKp` (the Kp that triggered the alert), `String triggerType` (`"forecast"` or `"realtime"`, null when IDLE)
- `AuroraController` — populates `forecastKp` from `stateCache.getLastTriggerKp()`, `triggerType` derived from `stateCache.getLastTriggerType()`

**Frontend:**
- `AuroraBanner` — `kpText` prefers `forecastKp` over `kp`; uses `Math.round()` (not `toFixed(1)`); appends `"forecast tonight"` suffix when `triggerType === "forecast"`. Examples: `"Kp 6 forecast tonight"` (lookahead) or `"Kp 6"` (realtime)

**Tests:**
- `AuroraStateCacheTest` — 2 new tests: `updateTrigger` stores type + kp; `reset()` clears them
- `AuroraBanner.test.jsx` — 3 new tests: forecast trigger shows suffix, realtime shows plain Kp, falls back to `kp` when `forecastKp` absent

### Added — Aurora: daytime forecast lookahead + map aurora mode

**Backend — dual-path aurora polling:**
- `AuroraPollingJob` — split into two independent paths: (1) forecast lookahead (no daylight gate, runs every poll cycle); (2) real-time check (daylight-gated, unchanged). `executePoll()` always runs forecast lookahead first, then real-time only if `!isDaylight()`. New `calculateTonightWindow()` handles daytime (today dusk → tomorrow dawn) and post-midnight (yesterday dusk → today dawn) cases. `NAUTICAL_BUFFER_MINUTES` made package-visible for tests.
- `TonightWindow` — new record (`dusk`, `dawn` as `ZonedDateTime`); `overlaps(from, to)` half-open interval check
- `TriggerType` — new enum (`FORECAST_LOOKAHEAD`, `REALTIME`) driving Claude prompt tone
- `AuroraOrchestrator.runForecastLookahead(TonightWindow)` — new method; fetches Kp forecast cheaply via `fetchKpForecast()`, checks if any window overlaps tonight and meets threshold, calls `scoreAndCache()` with `FORECAST_LOOKAHEAD` trigger type only on NOTIFY. `run()` now passes `TriggerType.REALTIME, null` to `scoreAndCache()`.
- `ClaudeAuroraInterpreter` — `interpret()` and `buildUserMessage()` updated to 7 args (added `TriggerType`, `TonightWindow`). System prompt gains tone guidance: `forecast_lookahead` → planning language ("forecast tonight", "plan your evening"); `realtime` → urgent language ("happening now", "get out there"). User message header includes trigger type, current UTC time, and tonight's dark window when provided.

**Frontend — aurora mode in map:**
- Forecast type selector — button row (☀️ Sunrise | 🌇 Sunset | 🌌 Aurora) above star filter chips; Aurora option visible only for ADMIN/PRO when `auroraStatus.active === true`; switching resets active star filters
- Aurora marker mode — markers show aurora stars (no fiery/golden arcs) when Aurora selected; null star = unrated marker
- Best Location card — banner between filter row and map showing highest-starred aurora location + "Centre map" flyTo button (`FlyToController` inner component)
- `MarkerPopupContent` — `isAuroraMode` prop; ineligible locations (no aurora score) show "🌌 Not suitable for aurora photography" pill

**Tests:**
- `AuroraPollingJobTest` — 3 new dual-path `executePoll` tests; 3 new `calculateTonightWindow` tests (daytime, post-midnight, dusk-always-before-dawn)
- `AuroraOrchestratorTest` — 6 new `runForecastLookahead` tests (threshold check, NOTIFY, SUPPRESS, outside window, fetch failure, Kp 7+ STRONG); real-time NOTIFY updated to verify `TriggerType.REALTIME`
- `ClaudeAuroraInterpreterTest` — 3 new trigger-type tests (`buildUserMessage` includes realtime context, forecast context, omits window section when null); all `interpret()`/`buildUserMessage()` calls updated to 7-arg signatures
- Total: 1009 backend tests passing

---

## [v2.5.0] - 2026-03-21

### Changed — Aurora: NOAA SWPC replaces AuroraWatch UK

Complete rewrite of the aurora pipeline replacing the deprecated AuroraWatch UK XML API with
NOAA SWPC public JSON endpoints. Alert levels renamed QUIET/MINOR/MODERATE/STRONG (from GREEN/YELLOW/AMBER/RED).

**Backend — new classes:**
- `NoaaSwpcClient` — fetches and caches 5 NOAA SWPC endpoints: Kp index (15min TTL), 3-day Kp forecast (15min), OVATION aurora probability grid at 55°N (5min), solar wind Bz/speed (1min), G-scale alerts (5min); fail-open (returns cached/empty on error); per-endpoint `CachedResult<T>` with timestamp
- `MetOfficeSpaceWeatherScraper` — Jsoup HTML scraper of the Met Office specialist space weather page; `@Scheduled` refresh (60min); truncates at 2000 chars to fit Claude context
- `WeatherTriageService` — northward 3-point transect triage (50/100/150 km) via Open-Meteo hourly `cloud_cover`; 0.1° grid deduplication; pass = any hour < 75% overcast in 6h window; `TriageResult` record with viable/rejected/cloudByLocation
- `ClaudeAuroraInterpreter` — single `claude-haiku-4-5` call for all viable locations; prompt includes Kp trend, 24h forecast, OVATION %, solar wind Bz, active alerts, Met Office narrative; returns JSON array with stars/summary/detail; strips code fences; fallback 1★ on parse error
- `AuroraOrchestrator` — drives full pipeline: NOAA fetch → `deriveAlertLevel()` (Kp + OVATION dual-signal, forecast lookahead) → `AuroraStateCache.evaluate()` → triage → Claude → cache; overcast-rejected locations auto-assigned 1★
- `SpaceWeatherData`, `SpaceWeatherAlert` — new model records

**Backend — modified:**
- `AlertLevel` — `QUIET(0)/MINOR(1)/MODERATE(2)/STRONG(3)` replacing `GREEN/YELLOW/AMBER/RED`; `fromKp(double)` factory; `hexColour()` and `description()` updated
- `AuroraProperties` — new `NoaaConfig` (5 URLs + plasma URL), `MetOfficeConfig`, `TriggerConfig` (kp/ovation thresholds + forecast lookahead), `BortleThreshold` (moderate/strong Bortle limits)
- `AuroraStatusResponse` — removed `station`; added `kp`, `ovationProbability`, `dataSource`
- `AuroraController` — enriches status with live NOAA Kp and OVATION (best-effort); no admin endpoints (moved to `AuroraAdminController`)
- `AuroraAdminController` — added `POST /api/aurora/admin/run` (triggers immediate orchestration cycle)
- `AuroraPollingJob` — simplified to `orchestrator.run()` call guarded by daylight check

**Backend — deleted:**
- `AuroraWatchClient`, `AuroraScorer`, `AuroraTransectFetcher` — replaced by above
- `AuroraStatus` model (orphaned after `AuroraWatchClient` removal)

**Dependencies:** `org.jsoup:jsoup:1.18.3` added

**Frontend:**
- `AuroraBanner.jsx` — `AMBER/RED → MODERATE/STRONG`; subtitle now includes live `Kp X.X` reading alongside location count
- `MapView.jsx` — `AMBER/RED → MODERATE/STRONG` in `ALERT_WORTHY` set, `auroraThreshold`, InfoTip copy
- `MarkerPopupContent.jsx` — aurora score pill colour logic `RED → STRONG`
- `auroraApi.js` — updated comment; added `triggerAuroraRun()` and `resetAuroraState()` for the two new admin endpoints

### Tests
- `NoaaSwpcClientTest` (21) — parse methods + fetch methods with mocked RestClient
- `WeatherTriageServiceTest` (8) — null/exception fallback, viable/rejected discrimination via reflection
- `AuroraOrchestratorTest` (18) — `deriveAlertLevel()` parameterized (8 cases), pipeline control flow
- `ClaudeAuroraInterpreterTest` (16) — `buildUserMessage()`, `parseResponse()`, `interpret()` with mocked Anthropic SDK
- `AlertLevelTest`, `AuroraStateCacheTest`, `AuroraPollingJobTest`, `AuroraControllerTest` — updated for new enum names
- JaCoCo: `NoaaSwpcClient$CachedResult`, `MetOfficeSpaceWeatherScraper`, `WeatherTriageService$CloudResponse/HourlyCloudData` added to exclusions

## [v2.4.0] - 2026-03-21

### Added — Aurora Photography Feature
- **Aurora alert status banner** — polling `AuroraWatchClient` (Scottish/English sites) via `AuroraPollingJob` (15 min, night-only); `AuroraStateCache` FSM (IDLE → MONITORING → AMBER → RED); `AuroraStatusBanner` React component shown above forecast timeline
- **Aurora location scoring** — `AuroraScorer` computes 1–5 star ratings per location using cloud cover (35% weight), moon penalty (`LunarPosition.auroraPenalty()`), and Bortle light-pollution class; `GET /api/aurora/locations` endpoint (Bearer) with `maxBortle`/`minStars` filters; `AuroraController`
- **Map popup aurora score section** — when alert level is AMBER or RED, `MapView` fetches scored locations and passes `auroraScore` to `MarkerPopupContent`; shows 🌌 Aurora header, star display (`★★★☆☆`), and cloud/moon/light-pollution detail breakdown
- **Bortle enrichment** — `LightPollutionClient` queries lightpollutionmap.info QueryRaster API (wa_2015 layer); `BortleEnrichmentService` batch-enriches all unenriched locations with SSE per-location progress (PENDING → EVALUATING → COMPLETE/FAILED); `LIGHT_POLLUTION` RunType; `POST /api/aurora/admin/enrich-bortle` returns 202 with `jobRunId` and appears in Job Runs page; `POST /api/aurora/admin/reset` resets the aurora state machine to IDLE
- **Directional cloud sampling for aurora** — `AuroraTransectFetcher` samples cloud cover at 3 points along the northward transect (0°, 345°, 15° azimuth) at 113 km offset via Open-Meteo hourly cloud_cover_low; deduplicates nearby grid cells; falls back to 50% on error
- **V55 migration** — adds `bortle_class` column to `locations` table (nullable integer)
- **Frontend aurora API module** — `auroraApi.js` with `getAuroraStatus()`, `getAuroraLocations()`, and `enrichBortle()`
- **Bortle column in Location Management** — read-only in both view and edit modes; InfoTip explaining 1–9 scale
- **Aurora filter InfoTip** — click-to-reveal tooltip on the 🌌 Aurora friendly map filter button explaining scoring criteria (alert level, cloud, moon, Bortle factors)
- **Drive Times + Light Pollution buttons moved** — from Location Management panel to Operations → Job Runs → Data Refresh section; Light Pollution enrichment triggers the live SSE progress panel

### Fixed
- **InfoTip line breaks** — changed `whitespace-normal` to `whitespace-pre-line` so `\n` in tooltip text renders as actual line breaks across all InfoTip instances

### Tests
- `AuroraAdminControllerTest` (10), `BortleEnrichmentServiceTest` (5), `LightPollutionClientHttpTest` (6), `AuroraTransectFetcherTest` (8)
- `AuroraPollingJobTest` updated for solar-utils v2 API (`civilDawn`/`civilDusk`)
- `ForecastControllerTest` extended with retry-failed, SSE endpoint, and location-filter coverage
- `LocationManagementView.test.jsx` — Bortle column: header renders, null shows `—`, value renders, read-only in edit mode (5 tests)
- JaCoCo ≥80% threshold maintained; `LightPollutionClient` JaCoCo-excluded (untestable UriBuilder lambda)

### Added (Mar 20, 2026) — Tide Alignment Pre-Claude Triage
- **TIDE_ALIGNMENT run optimisation** — new `OptimisationStrategyType` enabled by default for all colour run types (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM) via V54 migration
  - For SEASCAPE locations with tide preferences, applies a pre-Claude check: if no preferred tide type (HIGH/LOW/MID) falls within the golden/blue hour window around the solar event, skips Claude and persists a 1★ canned result
  - Window: `[civilDawn, sunrise+60min]` for sunrise; `[sunset-60min, civilDusk]` for sunset
  - Nearest tide extreme limited to ±12h of the solar event
  - Fail-open: sends to Claude when no tide data or no preferences are set
  - `TideAlignmentEvaluator` service with any-match across multiple preferred tide types
  - `TIDE_MISALIGNED` triage rule; map popup footer detects and displays "tide not aligned" reason
  - `TideData` + `TideSnapshot` records extended with `nearestHighTideTime` / `nearestLowTideTime`
  - `TideService.findNearestExtreme()` helper finds closest HIGH/LOW extreme within ±12h
- **SEASCAPE tide preference enforcement** — add/edit location forms require at least one tide preference when location type is SEASCAPE
- **Amber warning chip** — map popup shows an amber ⚠️ chip for SEASCAPE locations with no tide preferences set

### Added (Mar 12, 2026)
- **Spring/king tide badge pills** — map popup shows spring tide (🌊) or king tide (👑) badges when a HIGH tide exceeds the spring (125% avg) or king (P95) threshold; prominent styling when within ±90 min of the forecast solar event, muted with "outside golden/blue hours" text otherwise; king trumps spring (never both for the same tide); coexists with the existing rising tide warning badge
- **Tide stats: spring & king tide thresholds** — `TideStats` record extended with `springTideThreshold` (125% of avg high), `kingTideThreshold` (P95), and `kingTideCount`; displayed in Location Tides modal
- **H2 aggregate query robustness** — `TideService.getTideStats()` unwraps nested `Object[1]{Object[4]}` arrays returned by H2 and safely converts `Double`→`BigDecimal` for AVG() results
- **Map popup scroll fix** — expanded "More details" popup now scrolls when content exceeds map height; `PopupResizer` component directly manipulates Leaflet popup DOM to enforce max-height
- **Tide indicator spacing** — improved vertical breathing room between tide schedule, typical range, and golden/blue hour pills

### Added (Mar 12, 2026) — Cloud Approach Risk
- **Cloud approach risk detection** — two new signals augment directional cloud data to detect cloud approaching the solar horizon that a single event-time snapshot would miss
  - `SolarCloudTrend`: hourly low cloud at the 113 km solar horizon from T-3h to event time; `isBuilding()` detects a peak-vs-earliest increase of 20+ pp, appending a `[BUILDING]` label to the prompt that instructs Claude to penalise fiery_sky by 10–25 points
  - `UpwindCloudSample`: current low cloud at an upwind point along the wind vector vs the model's event-time prediction; high current cloud with low event-time prediction flags over-optimistic clearing
  - `CloudApproachData` record composes both signals into `AtmosphericData`; `ForecastDataAugmentor` assembles the data from Open-Meteo; `PromptBuilder` formats it as a `CLOUD APPROACH RISK:` block
  - V51 migration adds persistence columns to `forecast_evaluation`
  - Motivated by the Copt Hill 2026-03-11 sunset failure case (4-star prediction, ~2-star reality)

### Added (Mar 11, 2026)
- **LocationType.WATERFALL** — new location type with 💦 emoji across map filters, badges, location editor, and metrics
  - V50 migration reclassifies 31 waterfall locations from LANDSCAPE to WATERFALL
  - Waterfall locations show both colour forecasts AND hourly comfort rows (temp/wind/rain)
  - Waterfall scores excluded from cluster marker averages (waterfall photography is about the water, not sky colour)
- **NEXT_EVENT_ONLY optimisation strategy** — evaluates only the single nearest upcoming solar event per location, skipping all other sunrise/sunset slots; ideal for last-minute checks before heading out
  - V49 migration seeds the new strategy; conflicts with EVALUATE_ALL only

### Added (Mar 10, 2026)
- **Tide history backfill** — 12-month historical fetch via WorldTides API (7-day chunks, duplicate-aware skipping) with admin UI button and async execution
- **3-point cone cloud sampling** — replaces single-point directional sampling with azimuth ±15° cone (3 points averaged) to smooth Open-Meteo grid-cell boundary effects that caused inconsistent ratings for nearby locations (~11km resolution)
- **Tide stats endpoint** — `GET /api/tides/stats` with avg/max high and avg/min low from accumulated `tide_extreme` data; "Typical range" row shown in TideIndicator
- **Rising tide warning badge** — amber badge when high tide falls within ±90 min of solar event (blue+golden hour window)
- **Tide history preservation** — windowed delete replaces only the 14-day fetch window instead of deleting all rows per location
- **SEASCAPE filtering** — both tide refresh and backfill consistently filtered by `LocationType.SEASCAPE`
- **Editable lat/lon** — location inline edit now supports lat/lon editing with validation
- **Region pagination** — client-side pagination with location count column in regions table
- **St Mary's Lighthouse regression test** — new prompt regression test case from 10 Mar 2026 sunrise prod data

### Fixed (Mar 10, 2026)
- **Rising tide window** — widened from ±60 to ±90 min of solar event
- **Manage view gate** — `ManageView` guarded with `isAdmin` in all render paths so PRO/LITE users only see the map
- **Jackson CVE suppression** — suppress CVE-2026-29062 (Jackson 3.0.4 nesting depth bypass); fix requires Jackson 3.1.0 incompatible with Spring Boot 4.0.3

### Refactored (Mar 8, 2026)
- **Evaluation strategy hierarchy collapse** — replaced `AbstractEvaluationStrategy` + 3 trivial subclasses (`HaikuEvaluationStrategy`, `SonnetEvaluationStrategy`, `OpusEvaluationStrategy`) with a single `ClaudeEvaluationStrategy` parameterised by `EvaluationModel`
  - Model ID is the single source of truth via `EvaluationModel.getModelId()` — no more per-class `getModelName()` overrides
  - `EvaluationConfig` produces a `Map<EvaluationModel, EvaluationStrategy>` bean; `EvaluationService` and `ForecastCommandFactory` use map lookup instead of switch/injection of 4 named beans
- **PromptBuilder extraction** — moved prompt construction (`SYSTEM_PROMPT`, `PROMPT_SUFFIX`, `buildUserMessage()`, `buildOutputConfig()`, `toCardinal()`, `isDustElevated()`) from `AbstractEvaluationStrategy` into a dedicated `PromptBuilder` class; injected as a Spring bean
- **MetricsLoggingDecorator** — extracted timing, logging, and metrics recording from `EvaluationService` into a GoF Decorator (`MetricsLoggingDecorator`) that wraps any `EvaluationStrategy`; applied transparently when a `JobRunEntity` is present
- **Double buildUserMessage bug fix** — `evaluateWithDetails()` was calling `buildUserMessage()` twice per evaluation (once explicitly, once inside `invokeClaude()`); `invokeClaude()` now accepts a pre-built `String` parameter
- **ForecastDataAugmentor extraction** — moved `augmentWithDirectionalCloud()` and `augmentWithTideData()` from `ForecastService` into a dedicated `ForecastDataAugmentor` service; `ForecastService`, `ModelTestService`, and `PromptTestService` all delegate to it
- **Forecast DTO layer** — `ForecastEvaluationDto` record decouples the REST API contract from the JPA entity; `ForecastDtoMapper` maps entities to DTOs with role-based score selection (LITE users get basic observer-point scores, PRO/ADMIN get enhanced directional scores); `basic_*` columns never appear in the API response; `ForecastController` GET endpoints return DTOs instead of entities
- 690 backend tests — all passing, JaCoCo >= 80%

### Added (Mar 7, 2026)
- **Directional cloud sampling** — fetches cloud cover at 50 km offset points toward the solar horizon and antisolar horizon using Haversine forward formula (`GeoUtils.offsetPoint()`)
  - `DirectionalCloudData` record with 6 fields: solar/antisolar low/mid/high cloud percentages
  - `OpenMeteoClient.fetchCloudOnly()` — lightweight cloud-only Open-Meteo request with `@Retryable`
  - `OpenMeteoService.fetchDirectionalCloudData()` — computes offset coordinates, fetches cloud at both points, extracts nearest time slot
  - Graceful degradation: returns null on failure, evaluation falls back to single-point inference
  - 6 new columns on `forecast_evaluation`: `solar_low_cloud`, `solar_mid_cloud`, `solar_high_cloud`, `antisolar_low_cloud`, `antisolar_mid_cloud`, `antisolar_high_cloud`
- **Dual-tier scoring (freemium)** — single Claude API call returns both enhanced scores (using directional data) and basic scores (observer-point inference only)
  - 3 new columns on `forecast_evaluation`: `basic_fiery_sky_potential`, `basic_golden_hour_potential`, `basic_summary`
  - `SunsetEvaluation` extended with basic fields; 4-arg convenience constructor for backward compatibility
  - Evaluation prompt updated with directional cloud rules and dual-tier output schema
  - LITE users will see `basic_*` scores; PRO/ADMIN get enhanced directional scores (frontend gating TBD)
  - V48 migration adds all 9 columns
- **Prompt regression test suite** — live Claude API tests with real-world atmospheric data that assert scores stay within observed bounds
  - `PromptRegressionTest` with `@Tag("prompt-regression")`, excluded from `mvn verify`
  - Run on demand: `ANTHROPIC_API_KEY=... ./mvnw test -Pprompt-regression`
  - Copt Hill (negative case): blocked solar horizon, asserts rating <= 2, fiery <= 25, golden <= 35
  - Angel of the North (positive case): spectacular sunset, asserts rating >= 4, fiery >= 60, golden >= 60
  - `generate-regression-fixture.sh` — fetches Open-Meteo historical data and outputs Java fixture code
- **AtmosphericData decomposition** — split the 27-field `AtmosphericData` record into 5 composable sub-records: `CloudData`, `WeatherData`, `AerosolData`, `ComfortData`, `TideSnapshot`
  - `AtmosphericData` reduced from 27 positional fields to 9 named sub-records
  - `withDirectionalCloud()` and `withTide()` copy methods replace the painful 27-field copy-with pattern
  - `ForecastService.augmentWithDirectionalCloud()` reduced from 15 lines to 1 line
  - `ForecastService.augmentWithTideData()` reduced from 25 lines to 10 lines
  - `TestAtmosphericData` builder centralises test data construction across 12 test files
- 664 backend tests — all passing, JaCoCo >= 80%

### Fixed (Mar 7, 2026)
- **Solar-aware slot selection** — `findBestIndex()` replaces `findNearestIndex()` for choosing the Open-Meteo hourly slot; sunset picks the last slot at or before the event, sunrise picks the first slot at or after. Prevents using post-sunset or pre-sunrise data (0 W/m² radiation, meaningless conditions)
- **Directional cloud scoring thresholds** — adjusted from >50% to >60% solar low cloud for the hard "blocked" ceiling; 40-60% band penalises but considers that mid/high cloud may still catch colour through gaps in breaking low cloud

### Added (Mar 4, 2026)
- **Popup preview on prompt test results** — eye icon button on each succeeded result row opens a modal rendering the real `MarkerPopupContent` with mapped atmospheric data, scores, and location metadata; useful for visually checking badges like Sahara Dust
- **URL hash navigation** — active view and Manage tab persisted in URL hash (e.g. `#manage/prompttest`); page refresh returns to the same screen
- **Prompt test harness** — end-to-end prompt evaluation test that runs all colour locations through the Claude pipeline with a chosen model, stores results, and supports run comparison
  - `prompt_test_run` + `prompt_test_result` tables (V44, V45)
  - `PromptTestService` orchestrates: pick colour locations, fetch weather, evaluate with selected model, persist results with rating/fiery sky/golden hour scores
  - `PromptTestController` with ADMIN-only endpoints: `POST /api/prompt-test/run`, `POST /api/prompt-test/replay`, `GET /api/prompt-test/runs`, `GET /api/prompt-test/runs/{id}`, `GET /api/prompt-test/results`, `GET /api/prompt-test/git-info`
  - **Async execution** — POST /run and /replay return 202 Accepted immediately; work runs in background via `CompletableFuture.runAsync()` on virtual thread executor; frontend polls `GET /runs/{id}` every 3s for live progress updates
  - **Run comparison** — select two runs via checkboxes to see side-by-side results with score deltas
  - **Replay** — re-run a previous test with the same locations and dates but current prompt version, for A/B comparison of prompt changes
  - **Build info section** — shows current git commit, branch, and relative commit date above controls; hidden when git info unavailable
  - **Model versions** — `EvaluationModel` enum gains `version` field (HAIKU 4.5, SONNET 4.5, OPUS 4.6); `/api/models` returns `[{name, version}, ...]`; versions shown next to model radio buttons
  - **Date and Target columns** — results table shows target date and target type (SUNRISE/SUNSET) for each result
  - **Docker git fix** — build context changed from `./backend` to repo root so `git-commit-id-maven-plugin` can access `.git/`; git badge no longer shows "?" in Docker builds
  - 646 backend tests, 321 frontend tests — all passing

### Fixed (Mar 4, 2026)
- **Dust badge PM2.5 threshold** — raised from < 15 to < 35 µg/m³; Saharan dust events commonly push PM2.5 into the 20–30 range which was incorrectly suppressing the badge
- **Wildlife marker emoji** — fixed leftover eagle emoji (🦅) in `markerUtils.js` map marker medallions; now shows paw prints (🐾) matching the rest of the UI
- **Locations table layout** — removed Created column, rebalanced widths to prevent Type and Tide chips overflowing into adjacent columns
- **MetricsSummary cost aggregation** — combined token-based micro-dollar costs with legacy flat-rate pence costs instead of ignoring pence when any micro-dollars exist; was showing £0.14 instead of £24.99 for 19 runs
- **ModelSelectionView pricing** — converted from USD to GBP primary display with greyed-out USD in parentheses, matching the JobRunsGrid pattern

### Changed (Mar 4, 2026)
- **TideType enum refactored** — simplified from 5 sentinel values (HIGH_TIDE, LOW_TIDE, MID_TIDE, ANY_TIDE, NOT_COASTAL) to 3 real values (HIGH, MID, LOW)
  - Empty set replaces NOT_COASTAL; all three selected replaces ANY_TIDE
  - `AddLocationRequest` and `UpdateLocationRequest` now use `Set<TideType> tideTypes` (was single `TideType tideType`)
  - V43 Flyway migration renames and expands existing data in place — no data loss
  - `TideService.calculateTideAligned()` simplified to 3-case switch
  - `LocationService.isCoastal()` simplified to `!tideType.isEmpty()`
- **Emoji chip UI for location metadata** — Type and Tide columns in Locations table now use compact toggle chips
  - Location type: 🏔️ (Landscape), 🌊 (Seascape), 🐾 (Wildlife) — single-select, clickable in edit mode, read-only display with faded unselected icons
  - Tide type: H/M/L gold toggle chips — multi-select for SEASCAPE, disabled for non-coastal, prevents deselecting last chip
  - Column header filters replaced with matching clickable chips (no more text inputs for Type and Tide)
  - Tide column header filter supports multi-select with AND logic
- **Wildlife emoji** — changed from 🦅 to 🐾 (paw prints) across MapView filter bar and Locations table for better dark theme contrast (brightness filter applied)
- **MetricsSummary time filtering** — added Today / Last 7 Days toggle to filter summary statistics by date range; shows "mixed pricing" label when both cost types are present

### Added (Mar 4, 2026)
- **Client-side pagination for Locations and Users tables** — shared `usePagination` hook and `Pagination` component
  - Default page size of 10 with 10/25/50 size chips
  - First/Prev/Next/Last navigation buttons with "Showing X-Y of Z" summary
  - `table-fixed` with explicit column widths prevents column shifting between pages
  - Spacer rows on partial last page keep pagination controls anchored (no layout jump)
  - Resets to page 1 when filters change; pagination hidden when all items fit on one page
  - Truncated cell content with hover tooltips for long names/emails
  - 32 new tests: `usePagination.test.js` (13), `Pagination.test.jsx` (13), `UserManagementView.test.jsx` (4), `LocationManagementView.test.jsx` (+2)

### Added (Mar 3, 2026)
- **Marker clustering** — `react-leaflet-cluster` groups nearby markers at low zoom levels
  - Clusters display marker count with grey→gold background based on average child rating
  - PRO/ADMIN cluster icons include fiery sky (orange) and golden hour (gold) half-arc progress from averaged scores
  - LITE users see plain coloured cluster circles (no arcs)
  - `disableClusteringAtZoom={10}` ensures individual markers at close zoom
  - `maxClusterRadius={60}` for moderate clustering density
  - Long location name labels truncated with ellipsis (90px max-width); full name shown on hover via `title` attribute
  - `createClusterIcon` in `markerUtils.js` with 15 unit tests
- **Radial progress arcs on map markers** — SVG-based arc gauges replace plain coloured circles
  - PRO/ADMIN: two half-arcs (left = Fiery Sky orange, right = Golden Hour gold) filling bottom-up proportionally to 0–100 score
  - LITE: single proportional ring based on 1–5 star rating
  - Wildlife: plain green circle with eagle emoji (no arcs)
  - No-data: plain grey circle with ? (no arcs)
  - `markerUtils.js` extracted for testability: `buildMarkerSvg`, `scoreColour`, `markerLabelAndColour`, `RATING_COLOURS`
  - Marker label shows star rating (e.g. "4★") with rating-graded colour for all users
- **MarkerPopupContent tests** — 28 tests covering role-based score bar visibility, coastal tide display, wildlife and no-data rendering
- **useForecasts regression tests** — 6 tests including wildlife location inclusion, disabled location exclusion

### Fixed (Mar 3, 2026)
- **Wildlife locations missing from map** — `useForecasts` now builds location list from the full locations API response, not just forecast rows; wildlife locations without evaluations are no longer dropped
- **goldenHourType no longer filters evaluations** — `shouldEvaluateSunrise()` and `shouldEvaluateSunset()` now always return true; both sunrise and sunset forecasts are generated for every non-wildlife location regardless of `goldenHourType` (which is photographer preference metadata, not an evaluation filter)
- **Wildlife marker colour** — toned down from `#4ade80` to `#16a34a` (green-600) for consistency with popup header
- **Vite proxy default** — changed from port 8083 to 8082 to match actual backend port

### Changed (Mar 3, 2026)
- **Merge REQUIRE_PRIOR into SKIP_LOW_RATED** — SKIP_LOW_RATED now also skips when no prior evaluation exists, reducing strategies from 6 to 5
  - V40 migration: deletes REQUIRE_PRIOR rows from `optimisation_strategy` table
  - `OptimisationSkipEvaluator`: SKIP_LOW_RATED checks `latest.isEmpty()` first, then rating threshold
  - Mutual exclusion rules updated (REQUIRE_PRIOR entries removed)
- **Improved strategy labels and descriptions** — all five strategies have clearer names and actionable descriptions in the Admin UI
  - Skip Existing → "Skip Already-Evaluated", Force Imminent → "Always Evaluate Today", Force Stale → "Re-evaluate Stale Data", Evaluate All → "Evaluate Everything (JFDI)"
- **Per-call cost estimates in Run Config** — model cards show typical cost per call; cost estimate table shows run total based on actual configured location count
- **Configurable Vite proxy target** — `VITE_API_TARGET` env var in `frontend/.env` switches between local dev (8083) and Docker prod (8082); `/actuator` also proxied

### Fixed (Mar 3, 2026)
- **Disabled button UX** — `btn-primary` and `btn-secondary` now show visible disabled state (40% opacity, not-allowed cursor)
- **Add Location hint** — "Review & Confirm" button shows helper text when in place search mode without a geocode result
- **Vitest 4 compatibility** — `useIsMobile.test.js` replaced `vi.spyOn(window, 'matchMedia')` with `vi.stubGlobal('matchMedia', ...)` because `window.matchMedia` is `undefined` in Vitest 4's jsdom environment

### Added (Mar 3, 2026)
- **Configurable cost optimisation strategies** — five toggleable strategies per run type replace hard-coded Opus gate and long-term skip logic
  - Strategies: SKIP_LOW_RATED (threshold param), SKIP_EXISTING, FORCE_IMMINENT, FORCE_STALE, EVALUATE_ALL (JFDI mode)
  - V39 migration: `optimisation_strategy` table (15 rows seeded), `active_strategies` column on `job_run` for audit trail
  - `OptimisationSkipEvaluator` evaluates strategies with shared DB lookup; `OptimisationStrategyService` handles CRUD + mutual exclusion validation
  - `ForecastCommandExecutor` refactored to delegate skip logic to evaluator instead of hard-coded methods
  - Admin UI: "Cost Optimisation" section in Run Config tab with toggle pills, parameter buttons, and conflict indicators
  - `PUT /api/models/optimisation` endpoint for strategy toggles; `GET /api/models` now includes strategy data
  - Job Runs grid shows active strategies as badges; EVALUATE_ALL displays distinct "JFDI" badge
  - **LocationManagementView tests** — 8 new tests covering add form, disabled states, and hint messages
  - 607 backend tests, 151 frontend tests; all passing

### Added (Mar 2, 2026)
- **Token-based cost tracking** — replaces flat per-call pence estimates with actual token-based micro-dollar pricing from Anthropic SDK responses
  - `TokenUsage` record captures input, output, cache creation, and cache read tokens from every `Message.usage()` response
  - `CostCalculator` computes costs in micro-dollars (1 USD = 1,000,000 µ$) using real per-model USD/MTok rates: Haiku ($1/$5), Sonnet ($3/$15), Opus ($5/$25), with cache write/read rates and 50% batch discount
  - `ExchangeRateService` fetches daily USD-to-GBP rate from Frankfurter API (ECB data, no API key); caches in `exchange_rate` table; falls back to most recent cached rate on failure
  - Exchange rate snapshot stored per `job_run` and `model_test_run` so historical costs convert at the rate from the day they were incurred
  - V38 migration: token columns + `cost_micro_dollars` on `api_call_log` and `model_test_result`; `total_cost_micro_dollars` + `exchange_rate_gbp_per_usd` on `job_run` and `model_test_run`; new `exchange_rate` table
  - `AbstractEvaluationStrategy.extractTokenUsage()` reads all four token categories from SDK response
  - `JobRunService.logAnthropicApiCall()` records tokens + micro-dollar cost per call; `completeRun()` aggregates both legacy pence and micro-dollar totals
  - `ModelTestService` populates token fields and micro-dollar costs on test results and runs
  - Frontend `formatCost.js` utility: `formatCostGbp()` (with legacy pence fallback), `formatCostUsd()`, `formatTokens()`
  - `MetricsSummary` shows both GBP and USD totals; `JobRunDetail` shows per-call token breakdown (input/output/cache write/cache read); `JobRunsGrid` uses token-based costs; `ModelTestView` adds Tokens and Cost columns
  - `ModelSelectionView` shows real per-model pricing rates instead of hardcoded estimates
  - Legacy `cost_pence` / `total_cost_pence` columns retained for backward compatibility
  - 569 backend tests (up from 565), all passing; Checkstyle/SpotBugs/JaCoCo clean

### Changed (Mar 2, 2026)
- **Spring Framework 7 feature adoption** — virtual threads, RestClient, declarative resilience, and HTTP interface clients
  - **Virtual threads** — `spring.threads.virtual.enabled: true` in both profiles; `forecastExecutor` uses `Executors.newVirtualThreadPerTaskExecutor()` (replaces sized `ThreadPoolTaskExecutor`)
  - **RestClient replaces WebClient** — all HTTP clients migrated from reactive `WebClient.block()` to synchronous `RestClient`; Reactor/WebFlux removed from classpath entirely (`spring-boot-starter-webclient` + `reactor-test` dependencies dropped)
  - **@HttpExchange interfaces** — `OpenMeteoForecastApi` and `OpenMeteoAirQualityApi` declarative interfaces proxied via `HttpServiceProxyFactory` + `RestClientAdapter`; `OpenMeteoClient` wraps both with `@Retryable`
  - **Declarative retry** — `@EnableResilientMethods` + `@Retryable` (Spring Framework 7 `org.springframework.resilience`) replaces hand-rolled retry loops; `AnthropicApiClient` retries 529/content-filter, `OpenMeteoClient` retries 5xx/429; `MethodRetryPredicate` implementations: `ClaudeRetryPredicate`, `TransientHttpErrorPredicate`
  - **@ConcurrencyLimit(8)** on `ForecastService.runForecasts()` — caps parallel evaluations, replacing thread pool sizing
  - **TurnstileService** migrated from `RestTemplate` to `RestClient`
  - **GlobalExceptionHandler** — `WebClientResponseException` → `RestClientResponseException`
  - 541 backend tests (up from 535), all passing; Checkstyle/SpotBugs/JaCoCo clean
  - 10 new files created, 25 modified; net -810 / +923 lines

### Added (Mar 2, 2026)
- **PIT mutation testing** — `pitest-maven-plugin` 1.17.4 with JUnit 5 support; targets service, controller, and config packages; run locally with `./mvnw pitest:mutationCoverage`; HTML + XML reports in `target/pit-reports/`
  - Weekly CI workflow (`.github/workflows/pitest.yml`) runs every Monday 06:00 UTC with manual dispatch; uploads report as artifact

### Fixed (Mar 2, 2026)
- **H2 driver missing from fat JAR** — removed `<optional>true</optional>` from the H2 dependency in `pom.xml`; Spring Boot 4 excludes optional dependencies from the packaged JAR, causing `Cannot load driver class: org.h2.Driver` at startup in Docker
- **Jackson serialization config incompatible with Boot 4** — removed `spring.jackson.serialization.write-dates-as-timestamps: false` from `application.yml`; Spring Boot 4 uses `tools.jackson.databind` (Jackson 3) which doesn't recognise the old enum constant format; the default is already `false`

### Added (Mar 2, 2026)
- **Mobile bottom sheet for map markers** — on viewports ≤639px (below Tailwind `sm:` breakpoint), tapping a map marker opens a slide-up bottom sheet instead of a cramped Leaflet popup; scrollable content, tap-to-dismiss overlay, close button, body scroll lock; desktop keeps existing Leaflet popup unchanged
  - `useIsMobile` hook (MediaQueryList-based, listens for resize/orientation changes)
  - `BottomSheet` component (overlay + sheet + drag handle pill + close button, 200ms slide-up animation)
  - `MarkerPopupContent` extracted from MapView — shared by both Leaflet popup (desktop) and bottom sheet (mobile)
  - 11 new tests (7 BottomSheet, 4 useIsMobile) — 127 frontend tests total

### Changed (Mar 2, 2026)
- **React 19 upgrade** — `react` and `react-dom` 18.3.1 → 19.2.4, `react-leaflet` 4.2.1 → 5.0.0
  - Migrated all 4 `defaultProps` usages to JavaScript default parameters (deprecated in React 19): LocationAlerts, JobRunsGrid, LoginPage, RegisterPage
  - Supersedes two separate Dependabot PRs that couldn't be merged individually due to peer dependency conflicts
- **Spring Boot 4.0 migration** — upgraded from Spring Boot 3.x to 4.0.3 (Spring Security 7, Jackson 3)
  - `spring-boot-starter-web` → `spring-boot-starter-webmvc`, `spring-boot-starter-webflux` → `spring-boot-starter-webclient`
  - `flyway-core` → `spring-boot-starter-flyway`
  - Jackson 2 → Jackson 3: `com.fasterxml.jackson.databind` → `tools.jackson.databind`
  - Test annotations: `@MockBean` → `@MockitoBean`, new Boot 4.0 modularised test starters
  - `spring-boot-starter-security-test` for `@WithMockUser` MockMvc integration
  - Springdoc OpenAPI 2.3.0 → 3.0.1
  - All 535 backend tests pass, Checkstyle/SpotBugs/JaCoCo clean

### Added (Mar 2, 2026)
- **90 regression tests** — pre-v1.0 test hardening across backend and frontend
  - Backend (37 new, 497 → 534): RequestLoggingInterceptor (17), LocationController edge cases (+7), RegionController 404s (+4), UserController DELETE (+5), ModelTestController validation (+4)
  - Frontend (53 new, 57 → 110): conversions-extra (22), ScoreBar (10), SessionExpiryBanner (10), OutcomeModal (11)

### Added (Mar 1, 2026)
- **Responsive map popup** — popup width now uses `calc(100vw - 40px)` capped at `max-width: 600px`, so popups fit within phone viewports instead of overflowing at the hardcoded 600px
- **Model Test button descriptions** — descriptive text below each button explaining what it does: which locations are tested, whether weather/tide data is fetched fresh, and that all three Anthropic models are always run
- **Event-specific azimuth line** — map now only renders the sunrise or sunset azimuth line matching the selected event type, instead of always showing both
- **Star rating filter on map** — 5 toggle chips (1★–5★) on the map filter bar let users show any permutation of star ratings; AND-ed with existing location type filters; gold highlight when active; single Clear button resets both filter groups
- **Re-run model test** — re-run a previous model test using the same locations but fresh weather data and fresh Anthropic API calls, to measure variance between runs
  - `POST /api/model-test/rerun?testRunId=X` endpoint (ADMIN only)
  - "Re-run" button in results header with confirmation dialog listing locations
  - 5 new backend tests (3 service, 2 controller)
- **Last active tracking** — renamed `lastLoginAt` to `lastActiveAt`; now updated on every authenticated API request (throttled to once per hour) instead of only on login
  - V37 migration renames column
  - `JwtAuthenticationFilter` updates `lastActiveAt` when stale (>60 min)
  - Frontend column label: "Last Active"
- **Single-location model test** — admin can test one specific location with all three Claude models (Haiku/Sonnet/Opus) using identical atmospheric data, for debugging or spot-checking
  - `POST /api/model-test/run-location?locationId=X` endpoint (ADMIN only)
  - `ModelTestService.runTestForLocation()` validates location (enabled, has colour types, has region), fetches weather once, evaluates with 3 models
  - Frontend: "Test One Location" button opens a location picker modal with text filter, eligible location list, and cost note (3 API calls)
  - 9 new backend tests (6 service, 3 controller)
- **Run row toggle collapse** — clicking an already-expanded run in the Model Test table now collapses it instead of re-fetching results
- 497 backend tests, 57 frontend tests — all passing
- **Marketing email opt-in preference** — users can opt in/out of marketing emails during registration
  - V35 migration: `marketing_email_opt_in BOOLEAN NOT NULL DEFAULT TRUE` on `app_user`
  - Checkbox on RegisterPage (default checked): "Send me occasional emails about new features and photography tips"
  - Privacy policy modal updated with "Marketing Emails" section explaining opt-in, right to unsubscribe, and transactional email distinction
  - `POST /api/auth/register` accepts optional `marketingEmailOptIn` parameter (defaults true)
  - `PUT /api/auth/marketing-emails` new authenticated endpoint to toggle preference
  - Login and set-password responses include `marketingEmailOptIn` field
  - `AuthContext` stores and exposes preference for future settings page
  - 7 new backend tests (AuthControllerTest, UserServiceTest, RegistrationServiceTest), 2 new email service tests
- **Account deletion email notification** — when an admin deletes a user account, a polite notification email is sent to the user (if they have an email address) informing them their account has been removed

### Fixed (Mar 1, 2026)
- **Verification link bug after logout** — after completing registration (verify email + set password), logging out would show "Verification failed — This verification link has already been used" because the `?token=` URL parameter was never cleared; now cleared via useEffect in AuthGate when user becomes authenticated (RegisterPage unmounts before its own cleanup can fire)

### Changed (Mar 1, 2026)
- **Tailwind CSS v3 → v4 migration** — replaced `tailwindcss` v3 + `autoprefixer` with `@tailwindcss/postcss` v4; moved theme config from `tailwind.config.js` into CSS `@theme` block in `index.css`; deleted `tailwind.config.js`; inlined `.btn` base styles into `.btn-primary`/`.btn-secondary` (v4 disallows `@apply` of custom component classes)

### Added (Mar 1, 2026)
- **Model comparison test harness** — A/B/C test that runs Haiku, Sonnet, and Opus against identical atmospheric data for one location per region, for side-by-side evaluation comparison
  - V34 migration: `model_test_run` and `model_test_result` tables with FKs to regions/locations
  - `ModelTestService` orchestrates: find enabled regions, pick representative colour location per region, fetch weather once, run all three models, persist results with prompt/response capture
  - `ModelTestController` with ADMIN-only endpoints: `POST /api/model-test/run`, `GET /api/model-test/runs`, `GET /api/model-test/results`
  - `EvaluationDetail` record captures exact prompt sent and raw Claude response for reproducibility
  - `evaluateWithDetails()` method added to `AbstractEvaluationStrategy` and `EvaluationService`
  - Frontend: `ModelTestView.jsx` with run button, confirmation dialog, runs table, and comparison grid grouped by region with Haiku/Sonnet/Opus rows and delta indicators from Haiku baseline
  - "Model Test" tab added to ManageView
  - 12 service tests, 6 controller tests, 5 frontend tests
- **Styled confirmation dialogs for Job Runs** — replaced all 4 `window.confirm()` browser dialogs in JobRunsMetricsView with styled modal dialogs matching the app's existing pattern (dark overlay, rounded card, Cancel/Confirm buttons)

### Fixed (Mar 1, 2026)
- **Flaky LocationFailureTrackingTest** — removed `@ActiveProfiles("local")` that forced file-based H2 (causing stale state between test runs); now uses in-memory test DB with explicit state reset in `@BeforeEach`

### Added (Mar 1, 2026)
- **Self-registration with email verification** — users can sign up with email + username, verify via email link, then set their own password
  - V33 migration: `email_verification_token` table + unique email index on `app_user`
  - `RegistrationService` orchestrates register, resend (rate-limited: max 3 in 5 min), verify, and activate
  - Four new public auth endpoints: `/register`, `/resend-verification`, `/verify-email`, `/set-password` (auto-login on completion)
  - `verification-email.html` Thymeleaf template (dark theme, CTA button, 24-hour expiry)
  - Abandoned pending registrations (unverified) are automatically replaced on re-registration
  - Duplicate email/username returns 409 Conflict; resend returns generic 200 to prevent email enumeration
  - Frontend: `RegisterPage.jsx` multi-step state machine (register -> check email -> verify -> set password -> success)
  - `AuthContext.completeRegistration()` for token storage after registration
  - Login page gains "Don't have an account? Create one" link; privacy policy modal
  - `app.frontend-base-url` config for verification link URLs
  - 432 backend tests passing (31 new: RegistrationServiceTest, AuthController registration tests, UserService, UserEmailService)
- **Backend down indicator for all users** — non-admin users now see a red banner and greyed-out UI (opacity + pointer-events disabled) when the backend health poll returns DOWN; clears automatically on recovery
- **Region entity** — new `Region` table with nullable FK on locations for geographic grouping
  - `V31` migration creates `regions` table and seeds 5 rows: Tyne and Wear, The North Yorkshire Coast, The Lake District, The Yorkshire Dales, Northumberland
  - `V32` migration adds `region_id` FK column to `locations`
  - `RegionEntity`, `RegionRepository`, `RegionService`, `RegionController` — full CRUD with enable/disable
  - `GET /api/regions` (authenticated), `POST /api/regions` (ADMIN), `PUT /api/regions/{id}` (ADMIN), `PUT /api/regions/{id}/enabled` (ADMIN)
  - `LocationEntity` gains `@ManyToOne` nullable `region` field
  - `AddLocationRequest` and `UpdateLocationRequest` gain `regionId` parameter
  - Frontend: `regionApi.js`, `RegionManagementView.jsx` (sortable table with add/edit/enable-disable)
  - "Regions" tab added to ManageView between Locations and Job Runs
  - Region dropdown in location add and edit forms; Region column in locations table
  - 27 new backend tests (RegionServiceTest: 18, RegionControllerTest: 9) — 393 total passing

### Fixed (Mar 1, 2026)
- **Anthropic content filter retry** — 400 errors with "content filtering" now retry with exponential backoff (1s -> 2s -> 4s, 3 retries) alongside existing 529 retry; on final failure, full prompt inputs logged at WARN for reproduction
- **Anthropic retry exception type** — fixed dead code: replaced `HttpServerErrorException` (Spring) catch with `AnthropicServiceException` (SDK) which is what the Anthropic OkHttp client actually throws

### Changed (Mar 1, 2026)
- **Forecast horizon reduced to T+5** — `FORECAST_HORIZON_DAYS` changed from 7 to 5; Long-Term job now runs T+3 through T+5; WEATHER and TIDE date ranges also reduced accordingly
- **PhotoCast rebrand** — app name changed from "Photo Cast" to "PhotoCast" across all UI files (index.html, LoginPage, ChangePasswordPage, tests)
- **Leaflet dark theme** — custom-styled zoom controls and attribution matching the Plex dark palette (dark backgrounds, gold hover accents, rounded corners, subtle shadow)

### Added (Feb 28, 2026)
- **Manual tide refresh button** — admin Job Runs dashboard now has a "Refresh Tide Data" button alongside the existing forecast run buttons; triggers `POST /api/forecast/run/tide` to refresh WorldTides extremes for all coastal locations on demand
- **Today/Tomorrow labels on date strip** — first two date chips now show "Today · Sat 28 Feb" and "Tomorrow · Sun 1 Mar" with a trailing fade gradient to hint at scrollable overflow
- **Progressive disclosure popup** — map marker popup shows star rating + Claude summary at first glance; location metadata, score bars, and comfort data behind a "More details" toggle
- **Improved information hierarchy** — Claude summary promoted above score bars in the popup layout
- **Score bar tooltips** — Fiery Sky and Golden Hour labels show descriptive tooltips on hover (e.g. "Dramatic colour from clouds catching light")
- **SVG weather icons** — replaced emoji icons with inline SVG thermometer, wind, rain cloud, and droplet icons in both wildlife hourly table and colour comfort rows

### Changed (Feb 28, 2026)
- **Async forecast run endpoints** — all five run endpoints (`/run`, `/run/very-short-term`, `/run/short-term`, `/run/long-term`, `/run/tide`) now return **202 Accepted** immediately and execute asynchronously via `CompletableFuture.runAsync()`; eliminates `ClientAbortException: Broken pipe` errors caused by long-running runs exceeding HTTP/Cloudflare Tunnel timeouts
- **Frontend run buttons** — success messages now show "Forecast run started" instead of waiting for completion; job runs grid refreshes after 3-second delay to pick up the new run
- **Job Runs button layout** — run buttons grouped into a bordered card with labelled sections ("Forecast Runs" and "Data Refresh"); all buttons now gold `btn-primary` instead of a mix of gold and hard-to-see grey

### Fixed (Feb 28, 2026)
- **Today label duplication** — date strip was showing "Today · Today" instead of "Today · Sat 28 Feb"; `formatDateLabel` now accepts a `skipRelative` flag
- **Map popup footer always visible for ADMIN** — forecast generation timestamp (and tide data fetch timestamp for seascape locations) now shows outside the collapsible "More details" section for admin users
- **Map popup score labels** — removed `cursor: help` style that was showing a confusing `?` cursor on hover over Fiery Sky / Golden Hour labels

### Refactored (Feb 28, 2026)
- **Command + Strategy pattern refactoring** — GoF patterns applied to forecast run pipeline
  - `ForecastCommand` record encapsulates run parameters (run type, dates, locations, strategy, manual flag)
  - `ForecastCommandFactory` builds commands from `RunType`, resolving active model and evaluation strategy
  - `ForecastCommandExecutor` executes commands with parallel execution, skip logic (event passed, long-term exists, Opus min rating), and metrics tracking
  - `NoOpEvaluationStrategy` for wildlife/comfort-only locations — returns null evaluation without calling Claude
  - `RunType` enum (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM, WEATHER, TIDE) replaces both `JobName` and `ModelConfigType`
  - `ScheduledForecastService` simplified to thin scheduling wrapper: `commandFactory.create()` → `commandExecutor.execute()`
  - All forecast controller endpoints simplified to two-line create + execute
  - `V29` Flyway migration: renames `job_name` → `run_type` + adds `evaluation_model` on `job_run`; renames `config_type` → `run_type` on `model_selection`
  - `JobName` and `ModelConfigType` enums deleted
  - `JobRunEntity` now tracks both `runType` and `evaluationModel` separately
  - Frontend updated: `metricsApi.js`, `modelsApi.js`, `JobRunsGrid.jsx`, `JobRunsMetricsView.jsx`, `ModelSelectionView.jsx` all use `runType`
  - 357 backend tests (up from 332), 50 frontend tests — all passing with JaCoCo ≥ 80%

### Added (Feb 27, 2026)
- **Opus optimisation gate** — Opus very-short-term runs now skip slots where the most recent prior rating is below 3 stars (or no prior evaluation exists), saving cost and time on low-value forecasts
- **Per-run-type model configuration** — each run type (Very Short-Term, Short-Term, Long-Term) has an independently configurable Anthropic model (Haiku, Sonnet, Opus)
  - `ModelConfigType` enum: `VERY_SHORT_TERM`, `SHORT_TERM`, `LONG_TERM`
  - `V28` migration adds `config_type` column to `model_selection` and seeds one row per type
  - `ModelSelectionService` rewritten with per-config-type get/set and `getAllConfigs()`
  - `GET /api/models` now returns `{ available: [...], configs: { VERY_SHORT_TERM: "HAIKU", ... } }`
  - `PUT /api/models/active` now accepts `{ configType: "...", model: "..." }`
  - Frontend "Model Config" tab split into three sub-tabs with independent model pickers
- **Very-short-term forecast run** — new `POST /api/forecast/run/very-short-term` endpoint (T, T+1) using the VERY_SHORT_TERM model config; new run button in Job Runs dashboard
- **Flat evaluation strategy hierarchy** — Haiku, Sonnet, and Opus all extend `AbstractEvaluationStrategy` directly (Opus previously extended Sonnet); shared `SYSTEM_PROMPT` and `PROMPT_SUFFIX` in the abstract class; strategy differentiation is purely which Anthropic model is used
- **Cloudflare Tunnel** — app publicly live at `https://app.photocast.online` and `https://api.photocast.online`; installed as macOS launchd service, starts at boot
- **Email field on users** — `V27` migration adds `email` column to `app_user`; required when creating a user (basic format validation front and back); displayed in the Users table in ManageView
- **Admin password reset** — `PUT /api/users/{id}/reset-password` generates a secure 12-char temporary password server-side, sets `passwordChangeRequired = true`, returns the raw password once; ManageView shows a modal with copy-to-clipboard
- **Photo Cast rebrand** — app name updated from Golden Hour to Photo Cast; subtitle updated to "AI Driven Sunrise and Sunset Forecasting"; browser tab title updated
- **Username validation** — changed from "must be an email address" to "at least 5 characters" (email is now a separate required field)
- 332 backend tests, all passing

### Added (Feb 25, 2026)
- **Job Run Metrics** — persistent tracking of scheduled forecast runs and API call timings
  - `V20` migration adds `job_run` and `api_call_log` tables with cost tracking
  - `V21` migration adds `consecutive_failures`, `last_failure_at`, `disabled_reason` to locations table
  - `JobRunEntity`, `ApiCallLogEntity` JPA entities with full metrics capture
  - `JobRunService` for recording job starts/completions and API call details
  - `CostCalculator` service for calculating API call costs by service and model
  - Cost configuration in `application*.yml` with per-service pricing (Anthropic, WorldTides, Open-Meteo)
  - `JobMetricsController` endpoints: `GET /api/metrics/job-runs`, `GET /api/metrics/api-calls`
- **Job Metrics Dashboard** — Admin-only view in ManageView showing last 7 days of metrics
  - `JobRunsMetricsView`, `JobRunsGrid`, `JobRunDetail`, `MetricsSummary` React components
  - Sortable/pageable grid with per-service API call breakdown
  - 7-day aggregated statistics: total runs, success rate, slowest service, evaluation count
  - Cost aggregation per run and per job type
- **Retry Robustness** — resilient API failure handling
  - Anthropic 529 (overloaded) retry logic with exponential backoff (1s → 2s → 4s, max 30s, 3 retries)
  - Dead-letter mechanism: locations auto-disabled after 3 consecutive forecast failures
  - `AbstractEvaluationStrategy.invokeClaudeWithRetry()` with detailed logging
  - Request/response interceptor logs all `/api/**` calls at INFO level with timing
- **Docker & CloudFlare Deployment** — production-ready containerization
  - `Dockerfile` — multi-stage build, alpine base, health checks, non-root user
  - `docker-compose.yml` — service definition with volumes, environment variables, restart policy
  - `application-prod.yml` — production Spring Boot config with H2 persistence
  - `goldenhour-backup.sh` — automated daily database backups (keeps last 7)
  - Support for CloudFlare Tunnel exposure without opening router ports
  - Documented cron schedule for backups and scheduled forecast jobs
- 271 backend tests (up from 214), all passing with ≥80% JaCoCo coverage

### Earlier changes
- Wildlife location UI — pure-WILDLIFE locations get a green 🦅 map marker and an hourly comfort timeline in the popup (time · temp · wind · rain); no colour score bars
- Hourly comfort forecasts — one DB row per full UTC hour between sunrise and sunset via a single Open-Meteo call (`getHourlyAtmosphericData`); no Claude evaluation, zero AI cost
- `WILDLIFE` added to `EvaluationModel` enum; `HOURLY` added to `TargetType` enum
- `V19` Flyway migration — adds `temperature_celsius`, `apparent_temperature_celsius`, `precipitation_probability_percent` to `forecast_evaluation`; columns populated on every row (colour and wildlife)
- Comfort data on all colour popups — temp / feels-like / wind / rain shown below colour scores for LANDSCAPE/SEASCAPE locations
- `runWildlifeForecasts()` scheduled at 06:00 and 18:00 UTC for pure-WILDLIFE locations
- `hasColourTypes()` / `isPureWildlife()` location helpers in `ScheduledForecastService` and `ForecastController`
- `WildlifeComfortCard.jsx` — reusable comfort card component
- `conversions.js groupForecastsByDate` collects `HOURLY` rows into a sorted `hourly[]` array, deduplicating by slot + most-recent run
- 257 backend tests (up from 244), 78 frontend tests — all passing

### Session expiry warnings — amber banner at ≤7 days, red at ≤1 day; "Refresh session" button extends by 30 days
- Refresh token rotation — `/api/auth/refresh` revokes the old token and issues a new 30-day one on every call
- `refreshExpiresAt` included in login and refresh responses; stored in localStorage
- `SessionExpiryBanner` component — renders between header and main content when session is close to expiry
- Session countdown (`Session: 30d`) displayed in header below Sign out button for ADMIN users
- "↻ Reload data" button removed — F5 has the same effect and the data auto-reloads after any re-run

### Fixed
- `password_change_required` column add fails on H2 (`ddl-auto: update`) — added `DEFAULT FALSE` to `columnDefinition`
- Session days display shows 30d on fresh login (was 29d due to `Math.floor` rounding)


- JWT authentication — stateless Spring Security with ADMIN and USER roles
- `app_user` table (V10 migration) with default `admin` / `golden2026` account
- `refresh_token` table (V11 migration) for refresh token persistence
- `AppUserEntity`, `RefreshTokenEntity`, `UserRole` — user and token JPA entities
- `JwtService` — access token generation/validation, refresh token hashing (SHA-256)
- `UserService` — implements `UserDetailsService`, user CRUD operations
- `SecurityConfig` — stateless filter chain, disables anonymous auth (unauthenticated → 401)
- `JwtAuthenticationFilter` — extracts and validates JWT on every request
- `JwtProperties` — typed config binding for `jwt.*` in `application.yml`
- `AuthController` — `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`
- `UserController` — `GET/POST /api/users`, `PUT /api/users/{id}/enabled`, `PUT /api/users/{id}/role` (ADMIN-only)
- `AuthContext` + `AuthProvider` React context for token/role state
- `LoginPage` component — dark-theme login form with `data-testid` attributes
- `authApi.js` — `login()`, `refreshAccessToken()`, `logout()` API calls
- Axios request/response interceptors in `forecastApi.js` — auto-attaches JWT, retries once on 401 with refresh token
- `App.jsx` auth gate — renders `LoginPage` when unauthenticated; logout button in header
- `ViewToggle` hides Manage tab from non-ADMIN users
- User Management section in `ManageView` — table of users with enable/disable toggles and add-user form
- `UserRole` enum: renamed `USER` to `LITE_USER`; added `PRO_USER`
- `POST /api/auth/change-password` endpoint — validates complexity (min 8 chars, upper, lower, digit, special character)
- `V12` migration: `password_change_required` column on `app_user`
- First-login password change gate — `ChangePasswordPage` with live complexity checklist; shown after login when `passwordChangeRequired` flag is set
- ManageView split into Users / Locations sub-tabs
- Add-user form: username must be `"admin"` or a valid email address; show/hide password eye toggle
- CORS moved from deleted `CorsConfig.java` to `SecurityConfig` (`CorsConfigurationSource` bean at filter level)
- Show/hide password toggle on `LoginPage`
- `JwtServiceTest`, `AuthControllerTest`, `UserControllerTest`, `UserServiceTest`, `JwtAuthenticationFilterTest` — new test classes (148 total tests, 0 failures)
- `spring-security-test` added as explicit test dependency for `@WithMockUser`
- `jwt.*` config block added to `application-example.yml`, `application-local.yml`, and test `application.yml`
- SpotBugs exclusions for pre-existing `EI_EXPOSE_REP` issues in `OpenMeteoForecastResponse`, `OpenMeteoAirQualityResponse`, and `LocationEntity`

- `LocationType` enum (`LANDSCAPE`, `WILDLIFE`, `SEASCAPE`) stored as `@ElementCollection` join table (`V8` migration)
- `tideType` converted from single value to `Set<TideType>` via `@ElementCollection` join table (`V9` migration) — supports multiple preferred tides per location (e.g. `LOW_TIDE` + `MID_TIDE`)
- `MID_TIDE` added to `TideType` enum
- `goldenHourType`, `tideType`, and `locationType` wired into `application.yml` location config — YAML is now the source of truth for location metadata and synced to the DB on every startup
- `LocationRepository.findByName()` used by seeder to detect and apply metadata changes for existing locations
- `LocationService.isSeascape()` helper
- `LocationTypeBadges` component — pill badges for golden hour preference (amber), location type (grey), and tide preference (cyan) shown on compact cards, by-location header, and map popups
- Map popup redesigned: prominent title, event time pill inline with title, location type / golden hour type / tide rows, golden & blue hour row; azimuth direction pills removed (lines on map convey this better)
- All popup pills standardised to 11 px / 2 px 8 px padding
- Map popup footer shows "Forecast generated: 23 Feb 2026 13:25" (full date including year) separated by a hairline; `formatGeneratedAtFull()` added to conversions.js

## [0.3.0] - 2026-02-22

### Added
- DB-backed locations table (`V5` Flyway migration) — locations now persist across restarts
- `LocationEntity`, `LocationRepository`, `LocationService`, `LocationController`
- `GET /api/locations` and `POST /api/locations` endpoints
- `@PostConstruct` seed: YAML-configured locations are promoted to the database on first boot
- Runtime add-location form in ManageView — name, lat, lon with client-side validation and inline error display
- `fetchLocations` and `addLocation` added to `forecastApi.js`
- ManageView now fetches its own location list from the API (decoupled from props)
- Exponential backoff retry on Open-Meteo 5xx errors (2 retries, 1 s base, 4xx not retried)
- Multi-location UI: location tabs, by-date strip, compact card grid, map view (Leaflet), and view toggle
- Golden hour and blue hour time-range pills on each ForecastCard
- Per-card Re-run button wired to `POST /api/forecast/run` with specific `targetType`
- `ForecastService.runForecasts()` overload accepting optional `targetType` (null = both)
- `formatShiftedEventTimeUk`, `formatGeneratedAt`, `groupForecastsByLocation` utility functions
- 84 backend unit and integration tests (up from 61); covers LocationRepository, LocationService, LocationController

### Fixed
- `ForecastControllerTest` stub updated to match 5-arg `runForecasts` signature

## [0.2.0] - 2026-02-22

### Added
- Evaluation strategy pattern — `EvaluationStrategy` interface with `HaikuEvaluationStrategy` and `SonnetEvaluationStrategy`
- `@Profile("lite")` activates Haiku; default profile activates Sonnet
- Solar and antisolar horizon model added to Claude system prompt for directional cloud reasoning
- Local H2 dev profile (`-Dspring-boot.run.profiles=local`) — no Docker required for development
- H2 console available at `/h2-console` when running with local profile

### Fixed
- Java 23 test compatibility issue resolved

## [0.1.0] - 2026-02-22

### Added
- Spring Boot 3 REST API with Open-Meteo Forecast and Air Quality integration
- Claude evaluation via Anthropic Java SDK — 1–5 colour potential rating with reasoning
- `SolarService` wrapping `solar-utils` for precise sunrise/sunset times
- `ForecastService` orchestrating Open-Meteo → Claude → PostgreSQL pipeline
- `ScheduledForecastService` running evaluations at 06:00 and 18:00 UTC for T through T+7
- `ForecastController` — `GET /api/forecast`, `GET /api/forecast/history`, `POST /api/forecast/run`, `GET /api/forecast/compare`
- `OutcomeController` — `POST /api/outcome`, `GET /api/outcome`
- Flyway migrations V1–V4 for `forecast_evaluation` and `actual_outcome` tables
- Notification system — email (Thymeleaf HTML), Pushover (iOS push), macOS toast (osascript)
- React 18 frontend with Vite, Tailwind CSS, ForecastTimeline, StarRating, CloudCoverBars, WindIndicator, VisibilityIndicator, OutcomeModal
- `solar-utils` published to GitHub Packages; pulled automatically by Maven

[Unreleased]: https://github.com/gregochr/goldenhour/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/gregochr/goldenhour/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/gregochr/goldenhour/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/gregochr/goldenhour/releases/tag/v0.1.0
