# PhotoCast

AI-driven sunrise and sunset forecasting for landscape, wildlife, and coastal photographers. Pulls atmospheric and air quality data from Open-Meteo and CAMS, evaluates it with Claude, and returns dual colour-potential scores with plain-English reasoning — updated every 6-12 hours for up to 7 days ahead.

**Live:** [https://app.photocast.online](https://app.photocast.online)

[![CI](https://github.com/gregochr/goldenhour/actions/workflows/ci.yml/badge.svg)](https://github.com/gregochr/goldenhour/actions/workflows/ci.yml)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-yellow?logo=buy-me-a-coffee)](https://buymeacoffee.com/gregorychris)

---

## What it does

- Evaluates sunrise and sunset colour potential for any number of locations, T through T+7
- Two scores per event: **Fiery Sky Potential** (dramatic colour) and **Golden Hour Potential** (overall light quality), plus a 1-5 star rating
- Claude generates a plain-English explanation of the key factors driving the score
- Aerosol optical depth + PM2.5 proxy distinguishes warm dust from grey smoke — a competitive differentiator
- Location types: **Landscape** (colour scores), **Wildlife** (hourly comfort timeline, no AI cost), **Seascape** (scores + tide alignment)
- Per-run-type model configuration — three independent configs (Very Short-Term, Short-Term, Long-Term), each selectable as Haiku/Sonnet/Opus via Admin UI
- Opus optimisation gate — very-short-term runs skip slots with prior rating < 3 stars, avoiding wasted spend
- Model comparison test harness — A/B/C runs Haiku, Sonnet, and Opus against identical data for side-by-side evaluation
- Stores every evaluation so you can track how the forecast converges as the date approaches
- Outcome recording — log whether you went out and your actual rating; builds an accuracy feedback loop
- Self-registration with email verification and Cloudflare Turnstile CAPTCHA
- Marketing email opt-in preference (UK soft opt-in compliant)
- JWT-authenticated with ADMIN / PRO_USER / LITE_USER roles
- Notifications via email (Thymeleaf HTML templates) and Web Push (VAPID)
- Job run metrics with cost tracking and per-location failure detection
- Resilient API calls with exponential backoff retry and dead-letter mechanism for persistent failures
- Geographic regions for location grouping
- Backend health indicator in header (ADMIN), session expiry warnings

---

## Tech stack

| Layer | Technology |
|---|---|
| API | Spring Boot 3, Spring WebFlux (WebClient) |
| AI evaluation | Claude (Haiku / Sonnet / Opus) via Anthropic Java SDK |
| Solar times | [solar-utils](https://github.com/gregochr/solar-utils) v1.2.0 (GitHub Packages) |
| Weather data | Open-Meteo Forecast + Air Quality / CAMS APIs (free, no key) |
| Tide data | WorldTides API v3 (coastal locations, weekly refresh) |
| Database | H2 file database + Flyway migrations (V1-V35) |
| Security | Spring Security 6, stateless JWT (JJWT 0.12.6), Cloudflare Turnstile |
| Frontend | React 18, Vite, Tailwind CSS v4, Leaflet |
| Deployment | Docker + Cloudflare Tunnel (no open router ports) |
| Quality | Checkstyle, SpotBugs, JaCoCo (>=80%), ESLint, Vitest, Playwright |

---

## Prerequisites

- Java 21
- Node 20
- A GitHub personal access token with `read:packages` scope (for `solar-utils`)
- An Anthropic API key
- Docker (for production — not required for local dev)

---

## One-time setup

### 1. GitHub Packages token (for solar-utils)

```bash
cp settings.xml.example ~/.m2/settings.xml
# Edit ~/.m2/settings.xml and replace YOUR_GITHUB_TOKEN
```

Create a token at [github.com/settings/tokens](https://github.com/settings/tokens) with `read:packages` scope.

### 2. Configure the backend

```bash
cp backend/src/main/resources/application-example.yml backend/src/main/resources/application.yml
# Edit application.yml — set your Anthropic API key, JWT secret, and notification settings
```

---

## Running locally (no Docker)

The `local` Spring profile uses a file-based H2 database — no Docker or external DB required.

```bash
# Terminal 1 — backend
export ANTHROPIC_API_KEY=sk-ant-...
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Terminal 2 — frontend
cd frontend && npm install && npm run dev
```

- UI: [http://localhost:5173](http://localhost:5173)
- API: [http://localhost:8082](http://localhost:8082)
- H2 console: [http://localhost:8082/h2-console](http://localhost:8082/h2-console) (JDBC: `jdbc:h2:file:./data/goldenhour`, user: `sa`, password: empty)

Trigger an initial forecast run:

```bash
TOKEN=$(curl -s -X POST http://localhost:8082/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"golden2026"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

curl -X POST http://localhost:8082/api/forecast/run \
  -H "Authorization: Bearer $TOKEN"
```

---

## Running with Docker (production)

The production stack runs both services as Docker containers with H2 persisted to the Mac filesystem and exposed publicly via Cloudflare Tunnel.

```bash
# Build and start
docker compose build --no-cache
docker compose up -d

# Check health
curl http://localhost:8082/actuator/health
```

Secrets are passed as environment variables — see `docker-compose.yml` for the full list (`ANTHROPIC_API_KEY`, `JWT_SECRET`, `WORLDTIDES_API_KEY`, etc.).

The H2 database is volume-mounted to `/Users/gregochr/goldenhour-data` so data persists across container restarts and is covered by Time Machine. Automated daily backups at 02:00 keep the last 7 copies.

---

## Authentication

All API endpoints except `/api/auth/**` require a valid JWT.

**Default admin credentials:** `admin` / `golden2026`

> Change the admin password immediately after first login.

Users can self-register at the login page — email verification is required, and Cloudflare Turnstile protects against bots. Admins can also create users directly.

### Roles

| Role | Access |
|---|---|
| `ADMIN` | All endpoints + Manage tab (users, regions, job metrics, model selection, model test) |
| `PRO_USER` | 7-day forecast, all scores, unlimited locations, outcome recording |
| `LITE_USER` | 3-day forecast, star rating only, 1 location |

---

## Key API endpoints

### Auth

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/auth/login` | None | Exchange credentials for access + refresh tokens |
| `POST` | `/api/auth/refresh` | Refresh token | Issue a new access token (rotates refresh token) |
| `POST` | `/api/auth/logout` | Bearer | Revoke refresh token |
| `POST` | `/api/auth/change-password` | Bearer | Change own password |
| `POST` | `/api/auth/register` | None | Self-register with email verification |
| `POST` | `/api/auth/resend-verification` | None | Resend verification email (rate-limited) |
| `POST` | `/api/auth/verify-email` | None | Verify email with token from link |
| `POST` | `/api/auth/set-password` | None | Set password for verified user (auto-login) |
| `PUT` | `/api/auth/marketing-emails` | Bearer | Toggle marketing email opt-in |

### Forecast

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/forecast` | Bearer | T through T+7 for all locations |
| `POST` | `/api/forecast/run` | ADMIN | On-demand short-term evaluation run |
| `POST` | `/api/forecast/run/very-short-term` | ADMIN | Very short-term (T, T+1) |
| `POST` | `/api/forecast/run/short-term` | ADMIN | Short-term (T, T+1, T+2) |
| `POST` | `/api/forecast/run/long-term` | ADMIN | Long-term (T+3 through T+7) |

### Locations, regions & outcomes

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/locations` | Bearer | All persisted locations |
| `POST` | `/api/locations` | Bearer | Add a new location |
| `PUT` | `/api/locations/{name}/reset-failures` | ADMIN | Re-enable an auto-disabled location |
| `GET` | `/api/regions` | Bearer | All regions |
| `POST` | `/api/regions` | ADMIN | Create a region |
| `PUT` | `/api/regions/{id}` | ADMIN | Update a region |
| `PUT` | `/api/regions/{id}/enabled` | ADMIN | Enable/disable a region |
| `POST` | `/api/outcome` | Bearer | Record an actual observed outcome |
| `GET` | `/api/outcome` | Bearer | Query outcomes by location and date range |

### User management (ADMIN only)

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/users` | ADMIN | List all users |
| `POST` | `/api/users` | ADMIN | Create a user |
| `PUT` | `/api/users/{id}/enabled` | ADMIN | Enable or disable a user |
| `PUT` | `/api/users/{id}/role` | ADMIN | Change a user's role |
| `PUT` | `/api/users/{id}/reset-password` | ADMIN | Generate a temporary password |
| `DELETE` | `/api/users/{id}` | ADMIN | Delete a user (sends notification email) |

### Metrics & model testing (ADMIN only)

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/metrics/job-runs` | ADMIN | Pageable list of scheduled job runs |
| `GET` | `/api/metrics/api-calls` | ADMIN | API call log with costs and timings |
| `GET` | `/api/models` | Bearer | Available models and per-run-type active models |
| `PUT` | `/api/models/active` | ADMIN | Set active model for a run type |
| `POST` | `/api/model-test/run` | ADMIN | Trigger A/B/C model comparison test |
| `GET` | `/api/model-test/runs` | ADMIN | Recent model test runs |
| `GET` | `/api/model-test/results` | ADMIN | Results for a specific test run |

---

## Running tests

```bash
# Backend (JUnit 5, 479 tests, >=80% coverage enforced by JaCoCo)
cd backend && ./mvnw clean verify

# Frontend component tests (Vitest, 57 tests)
cd frontend && npm test

# Frontend end-to-end (Playwright — requires app running)
cd frontend && npm run test:e2e
```

---

## solar-utils dependency

[solar-utils](https://github.com/gregochr/solar-utils) is a shared library published to GitHub Packages. It is pulled automatically by Maven — no manual build required. See the one-time setup section above for the token requirement.

Public API (v1.2.0): `sunrise`, `sunset`, `civilDawn`, `civilDusk`, `solarNoon`, `dayLengthMinutes`, `sunriseAzimuth`, `sunsetAzimuth`.

---

## Made with ☕ by Chris

[Buy me a coffee](https://buymeacoffee.com/gregorychris) · [GitHub Sponsors](https://github.com/sponsors/gregochr)
