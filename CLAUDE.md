# CLAUDE.md — The Photographer's Golden Hour

## Project Overview

A full-stack app that evaluates sunrise/sunset colour potential at configured locations.

- **Backend**: Spring Boot 4 REST API — Open-Meteo weather + air quality, WorldTides tide data, Claude (Anthropic SDK) evaluation, H2/PostgreSQL persistence, scheduled runs, notifications (email/Pushover/macOS toast).
- **Frontend**: React 19 + Vite + Tailwind — map view (Leaflet), plan heatmap, forecast timeline, outcome recording, JWT-authenticated.

---

## What's Built

**Core pipeline**: Open-Meteo → Claude → H2 | Scheduled evaluations (06:00 + 18:00 UTC, T through T+7) | Notifications (email, Pushover, macOS toast)

**Scoring**: Two scores (Fiery Sky 0–100, Golden Hour 0–100) + 1–5 star rating | Dual-tier: enhanced (directional cloud) for PRO/ADMIN, basic (observer-point) for LITE | 3-point cone cloud sampling at 113 km offset (azimuth ±15°) via `GeoUtils` + `DirectionalCloudData` | Far-field sample at 226 km for horizon strip vs blanket detection | Cloud approach risk detection (`CloudApproachData`, `SolarCloudTrend`, `UpwindCloudSample`) | Cloud inversion scoring (`InversionScoreCalculator`, 0–10 from temp-dew gap/wind/humidity/low cloud) | Solar-aware slot selection (`findBestIndex()`) | Sahara Dust badge (AOD > 0.3 or dust > 50 µg/m³ with PM2.5 < 35) | Rising tide warning badge (high tide within ±90 min of solar event)

**Evaluation**: Single `ClaudeEvaluationStrategy` parameterised by `EvaluationModel` | `PromptBuilder` + `MetricsLoggingDecorator` (GoF Decorator) | `NoOpEvaluationStrategy` for wildlife | `AnthropicApiClient` with `@Retryable` | Composable `AtmosphericData` (5 sub-records + `DirectionalCloudData` + `CloudApproachData`)

**Aurora photography**: NOAA SWPC polling (`AuroraPollingJob`, 5 min, night-only) via `NoaaSwpcClient` (Kp, forecast, OVATION, solar wind Bz, G-scale alerts; per-endpoint caching) | `AuroraStateCache` FSM (IDLE → MONITORING → MODERATE/STRONG) | `WeatherTriageService` (3-point northward transect triage, any-clear-hour pass at < 75% overcast) | `ClaudeAuroraInterpreter` (single `claude-haiku-4-5` call for all viable locations; returns 1–5★ + summary + detail) | `AuroraOrchestrator` (orchestrates full pipeline; dual-signal `deriveAlertLevel()` using Kp + OVATION) | `MetOfficeSpaceWeatherScraper` (Jsoup scraper, 60 min refresh) | Bortle enrichment via lightpollutionmap.info (`LightPollutionClient`, `BortleEnrichmentService`, sb_2025 dataset with SQM conversion) | Map filter + popup aurora section | `AuroraBanner` React component (shows Kp reading) | Aurora viewline endpoint (OVATION nowcast southernmost visibility boundary, `AuroraViewlineOverlay` with colour-coded zones) | Alert levels: QUIET/MINOR/MODERATE/STRONG

**Daily Briefing / PhotoCast Planner**: "Go or movie night?" pre-flight check | `BriefingService` scheduled at 04:00/14:00/22:00 via `@Scheduled` | Open-Meteo weather + DB tide lookup for all enabled colour locations | Per-slot verdict (GO/MARGINAL/STANDDOWN) based on low/mid cloud, precipitation, visibility, humidity thresholds + building trend checks | Region rollup via majority vote | Lunar-driven tide classification (spring/king via `TideClassificationService`) | `AtomicReference` in-memory cache + `daily_briefing_cache` DB persistence | Split into Plan and Map tabs: Plan tab shows heatmap grid with event-based columns (next 6 solar events), quality slider, sunrise/sunset sub-columns, aurora grid integration | Claude best-bet recommendation with drive times, day-of-week, weather codes | Briefing evaluation via SSE (`BriefingEvaluationService` + `BriefingEvaluationController`) with drill-down scoring per region | Briefing model comparison test: `BriefingModelTestService` calls Haiku/Sonnet/Opus with same rollup, persists to `briefing_model_test_run/result` tables, `BriefingModelTestView` with agreement highlighting

**Plan-screen confidence**: One uniform, quiet confidence channel across the Plan screen so a far-horizon "Worth it" reads more provisional than a same-day one — layered *alongside* the star (quality) signal, never replacing it | Backend `ConfidenceDeriver` derives a per-region `Confidence` (HIGH/MEDIUM/LOW) from forecast horizon (dominant term) + rating spread (`BriefingRatingStats` min/max/`ratingRange`) + coverage; **null** when unknown (zero coverage → reads provisional, not falsely confident) | Computed at serve time in `BriefingService.enrichWithCachedScores` (both build + serve paths, request-time `today`), rides the `daily_briefing_cache` JSON on `BriefingRegion.confidence` (no migration; legacy payloads fail-soft to null) | Frontend `confidenceUtils` (`resolveConfidence` fail-soft, `CONFIDENCE_TREATMENT` fill-scale + provisional flag, `scaleRgbaAlpha`) + `ProvisionalMark` — grid cells dim the verdict fill by tier and mark low; Best Bet, summary-strip pills, and the shared `VerdictPill` (drill-down + mobile region rows) show the marker-on-low; **stars are never touched** | Durable per-evaluation `forecast_evaluation.confidence` (V127, horizon-only `ConfidenceDeriver.fromHorizon`) for analytics | Hot-topic certainty **vocabulary** — an orthogonal axis (`topicCertainty` + `CertaintyChip`) labelling each topic **almanac** (tides/astronomy — fixed) / **forecast** (weather-driven) / **chance** (NLC — unforecastable), derived client-side from topic type

**Command pattern**: `ForecastCommand` → `ForecastCommandFactory` → `ForecastCommandExecutor` | `RunType` enum (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM, WEATHER, TIDE, LIGHT_POLLUTION, BRIEFING) | Per-run-type model config (Haiku/Sonnet/Opus via Admin UI)

**Optimisation strategies**: 7 toggleable strategies (SKIP_LOW_RATED, SKIP_EXISTING, FORCE_IMMINENT, FORCE_STALE, EVALUATE_ALL, NEXT_EVENT_ONLY, TIDE_ALIGNMENT) per run type | `OptimisationSkipEvaluator` | Mutual exclusion validation | Active strategies snapshot on each `job_run`

**Cost tracking**: Token-based micro-dollar pricing from Anthropic SDK | `ExchangeRateService` (Frankfurter API, ECB data) | `CostCalculator` with cache/batch discount | Frontend shows GBP + USD costs

**Job metrics**: `job_run` + `api_call_log` tables | Admin dashboard (7-day stats, per-service breakdown) | Per-location failure tracking, auto-disable after 3 failures

**Resilience**: Resilience4j (`@Retryable` on `AnthropicApiClient` (529/content filter) and `OpenMeteoClient` (5xx/429) | `@ConcurrencyLimit(8)`) | Dead-letter mechanism | `RequestLoggingInterceptor` | SSE auto-reconnect after backend restart

**Locations**: Multi-location with map view (Leaflet/OSM) | Metadata: `SolarEventType`, `TideType` (H/M/L multi-select), `LocationType` (LANDSCAPE/WILDLIFE/SEASCAPE/WATERFALL) | Regions (geographic grouping) | Sunrise/sunset azimuth lines | Marker clustering (`react-leaflet-cluster`) | Star rating + location type filters | Emoji chip UI for metadata | Editable lat/lon

**Tide data**: WorldTides API, weekly refresh, `tide_extreme` table | `TideService` derives state/next tides from DB at evaluation time | Tide history preservation (windowed merge, not delete-all) | 12-month backfill capability | Tide stats endpoint (avg/max high, avg/min low) | SEASCAPE-filtered refresh | Lunar-driven spring/king tide classification (`TideClassificationService`) integrated into PromptBuilder and BriefingBestBetAdvisor

**Wildlife UI**: Hourly comfort rows (temp/wind/rain) between sunrise–sunset | Green 🐾 marker | No Claude call

**Waterfall UI**: Colour forecast AND hourly comfort rows | 💦 marker | Scores excluded from cluster marker averages (waterfall photography ≠ sky colour)

**Auth**: JWT (HMAC-SHA256, 24h access, 30-day refresh) | ADMIN / PRO_USER / LITE_USER roles | Self-registration with email verification + Turnstile CAPTCHA | First-login password change gate | Session expiry warnings | Marketing email opt-in

**Storm surge**: Weather-driven storm surge calculation for coastal tidal locations | `StormSurgeService` (inverse barometer effect + wind setup) | Coastal parameters on locations (V60) | Surge forecast columns on forecast_evaluation (V61) | Integrated into forecast pipeline and prompt

**Cloud inversion**: `InversionScoreCalculator` — likelihood scoring (0–10) from three independent surface terms (temp-dew gap 0–5, wind 0–3, low cloud deck 0–2) behind three gates: low cloud outside 15–80%, no measured temperature reversal aloft, or a stable layer deeper than the viewpoint each cap the score below the band it would otherwise reach | Vertical profile via `temperature_925hPa`/`temperature_850hPa` — **STRONG requires a measured reversal**, and no profile caps at MODERATE (never zero: `ForecastDataAugmentor` logs a WARN so the feature can't die silently) | Location elevation + overlooks_water metadata (V65) | `inversion_score` + `inversion_potential` columns on forecast_evaluation (V66) | Prompt states the measured reversal alongside the score, and tells Claude to weigh it over the score when they disagree

**Astro conditions**: `AstroConditionsService` — nightly observing quality scores for dark-sky locations | Template-scored (cloud cover, visibility, moonlight modifiers) | `AstroConditionsController` with `/api/astro/conditions` and `/api/astro/conditions/available-dates` endpoints | `astro_conditions` table (V64)

**User settings**: `UserSettingsService` + `UserSettingsController` — home location (postcode via `PostcodesIoClient` geocoding, lat/lon) and per-user drive times | `DriveTimeResolver` abstraction (replaces per-location `drive_duration_minutes`) | `user_drive_time` table (V67)

**Dynamic scheduler**: DB-backed scheduler management (`scheduler_job_config` table, V68) | `DynamicSchedulerService` — registers job targets via `@PostConstruct`, schedules on `ApplicationReadyEvent`, pause/resume/trigger/reschedule | `SchedulerConfig` with dedicated `ThreadPoolTaskScheduler` (pool=5) | `SchedulerController` (ADMIN-only, `/api/admin/scheduler`) | `SchedulerView.jsx` in Manage → Operations → Scheduler tab | Aurora jobs auto-disabled when `aurora.enabled=false` (`DISABLED_BY_CONFIG` status) | Replaces all `@Scheduled` annotations (tide refresh, daily briefing, aurora polling, Met Office scrape, run progress cleanup)

**Admin features**: User management | Expandable health status widget with live SSE service probes (mail, Claude API, Open-Meteo, tides) | Model comparison test harness (A/B/C across regions) | Prompt test harness (async, replay, comparison) | URL hash navigation | Client-side pagination | Confirmation dialog before Claude evaluation with cost estimate

**Deployment**: Docker (alpine, health checks, non-root) on a **Linux host** (not macOS — production moved off the Mac ~2026-03-16) | Cloudflare Tunnel (`photocast.online`) | Postgres 17 bind-mounted at `/home/gregochr/goldenhour-data/postgres` | Daily backups (keep last 7, systemd timer) | Persistent logs at `/home/gregochr/goldenhour-data/logs/` (volume-mounted to `/app/logs` in container; rolling `goldenhour.log` 50MB×30 days + `surge-calibration.log` 90 days)

---

## Monorepo Structure

```
goldenhour/
├── backend/               Spring Boot 4 (port 8082 local)
│   ├── src/main/java/com/gregochr/goldenhour/
│   │   ├── client/        @HttpExchange interfaces (Open-Meteo)
│   │   ├── config/        Security, JWT, retry predicates, cost config
│   │   ├── controller/    REST controllers
│   │   ├── entity/        JPA entities + enums
│   │   ├── repository/    Spring Data repos
│   │   ├── service/       Business logic, command/strategy patterns, evaluation/
│   │   ├── model/         AtmosphericData, DTOs, records
│   │   └── util/          GeoUtils
│   └── src/main/resources/
│       ├── application.yml          (gitignored)
│       ├── application-example.yml  (committed)
│       ├── application-local.yml    (H2 local dev)
│       ├── application-prod.yml     (production)
│       └── db/migration/            V1–V99 Flyway migrations
├── frontend/              React 19 + Vite (port 5173)
│   └── src/
│       ├── api/           Axios API modules
│       ├── components/    UI components
│       ├── hooks/         Custom hooks
│       └── context/       AuthContext
└── CLAUDE.md
```

---

## Dev Setup

```bash
# Backend (H2, no Docker)
export ANTHROPIC_API_KEY=your-key
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Frontend
cd frontend && npm run dev

# Trigger forecast run
curl -X POST http://localhost:8082/api/forecast/run

# Run all tests (requires Docker running — see note below)
cd backend && ./mvnw clean verify

# Prompt regression tests (requires ANTHROPIC_API_KEY)
cd backend && ANTHROPIC_API_KEY=... ./mvnw test -Pprompt-regression

# Frontend tests
cd frontend && npm run test
```

**Docker must be running to run the backend test suite.** Five classes extend `IntegrationTestBase`
(`backend/src/test/java/com/gregochr/goldenhour/integration/`) and start a `postgres:17-alpine`
Testcontainer so the Flyway migrations run against the real production engine. There is no failsafe
plugin and no surefire exclusion for them, so they execute in the ordinary `test` phase — `./mvnw test`
and `./mvnw clean verify` both need Docker. With Docker stopped you get an opaque Testcontainers
stack trace (`Could not find a valid Docker environment`), not a skip. Running the app locally does
not need Docker (H2 file DB).

Default local credentials: `admin` / `golden2026`

H2 console: `http://localhost:8082/h2-console` (JDBC: `jdbc:h2:file:./data/goldenhour`, user: `sa`, pass: empty). Reset: delete `backend/data/goldenhour.mv.db` and `.lock.db`.

---

## Key Architecture Decisions

- **Forecast DTO** — `ForecastController` returns `ForecastEvaluationDto` via `ForecastDtoMapper`. Role-based score selection: LITE gets basic scores, PRO/ADMIN get enhanced. `basic_*` entity columns never appear in API response.
- **Backend-heavy** — all calculations on backend. Frontend is a pure render layer.
- **Command pattern** — `ForecastCommand` → `ForecastCommandFactory` → `ForecastCommandExecutor`. Controllers/schedulers are thin wrappers.
- **Evaluation strategy** — single `ClaudeEvaluationStrategy` parameterised by `EvaluationModel`. `EvaluationConfig` produces `Map<EvaluationModel, EvaluationStrategy>` bean. `NoOpEvaluationStrategy` for wildlife.
- **Directional cloud sampling** — 3-point cone sampling (azimuth ±15° at 113 km offset, the geometric horizon distance for low cloud) to smooth Open-Meteo grid-cell boundary effects. Falls back to single-point. Prompt rules: solar low cloud >60% = blocked, 40-60% = penalise, <20% = clear. `findBestIndex()` avoids post-sunset/pre-sunrise slots. Far-field sample at 226 km (2× horizon) along the solar azimuth detects strip vs blanket: if solar horizon low cloud ≥50% but drops ≥30pp at 226 km → thin strip, soften penalty; if both ≥50% → extensive blanket, full penalty.
- **Cloud approach risk** — detects cloud approaching the solar horizon that a single event-time snapshot would miss. Two signals: (1) `SolarCloudTrend` — hourly low cloud at the solar horizon from T-3h to event time; a peak-vs-earliest increase of 20+ pp triggers a `[BUILDING]` label that tells Claude to penalise fiery_sky by 10–25 points. (2) `UpwindCloudSample` — current low cloud at an upwind point along the wind vector vs the model's event-time prediction; if current is much higher, the model may be too optimistic about clearing. `CloudApproachData` record composes both signals into `AtmosphericData`. `ForecastDataAugmentor` assembles the data from Open-Meteo; `PromptBuilder` formats it as a `CLOUD APPROACH RISK:` block in the user message. V51 migration adds persistence columns.
- **Cloud inversion scoring** — two rules worth not re-deriving. **Moisture is counted once**: the temp-dew gap and relative humidity are the same measurement (a gap ≤ 1 °C implies RH 92.7–94.2 % across −5 to 25 °C), so scoring both put 6 of 10 points on one reading and made every calm, saturated dawn total 9 before cloud was considered. **STRONG requires evidence, not inference**: an inversion is temperature *rising* with height, so no combination of surface readings alone may reach the band — `InversionScoreCalculator` demands a measured reversal from `temperature_925hPa` (fallback `850hPa`) against `temperature_2m`, and with no profile at all caps at MODERATE rather than claiming or abandoning the call (`ForecastDataAugmentor` logs a WARN so the feature cannot die silently). Low cloud is a **gate**, not a garnish — outside 15–80 % you are either inside the murk or there is no cloud to form a sea, and both used to score STRONG. The prompt states the measured reversal alongside the score because handing Claude a number and asking for it back makes the "evaluation" an echo of the calculator.
- **Aerosol proxy** — AOD + PM2.5: high AOD + low PM2.5 = dust (warm tones ✓); high AOD + high PM2.5 = smoke (haze ✗). No competitor does this.
- **Virtual threads** — `spring.threads.virtual.enabled: true`; `forecastExecutor` uses `newVirtualThreadPerTaskExecutor()`.
- **RestClient** — synchronous `RestClient` everywhere (no WebFlux). Open-Meteo via `@HttpExchange` + `HttpServiceProxyFactory`.
- **Declarative resilience** — Resilience4j; `@Retryable` with `MethodRetryPredicate` implementations; `@ConcurrencyLimit(8)`.
- **Location metadata** — production locations DB-managed via Admin UI (no YAML seeding). `application-local.yml` has locations for local dev.
- **JWT** — stateless HMAC-SHA256; refresh token stored hashed (SHA-256) in `refresh_token` table.
- **Freemium UI** — breadcrumbs not paywalls. See `docs/product/freemium_ui_strategy.md`.
- **Plan-screen confidence** — a per-region `Confidence` (HIGH/MEDIUM/LOW) derived by `ConfidenceDeriver` from forecast horizon (dominant: T+0/1 HIGH, T+2/3 MEDIUM, T+4+ LOW), downgraded one band (floor LOW) on wide rating spread (`ratingRange ≥ 2`) or thin coverage (< half the roster scored), and **null for zero coverage** so an unknown signal reads provisional, not falsely confident (deliberately unlike `Confidence.fromString`'s MEDIUM default). Computed at briefing **serve time** in `BriefingService.enrichWithCachedScores` (runs on both build + serve paths with request-time `today`, so the horizon stays fresh on re-serve) and rides the `daily_briefing_cache` JSON on the nullable `BriefingRegion.confidence` component — **no migration**; legacy payloads deserialize to null. The frontend treats it as **one uniform, quiet channel layered on the verdict** — `confidenceUtils.resolveConfidence` is fail-soft (prefers the backend field, falls back to a horizon-only tier); grid cells carry the full fill-decay gradient while Best Bet / summary-strip pills / the shared `VerdictPill` (drill-down + mobile) show a marker-on-low; the **star/quality signal is never touched**. A durable, horizon-only per-evaluation `forecast_evaluation.confidence` (V127, `ConfidenceDeriver.fromHorizon`, written at the sole `ForecastService.buildEntity` site) records the same for analytics but gates nothing. Hot topics carry an **orthogonal certainty vocabulary** (`topicCertainty`: almanac / forecast / chance) derived client-side from topic type — a separate axis from horizon decay, since a fixed tide, a weather forecast, and an unforecastable NLC display are different *kinds* of certainty.
- **Evaluation eligibility (Gate 4)** — per-`daysAhead` policy in `NightlyEligibilityPolicy` (`service/batch/`): T+0/T+1 all stabilities (NEAR=Sonnet); T+2 SETTLED+TRANSITIONAL (FAR=Haiku); T+3 SETTLED only; T+4+ never. **Both engines consult it**: the batch pipeline via `resolve()`, and the synchronous `ForecastCommandExecutor.applyStabilityFilter` via `permitsHorizon()` (unified July 2026 — the former `evaluationWindowDays()` policy-proxy read is gone; that method is display-only everywhere). Grid-cell classification + `stability_snapshot` publishing has a single producer, `GridCellStabilityService`, shared by both engines. Note: the stability filter is bypassed entirely for manually triggered admin runs (deliberate — the user explicitly requested those slots). Per-cycle `[BATCH ELIG]` INFO log shows included/excluded by `(daysAhead × stability)`.

---

## Configuration

Never commit `application.yml`. Only `application-example.yml` is committed.

Key config: `anthropic`, `worldtides`, `spring.datasource`, `spring.flyway`, `spring.mail`, `notifications`, `forecast.locations`, `jwt`, `server.port`, `aurora` (enabled, poll-interval-minutes, light-pollution-api-key, noaa.*, met-office.*, triggers.*, bortle-threshold.moderate/strong).

---

## Database Migrations

| Range | Key tables/changes |
|-------|-------------------|
| V1–V3 | `forecast_evaluation`, `actual_outcome`, azimuth |
| V5–V9 | `location` table + metadata (golden_hour_type, tide_type, location_type) |
| V10–V12 | `app_user`, `refresh_token`, password change gate |
| V13–V15 | Tide columns on forecast_evaluation, `tide_extreme` table, FK constraints |
| V16–V19 | evaluation_model, fiery_sky/golden_hour scores, rating, comfort fields |
| V20–V21 | `job_run` + `api_call_log`, location failure tracking |
| V22–V30 | model_selection, job_run enhancements, email on user, RunType refactor, location enabled |
| V31–V35 | `regions`, email_verification_token, `model_test_run/result`, marketing opt-in |
| V36–V39 | last_active_at, token columns + micro-dollar costs + exchange_rate, determinism re-run |
| V41–V43 | optimisation_strategy, TideType enum refactor |
| V44–V46 | prompt_test_run/result, SolarEventType refactor |
| V47–V48 | location_id FK, directional cloud columns + basic-tier scores |
| V49–V50 | NEXT_EVENT_ONLY strategy, WATERFALL location type + reclassification |
| V51 | Cloud approach risk columns (solar trend, upwind sample) on forecast_evaluation |
| V52 | SENTINEL_SAMPLING optimisation strategy rows; remove stale REQUIRE_PRIOR rows |
| V53 | far_solar_low_cloud column on forecast_evaluation (strip vs blanket detection) |
| V54 | TIDE_ALIGNMENT optimisation strategy rows for all colour run types |
| V55 | drive_duration_minutes column on locations |
| V56 | bortle_class column on locations (nullable integer) |
| V57 | aurora_forecast_result table |
| V58 | dew_point column on forecast_evaluation |
| V59 | daily_briefing_cache table |
| V60 | storm surge coastal parameters on locations |
| V61 | storm surge forecast columns on forecast_evaluation |
| V62 | sky_brightness_sqm column on locations |
| V63 | briefing_model_test_run + briefing_model_test_result tables |
| V64 | `astro_conditions` table (cloud/visibility/moon modifiers + explanations) |
| V65 | `elevation_m` and `overlooks_water` columns on locations (inversion detection) |
| V66 | `inversion_score` and `inversion_potential` columns on forecast_evaluation |
| V67 | User home location (postcode, lat/lon) + `user_drive_time` table |
| V68 | `scheduler_job_config` table + 5 seed rows (tide, briefing, aurora, met office, cleanup) |
| V89–V96 | Batch job run costs, cached evaluation, batch near/far term model, forecast triage |
| V97 | `evaluation_delta_log` table for empirical freshness threshold refinement |
| V98 | `stability_snapshot` table for persisting stability state across restarts |
| V99 | Batch observability: `custom_id`, `error_type`, `batch_id` on `api_call_log`; widen `error_message` to TEXT; partial indexes |
| V100+ | (not individually listed here — `ls backend/src/main/resources/db/migration/ \| sort -V` for the full set) |
| V127 | `forecast_evaluation.confidence` — durable horizon-derived per-evaluation confidence (nullable `VARCHAR(20)`, enum name); rides analytics, gates nothing |
| **latest** | **Deliberately not written down — every number recorded here has rotted.** Read it from the tree before adding a migration: `ls backend/src/main/resources/db/migration/ \| sort -V \| tail -1` |

---

## Roles

| Role | Permissions |
|------|-------------|
| `ADMIN` | All endpoints + Manage tab |
| `PRO_USER` | Forecast, outcomes, locations, re-runs |
| `LITE_USER` | Read-only forecast; may also **record** outcomes (`POST /api/outcome`) |

> "Read-only" scopes to the *forecast* surface, not the whole API. Recording an outcome is deliberately
> open to any authenticated user: `actual_outcome` is the evidence base for the calibration gate, and
> observations are the scarce input, so restricting who may contribute one works against the thing the
> table exists for. Enforcement lives on the endpoints that *read* premium detail, not on this write.

---

## API Endpoints

### Auth (no JWT required)
`POST /api/auth/login|refresh|register|resend-verification|verify-email|set-password`

### Auth (Bearer required)
`POST /api/auth/logout|change-password` | `PUT /api/auth/marketing-emails`

### Forecast (Bearer)
`GET /api/forecast` | `GET /api/forecast/compare`

### Forecast runs (ADMIN)
`POST /api/forecast/run` | `POST /api/forecast/run/very-short-term|short-term|long-term`

### Locations & Regions (Bearer / ADMIN for writes)
`GET|POST /api/locations` | `PUT /api/locations/{name}/reset-failures` (ADMIN) | `GET|POST /api/regions` | `PUT /api/regions/{id}` | `PUT /api/regions/{id}/enabled`

### Users (ADMIN)
`GET|POST /api/users` | `PUT /api/users/{id}/enabled|role|reset-password` | `DELETE /api/users/{id}`

### Outcomes (Bearer)
`GET|POST /api/outcome`

### Admin tools (ADMIN)
`GET /api/metrics/job-runs|api-calls` | `GET|PUT /api/models` | `PUT /api/models/active|optimisation` | `POST /api/model-test/run|run-location|rerun` | `GET /api/model-test/runs|results` | `POST /api/prompt-test/run|replay` | `GET /api/prompt-test/runs|runs/{id}|results|git-info` | `GET /api/admin/calibration?from=&to=`

### Cloud verification (ADMIN)
`GET /api/admin/cloud-verification` | `POST /api/admin/cloud-verification/backfill` | `GET /api/admin/cloud-verification/backfill/status` — scores past forecasts against **ERA5 reanalysis**, needing no recorded outcome. `actual_outcome` is empty (zero rows ever), so the calibration gate has nothing to score; but every threshold the scoring rules turn on is a *cloud* claim, and cloud is machine-checkable. ⚠️ **Reanalysis is not observation.** ERA5 is a model reconstruction that assimilates observations unavailable when the forecast was issued — genuinely independent information, but still a model field, not a measurement. Where ERA5 and the forecast model share a bias (low stratocumulus, marine layers, orographic cloud) the comparison flatters the forecast. Read a disagreement as *"the forecast differs from a better-informed model"*, not *"the forecast was wrong"*. It says nothing at all about whether a sunset was beautiful — that still needs recorded outcomes. **Two points per evaluation, matching the two claims a forecast makes**: the *gap* (low cloud at the 113 km solar horizon — did the low sun get through?) and the *canvas* (mid/high overhead at the observer — was there a screen to light up?). Report splits: `vetoFired`/`vetoNotFired` (`gapActuallyOpen` counts clear-horizon skies the veto suppressed), **`vetoUncapped`/`vetoCapped`** (below `MAX_UPWIND_DISTANCE_M` the upwind sample is a real advection nowcast; at the cap it is not — this is the D7 question), and `byWindSunAngle`. Backfill is **async** (`202` immediately, `409` if already running) and works the backlog in committed batches of 100 — a synchronous pass cannot finish inside a proxy timeout. It is **self-healing**: rows carrying no observations are cleared at the start of each run, and a batch that returns no observations at all stops the run (`lastError`) rather than blanking the remaining backlog — those two rules only work as a pair. Gated on the archive's ~6-day lag; reads the same `findBestIndex` slot the forecast scored. See `docs/engineering/cloud-approach-veto-fix.md`.

### Calibration gate (ADMIN)
`GET /api/admin/calibration` — forecast accuracy vs **recorded outcomes**. The only non-self-referential accuracy measure in the project: prompt-regression tests compare Claude to hand-written expectations, the sky-rating eval harness to fixtures, and model-comparison to other models — all can stay green while forecasts drift from reality. `ForecastCalibrationService` joins `forecast_evaluation` to `actual_outcome` on (location, date, target type), keeps the newest run per (slot, horizon), and buckets **overall / per `daysAhead` / per model**. Each bucket carries signed mean error (separates optimism from pessimism), mean absolute error, exact-match and within-one rates, plus two decision-error counts: **missedOpportunities** (predicted ≤2, actual ≥4) and **wastedTrips** (predicted ≥4, actual ≤2). Run over a fixed window before and after any prompt or sampling-geometry change and diff the buckets — aggregation is deterministic per window. An absolute rating ceiling can only create missed opportunities, so that count gates relaxing the cloud-approach veto (see `docs/engineering/cloud-approach-veto-fix.md`).

### Aurora (Bearer / ADMIN for writes)
`GET /api/aurora/status` (Bearer) | `GET /api/aurora/locations` (Bearer) | `GET /api/aurora/viewline` (Bearer) | `POST /api/aurora/admin/enrich-bortle` (ADMIN) | `POST /api/aurora/admin/run` (ADMIN — triggers immediate NOAA cycle) | `POST /api/aurora/admin/reset` (ADMIN)

### Briefing (Bearer / ADMIN for writes)
`GET /api/briefing` | `POST /api/briefing/run` (ADMIN) | `GET /api/briefing/evaluate/scores` (Bearer) | `DELETE /api/briefing/evaluate/cache` (ADMIN) | `POST /api/briefing/compare-models` (ADMIN) | `GET /api/briefing/compare-models/runs` (ADMIN) | `GET /api/briefing/compare-models/results` (ADMIN)

> The former SSE `GET /api/briefing/evaluate` and the `/evaluate/cache[/timestamp]` GETs no longer exist — `BriefingEvaluationController` exposes only `GET /scores` and `DELETE /cache`. This matters for HTTP caching: `/api/briefing` and `/api/briefing/evaluate/scores` are ETag-revalidated (`HttpCachingConfig`), and that whitelist is exact-match precisely so it can never catch a streaming endpoint.

### Astro Conditions (Bearer)
`GET /api/astro/conditions` | `GET /api/astro/conditions/available-dates`

### User Settings (Bearer)
`GET|PUT /api/user-settings`

### Scheduler (ADMIN)
`GET /api/admin/scheduler/jobs` | `PUT /api/admin/scheduler/jobs/{jobKey}/schedule` | `POST /api/admin/scheduler/jobs/{jobKey}/pause` | `POST /api/admin/scheduler/jobs/{jobKey}/resume` | `POST /api/admin/scheduler/jobs/{jobKey}/trigger`

### Tides (Bearer)
`GET /api/tides` | `GET /api/tides/stats` | `GET /api/tides/stats/all`

> Previously documented as ADMIN, which the code never enforced — `TideController` carries no
> `@PreAuthorize`, and `SecurityConfig` gates `/api/**` at `.authenticated()` only. Bearer is the intent,
> not a gap: tide extremes are astronomical data already surfaced in the forecast UI, so an ADMIN gate
> would break the app for every non-admin. The doc was wrong, not the controller.

### Push (mixed auth)
`GET /api/push/vapid-public-key` (none) | `POST|DELETE /api/push/subscribe` (Bearer)

---

## Product Strategy

See `docs/product/` for detailed reference documents.

**Differentiators**: Claude-generated "why" explanation | AOD+PM2.5 aerosol proxy | Cloud approach risk detection (temporal trend + upwind sampling) | Location type-specific UI | Outcome recording feedback loop

**Freemium split**: LITE gets basic scores, 3-day horizon, blurred metrics. PRO gets enhanced directional scores, 3-day horizon, full metrics.

### Role gating — UI pattern

Every new UI feature must be assessed for role gating.

- **Admin-only** (Manage tab, health widget, Run Forecast): hidden entirely
- **PRO/ADMIN** (all other premium features): visible but greyed out for LITE users
  - `opacity: 0.45`, `pointer-events: none`, `disabled` on interactive elements
  - Upsell text: "Pro" pill badge (inline) or "Upgrade to Pro" (standalone)
- **Exception**: elements too small for visual disable (e.g. marker SVG arcs) stay hidden; upsell goes on the nearest tappable surface (the popup)
- Backend 403 gating is the real enforcement; frontend greying is the UX layer

---

## Planned Features

- **Prediction accuracy feedback** — structured post-event feedback per user per evaluation (ACCURATE/SLIGHTLY_OFF/VERY_INACCURATE), admin breakdown by model and days_ahead
- **Web Push notifications** — replace Pushover with W3C Push API + VAPID (`webpush-java`), service worker, iOS Home Screen caveat
- **macOS menu bar widget** — Tauri app reusing React components, menu bar icon with best rating

---

## Test Standards

Read `docs/engineering/test-improvement-standards.md` before writing or modifying any test class.

---

## Code Standards

### Backend
- Checkstyle: Javadoc on public classes/methods, no unused imports, 4-space indent, 120-char lines
- SpotBugs: `High` threshold (`<threshold>High</threshold>` in `backend/pom.xml`), FindSecBugs plugin, bound to `verify`
- No business logic in controllers; no magic numbers; graceful error handling

### Frontend
- ESLint + Prettier | `data-testid` on key elements | Tailwind only (no inline styles) | PropTypes on all components

### Commits
Conventional commits: `feat:`, `fix:`, `chore:`, `test:`, `docs:`, `refactor:`

---

## Git Conventions

- Never commit `application.yml` — only `application-example.yml`
- `.gitignore`: `application.yml`, `*.env`, `node_modules/`, `target/`, `backend/data/`, `.claude/`
- Branch naming: `feature/`, `fix/`, `chore/`
- Update `CHANGELOG.md` on every meaningful commit
- **NEVER push to remote** — not even if the user has pushed before. Always wait to be explicitly asked.
- **NEVER create or delete git tags** — tags mark tested, confirmed releases. Wait for the user to instruct this after real-world testing.
- **NEVER change assertions in prompt regression tests** (`src/test/java/.../regression/`) — these encode ground-truth expectations against real Claude output and must only be updated by the user.

---

## Speeding Up the Dev Build Cycle

`./mvnw clean verify` runs Checkstyle → compile → test → JaCoCo → SpotBugs → repackage — ~6–7 min, and
**needs Docker running** (the `test` phase includes the 5 `IntegrationTestBase` Testcontainers classes).
Use targeted commands instead:

```bash
# Compile only — catches import/signature errors in seconds        [no Docker]
cd backend && ./mvnw compile -q

# Run a single test class — fastest inner loop   [no Docker, unless it extends IntegrationTestBase]
cd backend && ./mvnw test -pl . -Dtest=AuroraStateCacheTest -q

# Run all aurora-related tests only                                [no Docker]
cd backend && ./mvnw test -Dtest="Aurora*,ClaudeAuroraInterpreter*" -q

# Skip Checkstyle + SpotBugs (still runs all tests + JaCoCo)       [NEEDS DOCKER]
cd backend && ./mvnw verify -Dcheckstyle.skip=true -Dspotbugs.skip=true -q

# Full verify but skip clean (reuses compiled classes — shaves ~30s)  [NEEDS DOCKER]
cd backend && ./mvnw verify -q
```

**Checkstyle pre-flight before full verify** — run Checkstyle alone first; it fails fast (~15s) and catches line-length / unused-import violations before wasting 6 min:

```bash
cd backend && ./mvnw checkstyle:check -q
```

⚠️ **Gate on Maven's exit code, never on a grep of its output.** With `-q`, Checkstyle's violation lines are suppressed, so `| grep -E "ERROR"` finds nothing and looks clean. Worse, `$?` after a pipeline is the status of **grep**, not Maven — it is almost always `0`. That combination reported a green build twice on 2026-07-25 while Maven was in fact returning `1`, hiding both a Checkstyle violation and a genuinely failing test until CI caught them. Redirect to a file and echo the exit code as its own statement:

```bash
cd backend && ./mvnw checkstyle:check >/tmp/cs.log 2>&1; echo "exit: $?"
```

**Reproducing CI's `Backend — Build, Test & Coverage` locally** — a plain `./mvnw clean verify` cannot do this. The classes under `src/test/java/.../integration/` need Docker/Testcontainers; without Docker running, surefire aborts on them and the build **never reaches `jacoco:check` or `spotbugs:check`** — so the two gates most likely to fail CI are exactly the two a local `verify` silently skips. Exclude them and the build gets there:

```bash
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress \
  -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
grep -E "BUILD (SUCCESS|FAILURE)|Rule violated|Tests run: [0-9]+, Fail" /tmp/v.log | tail -6
```

Exit `0` is the gate; the grep is only for reading the detail. Use the `!**/integration/**` path glob rather than a list of class names — a name list rots (`HttpIntegrationTestBaseProbeTest` ends in `ProbeTest` and slips past `!*IntegrationTest`), and `-Dtest='!com.gregochr.goldenhour.integration.*'` does not work at all, as surefire ignores that form. JaCoCo coverage is then computed without those tests, i.e. slightly pessimistic — if it passes locally it passes on CI. **JaCoCo's rule is 80% line coverage per class**, which bites small new records: cover the defensive null branches with real assertions rather than deleting the guards.

**Don't use background Bash tasks for long Maven builds** — the background task output files are unreliable (often 0 bytes until the process finishes writing). Always run `./mvnw` in the foreground with an explicit `timeout` parameter so results come back immediately.

**Frontend tests are fast — run them separately:**

```bash
cd frontend && npm run test -- --reporter=dot   # ~90s, much faster than backend
```

The core rule: `compile → single-class test → checkstyle:check → full verify` as a ladder. Only climb to `clean verify` when you're confident everything is clean — and gate each rung on the exit code, not on what the output appears to say. The first three rungs run with Docker stopped; anything that reaches the full `test` phase (`./mvnw test`, `./mvnw verify`) does not — start Docker Desktop first, or you get a Testcontainers stack trace instead of a test failure.

---

## solar-utils

Resolved from **JitPack**, not GitHub Packages. `backend/pom.xml` declares
`com.github.gregochr:solar-utils:2.1.0` against the single `<repository>` whose `<id>` is `github` but
whose URL is `https://jitpack.io` — the id is misleading. No `read:packages` token is required.

A jar is also vendored in-tree at `backend/.m2/repository/com/gregochr/solar-utils/2.1.0/` and staged by
`backend/Dockerfile`, but its groupId is `com.gregochr` — a *different* coordinate from the one the POM
asks for, so it cannot satisfy the dependency; the Docker build still resolves 2.1.0 from JitPack.
Treat the vendored copy as a leftover from the pre-JitPack local-install era.

Public API (v2.1.0, `com.gregochr.solarutils.SolarCalculator`): `sunrise`, `sunset`, `civilDawn`,
`civilDusk`, `goldenHourStart`, `goldenHourEnd`, `solarNoon`, `dayLengthMinutes`, `sunriseAzimuth`,
`sunsetAzimuth`. The jar also ships `LunarCalculator` and `MoonriseMoonsetCalculator`.
