## Verification verdict on §8 — Stage contracts and phased delivery

**Bottom line.** The empirical core is sound. Every one of the six checkable risky claims holds, including the two load-bearing ones (the sync engine's gates are unreachable in production; the sentinel early stop is live and unguarded), and all 26 line counts in §8.10 are exact. But the *sequencing* — the part an engineer implements from — contains four defects that would break production or the build, and the section commits the one error it was written to prevent: **it deletes a display path.**

---

### Fatal — must be fixed before this is buildable

**F1. Phase 2a deletes `StabilitySnapshotProvider`, which is a display path, not a gate.**
The section's own rule is "the classifier survives for display; its gates and persistence go". `StabilitySnapshotProvider` *is* the display read surface:

- `BriefingRollupBuilder.java:403` — `stabilitySnapshotProvider.getLatestStabilitySummary()` inside the region rollup that builds `BriefingRegion`. **This is the Plan tab.**
- `BriefingBestBetAdvisor.java:150` — publish path.
- `StabilityController.java:22,29,45` — `GET /api/admin/stability/summary`.
- `BriefingEvaluationService.java` — drill-down.

Plus ~10 test classes. Phase 3 then drops `stability_snapshot` (V98), the table it reads, while Phase 5 removes the UI. Three phases, three inconsistent orders.

*Correction:* `StabilitySnapshotProvider`, `stability_snapshot` and `GridCellStabilityService`'s publish call **all survive**. What dies is `NightlyEligibilityPolicy` / `IntradayEligibilityPolicy` / `EligibilityPolicy` / `EligibilityDecision` — the consumers that *branch* on the classification. Delete the reader only if the stability view goes with it, in Phase 5, in one commit.

**F2. The SLATE failure mode is backwards.**
Claim: "Below 50 % success the run does not publish (`BriefingService.java:509`)."
Reality: `BriefingService.java:550-572` — below threshold the run **does** publish, serving either a stale-flagged last-known-good (`cache.set(staleResponse)`, `:558`, stale flag set `true` at `:555`) or the partial (`:568`). Only `persistBriefing()` is skipped. (`:509` is `long totalMs`; the flag is `:508`.)

Making SLATE "fail the run and write nothing" **deletes the LKG stale-serve path** and blanks the Plan tab on a bad weather-fetch day — contradicting the section's own PUBLISH clause, "a failed publish must never blank the served surface".

*Correction:* SLATE's below-threshold behaviour is: do not persist, do not proceed to EVALUATE, **do** publish the LKG with `stale=true`. Pin it: `BriefingServiceTest.belowThresholdSlateStillServesLastKnownGood`.

**F3. The `PipelinePhase` rename has no data migration and will throw on every historical row.**
Both columns are `@Enumerated(EnumType.STRING)` — `PipelineRunEntity.java:32,40-42` and `PipelineRunPhaseEntity.java:32-34`. Renaming `FORECAST_BATCH_SUBMIT → EVALUATE_SUBMIT` and `BRIEFING → PUBLISH`, and deleting `STABILITY_RECLASSIFY`, makes every pre-change `pipeline_run_phase` row unreadable: `IllegalArgumentException: Unknown name value [...]`. That kills the admin pipeline-run history and `PipelineRunComparisonService` — which §8.9 R5 then depends on for the best-bet before/after diff. `PipelineOrchestrator.java:333` also names the deleted constant and will not compile.

*Correction:* V140 must carry the rewrite, in both tables:

```sql
UPDATE pipeline_run_phase SET phase = CASE phase
    WHEN 'FORECAST_BATCH_SUBMIT' THEN 'EVALUATE_SUBMIT'
    WHEN 'FORECAST_BATCH_WAIT'   THEN 'EVALUATE_WAIT'
    WHEN 'RETRY_FAILED'          THEN 'EVALUATE_RETRY'
    WHEN 'BRIEFING'              THEN 'PUBLISH'
    WHEN 'STABILITY_RECLASSIFY'  THEN 'EVALUATE_SUBMIT'   -- historical rows keep a home
    ELSE phase END;
UPDATE pipeline_run SET current_phase = ( ... same mapping ... );
```
Cheaper alternative worth taking: **keep the existing constant names.** The rename buys vocabulary; vocabulary is not worth a migration over live history.

**F4. Phase 4 will cascade-delete the 25,730 ERA5 verification rows.**
`V129__add_cloud_verification.sql:43` — `FOREIGN KEY (forecast_evaluation_id) REFERENCES forecast_evaluation (id) ON DELETE CASCADE`. "Dropping `forecast_evaluation`" therefore destroys the entire verification evidence base, silently. R2 covers input starvation; nothing covers the cascade.

*Correction, three parts:*
1. Phase 0's backfill must preserve the id relationship, or add `evaluation_event_id` to `cloud_verification` populated from the backfill mapping. Otherwise the anti-join re-queues all 25,730 rows against ERA5.
2. `countUnverified` (`CloudVerificationRepository.java:71-80`) retargets **with** `findUnverified` — javadoc `:66`: "the two must agree, or reported progress will not converge on zero."
3. Before any `DROP TABLE forecast_evaluation`: `ALTER TABLE cloud_verification DROP CONSTRAINT <fk>;` as its own migration, one release earlier.

---

### Major — the deletion order does not keep the build green

**M1. Phase 2a breaks `src/main` compile in two places.**

| Deleted in 2a | Live consumer | Removed in |
|---|---|---|
| `OptimisationStrategyService` | `ModelsController.java:32, :52, :106, :110` — `GET /api/models` (`optimisationStrategies` key) + `PUT /api/models/optimisation` | Phase 5 |
| `FreshnessResolver` | `BriefingEvaluationService.java:71, :114, :419` — the `evaluation_delta_log` writer | Phase 3 |

§8.6 claims "the deletion order actually keeps the build green at each step". It does not.

*Correction:* move `OptimisationStrategyService` + `ModelsController` surgery + the optimisation UI into **one commit in Phase 5**; move `FreshnessResolver` into **Phase 3** with the `evaluation_delta_log` drop. Phase 2a keeps only what has no consumer outside the collectors: `NightlyEligibilityPolicy`, `IntradayEligibilityPolicy`, `EligibilityPolicy`, `EligibilityDecision`, `ForceEvalHeadlineSelector`, `ReclassSummary`, the `STABILITY_RECLASSIFY` phase.

**M2. Phase 1 silently ages the published weather by the whole batch latency.**
Today `refreshBriefing()` — *including its Open-Meteo fetch at `BriefingService.java:433-437`* — runs at the **tail** of the cycle: `PipelineOrchestrator.java:427`, after `waitForBatchSetComplete` at `:412`. Under the split, PUBLISH reuses a slate built before submit. At observed afternoon latency (98–173 min, `PipelineOrchestrator.java:98-101`) the 14:00 intraday cycle would publish weather up to ~3 hours older than it does today — undercutting §8.4's own argument that intraday exists to be the day's second fresh slate.

*Correction:* state the trade explicitly and add a Phase 1 proof obligation:
```
[PUBLISH] slate age at publish = Xm (pipeline_run=N)
```
alarming above 45 min. If unacceptable, the fix is a cheap **slate weather re-fetch inside PUBLISH** (weather only, no re-derivation of the day hierarchy) — SELECT still reads the head-of-cycle slate, so the circularity fix is intact.

**M3. The `ephemeral` flag rationale is factually wrong.**
Claim: "the flag only exists to suppress a write-through of a classification whose only consumer was the gate."
`ForecastTaskCollector.java:339-341` passes it to `classifyGridCellsAndPublishSnapshot`, suppressing a write to `stability_snapshot` — read by `StabilitySnapshotProvider` → `BriefingRollupBuilder:403` (display), `BriefingBestBetAdvisor:150` (publish), `StabilityController:45` (admin). Deleting the flag makes the 14:00 cycle overwrite the 01:00 snapshot the Plan tab reads all day. That is a display change presented as a no-op. If the flag goes, say which way and why.

---

### Kept that the deletion bias says should go

- **The gloss gate.** `BriefingGlossService.java:203` (`hasAnyEligibleSlot`) decides whether a region gets a Claude gloss call; `:268` shapes the prompt. A policy gate on Claude spend, inside PUBLISH, neither named nor deleted — so the "only branch on a classification is the travel-day test" invariant is false as written, and `publicationIsTerminal` as scoped will not catch it.
- **Gloss + best-bet paid for and binned.** `BriefingService.java:490` and `:499` run before the threshold branch at `:537`; on the below-threshold path the results are discarded for the LKG. ~56 Claude calls per occurrence. Fix while splitting: move both **after** the threshold test.
- **The orphaned SSE pair.** `ForecastController.java:471` and `:483` (+ `frontend/src/api/runProgressApi.js:17, :53`) are cited as evidence the sync engine is real UI, then never dispositioned by Phase 3.

---

### Must not be deleted with the gate

`BriefingGatingPolicy` (130 lines) **survives** — after Phase 2a its only consumer is `BriefingGlossService`. `BriefingGatingPolicyTest` survives with it. Write this down: "the verdict/hard-constraint block is deleted" plus a maximum-deletion bias reads as licence to remove the class, which breaks `BriefingGlossService.java:23, :203, :268`.

`shouldSkipEvent` moves from `ForecastCommandExecutor.java:751-760` into SELECT — but the sync engine computes `today`/`now` in **UTC** (`:236-237`) while `BriefingCandidateCollector` computes `today` in **Europe/London** (`:146`, with a comment saying why). A naive move is a one-hour BST skew against `solarService.sunsetUtc` and a one-day skew in `targetDate.equals(today)` around midnight. Move it as `shouldSkipEvent(LocalDate targetDate, TargetType, LocationEntity, LocalDate londonToday, LocalDateTime utcNow)` and pin both zones in the test.

---

### Smaller corrections

| Claim | Correct |
|---|---|
| "four-overload `submitForecastBatchForPipelineRun` ladder" | three overloads (`:193, :210, :244`); `:165` is `submitForecastBatch()`, a different method |
| "13 call sites across 8 files" | 9 call sites across 7 files (the grep returns 20 lines incl. javadoc) |
| "The gate is a single `continue`" | `ForecastTaskCollector.java:433` is `if (preEval.triaged() && !bluebellWoodInSeason && !woodlandTask)`; the two carve-outs and the rationale at `:425-432` must survive as a comment |
| "`src/test/java/**/regression/`" | no such package — the file is `service/evaluation/PromptRegressionTest.java`; the test is `coptHill_5Mar2026_sunset_blockedSolarHorizon` (67 % pinned at `:79, :111`) |
| "`api_call_log` cache-token columns: UNVERIFIED" | they exist — `V38:6-7`, plus `is_batch` `:8`, `cost_micro_dollars` `:9`; `ApiCallLogEntity.java:115,119`. Drop the `BatchResultProcessor:532` fallback |
| `BriefingService.java:509` / `:421-424` / `:452-456`; `V105:24`; `WeatherTriageEvaluator:40-61` | `:508` / `:417-419` / `:451-455`; `V105:23`; `WeatherTriageEvaluator:36-64`, thresholds `:26-28` |
| §8.7 normaliser `generate_series(0, 3)` | hard-codes the *post*-Phase-1 horizon; pre-Phase-1 windows cover 5 dates (`BriefingService.java:118`), understating the denominator and flattering every post-change comparison. Parameterise it |

---

### One measurement gap the section creates for itself

§8.0(b) newly identifies the sentinel early stop as a **live** cost gate — and it is the one gate that cannot be measured with the instruments §8.1 supplies, because the synchronous engine writes no `forecast_run_disposition` rows at all. Its volume must come from `job_run` / `api_call_log` filtered to `triggered_manually = true` on the three colour run types, or from the `"Region {} sentinel early-stop — {} tasks skipped"` line at `ForecastCommandExecutor.java:565-566`. Both are weaker evidence than a disposition query, and the section should say so rather than implying every removed gate is quantifiable the same way.