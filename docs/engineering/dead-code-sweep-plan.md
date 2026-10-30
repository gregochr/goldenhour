# Pre-launch dead-code sweep

**Status: PLAN REVIEWED, OWNER DECISIONS MADE 2026-10-30 (§7). Phase F deferred until after launch.**
Written against `main` at `1b807df4c` (after v2.35.2 and the model-test retirement, #1517 and #1521).

Owner goal (2026-10-30): close to going live, remove redundant code and tech debt.

### Phase log

| phase | branch | commit | date | notes |
|---|---|---|---|---|
| survey | `claude/dead-code-sweep` | — | 2026-10-30 | Three read-only sweeps (backend, frontend, schema and wire). The planner re-checked every "high" item by grep. Review: two lenses (refute A/B; C–E risks), about 35 charges, all folded in. Notable: five Phase B items turned out to be live test seams or test entry points, `last_polled_at` is on a kept endpoint, and dropping `voting_count` needs a same-release `DROP NOT NULL`. |
| A | `chore/dead-code-sweep-phase-a` | see PR | 2026-10-30 | Built as planned. `LocationSheetFactsParity.test.jsx` deleted (it only compared the sheet with the deleted popup; `LocationFourDaySheetFacts` and `locationSheetShell` already cover the sheet's facts). `getBriefingScore` moved into its test as the reference scan; `LOCATION_TYPES` deleted rather than imported; `prop-types` declared with its lock `dev` flags cleared. |

### Method and limits

- **Dead** means no live caller in `src/main` (backend) or no path from `main.jsx` (frontend). Callers in tests don't count.
- Every high-confidence item was re-checked with `grep -rnw` over `backend/src/main/java`, `backend/src/local` and `frontend/src` (excluding `test/`) before it went in this plan. Medium and low items are listed with the reason they might still be wanted.
- **Not dead (checked):**
  - No orphaned scheduler rows: all 28 job targets have a seed row, except `daily_briefing` (§7 D6).
  - No unmapped tables except the `forecast_type` lookup table, which a foreign key needs.
  - No unused npm dependencies.
  - No dead CSS block of 10 or more lines.
  - Every `photocast.*` config key is read.
- **Builders re-run the greps.** Line numbers drift; the builder re-runs each item's grep at build time and drops any item that has gained a caller.

### Estimated size

| phase | what | production LOC | test LOC |
|---|---|---|---|
| A | frontend dead modules and exports | ~1,450 | ~2,500 |
| B | backend dead classes, methods and config | ~550 Java + ~275 yml | ~600 |
| C | admin endpoints with no UI (owner picks) | up to ~2,700 | ~1,500 |
| D | briefing fields dead end to end | ~150 | ~200 |
| E | write-only columns (two-release drops) | ~40 | small |
| F | wire-format slimming (separate design) | — | — |

If every phase lands, that's roughly 5,000 lines of production code and 4,800 lines of tests.

---

## 1. Phase A: frontend dead modules and exports (one PR, no owner decision)

**Delete these modules and their tests:**

| module | LOC | tests |
|---|---|---|
| `components/MarkerPopupContent.jsx` (the old map popup; `MapView` no longer imports it) | 1,006 | `MarkerPopupContent.test.jsx` (1,637), `MarkerPopupContentDetail.test.jsx` (162) |
| `components/TideIndicator.jsx` (imported only by the above) | 127 | none |
| `utils/solarEventTimes.js` | 78 | `solarEventTimes.test.js` (144); remove its import from `windowKeys.test.js` and keep the rest of that file |
| `components/shared/VerdictPill.jsx` | 72 | `shared/VerdictPill.test.jsx` (86). **Keep `unratedVerdictWords.test.jsx`**: it also pins the live `VERDICT_TINT`/`VERDICT_TREATMENT` and the stylesheet's UNRATED rules (`index.css:3234,3259,6714` cite it). Drop only its `VerdictPill` case. |
| `components/shared/SlotLocationName.jsx` | 64 | `SlotLocationName.test.jsx` (37) |
| `hooks/useLocalStorageState.js` | 33 | `useLocalStorageState.test.js` (60) |

**`LocationSheetFactsParity.test.jsx`** checks the popup against `LocationSheetFacts`, which is live. Rework it to test `LocationSheetFacts` alone, or delete it if the sheet's own tests already cover the same facts. Say which in the PR.

**Stale mocks:** about 57 `MapView*` and `Ask*` test files `vi.mock('../components/MarkerPopupContent.jsx')`. Delete those mock lines. Likewise drop the `getDriveTimes` key from the `settingsApi` mocks once §3 C9 lands, if it does.

**Dead exports** (delete the export, and the code if nothing in its own module uses it, plus the test cases that cover only it):
- `utils/conversions.js`: `moonIlluminationStyle`, `MOON_EMOJI`, `MOON_PHASE_NAME`, `bortleLabel` (dead once the popup goes).
- `components/AuroraBanner.jsx`: `bzStatus`.
- `utils/briefingScoreIndex.js`: `getBriefingScore`.
- `utils/askNights.js`: `nightWindowId`.
- Single constants:
  - `utils/auroraRamp.js` `AURORA_TOGGLE_SWATCH`, `utils/busyButton.js` `BUSY_BUTTON_SECONDARY`;
  - `utils/locationTypes.js` `LOCATION_TYPES` (`LocationManagementView` declares its own; consider importing this one instead and deleting the local copy, whichever is smaller);
  - `utils/planOrigin.js` `AWAY_LIMIT_MINUTES`, `utils/topicPalette.js` `TOPIC_PALETTE_TYPES`.
- `utils/planningArea.js` `areaSpots`: **medium**. The name collides with a `heat.areaSpots` property, so confirm by import, not by name.
- Unused default exports in `hooks/useIsCoarsePointer.js` and `utils/firstClause.js` (every importer uses the named export).

**API functions nothing calls:**
- `api/forecastApi.js`: `fetchTidesForDate` and `fetchTideStats`. Both duplicate `tideApi`, and their only importer was `TideIndicator`.
- `api/tideApi.js`: `fetchTidesForDate`. Four tests mock it; drop those mock keys.
- `getDriveTimes`, `getSkyRatingEvalResults` and `updateMarketingEmailOptIn` go with their endpoints in Phase C (C9, C10, C11), not here.

**Keep:** `swrCache.storageKey`, `resetInviteTokenForTests` and `resetLinkTokenForTests`. These are deliberate test hooks, documented as such.

**Also in this PR:** add `prop-types` to `frontend/package.json` `dependencies`, at the version already in `package-lock.json`. It's imported in 165 files but installs only because another package depends on it. Prove it with `rm -rf node_modules && npm ci`.

**`utils/forecastDetailFacts.js`:** once the popup goes, `TRIAGE_REASON_LABELS`, `risingTideOf`, `isDustEnhanced` and `mistBadgeOf` are used only inside the file. Drop their `export` keyword unless a test imports them, and fix the header comment (lines 9-12) that describes the popup importing them.

**Comments:** the deleted names are cited in comments in `forecastDetailFacts`, `ScoreBar`, `mapCallout`, `leaveBy.js:40`, `locationSheet.js:208`, `WindowSheetDialog:59`, `windowFirstCards:212/882`, `LocationFourDaySheet:93`, `WindowGlanceDayTopics.jsx:100`, `MapView.jsx:142` and `index.css:8668` (which also names the deleted Prompt Test). Reword each to "the since-deleted X" or drop the citation.

**Reference docs** (CLAUDE.md requires them in the same PR): `reference/aurora.md`, `conditions.md`, `map-tab.md`, `plan-tab.md`, `architecture-decisions.md` and `roles-and-gating.md` cite `MarkerPopupContent` or `VerdictPill`. Re-grep them, and reword each citation as history.

**Gates:** the four frontend commands. **UI check:** this is a deletion of unreachable code, so nothing on screen should change. A browser smoke test of the Map tab (open a location callout) and the Plan tab is enough. Say whether it was seen.

## 2. Phase B: backend dead classes, methods and config (one PR, no owner decision)

All verified by grep: the only hit in `src/main` is the declaration.

**Whole classes:**
- `config/ForecastProperties` (80). `@ConfigurationProperties("forecast")` is bound but never injected. Also delete the `forecast:` blocks in `application-prod.yml:198-396`, `application-local.yml:56-106`, `application-example.yml:73-92` and `application-dev.yml:39-44`. ⚠️ The gitignored `application.yml` on production may carry a block too; an unbound prefix is harmless, so note it in the changelog rather than requiring an edit.
- `model/EvaluationResponse` (20) and `model/DriveTimesRequest` (9), with no references anywhere.

**`service/EvaluationFailure`:** delete the constants' unused machinery: `of`, `fromErrorType`, `fromStatus` and `reason()`. Keep `errorTypeOf`, `httpStatusOf`, `TYPE_*` and `FALLBACK_REASON`, which are live. If nothing is left that needs the enum constants, turn it into a final utility class, and say so in the PR. It isn't persisted, so no DDL.
- **Keep the `errorTypeOf` assertions** in `EvaluationFailureTest.java:145,158` (`of_circuitOpen…`, `of_bulkheadFull…`). They are the only coverage of its `circuit_open` and `bulkhead_full` branches; rewrite those tests to call `errorTypeOf` directly.
- `TYPE_PARSE_ERROR` loses its only reader. Live code writes the literal `"parse_error"` (`BatchResultProcessor:304,341`, `ForecastResultHandler:289,348`); use the constant there.
- Check that the run-progress UI doesn't depend on a string only the deleted constants produced (`frontend/src/test/runProgressFixtures.js:86-91` mirrors "Claude rejected the API key.").
- Fix the Javadoc at `ClaudeBreakerIgnorePredicate.java:16`, which links a nonexistent `EvaluationFailure#stopsRun`.

**Methods with no caller in main** (delete them and the tests that exist only for them):
- `OpenMeteoService.prefetchWeatherBatch`.
- `OpenMeteoClient.fetchForecastBriefing`, `OpenMeteoClient.fetchCloudOnly` and `MarineClient.fetchMarine`. Keep the `open-meteo-briefing` resilience instance; `BriefingService` uses it.
- `ScheduledBatchEvaluationService.submitForecastBatch()` (legacy zero-arg) and `ForecastTaskCollector.collectScheduledBatches()` (zero-arg). Both are dead in main but are the entry points for most of their test classes: 56 references in `ScheduledBatchEvaluationServiceTest`, and 45 in `ForecastTaskCollectorTest` plus `ForecastTaskCollectorForceEvalTest`. **Repoint those tests** at `submitForecastBatchForPipelineRun` and the live overload; do not delete them, or the 80 % per-class gate fails.
- `CustomIdFactory.forJfdi`. Keep `parseJfdi` and the `ParsedCustomId.Jfdi` switch arms (old rows); rewrite `CustomIdFactoryTest:104,133` with literal `jfdi-` ids so `parseJfdi` stays covered.
- `ForecastResultHandler.flushCacheKey` (already `@Deprecated`).
- `LocationService.shouldEvaluateSunrise`, `shouldEvaluateSunset` and `isSeascape`.
- `GitInfoService.getCommitAbbrev` and `isAvailable`.
- `JobRunService.findRun` and `BatchSummaryDeriver.batchRunTypes` (not even tests call these).
- `EvaluationViewService.forRegion` and `getTriagedByBatchLocationNames` (fix `TriagedByBatchResolver.java:25`, whose Javadoc still says the build path uses the latter).
- `JwtService.extractRole`: `TokenIssuerTest` uses it to assert the issued role claim, so decode the claim another way rather than lose the assertion.
- `LunarPhaseService.isNewOrFullMoon`, `SubRegionMembershipService.subRegionOf`, `RunProgressTracker.isComplete` and `BriefingEvaluationService.hasEvaluation`.
- `ForecastTaskCollector.resolveEligibility`, the `CyclePlaceEvidence` static factories (`triagedOnly`, `scoredIn`, `failedIn`, `collectionError`), `LunarEclipseWording.coverageWord`, `DirectionalSamplingGeometry.computeSolarHorizonPoint` and `BatchSubmissionSummary.allEmpty`.
  - **Medium.** These are test-only helpers. Where a test uses one to build fixtures, move the helper into test sources rather than losing the test.
- `KingTideHotTopicStrategy.findKingTide`, `formatCatch`, `formatCatchShort` and `formatLocationCount`, and `SpringTideHotTopicStrategy.findSpringTide`.
  - **Medium.** These are test seams that have drifted from the live path. If their tests are the only proof of a live rule, rewrite those tests against the live method first.

**Leave as deliberate test seams:**
- `NeighbourGuardLog.rememberedKeys`, `PipelineOrchestrator.currentCompletionState`, `RatingCombiner.appliedVisitors` and `BriefingGlossService.storedGlossCount`.
- `PipelineOrchestrator.runCycleSynchronously`, which the CI-only `OrchestratedDispositionWriteIntegrationTest.java:173` calls.
- `HotTopicEventEnricher.fixedEventFor`/`isStampedByDetector` and `TopicRarity.rankedTypes`. These are accessors over private live policy tables (`EVENT_BY_TYPE`, `EVENT_STAMPED_BY_DETECTOR`, `RANK_BY_TYPE`), and `HotTopicWindowAnchorRosterTest:28-52` uses them as the guard against storm surge vanishing from the Plan. A test-side copy would make that guard tautological.
- `SurpriseScore.bandOf`: it's the function V151 reserves for the hysteresis rule, and §5 keeps `topic_daily_log.band` for the same rule.

**Stale Javadoc to fix in this PR:** `AskLocalFixtureSeeder.java:82` (cites the `forecast.locations` block), `JobRunDto.java:14` (mentions `apiCalls`). **Docs:** `reference/configuration.md:10` lists `forecast.locations` and `poll-interval-minutes`.

**`AuroraProperties`:** delete the never-read fields `pollIntervalMinutes`, `kpClearThreshold`, `ovationClearThreshold`, `dailyLimit` and `delayBetweenCallsMs`, and their yml keys (`application-prod.yml:404-410`, `application-example.yml`). The example yml already says "not read".

**`JobRunEntity.apiCalls`:** a `@OneToMany` that nothing reads. Removing the mapping needs no DDL.

**Do not touch the enum constants** (`CoastalStructure`, `SeenTag`, `TriageReason.GENERIC` and `DispositionCategory`). They are persisted or parsed from strings, so stored rows can hold them.

**Gates:** the full backend ladder ending in `clean verify`. Coverage is 80 % per class, so watch the classes that lose test-only methods along with their tests; the ratio usually holds.

## 3. Phase C: admin endpoints with no frontend caller (owner picks, §7)

These have no caller in `frontend/src`. Most are curl tools that the docs mention. Recommendations:

| # | endpoint and what dies with it | LOC | recommend |
|---|---|---|---|
| C1 | `GET /api/admin/stability/summary`: `StabilityController` (the snapshot provider stays) | 51 | **delete**; `serve-surfaces.md` already calls it dead |
| C2 | `GET /api/forecast/history`: `ForecastController.getHistory`, `ForecastEvaluationRepository.findByLocationIdInAndTargetDateBetween`, `ForecastHistoryQueryCountTest`; fix the Javadoc at `ForecastHorizon.java:137` | ~43 | **delete** |
| C3 | `GET /api/forecast/compare`: `getCompare` and its repository finder | ~25 | **delete**; move `GlobalExceptionHandlerTest` to another endpoint |
| C4 | `GET /api/locations/grid-cells`: controller method and `LocationService.getGridCellSummary` | ~45 | **delete** |
| C5 | `GET /api/admin/build-info`: `AdminBuildInfoController` | 52 | keep or delete; the version pill shows the version already. **Delete** unless you curl it, and update `integration-test-strategy.md:9,461`, which calls it the authenticated version source |
| C6 | `POST /api/admin/batches/force-submit` and `GET /force-result/{id}`: `ForceSubmitBatchService` lines 210-398, `ForceSubmitRequest`, `CustomIdFactory.forForceSubmit` (`submitJfdiBatch` stays) | ~250 | **delete** unless you still use it from curl; JFDI covers forcing a batch |
| C7 | `GET /api/admin/calibration`: `CalibrationController`, `ForecastCalibrationService`, `CalibrationReport`, `CalibrationBucket`, `CalibrationPair` and the repository query | ~705 | **keep**. The reference calls it "the only non-self-referential accuracy measure" (forecast versus readers' ratings); it becomes valuable once live users rate sunsets. A UI for it would be the better follow-up. |
| C8 | `GET /api/cloud-verification/report`: `CloudVerificationService.report()` and its helpers (lines 584-1136), `CloudVerificationReport`, `Bucket`, `Pair` and `findVerifiedPairs` (the backfill job and POST stay) | ~1,160 | **your call.** It's an analysis tool cited by the far-cloud and veto plans. If that research is finished, delete; otherwise keep. |
| C9 | `GET /api/user/settings/drive-times` and `settingsApi.getDriveTimes` | ~20 | **delete** (`DriveTimeResolver` stays live; `POST /drive-times/refresh` stays). Edit `HttpCachingConfig.java:59` Javadoc and `HttpCachingConfigTest.java:102`, which list the path |
| C10 | `GET /api/admin/sky-rating-eval/runs/{id}/results`, `SkyRatingEvalService.resultsForRun` and `getSkyRatingEvalResults` | ~20 | **delete**, unless Sky Eval should show per-fixture results (then wire it up instead) |
| C11 | `PUT /api/auth/marketing-emails` and `authApi.updateMarketingEmailOptIn` | ~30 | **your call, one of two.** The app sends no marketing email today (every template is transactional), but registration still collects consent (`RegisterPage.jsx:71-72,364`, read at `AuthController.java:303`). UK GDPR Art 7(3) says withdrawing consent must be as easy as giving it. Either (a) **keep the endpoint and add the opt-out toggle to user settings** (`AuthContext.marketingEmailOptIn` is already there, unread), or (b) **remove the checkbox, endpoint and column** until a marketing feature exists. (a) if you plan marketing email soon; otherwise (b). |
| C12 | break-glass tools: `/api/admin/batches/reset-guards`, `/batches/recent`, `/batches/{id}`, `DELETE /api/briefing/evaluate/cache`, `/api/aurora/admin/run` and `/reset`, `/api/admin/ask/dry-run`, `/ready/precompute` and `/metrics` | — | **keep.** These are documented operator tools for incidents; an incident at launch is the worst time to find them gone. |

Each deletion updates `reference/api-endpoints.md` and the README endpoint table. An unmapped path now answers 404 (the `NoResourceFoundException` handler from the sync-engine retirement).

## 4. Phase D: briefing fields dead end to end (one PR)

These fields are written by the backend, read by nothing (backend or frontend), and served on `/api/briefing`. All eleven came up clean for backend readers (re-checked in review), and none reaches a Claude prompt or a `regression/` test:
- `DailyBriefingResponse.partialFailure` and `failedLocationCount`;
- `BriefingRegion.lightlyEvaluated`;
- `BriefingSlot.standdownReason`;
- `BriefingWindow.topRarityRank`;
- `TideRunDay.highWaterAnomaly`, `highWaterRank`, `alignmentPhrase` and `bestAligned`;
- `AuroraStatusResponse.dataSource` and `UserSettingsResponse.postcodePromptDismissedAt` (no backend reader; re-check).

**⚠️ Deploy hazard: this must be solved in the same PR.** `BriefingService.loadPersistedBriefing` (`BriefingService.java:292`) reads `daily_briefing_cache.payload` with the Jackson 2 `AppConfig` mapper, whose `FAIL_ON_UNKNOWN_PROPERTIES` is on. On the first start after deploy, the payload written by the previous image still has the removed properties. The load then throws, the failure is swallowed, and the Plan tab is empty until the next pipeline cycle (`BriefingRegion.java:128-142` documents this exact trap).

**Fix:** add `@JsonIgnoreProperties(ignoreUnknown = true)` to each record whose own JSON object carries a removed key: `DailyBriefingResponse`, `BriefingRegion`, `BriefingSlot`, `BriefingWindow`, and `TideRunDay`. `TideRunDay` sits under `HotTopic.tideRun` and has an explicit `@JsonCreator` (`:185-198`); the annotation goes on the record. Jackson 3 already ignores unknown properties by default, and the annotation lives in the shared `com.fasterxml.jackson.annotation` package, so one annotation covers both graphs.
- Optionally add it to every record in the tree. That also closes the rollback trap that `BriefingRegion`'s Javadoc warns about.
- Pin it with a test that deserialises, through `AppConfig.objectMapper()`, a payload carrying the removed properties.

No other persisted copy of this tree is read back (checked in review): `cached_evaluation` holds `BriefingEvaluationResult`, which loses nothing; `AskAnswer` already ignores unknown properties; `briefing_region_snapshot` is columns; the epoch and ETag are in memory. Rollback is safe: an older image reading a newer payload sees the removed fields as null, false or 0.

**`standdownReason` takes more with it:** `BriefingVerdictEvaluator.StanddownReason` and `deriveStanddownReason` (`:77`, `:370`), `WoodlandVerdictEvaluator.deriveStanddownReason` (`:186`), and the Lite TIDE_MISMATCH strip at `BriefingSlot.java:319`. Update the Lite-shaping text in `reference/api-endpoints.md:285` and `reference/roles-and-gating.md`.

**Tests and contracts to edit deliberately:**
- `JsonDateFormatContractTest.java:103-117` pins `postcodePromptDismissedAt`; Instant coverage survives at `:91-101`.
- `ReviewedTypes.java:30,128` (the `LitePickSentinel` table) lists the fields.
- `DailyBriefingResponseJsonTest` and `LitePickEnforcementControllerTest` assert `standdownReason`.

**Docs:**
- `reference/plan-tab.md` and `v1-retirement-plan.md` §8;
- `reference/api-endpoints.md:358` (`alignmentPhrase`);
- `reference/hot-topics-tides-eclipse.md` (`highWaterRank`);
- `reference/architecture-decisions.md` (`highWaterAnomaly`, `highWaterRank`);
- the comment at `frontend/src/utils/windowFirstCards.js:999` (`topRarityRank`).

## 5. Phase E: write-only columns (two releases)

Rollback rule: remove the mapping in release N, and drop the column in release N+1 (the model-test retirement's Phase 1 and 2 shape).

| item | release N | release N+1 | recommend |
|---|---|---|---|
| `job_run.created_at` | — (no field maps it) | drop column | **do**, any release |
| `forecast_batch.last_polled_at` | — | — | **keep.** Not write-only after all: the kept break-glass `/api/admin/batches/recent` and `/{id}` return `ForecastBatchEntity` directly, so it's on the wire, and "when did the poller last touch this stuck batch" is what those tools are for. |
| `briefing_region_snapshot.voting_count` | remove the field and its write, **and in the same release** `ALTER COLUMN voting_count DROP NOT NULL` (it is `INTEGER NOT NULL` with no default, V144:67). Without that, every snapshot insert fails, `BriefingService:913` swallows it, and the Plan strip's movement chip goes dark silently. | drop column | your call (it may be analysis data) |
| `api_call_log.request_url` and `request_method` | — | — | **keep.** Cheap, and useful in psql when an API call goes wrong. |
| `evaluation_delta_log` | — | — | **keep, and add retention** (it grows with every re-evaluation and nothing prunes it). V141 cites it as evidence. |
| `cloud_verification` horizon, far and observer columns (~17) | — | — | **leave**; the report's native projection may read some (goes with C8) |
| `sky_rating_eval_run` git and trigger metadata | — | — | **keep** (audit metadata) |
| `topic_daily_log.band` | — | — | **keep** (reserved for the planned hysteresis rule, V151) |

## 6. Phase F: wire-format slimming (separate design, not a deletion)

The biggest wire cost is fields the backend needs but the client never shows:
- `BriefingSlot`'s unwrapped weather (8 fields) and tide (8 fields), on every location × event × day;
- `BriefingRegion`'s four `region*` weather fields and `tideHighlights`;
- `HotTopic.expandedDetail` and `surgeRun`;
- `auroraTonight` and `auroraTomorrow`;
- the best-bet fields;
- the Ask `spread` counts;
- several `LocationDto` fields.

None of these can just be deleted. The backend reads them, and the briefing is persisted as the same JSON it serves. Slimming the wire needs a separate served shape: a Jackson view, or a dedicated response type built by `ServedBriefingAssembler`. That touches the ETag-cached `/api/briefing` contract and the Backend-heavy rule, so it needs its own plan with a measurement of the payload size first. **Recommend:** measure the gzipped `/api/briefing` size in production. If it's under about 100 KB, leave this alone until after launch.

## 7. Owner decisions

**Decided 2026-10-30. The owner accepted the planner's recommendations; where none was given, the conservative option was taken.**
- **D1:**
  - Delete C1–C6, C9 and C10.
  - Keep C7 (calibration), C8 (the cloud-verification report; the conservative pick) and C12 (break-glass tools).
  - C11: option (a), keep the endpoint and add the marketing opt-out toggle to user settings.
- **D2:** go.
- **D3:** keep `voting_count`. Add `evaluation_delta_log` retention in Phase E.
- **D4:** skip Phase F until after launch.
- **D5:** `prop-types` goes in Phase A.
- **D6:** delete the `daily_briefing` target, in Phase B.

The questions as asked:

- **D1 (Phase C):** confirm the recommended column in §3, especially C6 force-submit, C7 calibration (keep), C8 the cloud-verification report, and C11 marketing consent (add an opt-out toggle, or remove the checkbox).
- **D2 (Phase D):** go ahead? It's low risk once the `ignoreUnknown` fix is in.
- **D3 (Phase E):** `voting_count`, and whether to add `evaluation_delta_log` retention.
- **D4 (Phase F):** measure first, or skip until after launch?
- **D5:** `prop-types` goes into Phase A unless you'd rather keep that PR deletion-only.
- **D6:** the `daily_briefing` job target (`ScheduledForecastService:88` and `refreshDailyBriefing`) was kept in V103 as a one-line revert path to the cron model. The pipeline's BRIEFING phase has run for months since, and the revert was never one line: it also needs the config row re-inserted. "Run now" cannot reach it (`triggerNow` needs the row). Delete it now (about 30 lines: the registration, `refreshDailyBriefing`, the Javadoc at `BriefingService.java:579`, and `ScheduledForecastServiceTest:264-274`)?

## 8. Order, PRs, gates

A and B have no decisions and can run in parallel as separate PRs (they touch disjoint files). C, D and E follow your decisions; E's column drops ship one release after their field removals, and merge only after you have released and deployed release N (as with the model-test retirement's Phase 2). Each phase follows CLAUDE.md's build-and-merge rule: Sonnet builds in its own worktree; local gates; a ready PR; CI green; Codex 👍 on the head commit; squash-merge. Each phase adds a `changelog.d/` entry under `### Removed — …`.
