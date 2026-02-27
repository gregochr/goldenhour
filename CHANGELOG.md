# Changelog

All notable changes to Photo Cast are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added (Feb 27, 2026)
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
