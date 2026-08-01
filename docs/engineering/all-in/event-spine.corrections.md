## Verification result — §4.4 append-only evaluation spine

**Verdict: the central thesis survives; the implementation detail does not.** §4.4.0's sequencing
argument (evaluation_event must land *before* triage is deleted, or the ERA5 harness goes dark and
reports success while doing so) is CONFIRMED against the tree and is the most valuable finding in the
section. Three fatal defects and four material omissions must be fixed before an engineer builds
from this.

### Fatal — fix before implementation

1. **§4.4.1a contradicts §4.4.1's own CHECK.** `SKIPPED_ERROR → outcome='FAILED', skip_reason='ASSEMBLY_ERROR'`
   is unrepresentable: `ck_evaluation_event_skip_reason` requires
   `(outcome IN ('SKIPPED','TRIAGED')) = (skip_reason IS NOT NULL)`, so FAILED + a reason is
   `false = true` → violation. Because `recordCycleDecisions` is one REQUIRES_NEW transaction, one
   such event takes the cycle's ~1,000 others with it.
   *Fix:* either add `'FAILED'` to the constraint's left-hand set, or drop skip_reason from FAILED
   and carry the assembly error in `skip_detail` only. Then re-derive index (5), whose
   `WHERE outcome IN ('SKIPPED','TRIAGED','FAILED')` currently indexes NULLs for a third of its rows.

2. **§4.4.9a's "only the FROM clause changes" is wrong.** `CloudVerificationRepository.java:93-101`
   projects `e.location.name`, `e.directionalCloud.solarLow`, `e.cloudApproach.solarTrendBuilding`,
   `e.cloudApproach.upwindCurrentLowCloud`, `e.cloudApproach.upwindDistanceKm`, `e.windDirection`.
   V139 is flat — no embeddables — and has **no `location_name` column and no LocationEntity
   association**, yet `CloudVerificationPair`'s first component is `String locationName`.
   *Fix:* add `location_name VARCHAR(255) NOT NULL` to V139 (it is a denormalised copy for exactly
   the same reason `location_lat`/`location_lon` are), and rewrite every path expression in
   `findVerifiedPairs`.

3. **§4.4.9a's "CloudVerificationService needs no change" is wrong.** `CloudVerificationService.java:224`
   calls `.forecastEvaluationId(candidate.evaluationId())`. Renaming the field breaks it, plus
   `CloudVerificationEntity.java:50`, `CloudVerificationServiceTest.java:100`,
   `CloudVerificationRepositoryTest.java:200`.

### Major

4. **V141's in-place remap will fail on Postgres.** `cloud_verification.forecast_evaluation_id` is
   `NOT NULL UNIQUE` (V129:22) and Postgres checks non-deferrable unique constraints row-by-row inside
   an UPDATE. V140 inserts ~1 evaluation_event per forecast_evaluation row, so the id ranges overlap
   almost exactly and a transient duplicate is near-certain — the `UPDATE t SET id = id+1` failure.
   The duplicate-natural-key pre-flight does not catch it.
   *Fix:* drop the unique index before the UPDATE and recreate it after, or declare the constraint
   `DEFERRABLE INITIALLY DEFERRED`.

5. **No index supports the display projection.** `idx_evaluation_event_slot_latest` omits
   `prompt_kind`, so `evaluation_scored_current`'s `DISTINCT ON (location_id, target_date,
   target_type, prompt_kind)` cannot use it. Insert `prompt_kind` after `target_type`, or withdraw the
   "resolves as an index scan + Unique" claim for the Plan-tab path.

6. **The triage-population characterisation is overstated.** `WeatherTriageEvaluator` has three rules
   — solar low cloud > 80 (`:26,:42`), precipitation > 2 mm, visibility < 5000 m — plus the separate
   tide-alignment triage (`ForecastService.java:396-411`), and `TriageReason` carries five values
   (`:16-29`). The harness population is a mixture, not a clean `>80` cut. The selection-effect
   argument stands; "the means are a restatement of the threshold" does not.

### The display/gate boundary is drawn in the wrong place — user-visible

**Triage is also a display path.** `EvaluationViewService.java:458` turns `cachedResult.triageReason()`
into `Verdict.STANDDOWN` and feeds `DisplayVerdict.resolve` (`DisplayVerdict.java:40-58`) — the
fallback that colours unscored Plan cells. `ForecastDtoMapper.java:240` and `:612` render
`triageReason`/`triageMessage` into the API payload, and `BriefingEvaluationResult` has dedicated
components for them (`:27-28`). §4.4.9 deletes this channel without a word.

**And the one surviving gate has no display.** `evaluation_scored_current` filters
`outcome='EVALUATED'`, so a TRAVEL_DAY skip is invisible to every read path. The section needs a
third projection or an explicit statement that travel days render as absence.

### Missed deletions and blast radius

- `GET /api/metrics/job-runs/{id}/disposition-breakdown` (`JobMetricsController.java:165`, injecting
  `ForecastDispositionService` at `:49,:60`, returning `DispositionBreakdownResponse` at `:8`) and its
  React UI `frontend/src/components/DispositionBreakdown.jsx` + two frontend test files. The ledger
  deletes the backend and never mentions the endpoint, the response, or the screen. §4.4.2's index (4)
  shows the capability is meant to survive — design its replacement.
- **Two submit paths bypass the hook.** `doSubmitForecastBatchForRegions`
  (`ScheduledBatchEvaluationService.java:528-550`) and `ForceSubmitBatchService` (`:109`, `:179`)
  submit batches without `persistCycleDispositions` and outside `submitBuckets`. They yield EVALUATED
  events with no SUBMITTED denominator — the exact defect the spine exists to remove — and R5 can never
  balance. `ForecastTaskCollector` has a second `fetchWeatherAndTriage` at `:790` serving the first of
  these; §4.4.0 cites only `:374`.
- **24 test files reference the deleted types, not 4.** Critically `AbstractControllerTest` (`:14`,
  `:21`) declares `@MockitoBean`s for both services and is the base for every controller test, so
  step 7 breaks compilation of the whole package — §4.4.11's "green at each step" does not hold.
  Also `CachePayloadGoldenMasterTest` (392, a pinned payload contract), `CollectForecastTasksCachedGateTest`
  (350), `OrchestratedDispositionWriteIntegrationTest` (315), `DispositionWriteIntegrationTest` (266),
  `EvaluationDeltaLogTest` (229), `JobMetricsControllerDispositionTest` (119), `CacheKeyFactoryTest`
  (116) — +1,787 lines from seven files alone — and `BestBetAuroraPromptRegressionTest`, which sits
  under a protected name and must be inspected first.
- `CacheKeyFactory` is live at `ForecastResultHandler.java:174,:242` and
  `BriefingCandidateCollector.java:210`; `BriefingCandidateCollector.java:217` calls
  `hasFreshEvaluation`. All four are outside the `:289-336` block the ledger scopes.

### Persistence mechanics the design did not address

- `ddl-auto: validate` in all three runtime profiles (`application.yml:12`, `-dev:12`, `-prod:13`).
  Mapping `@Immutable` entities onto SQL **views** must be proven against Hibernate 6 + Postgres 17
  before shipping — a validation miss refuses to boot.
- `produced_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()` mapped as an ordinary field means
  Hibernate lists it in the INSERT as NULL. It needs `@Column(insertable = false, updatable = false)`
  + `@Generated(event = INSERT)`. A `@PrePersist` stamp instead would forfeit the whole
  `clock_timestamp()` argument.
- The CHECK constraints are gates at the point of record. `ck_evaluation_event_cloud_pct` discards a
  raw out-of-range reading (and, inside the single-transaction cycle write, ~1,000 innocent events with
  it), and `ck_evaluation_event_scores_only_when_evaluated` will reject any historical row with
  `basic_fiery_sky_potential` set but rating/fiery/golden all null — which V140 classifies FAILED.
  Run counting queries for all three against `forecast_evaluation` before V139 lands, and prefer a
  logged warning to a rejected observation for the cloud range.
- Use `GENERATED BY DEFAULT AS IDENTITY` for consistency with V1:3, V5:2, V91:5, V101:20, V129:22 —
  ALWAYS blocks the seeded ids the §4.4.7 ordering tests will want.

### Corrections to specific statements

- **H2 2.x does parse `DISTINCT ON`** (since 1.4.198; local repo holds 2.1.214–2.4.240). Keep the
  conclusion — projection tests must extend `IntegrationTestBase` — but state the real reason:
  `src/test/resources/application.yml:8-13` sets `ddl-auto: create-drop` and `flyway.enabled: false`,
  so Hibernate creates the view-mapped entities as empty tables and the view never exists.
- **`lombok.config` exists** at `backend/lombok.config` with `lombok.addLombokGeneratedAnnotation = true`.
  Delete the UNVERIFIED JaCoCo note; the accessors are already excluded.
- `SurvivorAtmosphereEntity.java` is **117** lines, not 118.
- Measurement query (3) should count all five `TriageReason` values (`TriageReason.java:16-29`), not
  just `HIGH_CLOUD` and `TIDE_MISALIGNED`.

### Kept that the deletion bias says should go

`prompt_inputs JSONB` is added with the design's own admission that nothing queries it. Under the
"delete it and say what would bring it back" rule, drop the column and note that reinstating it is
one `ALTER TABLE ... ADD COLUMN`.

### What held up under attack

The `forecast_run_at` timezone finding is correct in every particular: sole writer at
`ForecastService.java:644` (`LocalDateTime.now(ZoneOffset.UTC)`), no DB default (`V1:9`), false Javadoc
at `EvaluationViewService.java:527-535`, wrong conversion at `:543`, and the bias toward the cache
through `cachedIsAtLeastAsFresh` (`:518-525`) is real. The "no other forecast_evaluation writer" grep
holds. `ForecastCommandExecutor` is reached only from three ADMIN endpoints, so the residual-population
argument stands. The `clock_timestamp()` reasoning, the `id DESC` scoping, and every line count except
one are accurate. The 15,500-char prompt floor, the regression assertions, raw cloud recording and
aurora scope are all respected.