# Changelog

All notable changes to PhotoCast are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Fixed — V84 migration tolerant of missing locations (v2.12.2)
- **`V84__add_bluebell_support.sql`** — replaced two `INSERT INTO location_location_type (location_id, location_type) VALUES (40/87, 'BLUEBELL')` statements with `INSERT…SELECT…WHERE EXISTS` against `locations.id`. The original hardcoded IDs 40 (Allen Banks) and 87 (Roseberry Topping) work in production where those rows exist from accumulated seed data, but fail the V8 foreign key against any fresh database — including the Postgres testcontainer introduced in v2.12.2's integration test pyramid. The bug was latent because existing `@SpringBootTest` tests use H2 with `ddl-auto=create-drop` and skip Flyway entirely. Production behaviour is unchanged: where IDs 40 and 87 exist, the new statement produces identical rows. The `NOT EXISTS` predicate also guards against accidental replay
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
