# CLAUDE.md — The Photographer's Golden Hour

## Project Overview

A full-stack app that evaluates sunrise/sunset colour potential at configured locations.

- **Backend**: Spring Boot 4 REST API — Open-Meteo weather + air quality, WorldTides tide data, Claude (Anthropic SDK) evaluation, H2 persistence, scheduled runs, notifications (email/Pushover/macOS toast).
- **Frontend**: React 19 + Vite + Tailwind — map view (Leaflet), forecast timeline, outcome recording, JWT-authenticated.

---

## What's Built

**Core pipeline**: Open-Meteo → Claude → H2 | Scheduled evaluations (06:00 + 18:00 UTC, T through T+7) | Notifications (email, Pushover, macOS toast)

**Scoring**: Two scores (Fiery Sky 0–100, Golden Hour 0–100) + 1–5 star rating | Dual-tier: enhanced (directional cloud) for PRO/ADMIN, basic (observer-point) for LITE | 3-point cone cloud sampling at 113 km offset (azimuth ±15°) via `GeoUtils` + `DirectionalCloudData` | Far-field sample at 226 km for horizon strip vs blanket detection | Cloud approach risk detection (`CloudApproachData`, `SolarCloudTrend`, `UpwindCloudSample`) | Solar-aware slot selection (`findBestIndex()`) | Sahara Dust badge (AOD > 0.3 or dust > 50 µg/m³ with PM2.5 < 35) | Rising tide warning badge (high tide within ±90 min of solar event)

**Evaluation**: Single `ClaudeEvaluationStrategy` parameterised by `EvaluationModel` | `PromptBuilder` + `MetricsLoggingDecorator` (GoF Decorator) | `NoOpEvaluationStrategy` for wildlife | `AnthropicApiClient` with `@Retryable` | Composable `AtmosphericData` (5 sub-records + `DirectionalCloudData` + `CloudApproachData`)

**Aurora photography**: AuroraWatch polling (`AuroraPollingJob`, 15 min, night-only) | `AuroraStateCache` FSM (IDLE → MONITORING → AMBER → RED) | `AuroraScorer` (1–5★ from alert level + cloud + moon + Bortle) | `AuroraTransectFetcher` (3-point northward cloud transect) | Bortle enrichment via lightpollutionmap.info (`LightPollutionClient`, `BortleEnrichmentService`) | Map filter + popup aurora section | `AuroraBanner` React component

**Command pattern**: `ForecastCommand` → `ForecastCommandFactory` → `ForecastCommandExecutor` | `RunType` enum (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM, WEATHER, TIDE, LIGHT_POLLUTION) | Per-run-type model config (Haiku/Sonnet/Opus via Admin UI)

**Optimisation strategies**: 7 toggleable strategies (SKIP_LOW_RATED, SKIP_EXISTING, FORCE_IMMINENT, FORCE_STALE, EVALUATE_ALL, NEXT_EVENT_ONLY, TIDE_ALIGNMENT) per run type | `OptimisationSkipEvaluator` | Mutual exclusion validation | Active strategies snapshot on each `job_run`

**Cost tracking**: Token-based micro-dollar pricing from Anthropic SDK | `ExchangeRateService` (Frankfurter API, ECB data) | `CostCalculator` with cache/batch discount | Frontend shows GBP + USD costs

**Job metrics**: `job_run` + `api_call_log` tables | Admin dashboard (7-day stats, per-service breakdown) | Per-location failure tracking, auto-disable after 3 failures

**Resilience**: `@Retryable` on `AnthropicApiClient` (529/content filter) and `OpenMeteoClient` (5xx/429) | `@ConcurrencyLimit(8)` | Dead-letter mechanism | `RequestLoggingInterceptor`

**Locations**: Multi-location with map view (Leaflet/OSM) | Metadata: `SolarEventType`, `TideType` (H/M/L multi-select), `LocationType` (LANDSCAPE/WILDLIFE/SEASCAPE/WATERFALL) | Regions (geographic grouping) | Sunrise/sunset azimuth lines | Marker clustering (`react-leaflet-cluster`) | Star rating + location type filters | Emoji chip UI for metadata | Editable lat/lon

**Tide data**: WorldTides API, weekly refresh, `tide_extreme` table | `TideService` derives state/next tides from DB at evaluation time | Tide history preservation (windowed merge, not delete-all) | 12-month backfill capability | Tide stats endpoint (avg/max high, avg/min low) | SEASCAPE-filtered refresh

**Wildlife UI**: Hourly comfort rows (temp/wind/rain) between sunrise–sunset | Green 🐾 marker | No Claude call

**Waterfall UI**: Colour forecast AND hourly comfort rows | 💦 marker | Scores excluded from cluster marker averages (waterfall photography ≠ sky colour)

**Auth**: JWT (HMAC-SHA256, 24h access, 30-day refresh) | ADMIN / PRO_USER / LITE_USER roles | Self-registration with email verification + Turnstile CAPTCHA | First-login password change gate | Session expiry warnings | Marketing email opt-in

**Admin features**: User management | Backend health indicator (green/red dot) | Model comparison test harness (A/B/C across regions) | Prompt test harness (async, replay, comparison) | URL hash navigation | Client-side pagination

**Deployment**: Docker (alpine, health checks, non-root) | Cloudflare Tunnel (`photocast.online`) | H2 volume-mounted to Mac filesystem | Daily backups (keep last 7)

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
│       └── db/migration/            V1–V55 Flyway migrations
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

# Run all tests
cd backend && ./mvnw clean verify

# Prompt regression tests (requires ANTHROPIC_API_KEY)
cd backend && ANTHROPIC_API_KEY=... ./mvnw test -Pprompt-regression

# Frontend tests
cd frontend && npm run test
```

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
- **Aerosol proxy** — AOD + PM2.5: high AOD + low PM2.5 = dust (warm tones ✓); high AOD + high PM2.5 = smoke (haze ✗). No competitor does this.
- **Virtual threads** — `spring.threads.virtual.enabled: true`; `forecastExecutor` uses `newVirtualThreadPerTaskExecutor()`.
- **RestClient** — synchronous `RestClient` everywhere (no WebFlux). Open-Meteo via `@HttpExchange` + `HttpServiceProxyFactory`.
- **Declarative resilience** — `@EnableResilientMethods`; `@Retryable` with `MethodRetryPredicate` implementations; `@ConcurrencyLimit(8)`.
- **Location metadata** — production locations DB-managed via Admin UI (no YAML seeding). `application-local.yml` has locations for local dev.
- **JWT** — stateless HMAC-SHA256; refresh token stored hashed (SHA-256) in `refresh_token` table.
- **Freemium UI** — breadcrumbs not paywalls. See `docs/product/freemium_ui_strategy.md`.

---

## Configuration

Never commit `application.yml`. Only `application-example.yml` is committed.

Key config: `anthropic`, `worldtides`, `spring.datasource`, `spring.flyway`, `spring.mail`, `notifications`, `forecast.locations`, `jwt`, `server.port`, `aurora` (enabled, poll-interval-minutes, light-pollution-api-key, bortle-threshold.amber/red).

---

## Database Migrations (V1–V55)

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
| V55 | bortle_class column on locations (nullable integer) |

---

## Roles

| Role | Permissions |
|------|-------------|
| `ADMIN` | All endpoints + Manage tab |
| `PRO_USER` | Forecast, outcomes, locations, re-runs |
| `LITE_USER` | Read-only forecast and outcomes |

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
`GET /api/metrics/job-runs|api-calls` | `GET|PUT /api/models` | `PUT /api/models/active|optimisation` | `POST /api/model-test/run|run-location|rerun` | `GET /api/model-test/runs|results` | `POST /api/prompt-test/run|replay` | `GET /api/prompt-test/runs|runs/{id}|results|git-info`

### Aurora (Bearer / ADMIN for writes)
`GET /api/aurora/status` (Bearer) | `GET /api/aurora/locations` (Bearer) | `POST /api/aurora/admin/enrich-bortle` (ADMIN) | `POST /api/aurora/admin/reset` (ADMIN)

### Tides (ADMIN)
`GET /api/tides` | `GET /api/tides/stats`

### Push (mixed auth)
`GET /api/push/vapid-public-key` (none) | `POST|DELETE /api/push/subscribe` (Bearer)

---

## Product Strategy

See `docs/product/` for detailed reference documents.

**Differentiators**: Claude-generated "why" explanation | AOD+PM2.5 aerosol proxy | Cloud approach risk detection (temporal trend + upwind sampling) | Location type-specific UI | Outcome recording feedback loop

**Freemium split**: LITE gets basic scores, 3-day horizon, 1 location, blurred metrics. PRO gets enhanced directional scores, 7-day horizon, unlimited locations, full metrics.

---

## Planned Features

- **Prediction accuracy feedback** — structured post-event feedback per user per evaluation (ACCURATE/SLIGHTLY_OFF/VERY_INACCURATE), admin breakdown by model and days_ahead
- **Web Push notifications** — replace Pushover with W3C Push API + VAPID (`webpush-java`), service worker, iOS Home Screen caveat
- **macOS menu bar widget** — Tauri app reusing React components, menu bar icon with best rating
- **Tide strength indicator** — flag "very high" tides on the map popup using two signals: (1) lunar phase (spring tides near new/full moon, ~1-2 day lag) and (2) historical percentile from accumulated `tide_extreme` data (top ~20% of a location's highs). Lunar calculation may belong in `solar-utils`. Consider "King tide" label for equinoctial springs. (Partially built: rising tide badge, tide stats endpoint, and history accumulation are done; percentile and lunar signals remain.)

---

## Code Standards

### Backend
- Checkstyle: Javadoc on public classes/methods, no unused imports, 4-space indent, 120-char lines
- SpotBugs: medium threshold
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

## solar-utils

Shared library on GitHub Packages (`~/.m2/settings.xml` needs `read:packages` token).

Public API (v1.2.0): `sunrise`, `sunset`, `civilDawn`, `civilDusk`, `solarNoon`, `dayLengthMinutes`, `sunriseAzimuth`, `sunsetAzimuth`.
