## Verifier's corrections to §8 (Staleness, ops UI, calibration instrument)

Verified against HEAD `d421ef5f` (the section claims `a484d1c4`; two commits behind, immaterial in content).

The section's central finding — **§8.4 Break 3** — is correct, important, and correctly evidenced. `POST /api/outcome` cannot write `actual_rating`, the column `findCalibrationPairs` joins on (`ForecastEvaluationRepository.java:163`), because `ActualOutcome` (`model/ActualOutcome.java:23-33`) has no such field and `OutcomeService.record` (`:100-111`) never sets it. That, plus §8.3's `int → Integer` fix, is the shippable core. Almost everything else needs correcting.

---

### A. Fix before anyone implements

**A1 — §8.0's third correction is itself wrong, and it invalidates §8.1.2's design.**
`getScoresForEnrichmentBulk` does **not** compute `CachedEntry.evaluatedAt` and discard it. Its body (`EvaluationViewService.java:327-406`) calls `briefingEvaluationService.getCachedScores(...)` **directly** at `:341`; `loadCachedEvaluations` (`:569`) is reached only from `forDateRange` (`:145`) and `cachedOnlyViewsForDateRange` (`:171`).

Consequences for `getScoresForEnrichmentBulkWithAge`:
- the cached branch must add a `getCachedEvaluatedAt(regionName, date, type)` call inside the loop at `:337-347` (in-memory only — `BriefingEvaluationService.java:153-158` is a bare `cache.get`, no DB fallback);
- the forecast-row fallback at `:386-402` must capture `forecastRunInstant(row)`, which it currently discards;
- the `getUpdatedAt()`-over-`getEvaluatedAt()` reasoning at `:613-620` does **not** apply to this path.

**A2 — the build path has no designed method.** `RegionScoreResolver` (`BriefingService.java:653-657`) widening breaks `evaluationViewService::getScoresForEnrichment` at `:665`. That is the singular method at `EvaluationViewService.java:273-308` and needs the same two acquisitions (`:277` cache half, `:289-304` fallback half). §8.1.2 must specify it.

**A3 — `STALE_AFTER = 24h` fires on healthy cells.** The intraday cycle covers only the decision window — T sunset, T+1 sunrise, T+1 sunset (`IntradayCandidateCollectionStrategy.java:10-13`, `CandidateCollectionStrategy.java:12-13`, `V105:22`). Every **T+2..T+6** cell is nightly-only, i.e. a 24h healthy cadence against a `> 24h` test. A one-minute-late run turns the far half of the Plan grid provisional. Either:
- run the constant off the nightly cadence with headroom (`36h`), and say so; or
- key staleness to the slot's own expected refresh source.
Before choosing, run `SELECT job_key, cron_expression, status FROM scheduler_job_config;` — no migration after `V73:20-24` (`'0 0 3,15 * * *'`, `PAUSED`) alters it, so the `~01:00` figure in `V105:3` is comment-only and unverified.

**A4 — `SKIPPED_NO_REFRESH_NEEDED` is live, not reserved.** `IntradayEligibilityPolicy.java:49-50` writes it on the ACTIVE intraday job; `ReclassSummary.java:20` counts it; `IntradayEligibilityPolicyTest:32`, `ForecastTaskCollectorTest:1145`, `OrchestratedDispositionWriteIntegrationTest:262` and `DispositionCategoryTest:60-62` exercise it. The "not used yet" quote is a stale Javadoc at `DispositionCategory.java:102-106`. It still dies with the intraday gate — but as a gate, and `DispositionCategoryTest:60-62` must be edited in the same commit.

**A5 — the two deletion tables are incomplete and will not compile.**

Optimisation cascade, absent from §8.2: `ForecastCommandExecutor.java` (`:9-10, :70-71, :107-108, :121-122, :163-168, :229, :260-265, :287, :317-318`), `ForecastService.java:274`, `ModelsController.java:5,7,32`; tests `ForecastCommandExecutorTest`, `ForecastServiceTest`, `ModelsControllerTest`, `AbstractControllerTest`, `HttpCachingIntegrationTest`. Add a `DROP TABLE optimisation_strategy` migration (created/seeded by V41, V42, V49, V52, V54).

Freshness cascade, absent from §8.2: `service/FreshnessResolver.java` (67 lines, the sole `FreshnessProperties` consumer) is a constructor dependency of `BriefingEvaluationService.java:114`, `ForecastTaskCollector.java:158`, `BriefingCandidateCollector.java:72`, and is mocked or constructed in `BriefingEvaluationServiceCacheFreshnessTest:30`, `EvaluationDeltaLogTest:41`, `BriefingEvaluationServiceTest:64`, `ForecastTaskCollectorForceEvalTest:92`, `CollectForecastTasksCachedGateTest:90-95`, `ForecastTaskCollectorTest:114`.

**A6 — the "stars never touched" test already exists and the proposed one is broken.**
`HeatmapGrid.test.jsx:1516-1524` (`'leaves the star/quality badge untouched across confidence tiers (separate channels)'`) already renders `high` and `low` and compares the badge style, using the correct accessor at `:1483-1485`: `getByTestId('mean-score-badge').querySelector('span')`. The design's version reads `getAttribute('style')` on the **div** at `HeatmapGrid.jsx:657`, which has no style attribute — it would pass vacuously. **Extend the existing test with a stale case; do not add a new one.**

---

### B. Citation corrections

| Design says | Truth |
|---|---|
| `FreshnessProperties` `:35`, `:41`, `:48`, `:44-46` | `:36`, `:43`, `:50`, `:47-49` (`:29` is right) |
| Cost Optimisation panel `:623-720` (~98 lines) | `:623-706` (84). `:708-718` is the **About** box, which survives — only its bullet `:713` goes. As written the instruction deletes a box the design elsewhere edits. |
| "all four withers … and the 15-arg back-compat constructor" | three withers (`BriefingRegion.java:75, :90, :107`) and **three** convenience constructors (`:120-132`, `:139-150`, `:158-167`). `BriefingService.java:737-744` calls the **13-arg** one. Six forwarding sites, not four. |
| `photocast.freshness` in `application.yml` / `-dev` / `-prod` / `-example` | only `application-example.yml:175`. `-dev`/`-prod`/`-local` have no such key. `application-example.yml:129` `freshness-hours` is **NLC** config — do not grep-delete on the word. `application.yml` is gitignored: mark UNVERIFIED. |
| "grep -rni stability frontend/src returns only [6 sites]" | also `JobRunsMetricsViewBatch.test.jsx:118` and `:359`, both asserting `/triage and stability gates/`. Deleting `JobRunsMetricsView.jsx:883` fails both; neither is in §8.5. |
| disposition call sites "untouched" | renaming to `DispositionLine.jsx` forces edits at `JobRunDetail.jsx:5,340`, `PipelineRunsView.jsx:5,588`, the `vi.mock` path at `PipelineRunsView.test.jsx:10`, and deletion of `DispositionBreakdown.test.jsx` (~275 lines). The **prop** is unchanged; the call sites are not. |
| reconciliation line `:99-109` renders "N candidates considered — 312 evaluated, 46 travel day" | `:99-109` renders only `"{N} candidate(s) considered"`. The tail comes from `:80-86`/`:111+`, which the design deletes. Write the sentence. |
| §8.3.3 "grepped … exhaustively" | omits `ForecastCalibrationServiceTest:135-148` (`report_noPairs_returnsEmptyReport`) — the only existing empty-path test. Safe (it never asserts the counters), but it is where the null assertion belongs, replacing the proposed `report_zeroPairs_reportsNullDecisionCountsNotZero`. |
| `CalibrationReport` "gains two components" | also needs `@param` added to the **compact constructor** Javadoc at `:36-46` as well as the header at `:19-26`, or Checkstyle fails. |
| `OutcomeService.record` "nine `.field(...)` calls" | ten (`:100-111`). The absence of `.actualRating` is correct. |

---

### C. Unchecked risks the section should have raised

**C1 — `Instant` on the wire.** `BriefingService` serializes the cache with the `AppConfig.java:56-57` bean: `new ObjectMapper().registerModule(new JavaTimeModule())`, with `WRITE_DATES_AS_TIMESTAMPS` left **enabled** (the only `disable(...)` in the tree is `RunProgressTracker.java:48`). `scoresProducedAt` persists as a numeric epoch. The HTTP layer is a different mapper (Boot 4 / Jackson 3 — see `EvaluationConfig.java:3`), so the wire is probably ISO, but `confidence` is an **enum**, not a temporal type, and is no precedent. Verify with a real `GET /api/briefing` before shipping, or type the component `String`.

**C2 — `MarkerPopupContent` needs more than the section admits.** No `outcomes` key on the location propTypes shape (`:1234-1242`), no `onOutcomeRecorded` prop, no MapView handler, and no invalidation of the client-cached `{forecasts, locationMeta, outcomes}` payload (`useForecasts.js:65, :96`) after a POST.

**C3 — the recording window is two days, not seven.** `ForecastController.PAST_WINDOW_DAYS = 2` (`:85`) bounds what the DateStrip can reach; the outcomes fetch spans seven. Five of seven days are unrecordable. State it, or route to `GET /api/forecast/history`.

**C4 — `ConfidenceDeriver.derive` has a fifth path.** The unconditional `return Confidence.LOW` at `:111-113` (degenerate voting roster) precedes the downgrade block. §8.1.4's four-row table claims exhaustiveness and omits it.

---

### D. Deletion bias under-applied

- No line-count total is reported for the section, which the brief requires.
- `BriefingEvaluationService.hasEvaluation(String)` (`:169-172`) — Javadoc: *"Available for future schedulers … to gate work"*. An unexercised gate helper in a file the section edits. Delete it.
- `ForecastDispositionService.java:38-39` — *"trivial for either H2 or Postgres"*. Stale under correction (1).
- No `DROP TABLE optimisation_strategy` migration.
- State explicitly that `StabilitySnapshotProvider` **survives** the `StabilityController` deletion — it is a constructor dependency of `BriefingEvaluationService` (`:109, :115`) and an implementer will otherwise delete it.

---

### E. Sequencing (§8.6 does not stay green)

1. `CalibrationBucket` `int → Integer` + `CalibrationDataState` + `CalibrationReport` fields + both Javadoc blocks + the log line. **Independently shippable** — §8.3.3 holds, with `ForecastCalibrationServiceTest:135-148` added to the audit.
2. `ActualOutcome.actualRating` + `OutcomeService` + `CalibrationJoinIntegrationTest`. (This test is the best thing in §8.5 — no test has ever crossed the write→read seam.)
3. `OutcomeStars` + `MarkerPopupContent` propTypes + `onOutcomeRecorded` + MapView handler + cache invalidation; delete `OutcomeModal.jsx` **and** `OutcomeModal.test.jsx` in the same commit.
4. Staleness (§8.1) must ship in **one** commit with the whole Freshness cascade (A5) — `FreshnessProperties` alone does not compile.
5. UI shrink (§8.2) must ship after the `DispositionCategory` constant deletions and must carry `DispositionCategoryTest:60-62`, `JobRunsMetricsViewBatch.test.jsx:118/:359`, `DispositionBreakdown.test.jsx`, `PipelineRunsView.test.jsx:10` and the full optimisation cascade.

---

### F. Sound as written — do not relitigate

The display/gate boundary is drawn correctly: this section touches `ConfidenceDeriver` and `DispositionBreakdown` (display) and leaves `BriefingVerdictEvaluator`, `DisplayVerdict` and the Plan tab's GO/MARGINAL/STANDDOWN path untouched. I hunted specifically for an accidental display deletion and found none. The 15,500-char `SYSTEM_PROMPT` floor (`PromptBuilder.java:65`) is untouched; no regression assertion under `src/test/java/**/regression/` is modified; no cloud value is transformed at record; aurora is not touched. The `NONE ≠ STALE` distinction (§8.1.4) is right and matches `confidenceUtils.js:50-58`. Keeping `sampleCount` as `int` while the derived findings go `Integer` is exactly the right line. The 200 OK-with-explicit-nulls decision over 204, and the refusal of `@JsonInclude(NON_NULL)`, are both correct. The triage-cost conflict is surfaced honestly and the re-measurement SQL is valid Postgres 17.
