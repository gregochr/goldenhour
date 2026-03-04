# CLAUDE.md — The Photographer's Golden Hour

## Project Overview

A full-stack app that evaluates sunrise/sunset colour potential at configured locations.

- **Backend**: Spring Boot 4 REST API — Open-Meteo weather + air quality, WorldTides tide data, Claude (Anthropic SDK) evaluation, PostgreSQL persistence, scheduled runs, email/Web Push/macOS toast notifications.
- **Frontend**: React 19 + Vite + Tailwind — map view (Leaflet), forecast timeline, outcome recording, JWT-authenticated.
- **Future**: macOS menu bar widget (Tauri).

---

## What's Built

- Core forecast pipeline: Open-Meteo → Claude → PostgreSQL
- Scheduled evaluations (06:00 + 18:00 UTC, T through T+7)
- Notifications: email (Thymeleaf HTML), Pushover, macOS toast
- Tide data: WorldTides API, weekly refresh per coastal location, `tide_extreme` table (V14), `TideService` derives state/next tides from DB at evaluation time
- Outcome recording (`actual_outcome` table, UI form)
- Multi-location support with map view (Leaflet/OpenStreetMap)
- Location metadata: `goldenHourType` (SUNRISE/SUNSET/BOTH_TIMES/ANYTIME), `tideType` (HIGH/MID/LOW — multi-select set; empty = not coastal), `locationType` (LANDSCAPE/WILDLIFE/SEASCAPE)
- Sunrise/sunset azimuth lines on map
- **Two scores** ✓ — Fiery Sky Potential (0–100, dramatic colour) and Golden Hour Potential (0–100, light quality) alongside the 1–5 star rating; V17 columns, differentiated weighting in the shared evaluation prompt
- **Configurable cost optimisation strategies** ✓ — five toggleable strategies per run type (SKIP_LOW_RATED, SKIP_EXISTING, FORCE_IMMINENT, FORCE_STALE, EVALUATE_ALL), managed via Admin UI "Run Config" tab; mutual exclusion validation; `optimisation_strategy` table (V41); `OptimisationSkipEvaluator` replaces hard-coded Opus gate and long-term skip logic; active strategies snapshot on each `job_run` for audit; REQUIRE_PRIOR merged into SKIP_LOW_RATED (V42)
- **Per-run-type model config** ✓ — three independent model configs (Very Short-Term, Short-Term, Long-Term), each selectable as Haiku/Sonnet/Opus via Admin UI
- Flat evaluation strategy hierarchy: Haiku, Sonnet, Opus all extend `AbstractEvaluationStrategy` directly with shared prompts; differentiation is purely which Anthropic model is used
- Wildlife location UI: pure-WILDLIFE locations get hourly comfort rows (temp/wind/rain) between sunrise and sunset, green 🐾 marker; no Claude call
- Comfort fields (temperature, apparent temperature, precipitation probability) stored on every forecast row
- JWT authentication: ADMIN / PRO_USER / LITE_USER roles
- User management (ADMIN-only Manage tab)
- First-login password change gate with complexity enforcement
- Session expiry warnings (amber ≤7 days, red ≤1 day); refresh token rotates on every `/refresh` call
- Session countdown (`Session: 30d`) in header for ADMIN users
- Backend health indicator: green/red dot in header (ADMIN only), polls `/actuator/health` every 30 s
- **Command + Strategy patterns** ✓ — GoF Command pattern (`ForecastCommand`, `ForecastCommandFactory`, `ForecastCommandExecutor`) cleanly separates what to run from how to run it; `RunType` enum replaces both `JobName` and `ModelConfigType`; `NoOpEvaluationStrategy` for wildlife locations
- **Token-based cost tracking** ✓ — actual token-based micro-dollar pricing from Anthropic SDK, replacing flat per-call pence estimates
  - `TokenUsage` record extracts input, output, cache creation, and cache read tokens from every Claude API response
  - Costs stored in micro-dollars (1 USD = 1,000,000 µ$) using real per-model USD/MTok rates with cache and batch discount support
  - `ExchangeRateService` fetches daily USD-to-GBP from Frankfurter API (ECB data); `exchange_rate` table caches rates; exchange rate snapshot per job run for historically accurate GBP conversion
  - V38 migration: token columns + micro-dollar costs on `api_call_log`, `model_test_result`, `job_run`, `model_test_run`; new `exchange_rate` table
  - Frontend shows both GBP and USD costs with per-call token breakdown; legacy pence fallback for old data
- **Job run metrics** ✓ — persistent tracking of all scheduled forecast runs and external API calls with cost aggregation
  - `job_run` table (V20) tracks run type, evaluation model, duration, success/failure counts, total cost in pence and micro-dollars
  - `api_call_log` table (V20) records every API call with request/response details, duration, cost, token counts
  - Admin metrics dashboard shows last 7 days: total runs, success rate, slowest service, cost breakdown in GBP and USD
  - Per-location failure tracking: `consecutive_failures`, auto-disable after 3 failures (V21)
- **Retry robustness** ✓ — declarative `@Retryable` (Spring Framework 7) with exponential backoff
  - Anthropic 529 (overloaded) + content filter retry via `AnthropicApiClient` + `ClaudeRetryPredicate`
  - Open-Meteo/WorldTides 5xx/429 retry via `OpenMeteoClient` + `TransientHttpErrorPredicate`
  - Dead-letter mechanism for persistent location failures
  - `@ConcurrencyLimit(8)` on `ForecastService.runForecasts()` caps parallel evaluations
  - Request/response logging interceptor captures all `/api/**` endpoints at INFO level
- **Docker & CloudFlare Tunnel** ✓ — production deployment ready
  - `Dockerfile` with health checks, alpine base, non-root user
  - `docker-compose.yml` with H2 persistence to Mac filesystem
  - `application-prod.yml` with Flyway enabled, production Spring config
  - Automated daily backups via cron (keeps last 7 backups)
  - CloudFlare Tunnel exposure without opening router ports
- **Model comparison test harness** ✓ — A/B/C test runs Haiku, Sonnet, and Opus against identical atmospheric data for one location per region
  - `model_test_run` + `model_test_result` tables (V34)
  - `ModelTestService` orchestrates region selection, single weather fetch, triple evaluation with prompt/response capture
  - `EvaluationDetail` record and `evaluateWithDetails()` method for full prompt/response transparency
  - Admin UI: "Model Test" tab in ManageView with comparison grid and delta indicators from Haiku baseline
  - Styled confirmation dialogs replace `window.confirm()` across Job Runs and Model Test views
  - Single-location test: `POST /run-location?locationId=X` with location picker modal
  - Re-run previous test: `POST /rerun?testRunId=X` with same locations, fresh data
  - Run row toggle: click expanded run to collapse
- **Marker clustering** ✓ — `react-leaflet-cluster` groups nearby markers at low zoom (< 10); dark-themed cluster icons with count, background colour from grey→gold based on average child rating, fiery sky / golden hour arc progress for PRO/ADMIN; long location name labels truncated with ellipsis (90px max-width, full name on hover)
- **Star rating filter** ✓ — 5 toggle chips (1★–5★) on the map filter bar; any permutation selectable; AND-ed with location type filters
- **Last active tracking** ✓ — `lastActiveAt` column (V37) updated on every authenticated API request, throttled to once per hour; replaces login-only `lastLoginAt`
- **Self-registration with email verification** ✓ — users sign up with email + username, verify via emailed link, set their own password
  - V33: `email_verification_token` table + unique email index on `app_user`
  - Multi-step RegisterPage: register → check email → verify → set password → auto-login
  - Cloudflare Turnstile CAPTCHA on registration form
  - Rate-limited resend (max 3 in 5 minutes); abandoned registrations auto-replaced
- **Marketing email opt-in** ✓ — checkbox on registration (default checked, UK soft opt-in compliant)
  - V35: `marketing_email_opt_in` column on `app_user`
  - `PUT /api/auth/marketing-emails` toggle endpoint; preference returned in login/set-password responses
  - Privacy policy modal updated with marketing emails section
- **Account deletion email** ✓ — polite notification sent when admin deletes a user with an email address
- **Regions** ✓ — geographic grouping for locations (V31/V32), full CRUD, used by model test harness
- **Client-side pagination** ✓ — shared `usePagination` hook and `Pagination` component for Locations and Users tables
  - Page size chips (10/25/50), First/Prev/Next/Last navigation, "Showing X-Y of Z" summary
  - `table-fixed` with explicit column widths prevents layout shift between pages
  - Spacer rows on partial last page keep pagination controls anchored
  - Resets to page 1 on filter change; hidden when all items fit on one page
- **Emoji chip UI for location metadata** ✓ — Type (🏔️/🌊/🐾) and Tide (H/M/L) displayed as compact toggle chips in both read-only and edit modes
  - Type: single-select emoji chips (click to change in edit mode); read-only shows active type at full opacity, others faded + greyscale
  - Tide: multi-select H/M/L chips; gold fill when selected; disabled for non-SEASCAPE; prevents deselecting the last chip
  - Column header filters use matching clickable chips instead of text inputs
  - `TideToggleChips` and `LocationTypeChips` components in `LocationManagementView.jsx`
- **Prompt test harness** ✓ — end-to-end prompt evaluation test running all colour locations through the Claude pipeline
  - `prompt_test_run` + `prompt_test_result` tables (V44, V45)
  - `PromptTestService` orchestrates: colour location selection, weather fetch, model evaluation, result persistence
  - Async execution: POST /run and /replay return 202 immediately; frontend polls `GET /runs/{id}` every 3s for live progress
  - Run comparison: select two runs via checkboxes for side-by-side score deltas
  - Replay: re-run with same locations/dates but current prompt version for A/B comparison
  - Build info section: git commit, branch, relative date above controls
  - Model versions: `EvaluationModel.version` field exposed via `/api/models` as `[{name, version}, ...]`
  - Admin UI: "Prompt Test" tab in ManageView with model/run-type selection, progress indicator, results table with Date/Target columns
  - Popup preview: eye icon on each succeeded result row opens a modal rendering `MarkerPopupContent` with mapped atmospheric data for visual badge verification (e.g. Sahara Dust)
- **URL hash navigation** ✓ — active view and Manage tab persisted in URL hash (e.g. `#manage/prompttest`); survives page refresh and supports browser back/forward
- **Sahara Dust badge** ✓ — `isDustEnhanced()` in `MarkerPopupContent` shows a gold badge when AOD > 0.3 or dust > 50 µg/m³ with PM2.5 < 35; threshold raised from 15 to accommodate genuine Saharan dust events where mineral particles moderately elevate PM2.5

---

## Monorepo Structure

```
goldenhour/
├── backend/               Spring Boot 4 app (port 8082 local)
│   ├── src/main/java/com/gregochr/goldenhour/
│   │   ├── client/        OpenMeteoForecastApi, OpenMeteoAirQualityApi (@HttpExchange interfaces)
│   │   ├── config/        SecurityConfig, JwtAuthenticationFilter, JwtProperties, AppConfig, CostProperties, TransientHttpErrorPredicate, ClaudeRetryPredicate
│   │   ├── controller/    ForecastController, OutcomeController, LocationController, AuthController, UserController, JobMetricsController, ModelsController, ModelTestController, PromptTestController, RegionController
│   │   ├── entity/        ForecastEvaluationEntity, ActualOutcomeEntity, LocationEntity, AppUserEntity, RefreshTokenEntity, TideExtremeEntity, JobRunEntity, ApiCallLogEntity, ModelSelectionEntity, ModelTestRunEntity, ModelTestResultEntity, PromptTestRunEntity, PromptTestResultEntity, EmailVerificationTokenEntity, RegionEntity, ExchangeRateEntity, OptimisationStrategyEntity, UserRole, GoldenHourType, TideType, TideExtremeType, LocationType, TideState, RunType, ServiceName, EvaluationModel, TargetType, OptimisationStrategyType
│   │   ├── repository/    all Spring Data repos + JobRunRepository, ApiCallLogRepository, ModelTestRunRepository, ModelTestResultRepository, PromptTestRunRepository, PromptTestResultRepository, EmailVerificationTokenRepository, ExchangeRateRepository, OptimisationStrategyRepository
│   │   ├── service/       ForecastService, ForecastCommand, ForecastCommandFactory, ForecastCommandExecutor, OpenMeteoService, OpenMeteoClient, SolarService, EvaluationService, LocationService, OutcomeService, JwtService, UserService, RegistrationService, TurnstileService, TideService, ScheduledForecastService, JobRunService, CostCalculator, ExchangeRateService, ModelSelectionService, ModelTestService, PromptTestService, OptimisationStrategyService, OptimisationSkipEvaluator, evaluation/ (AnthropicApiClient, AbstractEvaluationStrategy, Haiku/Sonnet/Opus strategies), notification/
│   │   └── model/         AtmosphericData, SunsetEvaluation, EvaluationDetail, TokenUsage, OptimisationStrategyUpdateRequest, etc.
│   └── src/main/resources/
│       ├── application.yml          (gitignored — never commit)
│       ├── application-example.yml  (committed — placeholders)
│       ├── application-local.yml    (H2 local dev profile)
│       ├── application-prod.yml     (production config with H2 persistence)
│       └── db/migration/            V1–V47 Flyway migrations
├── frontend/              React 19 + Vite (port 5173)
│   └── src/
│       ├── api/           authApi.js, forecastApi.js, modelsApi.js, modelTestApi.js, promptTestApi.js (global axios interceptors)
│       ├── components/    LoginPage, ChangePasswordPage, ManageView, MapView, Pagination, ...
│       ├── hooks/         usePagination, useForecasts, useHealthStatus, useIsMobile
│       └── context/       AuthContext.jsx
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

# Run all tests
cd backend && ./mvnw clean verify
```

Default local credentials: `admin` / `golden2026`

H2 console: `http://localhost:8082/h2-console` (JDBC: `jdbc:h2:file:./data/goldenhour`, user: `sa`, pass: empty)

To reset local DB: delete `backend/data/goldenhour.mv.db` and `.lock.db`.

---

## Key Architecture Decisions

- **No DTOs** — `ForecastController` returns entities directly; all fields auto-exposed.
- **Backend-heavy** — all calculations (dayLabel, windCardinal, visibilityKm, azimuthDeg, tideAligned) computed on backend. Frontend is a pure render layer.
- **Evaluation strategy** — flat hierarchy: `AbstractEvaluationStrategy` (shared prompts, parsing) → `HaikuEvaluationStrategy`, `SonnetEvaluationStrategy`, `OpusEvaluationStrategy`, plus `NoOpEvaluationStrategy` for wildlife (no Claude call). Only `getEvaluationModel()` and `getModelName()` differ per Claude strategy. `AnthropicApiClient` handles API calls with declarative `@Retryable`. `EvaluationService` delegates to the strategy matching the admin-selected model for each run type.
- **Command pattern** — `ForecastCommand` record encapsulates run parameters (run type, dates, locations, strategy, manual flag). `ForecastCommandFactory` builds commands from `RunType`, resolving the active model and strategy. `ForecastCommandExecutor` runs commands with parallel execution, configurable optimisation strategies, and metrics tracking. Controllers and schedulers are thin wrappers: `commandFactory.create()` → `commandExecutor.execute()`.
- **Optimisation strategies** — `OptimisationSkipEvaluator` evaluates five configurable strategies (SKIP_LOW_RATED, SKIP_EXISTING, FORCE_IMMINENT, FORCE_STALE, EVALUATE_ALL) loaded once per run from `optimisation_strategy` table. SKIP_LOW_RATED also covers the "no prior evaluation" case (formerly REQUIRE_PRIOR, merged in V40). Mutual exclusion validation prevents conflicting combinations. Active strategies are snapshotted on each `job_run` record for audit. Replaces hard-coded Opus gate and long-term skip logic.
- **Per-run-type model config** — `RunType` enum (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM, WEATHER, TIDE); each run button/endpoint uses its own configured model. `ModelSelectionService.getAllConfigs()` returns the full map.
- **JWT** — stateless HMAC-SHA256; 24 h access token, 30-day refresh token stored hashed (SHA-256) in `refresh_token` table.
- **CORS** — configured in `SecurityConfig` via `CorsConfigurationSource` bean; `allowedOriginPatterns` covers `localhost:*` and LAN subnets.
- **Location metadata** — production locations are DB-managed via the Admin UI (no YAML seeding). `application-local.yml` still has location config for local dev convenience.
- **Layer inference** — cloud altitude layers are used as a proxy for directional cloud positioning. Low < 30% = solar horizon clear ✓; mid 30–60% = canvas above horizon ✓; high 20–60% = depth and texture ✓. True 5-point directional sampling deferred to v1.1. See `docs/product/directional_analysis_breakdown.md`.
- **Aerosol proxy** — AOD + PM2.5 together proxy aerosol type: high AOD + low PM2.5 = dust (warm tones ✓); high AOD + high PM2.5 = smoke (grey haze ✗). Implemented in evaluation prompt; no competitor does this.
- **Virtual threads** — `spring.threads.virtual.enabled: true` in both profiles; Tomcat, `@Async`, and scheduling all use virtual threads. `forecastExecutor` bean uses `Executors.newVirtualThreadPerTaskExecutor()`.
- **RestClient** — all HTTP clients use Spring's synchronous `RestClient` (no Reactor/WebFlux on classpath). `TideService`, `PushoverNotificationService`, `TurnstileService` inject `RestClient` directly. Open-Meteo calls go through `@HttpExchange` interfaces (`OpenMeteoForecastApi`, `OpenMeteoAirQualityApi`) proxied via `HttpServiceProxyFactory`.
- **Declarative resilience** — `@EnableResilientMethods` in `AppConfig`; `@Retryable` on `AnthropicApiClient` and `OpenMeteoClient` with `MethodRetryPredicate` implementations (`ClaudeRetryPredicate`, `TransientHttpErrorPredicate`); `@ConcurrencyLimit(8)` on `ForecastService.runForecasts()`.
- **Freemium UI** — breadcrumbs not paywalls: blur/lock for gated metrics, soft limits for horizon/locations, discovery moments for aerosol detail. See `docs/product/freemium_ui_strategy.md`.

---

## Configuration

Never commit `application.yml`. Only `application-example.yml` is committed.

Key config sections: `anthropic`, `worldtides`, `spring.datasource`, `spring.flyway`, `spring.mail`, `notifications`, `forecast.locations`, `jwt`, `server.port`.

```yaml
jwt:
  secret: YOUR_JWT_SECRET_BASE64   # openssl rand -base64 32
  access-token-expiry-hours: 24
  refresh-token-expiry-days: 30
```

---

## Database Migrations

| Version | Description |
|---------|-------------|
| V1 | `forecast_evaluation` table |
| V2 | `actual_outcome` table |
| V3 | `azimuth_deg` on forecast_evaluation |
| V5 | `location` table |
| V6 | `golden_hour_type` on location |
| V7 | `tide_type` on location |
| V8 | `location_type` element collection join table |
| V9 | `tide_type` converted to element collection join table |
| V10 | `app_user` table + default admin row |
| V11 | `refresh_token` table |
| V12 | `password_change_required` on app_user |
| V13 | tide columns on `forecast_evaluation` (tideState, nextHighTide, nextLowTide, tideAligned) |
| V14 | `tide_extreme` table — pre-fetched WorldTides extremes, FK to `locations` |
| V15 | FK constraints: `forecast_evaluation.location_name` → `locations(name)`, same for `actual_outcome` |
| V16 | `evaluation_model` column on `forecast_evaluation` (HAIKU / SONNET) |
| V17 | `fiery_sky_potential`, `golden_hour_potential` score columns on `forecast_evaluation` |
| V18 | `rating` column on `forecast_evaluation` (1–5 Haiku rating) |
| V19 | `temperature_celsius`, `apparent_temperature_celsius`, `precipitation_probability_percent` on `forecast_evaluation` |
| V20 | `job_run` + `api_call_log` tables — job run metrics and API call logging |
| V21 | `consecutive_failures`, `last_failure_at`, `disabled_reason` on `location` |
| V22 | `model_selection` table |
| V23 | `triggered_manually` on `job_run` |
| V24 | Rename `job_name` WILDLIFE → WEATHER |
| V25 | `target_date_range` on `job_run` |
| V26 | `model` + `target` on `api_call_log` |
| V27 | `email VARCHAR(255)` (nullable) on `app_user` |
| V28 | `config_type` on `model_selection` — per-run-type model configuration (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM) |
| V29 | Rename `job_name` → `run_type` + add `evaluation_model` on `job_run`; rename `config_type` → `run_type` on `model_selection`; drop `JobName`/`ModelConfigType` enums |
| V30 | `enabled` column on `locations` |
| V31 | `regions` table — geographic grouping for locations |
| V32 | `region_id` FK on `locations` |
| V33 | `email_verification_token` table + unique email index on `app_user` |
| V34 | `model_test_run` + `model_test_result` tables — A/B/C model comparison testing |
| V35 | `marketing_email_opt_in` on `app_user` — marketing email preference (default TRUE) |
| V36 | `last_login_at` column on `app_user` — user login timestamp |
| V37 | Rename `last_login_at` → `last_active_at` on `app_user` — throttled activity tracking |
| V38 | Token columns (`input_tokens`, `output_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`, `is_batch`, `cost_micro_dollars`) on `api_call_log` and `model_test_result`; `total_cost_micro_dollars` + `exchange_rate_gbp_per_usd` on `job_run` and `model_test_run`; new `exchange_rate` table |
| V39 | Determinism re-run support — atmospheric data JSON, run lineage, structured fields on `model_test_result` |
| V41 | `optimisation_strategy` table (5 strategies × 3 run types seeded); `active_strategies` column on `job_run` |
| V42 | Remove REQUIRE_PRIOR strategy (merged into SKIP_LOW_RATED) |
| V43 | Refactor `TideType` enum: HIGH_TIDE→HIGH, MID_TIDE→MID, LOW_TIDE→LOW; expand ANY_TIDE to all three; remove ANY_TIDE and NOT_COASTAL sentinels |
| V44 | `prompt_test_run` + `prompt_test_result` tables — prompt evaluation test harness |
| V45 | `run_type` column on `prompt_test_run` |
| V46 | Refactor `GoldenHourType` → `SolarEventType` on `locations` |
| V47 | `location_id` FK on `forecast_evaluation` and `actual_outcome` |

---

## Roles

| Role | Permissions |
|------|-------------|
| `ADMIN` | All endpoints + Manage tab (user management) |
| `PRO_USER` | Forecast, outcomes, add locations, trigger re-runs |
| `LITE_USER` | Read-only forecast and outcomes |

---

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/auth/login` | None | Login, returns access + refresh tokens |
| `POST` | `/api/auth/refresh` | None | New access token from refresh token |
| `POST` | `/api/auth/logout` | Bearer | Revoke refresh token |
| `POST` | `/api/auth/change-password` | Bearer | Change password, clears `passwordChangeRequired` |
| `POST` | `/api/auth/register` | None | Self-register with email verification (Turnstile CAPTCHA) |
| `POST` | `/api/auth/resend-verification` | None | Resend verification email (rate-limited) |
| `POST` | `/api/auth/verify-email` | None | Verify email with token from verification link |
| `POST` | `/api/auth/set-password` | None | Set password for verified user, auto-login |
| `PUT` | `/api/auth/marketing-emails` | Bearer | Toggle marketing email opt-in preference |
| `GET` | `/api/forecast` | Bearer | T through T+7 for all locations |
| `POST` | `/api/forecast/run` | ADMIN | Trigger on-demand evaluation (uses SHORT_TERM model config) |
| `POST` | `/api/forecast/run/very-short-term` | ADMIN | Run very-short-term forecasts (T, T+1) using VERY_SHORT_TERM model config |
| `POST` | `/api/forecast/run/short-term` | ADMIN | Run short-term forecasts (T, T+1, T+2) using SHORT_TERM model config |
| `POST` | `/api/forecast/run/long-term` | ADMIN | Run long-term forecasts (T+3 through T+7) using LONG_TERM model config |
| `GET` | `/api/forecast/compare` | Bearer | Compare evaluations across runs (dev only) |
| `GET` | `/api/outcome` | Bearer | Outcomes for date range |
| `POST` | `/api/outcome` | Bearer | Record actual outcome |
| `GET` | `/api/locations` | Bearer | All locations |
| `POST` | `/api/locations` | Bearer | Add location |
| `GET` | `/api/users` | ADMIN | List users |
| `POST` | `/api/users` | ADMIN | Create user (requires email) |
| `PUT` | `/api/users/{id}/enabled` | ADMIN | Enable/disable user |
| `PUT` | `/api/users/{id}/role` | ADMIN | Change user role |
| `PUT` | `/api/users/{id}/reset-password` | ADMIN | Generate temp password, set passwordChangeRequired |
| `GET` | `/api/metrics/job-runs` | ADMIN | Pageable job run history |
| `GET` | `/api/metrics/api-calls` | ADMIN | API call log with costs |
| `GET` | `/api/models` | Bearer | Available models, per-run-type active models, and optimisation strategies |
| `PUT` | `/api/models/active` | ADMIN | Set active model for a run type (`runType` + `model`) |
| `PUT` | `/api/models/optimisation` | ADMIN | Toggle an optimisation strategy (`runType` + `strategyType` + `enabled` + `paramValue`) |
| `PUT` | `/api/locations/{name}/reset-failures` | ADMIN | Re-enable auto-disabled location |
| `POST` | `/api/model-test/run` | ADMIN | Trigger A/B/C model comparison test across all regions |
| `POST` | `/api/model-test/run-location` | ADMIN | Test a single location with all 3 models (`locationId` param) |
| `POST` | `/api/model-test/rerun` | ADMIN | Re-run a previous test with same locations, fresh data (`testRunId` param) |
| `GET` | `/api/model-test/runs` | ADMIN | Recent model test runs (last 20) |
| `GET` | `/api/model-test/results` | ADMIN | Results for a test run (`testRunId` param) |
| `POST` | `/api/prompt-test/run` | ADMIN | Start async prompt test run (returns 202, poll for completion) |
| `POST` | `/api/prompt-test/replay` | ADMIN | Replay a previous prompt test with current prompt version (returns 202) |
| `GET` | `/api/prompt-test/runs` | ADMIN | Recent prompt test runs (last 20) |
| `GET` | `/api/prompt-test/runs/{id}` | ADMIN | Single prompt test run (for polling progress) |
| `GET` | `/api/prompt-test/results` | ADMIN | Results for a prompt test run (`testRunId` param) |
| `GET` | `/api/prompt-test/git-info` | ADMIN | Current git commit info for build identification |
| `GET` | `/api/push/vapid-public-key` | None | Returns VAPID public key for frontend subscription |
| `POST` | `/api/push/subscribe` | Bearer | Save a push subscription |
| `DELETE` | `/api/push/subscribe` | Bearer | Remove a push subscription |
| `GET` | `/api/regions` | Bearer | All regions |
| `POST` | `/api/regions` | ADMIN | Create region |
| `PUT` | `/api/regions/{id}` | ADMIN | Update region |
| `PUT` | `/api/regions/{id}/enabled` | ADMIN | Enable/disable region |
| `DELETE` | `/api/users/{id}` | ADMIN | Delete user (sends notification email) |

---

## Product Strategy

See `docs/product/` for detailed reference documents.

### Competitive Landscape

Main competitors: Alpenglow, SkyCandy, PhotoWeather, Sunsethue, Burning Sky. Key gaps
in the market: no competitor explains *why* in plain English, aerosol data is rarely
surfaced, and none segment by location type (landscape vs wildlife vs coastal).

**Golden Hour differentiators:**
- Claude-generated explanation (the biggest one — no competitor does this)
- Aerosol optical depth surfaced to users (dust enhances warm tones; smoke muddies them)
- Location types with type-specific UI (landscape scores vs wildlife comfort forecast)
- Outcome recording + accuracy feedback loop

### Two Scores ✓ Built

Claude evaluates each forecast on three scales: a 1–5 star rating, Fiery Sky Potential (0–100, dramatic colour requiring clouds to catch light), and Golden Hour Potential (0–100, overall light quality that can score high even with clear sky). Different parameter weighting is baked into the shared evaluation prompt. Columns added in V17.

### Aerosol Differentiation

Open-Meteo CAMS provides AOD + PM2.5. Use as a proxy:
- High AOD + low PM2.5 = probably dust → enhances warm reds ✓
- High AOD + high PM2.5 = probably smoke → grey/brown haze ✗

This is a competitive advantage. Most competitors don't touch aerosols at all.

### Forecast Refresh Cadence

- ECMWF/GFS/ICON models refresh every 6 hours (0z, 6z, 12z, 18z UTC)
- CAMS aerosols refresh every 24h (Europe) / 12h (global)
- Current schedule (06:00 + 18:00 UTC) is sensible; consider adding 12:00 to catch midday update
- Always show "Updated at HH:MM" timestamp in the UI
- Notify users only on material score changes (±15pp or one full star) to avoid alert fatigue

### Freemium Tier Split

| Feature | LITE_USER | PRO_USER | ADMIN |
|---------|-----------|----------|-------|
| Score + one-line summary | ✓ | ✓ | ✓ |
| "Why" explanation (3 key factors) | ✓ | ✓ | ✓ |
| Forecast horizon | 3 days | 7 days | 7 days |
| Locations | 1 | Unlimited | Unlimited |
| Cloud layer breakdown | Blurred/teased | ✓ | ✓ |
| Aerosol metrics | Blurred/teased | ✓ | ✓ |
| Full technical metrics | Blurred/teased | ✓ | ✓ |
| Directional map hint | — | ✓ | ✓ |

Use breadcrumbs (blur/lock/soft limits) not hard paywalls. Show features exist,
then gate them. See `docs/product/freemium_ui_strategy.md`.

---

## Planned Features

### 1. Cloudflare Tunnel ✓ BUILT

Expose home-hosted backend without opening router ports.

```bash
brew install cloudflared
cloudflared tunnel login
cloudflared tunnel create goldenhour
# Configure ~/.cloudflared/config.yml with tunnel id and ingress rules
cloudflared tunnel route dns goldenhour api.goldenhour.example.com
cloudflared service install   # run as launchd on Mac Mini
```

Validate with a temporary tunnel first: `cloudflared tunnel --url http://localhost:8082`

Documentation in `DEPLOYMENT.md` covers full setup including health checks and DNS routing.

### 2. Docker Production Deployment ✓ BUILT

- Spring Boot backend containerised on Mac Mini; Cloudflare Tunnel exposes it publicly
- **Database**: H2 file database (same as local dev), volume-mounted to Mac filesystem (`/Users/gregochr/goldenhour-data`) so data persists and is covered by Time Machine. No PostgreSQL for initial prod — keep it simple.
- `eclipse-temurin:21-jre-alpine` base image; `--restart=always` for crash recovery and Mac Mini reboots
- HEALTHCHECK on `GET /actuator/health` (also used as Cloudflare Tunnel health probe)
- Secrets passed as env vars: `ANTHROPIC_API_KEY`, `JWT_SECRET`, `WORLDTIDES_API_KEY`
- **DB backup**: cron at 02:00 daily — `cp goldenhour.mv.db backups/goldenhour_$TIMESTAMP.mv.db`, keep last 7
- Resilience: `--restart=always` covers crashes; Mac Mini power loss requires manual restart (acceptable — low stakes)
- Production config `application-prod.yml` with Flyway migrations enabled, H2 dialect
- `Dockerfile` with multi-stage build, health checks, non-root user for security
- `docker-compose.yml` with volumes, environment variables, restart policy
- `goldenhour-backup.sh` automated backup script (keeps last 7 backups)

### 3. Backend Health Status Indicator ✓ Built

- Small green/red dot in app header, ADMIN only
- Polls `/actuator/health` every 30 seconds
- Shows UP / DOWN — no auto-reconnect logic

### 4. Tide Data ✓ Built

WorldTides API (v3) for coastal locations. `TideService.fetchAndStoreTideExtremes` fetches 14 days of high/low extremes weekly and stores in `tide_extreme` (FK to `locations`). `deriveTideData` looks up from DB at evaluation time — no live API call per forecast.

`ScheduledForecastService` runs a weekly refresh (Monday 02:00 UTC, configurable via `tide.schedule.cron`). `LocationService.@PostConstruct` triggers immediate fetch when a new coastal location is first seeded. API key via `WORLDTIDES_API_KEY` env var.

### 5. Wildlife Location UI ✓ Built

Pure-WILDLIFE locations get hourly comfort rows (one per UTC hour, sunrise–sunset) via a single Open-Meteo call — no Claude evaluation. Green 🐾 marker on map; popup shows time · temp · wind · rain timeline. Colour locations (LANDSCAPE/SEASCAPE/mixed) keep score bars and also show comfort data below them. `V19` migration adds temperature, apparent temperature, and precipitation probability columns to `forecast_evaluation`.


Backend generates a `practicalWeather.summary` via Claude (short comfort-focused prompt). Frontend renders as a simple card — no star rating, no cloud bars.

### 6. Prediction Accuracy Feedback

Structured post-event feedback attributed to the logged-in user.

`prediction_feedback` table: `id`, `forecast_evaluation_id` (FK), `user_id` (FK), `accuracy` (ACCURATE / SLIGHTLY_OFF / VERY_INACCURATE), `notes`, `recorded_at`.

One feedback record per user per evaluation (upsert on re-submit).

Endpoints:
- `POST /api/feedback` — submit (USER+)
- `GET /api/feedback?evaluationId=` — get for one evaluation
- `GET /api/feedback/summary` — aggregate stats (ADMIN)

Admin Manage tab shows accuracy breakdown by model and by days_ahead.

Privacy notice shown once on first feedback submission (stored in localStorage).

### 7. Web Push Notifications

Replace Pushover with Web Push (W3C Push API + VAPID). No third-party app required for users, free, cross-platform, and feels native on desktop and Android. Delivers forecast alerts directly to the browser/OS notification centre.

**iOS caveat**: background push is only available when the site is added to the Home Screen via Safari's share button → "Add to Home Screen". Users on iOS Safari in a regular browser tab cannot receive push. Show a one-time guidance prompt explaining this during the permission flow.

**Backend — `webpush-java` library** (`nl.martijndwars:web-push`):
- Generate a VAPID key pair once (`VAPIDKeyPairs.generate()`) and store in config as `web-push.vapid-public-key` and `web-push.vapid-private-key` (base64url encoded)
- `PushSubscriptionEntity` — stores `endpoint`, `p256dh` (public key), `auth` (auth secret), `userId` FK, `createdAt`; V15 migration
- `WebPushService.send(PushSubscriptionEntity, String payload)` — builds a `Notification` and calls `PushService.send()`; catches `410 Gone` to auto-delete stale subscriptions
- `PushSubscriptionController`: `POST /api/push/subscribe` (save), `DELETE /api/push/subscribe` (unsubscribe), `GET /api/push/vapid-public-key` (returns public key to frontend, no auth required)
- `ForecastService` calls `WebPushService` after each evaluation alongside the existing notification channels

**Frontend**:
- `public/sw.js` — service worker that handles `push` events and calls `self.registration.showNotification()`
- Register service worker on app load (`navigator.serviceWorker.register('/sw.js')`)
- `NotificationPermission.jsx` — shown once; calls `Notification.requestPermission()`, then `PushManager.subscribe()` with the VAPID public key; shows iOS "Add to Home Screen" guidance if `navigator.standalone === false` on iOS
- Send the resulting `PushSubscription` JSON to `POST /api/push/subscribe`

**Config** (`application.yml`):
```yaml
web-push:
  vapid-public-key: ${VAPID_PUBLIC_KEY}
  vapid-private-key: ${VAPID_PRIVATE_KEY}
  subject: mailto:admin@goldenhour.example.com
```

Generate keys once: `openssl ecparam -name prime256v1 -genkey -noout -out vapid_private.pem`

### 8. Job Run Metrics ✓ BUILT

Track performance of scheduled forecast runs and external API calls, stored in H2.

- `job_run` table: `id`, `run_type` (VERY_SHORT_TERM/SHORT_TERM/LONG_TERM/WEATHER/TIDE), `evaluation_model` (HAIKU/SONNET/OPUS/WILDLIFE, nullable), `started_at`, `completed_at`, `duration_ms`, `locations_processed`, `succeeded`, `failed`, `total_cost_pence`, `total_cost_micro_dollars`, `exchange_rate_gbp_per_usd`
- `api_call_log` table: `id`, `job_run_id` (FK), `service` (OPEN_METEO_FORECAST/OPEN_METEO_AIR_QUALITY/WORLD_TIDES/ANTHROPIC), `called_at`, `completed_at`, `duration_ms`, `request_method`, `request_url`, `request_body`, `status_code`, `response_body`, `succeeded`, `error_message`, `cost_pence`, `cost_micro_dollars`, `input_tokens`, `output_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`, `is_batch`
- `exchange_rate` table: `id`, `rate_date` (unique), `gbp_per_usd`, `fetched_at` — daily USD-to-GBP rate cache from Frankfurter API
- `JobRunService` records run metrics (start/complete with RunType + EvaluationModel); `logAnthropicApiCall()` extracts token usage and computes micro-dollar costs
- `CostCalculator` computes token-based micro-dollar costs per model (Haiku/Sonnet/Opus rates) with cache and batch discount; also flat micro-dollar costs for non-Anthropic services
- `ExchangeRateService` fetches/caches daily USD-to-GBP from Frankfurter API (ECB data); fallback to most recent cached rate
- `JobMetricsController` endpoints: `GET /api/metrics/job-runs`, `GET /api/metrics/api-calls`
- Admin Manage tab → "Job Runs" tab: shows last N job runs with per-service breakdown, 7-day summary stats, costs in both GBP and USD, per-call token breakdown
- Flyway migrations: V20 (job_run + api_call_log tables), V21 (location failure tracking), V38 (token columns, micro-dollar costs, exchange rate)
- Frontend components: `JobRunsMetricsView`, `JobRunsGrid`, `JobRunDetail`, `MetricsSummary`; `formatCost.js` utility for GBP/USD formatting
- 569 backend tests, all passing with ≥80% JaCoCo coverage

### 9. Retry Robustness ✓ BUILT

Each scheduled job run is resilient — a 429 or transient 5xx on one location/slot does not abort the entire run. All retry logic uses Spring Framework 7's declarative `@Retryable` annotation (via `@EnableResilientMethods`).

- **Open-Meteo**: `@Retryable` on `OpenMeteoClient.fetchForecast()` / `fetchAirQuality()` with `TransientHttpErrorPredicate` (retries 5xx/429, 2 retries, 5s base delay)
- **Anthropic**: `@Retryable` on `AnthropicApiClient.createMessage()` with `ClaudeRetryPredicate` (retries 529 overloaded + 400 content filter, 3 retries, 1s base, max 30s)
- **Concurrency**: `@ConcurrencyLimit(8)` on `ForecastService.runForecasts()` caps parallel evaluations (replaces thread pool sizing)
- **Per-location failure isolation**: `ForecastCommandExecutor` catches exceptions per location
  - Failures logged to `job_run` and `api_call_log` metrics with error messages
  - `ForecastService` logs API calls with status codes and error details
- **Dead-letter mechanism**: locations auto-disabled after 3 consecutive forecast failures (V21)
  - `consecutive_failures` counter on location entity (reset to 0 on success)
  - `last_failure_at` timestamp tracks most recent failure
  - `disabled_reason` field explains why location was auto-disabled
  - Frontend could show disabled location badge in ManageView (future UI enhancement)
- **Request/response logging**: `RequestLoggingInterceptor` logs all `/api/**` endpoints at INFO level
  - Captures method, URL, status code, duration for debugging and auditing

### 10. macOS Menu Bar Widget (Tauri)

Separate Tauri app reusing React components from `frontend/src/components/`. Menu bar icon shows today's best rating. Click expands T through T+7. Native macOS toast notifications (replaces osascript).

Keep React components decoupled from browser-specific APIs to ease Tauri reuse.

---

## Code Standards

### Backend
- Checkstyle: Javadoc on all public classes/methods, no unused imports, 4-space indent, 120-char line limit
- SpotBugs: medium threshold — all medium/high bugs must be resolved
- No business logic in controllers; no magic numbers; all external API errors handled gracefully

### Frontend
- ESLint + Prettier
- `data-testid` on all key UI elements
- No inline styles — Tailwind only
- PropTypes on all components

### Commits
Conventional commits: `feat:`, `fix:`, `chore:`, `test:`, `docs:`, `refactor:`

---

## Testing

```bash
cd backend && ./mvnw clean verify     # 646 tests, JaCoCo ≥ 80%
cd frontend && npm run test           # 321 Vitest component tests
cd frontend && npm run test:e2e       # Playwright (requires app running)
```

---

## Git Conventions

- Never commit `application.yml` — only `application-example.yml`
- `.gitignore`: `application.yml`, `*.env`, `node_modules/`, `target/`, `backend/data/`, `.claude/`
- Branch naming: `feature/`, `fix/`, `chore/`
- Update `CHANGELOG.md` on every meaningful commit

---

## solar-utils

Shared library on GitHub Packages. Pulled automatically by Maven.
One-time setup: create a GitHub token with `read:packages` scope and add to `~/.m2/settings.xml`.

Public API (v1.2.0): `sunrise`, `sunset`, `civilDawn`, `civilDusk`, `solarNoon`, `dayLengthMinutes`, `sunriseAzimuth`, `sunsetAzimuth`.
