# Retiring the Best bet advisor call

Owner decision, 2026-11-21: "yeah, let's remove it". Saves about $2.8 per 30 days (ledger figure of 2026-11-01,
an estimate; `claude-cost-ledger.md`). The advisor (`BriefingBestBetAdvisor`, Sonnet/Haiku per `model_selection`
row `BRIEFING_BEST_BET`) writes `DailyBriefingResponse.bestBets`, which no reader screen renders.

## What is NOT changing (the invariants this plan must hold)

- The reader-facing Best bet and Also good are chosen by `PlanWindowProjector` from the ratings and worded by
  the region gloss (`BriefingGlossService`). Neither reads `bestBets`. Ratings, verdicts, the pick choice, the gloss
  and every other Claude prompt are untouched.
- Aurora does not depend on the advisor: `AuroraRegionSelector` is used only by the advisor's rollup.
- Ask PhotoCast, Coming up, hot topics, Lite shaping and the frontend picks UI do not read `bestBets`.

## What the owner loses (admin only, accepted)

The intraday-against-nightly pick comparison box in the Runs cycle panel, the advisor replay endpoint
(`POST /api/admin/advisor-replay`, no UI), the Run Config "Briefing" model tab and cost line, and the
`pipeline_run.best_bet_status` signal.

## Traps found by the trace (the build must handle each)

1. **Stale-pick resurrection.** `ServedBriefingAssembler.applyBestBetFallback` pulls old picks from
   `pipeline_run_pick` when `bestBetStatus() == FAILED`. After the call is removed the briefing must carry
   `bestBets = []`, `bestBetModel = null`, `bestBetStatus = null` (never `FAILED`), and the fallback path is deleted.
   A briefing persisted before deploy (`daily_briefing_cache`) may still carry old `bestBets`/`FAILED`; it must
   serve without error and without resurrecting picks (the fallback is gone, so a stored FAILED is inert).
2. **Old cached JSON.** The three response fields stay on `DailyBriefingResponse` in R1 so old stored JSON
   still deserialises and the wire format is unchanged (`JsonDateFormatContractTest`, `DailyBriefingResponseJsonTest`).
3. **Enum and table removal need Flyway**, which is proven only in CI (no Docker locally). So they are R2, with
   a Testcontainers test, not R1.
4. **`BestBetAuroraPromptRegressionTest`** tests the advisor's own prompt (`@Tag("prompt-regression")`). It is
   deleted with its subject. This is the one prompt-regression file touched; it is a deletion, not an assertion
   change, and it is called out in the PR description for the owner.

## R1: stop calling it, delete the code and the admin surfaces (no migration)

Backend:
- `BriefingService`: remove the `bestBetAdvisor.advise` call (lines ~727-731), the constructor parameter and every
  call site/test. Build the response with `List.of()`, `null`, `null` for `bestBets`, `bestBetModel`, `bestBetStatus`.
  The stale (last-known-good) branch must stop copying `lkg.bestBets()/bestBetModel()/bestBetStatus()`.
- Delete `BriefingBestBetAdvisor`, `BestBetPromptText`, `BestBetRanker`, `BestBetPickValidator`,
  `BestBetResponseParser`, `BestBetEnricher`, `BriefingRollupBuilder`, `AuroraRegionSelector`, `RollupResult`,
  `CandidateCoverage` (and any other type only they use), `AdvisorReplayController` with its request/response records,
  `BestBetFallbackService`, and `ServedBriefingAssembler.applyBestBetFallback`.
- `PipelineOrchestrator.persistPicksForCycle` and its calls: delete. Delete `PipelineRunPickService`,
  `PipelineRunPickRepository`, `PipelineRunPickEntity`, `PipelineRunPickComparison`, `PipelineRunComparisonService`,
  the `comparison` field of `PipelineRunDetail` and `PipelineRunController`'s use of it.
  `pipeline_run.best_bet_status` stays as a column and entity field in R1, but nothing writes it
  (`recordBestBetStatus` deleted) and nothing reads it.
- KEEP: `AlsoGoodFloor`, `BriefingRegion.pickEligible`, `BriefingRatingStats`, `PlanWindowProjector`,
  `BriefingHonestyFilter` and `BriefingRoleShaper` (their bestBets handling becomes a no-op; removing it is R2),
  `RunType.BRIEFING_BEST_BET`, `ModelSelectionService`'s row, the Run Config tab (R2). Fix Javadoc that names deleted classes.
- Remove the `photocast.best-bet.*` config keys only if nothing else reads them; update
  `application-example.yml` and `docs/engineering/reference/configuration.md`.
- Spend: no more `briefing-best-bet` `api_call_log` rows. `RunCostProfileService`'s best-bet entry stays until R2.

Frontend:
- Delete `components/runs/CycleComparison.jsx`, its call in `CyclePanel.jsx`, the pick helpers in `utils/cycleView.js`
  (`pickLine`, `pickHeadline`, `rankLabel`) and the comparison parts of `api/pipelineRunApi.js`, with their tests.
  Run the import-graph orphan check afterwards and keep deleting until clean. Never lower coverage thresholds.

Tests: delete the advisor, rollup, ranker, validator, parser, enricher, selector, replay, fallback, pick-service and
comparison tests; edit `BriefingServiceTest`, `BriefingServiceOverlayAndFlightTest`, `BriefingServiceServedMemoTest`,
`PipelineOrchestratorTest`, `AbstractControllerTest`, `BriefingControllerTest`, `HttpCachingIntegrationTest` and the
frontend `roles.spec.js` smoke only as far as the removed pieces require. Add a test that a stored briefing with
`bestBetStatus = FAILED` and non-empty `bestBets` serves with no picks resurrected, and that a rebuilt briefing carries
`bestBets = []`, `bestBetModel = null`, `bestBetStatus = null`. Backend per-class coverage (95% line, 90% branch)
and the changed-lines check must hold.

Docs: `claude-cost-ledger.md` dated entry (decision, estimate, unmeasured); `reference/pipeline.md`,
`ratings-stores.md`, `api-endpoints.md` (advisor-replay endpoint, `comparison`), `architecture-decisions.md`,
`configuration.md`, CLAUDE.md and any plan whose statement is now false (grep `BestBetAdvisor`, `advisor-replay`,
`pipeline_run_pick`, `CycleComparison`); `glance-to-map-plan.md` line 120: day-5 trim rejected (the fifth date's
gloss is what gives T+3 a pick overnight), advisor retired. Changelog entry.

## R2: dead fields, enum, table (needs Flyway, proven in CI)

After R1 has been live for a deploy and the owner has seen no regressions:
- Flyway migration (next number from the tree): drop `pipeline_run_pick`, drop `pipeline_run.best_bet_status`,
  delete the `model_selection` row for `BRIEFING_BEST_BET`. Testcontainers test.
- Remove `RunType.BRIEFING_BEST_BET` (check `LocalH2EnumWidener`, `RunClassifier`, `RunDeepType`, `ModelSelectionService`,
  `RunCostProfileService`, the Run Config tab in `ModelSelectionView.jsx` and its extended-thinking toggle).
- Remove `bestBets`, `bestBetModel`, `bestBetStatus`, `bestBetsWithdrawn` from `DailyBriefingResponse` and the
  `BestBet`, `BestBetResult`, `BestBetStatus` records, `BriefingHonestyFilter.withdrawUnsupportedBets` and the
  `BriefingRoleShaper` lines. Check that old `daily_briefing_cache` JSON still deserialises (Jackson 3 unknown-property
  setting) or that the table is cleared by the same migration. This is a wire-format change: a separate PR.

## Acceptance (R1)

- `grep -rn "BriefingBestBetAdvisor\|BestBetPromptText\|AuroraRegionSelector\|AdvisorReplay\|PipelineRunPick\|CycleComparison\|applyBestBetFallback" backend/src frontend/src` returns nothing.
- The served `/api/briefing` JSON shape is unchanged; `bestBets` is `[]`, `bestBetModel` and `bestBetStatus` are null.
- Glance, Map and Plan picks, Ask and aurora behave exactly as before (the existing picks tests pass unchanged).
- Backend `clean verify` (integration tests excluded), the frontend four commands and the E2E smoke are green.

## Review corrections (backend review, 2026-11-21; these override the sections above)

1. **`BriefingHonestyFilter` uses `BestBetRanker.afterRemoval` (line 316), so deleting the ranker breaks compile.**
   Decision: R1 removes `withdrawUnsupportedBets` and the `bestBetsWithdrawn` write from the filter (the field stays on
   the response until R2, always unset), so the whole ranker goes. Delete the filter's bestBets tests.
2. **A cache restored at startup still carries old picks.** `BriefingService` `@PostConstruct` (~lines 286-293) loads
   `daily_briefing_cache` into both `cache` and `lastKnownGood`. R1 neutralises on restore:
   `persisted.withBestBets(List.of(), null).withBestBetModel(null).withBestBetStatus(null)` before it is stored.
   Tests: a stored `SUCCESS_WITH_PICKS` payload and a stored `FAILED` payload both serve `bestBets = []` with
   null model and status, in the cache and in last-known-good. The acceptance "bestBets is []" then holds from startup.
3. **`ServedBriefingAssembler`** takes `BestBetFallbackService` in two constructors; remove the parameter and fix
   `ServedBriefingAssemblerTest` (delete `fallbackPickOnBlankedRegion_isWithdrawn` and the ordering-guard javadoc),
   `ServedBriefingAssemblerNeighbourGuardTest`, `ServedBriefingAssemblerWarningGoldenTest`, `BriefingServiceTest`.
4. **`PipelineOrchestrator`** has five constructor overloads taking `PipelineRunPickService` (about lines 154, 199, 246,
   285, 327, 370) and the call at ~803; fix every `new PipelineOrchestrator(` in `PipelineOrchestratorTest`,
   `...AskReadyTest`, `...AlmanacRefreshTest`, `...FireLogTest`. Delete `PipelineRunService.recordBestBetStatus` and its
   tests. Fix the `LateBatchBriefingRebuild` and `PipelinePhase` javadoc and `cycleView.js` label
   `'Briefing (gloss + best-bet)'` to "gloss".
5. **Prompt-regression wiring.** Deleting `BestBetAuroraPromptRegressionTest` also needs:
   `.github/workflows/prompt-regression.yml` (the `-Dtest='SkyRatingRegressionTest,BestBetAuroraPromptRegressionTest,
   SystemPromptTokenCountTest'` lines ~98, 144, 148), `backend/pom.xml` (~line 875 exclusion entry),
   `scripts/test/prompt-regression-workflow.test.sh` (~line 114 asserts that string). Check
   `prompt-regression-paths.test.sh` and `...-summary.test.sh` fixtures treat the class name as an opaque string.
   Never change a surviving regression assertion; this deletes one file with its subject.
6. **Run Config tab leaves in R1, not R2.** Remove the Briefing tab from `ModelSelectionView.jsx` (tabs array,
   `DEFAULT_TAB`, the extended-thinking toggle) and its tests, drop `BRIEFING_BEST_BET` from
   `ModelSelectionService.CONFIGURABLE_RUN_TYPES` and from `RunCostProfileService`'s profiled tabs. KEEP the enum value,
   `RunClassifier`, `RunDeepType` and `tabOf` for historical rows until R2. The `model_selection` row stays until R2.
7. **Javadoc and `{@link}` references** to deleted classes (these break `javadoc` or mislead): `BriefingRegion`,
   `AlsoGoodFloor`, `PlanWindowProjector`, `BriefingRatingStats`, `PlanRenderLimits`, `AskSnapshotBuilder`,
   `AskWindowId`, `ForecastHorizon`, `BriefingSlot`, `WeatherAugmentedTideService`, `BriefingEvaluationService`,
   `ForecastScoreEntity`, `RunCostProfileService`, `EvaluationViewService` (lines ~985-989, 310, 1175) and
   `EvaluationViewServiceTest`, `frontend/src/utils/askMapContext.js`. Grep `PipelineRunPickService|BriefingRollupBuilder|
   BestBetRanker|BriefingBestBetAdvisor` across `backend/src` and `frontend/src` comments, not only code.
8. **Acceptance grep scope:** `PipelineRunPickDedupeMigrationTest` and the migration tests that mention `PipelineRunPick`
   in javadoc (`ClearDriveTimeStampWithoutRowsMigrationTest`, `RetireDeadOptimisationStrategiesMigrationTest`) keep V148
   verified and stay in R1: exempt from the grep. Scope the grep to `backend/src/main`, `backend/src/local*`, frontend
   `src`, and the tests this plan deletes.
9. **`src/local` and `src/local-test`** (`E2eLocalFixtureSeeder`, `E2eLocalFixtureSeederTest`) persist
   `BestBetResult`/`SUCCESS_NO_PICKS`: change the seeded briefing to `List.of()`, null model, null status now (the types stay until R2).
10. **Frontend orphans:** also delete `changedDimensionsText` (only `CycleComparison.jsx` and `cycleView.test.js` use
    it); fix `RunsCycles.test.jsx` (~376-471, `cross-run-comparison`), `runsFixtures.js` (~194), the `pipelineRunApi.js`
    doc, `BackendPipelineRunControllerTest` (~183-193 asserts `$.comparison`), `PipelineRunControllerTest` and
    `AbstractControllerTest` (drop the `PipelineRunComparisonService` mock). Removing `comparison` from
    `PipelineRunDetail` changes the admin-only JSON of `/api/admin/pipeline-runs/{id}`: say so in the PR; the public
    briefing shape is unchanged.
11. **Config:** remove BOTH `photocast.best-bet.max-tokens` and `photocast.best-bet.fallback-max-age-hours` from
    `application-example.yml`, any profile, and `reference/configuration.md`.
12. **Do NOT touch** `ForceEvalHeadlineSelector`, `ForecastTaskCollector` and `ScheduledBatchEvaluationService`'s
    "best-bet headline contenders": they force-evaluate far-out cells whose ratings feed `PlanWindowProjector`. That
    spend is separate from the $2.8 and stays.

## Review corrections (frontend/tests/docs review, 2026-11-21)

13. **Sky prompt-regression job will be requested** by the PR (`scripts/prompt-regression-paths.sh` sets `sky=true` for any
    change under `service/evaluation/*` and for a `*Regression*.java` path). It spends real money and waits for the owner;
    never wait on it or approve it, say so in the PR body. Do not edit `prompt-regression-paths.sh`.
14. **Run Config (overrides item 6 detail):** remove the Briefing tab, its Extended Thinking panel, the tip text and
    `BRIEFING_BEST_BET` from `DEFAULT_TAB` (use `AURORA_EVALUATION`), the hash/fallback logic at `ModelSelectionView.jsx` ~:274,
    the cost-profile key, and fix `ModelSelectionView.test.jsx` (~40 refs), `ModelSelectionViewHash.test.jsx`,
    `modelsApi.js` JSDoc. `platform.md` ~199-205 documents "falls back to Briefing": update it.
15. **`cycleView.js`** also loses `changedDimensionsText`, `DIMENSION_LABEL`, `pickDate`, `PICK_DATE_FORMAT`, `capitalise`
    (only used by the comparison) and the `UK_ZONE` import once unused; edit `cycleView.test.js` imports and the
    `pickLine`/`pickHeadline`/`rankLabel`/`changedDimensionsText` blocks. `pipelineRunApi.js` is NOT deleted: only two
    JSDoc lines change (`fetchPipelineRunDetail` is live in `useCycleDetail.js`). In `RunsCycles.test.jsx` delete only the
    `describe('the comparison')` block and its fixtures (~364-480). Change the label `'Briefing (gloss + best-bet)'` to
    `'Briefing (gloss)'` and fix `RunsCycles.test.jsx:257` and `RunsLiveStrip.test.jsx:105`; fix `PipelinePhase.java:61`
    and `PipelineOrchestrator.java:72` comments.
16. **More tests to edit or delete:** `PipelineRunControllerTest`, `PipelineRunPickRepositoryTest` (delete),
    `EvaluationViewServiceTest`, `BriefingRollupBuilderAuroraSnapshotTest` (delete), `BestBetRankerCoverageTest` (delete),
    `DailyBriefingResponseBigSeasTest`. KEEP `BestBet` and `BestBetStatus` (R1 keeps the record shape; `DailyBriefingResponseJsonTest`,
    `RecordWitherPreservationTest`, `ReviewedTypes` use them). Delete `BestBetResult` in R1 if nothing in `src/main` still
    uses it after the change. `PipelineRunPickDedupeMigrationTest` and the two other migration tests must keep compiling and
    passing without the deleted entity and repository (check they use raw SQL); if one uses the entity, rewrite it to raw SQL.
    Every kept class must stay at 95% line / 90% branch per class: where a kept class loses its dedicated tests, add a minimal one.
17. **Docs that become false** (fix each; historical investigations may stay): `reference/pipeline.md` (~13, 25),
    `plan-tab.md` (~8, 153, 240), `architecture-decisions.md` (~38, 51), `api-endpoints.md` (~197, 204, 236-237, 260),
    `platform.md` (~199-205), `ratings-stores.md` (~302-355, 384, 419), `aurora.md` (~6), `roles-and-gating.md` (~64),
    `dev-and-testing.md` (~362), `claude-cost-ledger.md` (~23, 189, plus the new entry), `cost-queries/measured-costs.sql`,
    `docs/pipeline-reference.html`, `docs/product/backlog.md`, `docs/design/thin-sample/PICK_ELIGIBILITY.md`,
    `plan-panel-data-contracts.md`, `glance-to-map-plan.md` (lines ~12, 63, 119, 120, 125). CLAUDE.md and the landing
    pages need no change (`landing/faq.html` line ~182 describes the gloss-worded pick; leave it). `configuration.md` has no
    best-bet key, so no change there.
18. **`@PostConstruct` neutralisation** (item 2) is the single definition of "bestBets is empty": acceptance holds from startup.
