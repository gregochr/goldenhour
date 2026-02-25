# CLAUDE.md — The Photographer's Golden Hour

## Project Overview

A full-stack app that evaluates sunrise/sunset colour potential at configured locations.

- **Backend**: Spring Boot 3 REST API — Open-Meteo weather + air quality, WorldTides tide data, Claude (Anthropic SDK) evaluation, PostgreSQL persistence, scheduled runs, email/Web Push/macOS toast notifications.
- **Frontend**: React 18 + Vite + Tailwind — map view (Leaflet), forecast timeline, outcome recording, JWT-authenticated.
- **Future**: macOS menu bar widget (Tauri).

---

## What's Built

- Core forecast pipeline: Open-Meteo → Claude → PostgreSQL
- Scheduled evaluations (06:00 + 18:00 UTC, T through T+7)
- Notifications: email (Thymeleaf HTML), Pushover, macOS toast
- Tide data: WorldTides API, weekly refresh per coastal location, `tide_extreme` table (V14), `TideService` derives state/next tides from DB at evaluation time
- Outcome recording (`actual_outcome` table, UI form)
- Multi-location support with map view (Leaflet/OpenStreetMap)
- Location metadata: `goldenHourType` (SUNRISE/SUNSET/BOTH_TIMES/ANYTIME), `tideType` (HIGH_TIDE/LOW_TIDE/ANY_TIDE/MID_TIDE/NOT_COASTAL), `locationType` (LANDSCAPE/WILDLIFE/SEASCAPE)
- Sunrise/sunset azimuth lines on map
- Dual evaluation model: HAIKU (1–5 rating, every 12 h) for LITE, SONNET (0–100 dual scores, every 6 h) for PRO/ADMIN
- Wildlife location UI: pure-WILDLIFE locations get hourly comfort rows (temp/wind/rain) between sunrise and sunset, green 🦅 marker; no Claude call
- Comfort fields (temperature, apparent temperature, precipitation probability) stored on every forecast row
- JWT authentication: ADMIN / PRO_USER / LITE_USER roles
- User management (ADMIN-only Manage tab)
- First-login password change gate with complexity enforcement
- Session expiry warnings (amber ≤7 days, red ≤1 day); refresh token rotates on every `/refresh` call
- Session countdown (`Session: 30d`) in header for ADMIN users
- Backend health indicator: green/red dot in header (ADMIN only), polls `/actuator/health` every 30 s

---

## Monorepo Structure

```
goldenhour/
├── backend/               Spring Boot 3 app (port 8082 local)
│   ├── src/main/java/com/gregochr/goldenhour/
│   │   ├── config/        SecurityConfig, JwtAuthenticationFilter, JwtProperties, AppConfig
│   │   ├── controller/    ForecastController, OutcomeController, LocationController, AuthController, UserController
│   │   ├── entity/        ForecastEvaluationEntity, ActualOutcomeEntity, LocationEntity, AppUserEntity, RefreshTokenEntity, TideExtremeEntity, UserRole, GoldenHourType, TideType, TideExtremeType, LocationType, TideState
│   │   ├── repository/    all Spring Data repos
│   │   ├── service/       ForecastService, OpenMeteoService, SolarService, EvaluationService, LocationService, OutcomeService, JwtService, UserService, TideService, ScheduledForecastService, notification/
│   │   └── model/         AtmosphericData, SunsetEvaluation, etc.
│   └── src/main/resources/
│       ├── application.yml          (gitignored — never commit)
│       ├── application-example.yml  (committed — placeholders)
│       ├── application-local.yml    (H2 local dev profile)
│       └── db/migration/            V1–V19 Flyway migrations
├── frontend/              React 18 + Vite (port 5173)
│   └── src/
│       ├── api/           authApi.js, forecastApi.js (global axios interceptors)
│       ├── components/    LoginPage, ChangePasswordPage, ManageView, MapView, ForecastTimeline, ...
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
- **Evaluation strategy** — `EvaluationStrategy` interface; `@Profile("lite")` wires Haiku, default wires Sonnet.
- **JWT** — stateless HMAC-SHA256; 24 h access token, 30-day refresh token stored hashed (SHA-256) in `refresh_token` table.
- **CORS** — configured in `SecurityConfig` via `CorsConfigurationSource` bean; `allowedOriginPatterns` covers `localhost:*` and LAN subnets.
- **Location metadata** — YAML is source of truth; `LocationService.@PostConstruct` seeds and syncs to DB on every startup.
- **Layer inference** — cloud altitude layers are used as a proxy for directional cloud positioning. Low < 30% = solar horizon clear ✓; mid 30–60% = canvas above horizon ✓; high 20–60% = depth and texture ✓. True 5-point directional sampling deferred to v1.1. See `docs/product/directional_analysis_breakdown.md`.
- **Aerosol proxy** — AOD + PM2.5 together proxy aerosol type: high AOD + low PM2.5 = dust (warm tones ✓); high AOD + high PM2.5 = smoke (grey haze ✗). Implemented in evaluation prompt; no competitor does this.
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
| `GET` | `/api/forecast` | Bearer | T through T+7 for all locations |
| `POST` | `/api/forecast/run` | Bearer | Trigger on-demand evaluation |
| `GET` | `/api/forecast/compare` | Bearer | Compare Haiku vs Sonnet (dev only) |
| `GET` | `/api/outcome` | Bearer | Outcomes for date range |
| `POST` | `/api/outcome` | Bearer | Record actual outcome |
| `GET` | `/api/locations` | Bearer | All locations |
| `POST` | `/api/locations` | Bearer | Add location |
| `GET` | `/api/users` | ADMIN | List users |
| `POST` | `/api/users` | ADMIN | Create user |
| `PUT` | `/api/users/{id}/enabled` | ADMIN | Enable/disable user |
| `PUT` | `/api/users/{id}/role` | ADMIN | Change user role |
| `GET` | `/api/push/vapid-public-key` | None | Returns VAPID public key for frontend subscription |
| `POST` | `/api/push/subscribe` | Bearer | Save a push subscription |
| `DELETE` | `/api/push/subscribe` | Bearer | Remove a push subscription |

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

### Two Scores (Planned)

PhotoWeather distinguishes between two things that are genuinely different:
- **Fiery Sky Potential** — dramatic colour (requires clouds to catch light)
- **Golden Hour Potential** — overall light quality (can score high with clear sky)

These require different parameter weighting. Planned for a future evaluation update.

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

### 1. Cloudflare Tunnel

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

### 2. Docker Production Deployment

- Spring Boot backend containerised on Mac Mini; Cloudflare Tunnel exposes it publicly
- **Database**: H2 file database (same as local dev), volume-mounted to Mac filesystem (`/Users/gregochr/goldenhour-data`) so data persists and is covered by Time Machine. No PostgreSQL for initial prod — keep it simple.
- `eclipse-temurin:21-jre-alpine` base image; `--restart=always` for crash recovery and Mac Mini reboots
- HEALTHCHECK on `GET /actuator/health` (also used as Cloudflare Tunnel health probe)
- Secrets passed as env vars: `ANTHROPIC_API_KEY`, `JWT_SECRET`
- **DB backup**: cron at 02:00 daily — `cp goldenhour.mv.db backups/goldenhour_$TIMESTAMP.mv.db`, keep last 7
- Resilience: `--restart=always` covers crashes; Mac Mini power loss requires manual restart (acceptable — low stakes)

### 3. Backend Health Status Indicator ✓ Built

- Small green/red dot in app header, ADMIN only
- Polls `/actuator/health` every 30 seconds
- Shows UP / DOWN — no auto-reconnect logic

### 4. Tide Data ✓ Built

WorldTides API (v3) for coastal locations. `TideService.fetchAndStoreTideExtremes` fetches 14 days of high/low extremes weekly and stores in `tide_extreme` (FK to `locations`). `deriveTideData` looks up from DB at evaluation time — no live API call per forecast.

`ScheduledForecastService` runs a weekly refresh (Monday 02:00 UTC, configurable via `tide.schedule.cron`). `LocationService.@PostConstruct` triggers immediate fetch when a new coastal location is first seeded. API key via `WORLDTIDES_API_KEY` env var.

### 5. Wildlife Location UI ✓ Built

Pure-WILDLIFE locations get hourly comfort rows (one per UTC hour, sunrise–sunset) via a single Open-Meteo call — no Claude evaluation. Green 🦅 marker on map; popup shows time · temp · wind · rain timeline. Colour locations (LANDSCAPE/SEASCAPE/mixed) keep score bars and also show comfort data below them. `V19` migration adds temperature, apparent temperature, and precipitation probability columns to `forecast_evaluation`.


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

### 8. Job Run Metrics

Track performance of scheduled forecast runs and external API calls, stored in Postgres.

- `job_run` table: `id`, `job_name` (SONNET/HAIKU/WILDLIFE/TIDE), `started_at`, `completed_at`, `duration_ms`, `locations_total`, `succeeded`, `failed`
- `api_call_log` table: `id`, `job_run_id` (FK), `service` (OPEN_METEO_FORECAST/OPEN_METEO_AIR_QUALITY/WORLD_TIDES/ANTHROPIC), `called_at`, `duration_ms`, `status_code`, `succeeded`
- `JobRunService` wraps `ScheduledForecastService` and `TideService` calls, records timings
- Admin Manage tab: show last N job runs with per-service average response times and error rates
- Flyway migration: V20

### 9. Retry Robustness

Each scheduled job run should be resilient — a 429 or transient 5xx on one location/slot should not abort the entire run.

- Open-Meteo and WorldTides: already have `Retry.backoff(2, 5s)` for 5xx/429; verify WILDLIFE hourly path is covered
- Anthropic: add retry on 529 (overloaded) with exponential backoff
- Per-location failure isolation: already in `ScheduledForecastService` (try/catch per location); extend to log failure reason to `job_run` metrics
- Dead-letter concept: locations that fail 3 consecutive runs get flagged in `location` table (`consecutive_failures` counter, reset on success)

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
cd backend && ./mvnw clean verify     # 214 tests, JaCoCo ≥ 80%
cd frontend && npm run test           # Vitest component tests
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
