# CLAUDE.md — The Photographer's Golden Hour

## Project Overview

A full-stack app that evaluates sunrise/sunset colour potential at configured locations.

- **Backend**: Spring Boot 3 REST API — Open-Meteo weather + air quality, Claude (Anthropic SDK) evaluation, PostgreSQL persistence, scheduled runs, email/Pushover/macOS toast notifications.
- **Frontend**: React 18 + Vite + Tailwind — map view (Leaflet), forecast timeline, outcome recording, JWT-authenticated.
- **Future**: macOS menu bar widget (Tauri).

---

## What's Built

- Core forecast pipeline: Open-Meteo → Claude → PostgreSQL
- Scheduled evaluations (06:00 + 18:00 UTC, T through T+7)
- Notifications: email (Thymeleaf HTML), Pushover, macOS toast
- Outcome recording (`actual_outcome` table, UI form)
- Multi-location support with map view (Leaflet/OpenStreetMap)
- Location metadata: `goldenHourType` (SUNRISE/SUNSET/BOTH_TIMES/ANYTIME), `tideType` (HIGH_TIDE/LOW_TIDE/ANY_TIDE/MID_TIDE/NOT_COASTAL), `locationType` (LANDSCAPE/WILDLIFE/SEASCAPE)
- Sunrise/sunset azimuth lines on map
- Evaluation strategy pattern: `@Profile("lite")` = Haiku, default = Sonnet
- JWT authentication: ADMIN / PRO_USER / LITE_USER roles
- User management (ADMIN-only Manage tab)
- First-login password change gate with complexity enforcement

---

## Monorepo Structure

```
goldenhour/
├── backend/               Spring Boot 3 app (port 8082 local)
│   ├── src/main/java/com/gregochr/goldenhour/
│   │   ├── config/        SecurityConfig, JwtAuthenticationFilter, JwtProperties, AppConfig
│   │   ├── controller/    ForecastController, OutcomeController, LocationController, AuthController, UserController
│   │   ├── entity/        ForecastEvaluationEntity, ActualOutcomeEntity, LocationEntity, AppUserEntity, RefreshTokenEntity, UserRole, GoldenHourType, TideType, LocationType
│   │   ├── repository/    all Spring Data repos
│   │   ├── service/       ForecastService, OpenMeteoService, SolarService, EvaluationService, LocationService, OutcomeService, JwtService, UserService, ScheduledForecastService, notification/
│   │   └── model/         AtmosphericData, SunsetEvaluation, etc.
│   └── src/main/resources/
│       ├── application.yml          (gitignored — never commit)
│       ├── application-example.yml  (committed — placeholders)
│       ├── application-local.yml    (H2 local dev profile)
│       └── db/migration/            V1–V12 Flyway migrations
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

---

## Configuration

Never commit `application.yml`. Only `application-example.yml` is committed.

Key config sections: `anthropic`, `spring.datasource`, `spring.flyway`, `spring.mail`, `notifications`, `forecast.locations`, `jwt`, `server.port`.

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

---

## Planned Features

### 1. Token Expiry Warnings

- At 7 days remaining on access token: subtle amber banner
- At 1 day remaining: more prominent warning
- "Refresh session" button — exchanges refresh token for new access token without re-login
- Frontend checks token `exp` on mount and after each API call

### 2. Cloudflare Tunnel

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

### 3. Docker Production Deployment

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/goldenhour-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`docker-compose.prod.yml` runs backend + postgres + grafana. Secrets in `.env` (gitignored).

Spring Boot Actuator health endpoint: `GET /actuator/health` (used as Cloudflare Tunnel health probe).

### 4. Backend Health Status Indicator

- Small green/red dot in app header, ADMIN only
- Polls `/actuator/health` every 30 seconds
- Shows UP / DOWN — no auto-reconnect logic

### 5. Tide Data

For locations where `tideType != NOT_COASTAL`. Use NOAA or UK Admiralty API for tide times; Open-Meteo Marine for wave height.

`TideService`:
- `getTideState(location, dateTime)` → RISING / FALLING / HIGH / LOW
- `getNextHighTide(location, date)` / `getNextLowTide(location, date)`

Include in forecast response when location is coastal:
```json
{
  "tideState": "FALLING",
  "nextLowTideTime": "...",
  "nextLowTideHeightMetres": 0.4,
  "tideAligned": true
}
```

`tideAligned = true` when tide state at solar event time matches location's `tideType` preference.

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

### 7. macOS Menu Bar Widget (Tauri)

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
cd backend && ./mvnw clean verify     # 148 tests, JaCoCo ≥ 80%
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
