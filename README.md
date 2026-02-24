# 🌅 Golden Hour

A full-stack application that predicts the colour potential of sunrise and sunset for landscape photographers. It pulls atmospheric and air quality data from Open-Meteo, passes it to Claude, and returns a 1–5 rating with plain English reasoning — updated twice daily for locations up to 7 days ahead.

[![CI](https://github.com/gregochr/goldenhour/actions/workflows/ci.yml/badge.svg)](https://github.com/gregochr/goldenhour/actions/workflows/ci.yml)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-yellow?logo=buy-me-a-coffee)](https://buymeacoffee.com/gregorychris)
[![GitHub Sponsors](https://img.shields.io/github/sponsors/gregochr?logo=github)](https://github.com/sponsors/gregochr)

---

## What it does

- Evaluates sunrise and sunset colour potential for any number of locations, T through T+7
- Rates each event 1–5 using cloud layers, visibility, humidity, aerosol optical depth, and boundary layer height
- Stores every evaluation so you can track how the forecast converges as the date approaches
- Lets you record actual outcomes (went out / actual rating / notes) alongside predictions
- Sends a daily digest at 07:30 via email, Pushover (iOS), and macOS toast
- JWT-authenticated — users are created by an admin; the Manage tab is ADMIN-only

---

## Tech stack

| Layer | Technology |
|---|---|
| API | Spring Boot 3, Spring WebFlux (WebClient) |
| AI evaluation | Claude via Anthropic Java SDK |
| Solar times | [solar-utils](https://github.com/gregochr/solar-utils) (GitHub Packages) |
| Weather data | Open-Meteo Forecast + Air Quality APIs (free, no key) |
| Database | PostgreSQL + Flyway migrations |
| Security | Spring Security 6, stateless JWT (JJWT 0.12.6) |
| Frontend | React 18, Vite, Tailwind CSS, Recharts, Leaflet |
| Dev database | H2 file (local profile — no Docker needed) |

---

## Prerequisites

- Java 21
- Node 20
- Docker (for PostgreSQL in production) — or use the local H2 profile for development
- A GitHub personal access token with `read:packages` scope (for `solar-utils`)
- An Anthropic API key

---

## One-time setup

### 1. GitHub Packages token (for solar-utils)

`solar-utils` is pulled from GitHub Packages. Maven needs a token even for public packages.

```bash
cp settings.xml.example ~/.m2/settings.xml
# Edit ~/.m2/settings.xml and replace YOUR_GITHUB_TOKEN
```

Create a token at [github.com/settings/tokens](https://github.com/settings/tokens) with `read:packages` scope.

### 2. Configure the backend

```bash
cp backend/src/main/resources/application-example.yml backend/src/main/resources/application.yml
# Edit application.yml — set your Anthropic API key, DB credentials, JWT secret, and notification settings
```

---

## Running locally (no Docker)

The `local` Spring profile swaps PostgreSQL for a file-based H2 database so you can run on a laptop without Docker.

```bash
# Terminal 1 — backend
export ANTHROPIC_API_KEY=sk-ant-...
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Terminal 2 — frontend
cd frontend && npm install && npm run dev
```

- API: [http://localhost:8082](http://localhost:8082)
- UI: [http://localhost:5173](http://localhost:5173)
- H2 console: [http://localhost:8082/h2-console](http://localhost:8082/h2-console) (JDBC URL: `jdbc:h2:file:./data/goldenhour`, user: `sa`, password: empty)

Trigger an initial forecast run (login first — see Authentication below):

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

Start PostgreSQL, then run the backend with the default (PostgreSQL) profile:

```bash
docker-compose up -d

export ANTHROPIC_API_KEY=sk-ant-...
cd backend && ./mvnw spring-boot:run

cd frontend && npm install && npm run dev
```

---

## Authentication

All API endpoints except `/api/auth/**` require a valid JWT. The default admin account is created by the V10 Flyway migration.

**Default credentials:** `admin` / `golden2026`

> Change the admin password immediately after first login via the UI (Profile → Change Password).

### Roles

| Role | Access |
|---|---|
| `ADMIN` | All endpoints + Manage tab (user management) |
| `USER` | Forecast, outcome recording, prediction feedback |

Users are created by an admin in the Manage tab — there is no self-registration.

---

## Key API endpoints

### Auth

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/auth/login` | None | Exchange credentials for access + refresh tokens |
| `POST` | `/api/auth/refresh` | Refresh token | Issue a new access token |
| `POST` | `/api/auth/logout` | Bearer | Revoke refresh token |
| `POST` | `/api/auth/change-password` | Bearer | Change own password |

### Forecast

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/forecast` | Bearer | T through T+7 for all locations |
| `GET` | `/api/forecast/history` | Bearer | Historical evaluations by date range |
| `POST` | `/api/forecast/run` | Bearer | On-demand run |
| `GET` | `/api/forecast/compare` | Bearer | All evaluation runs for a location+date |

### Locations & outcomes

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/locations` | Bearer | All persisted locations |
| `POST` | `/api/locations` | Bearer | Add a new location |
| `POST` | `/api/outcome` | Bearer | Record an actual observed outcome |
| `GET` | `/api/outcome` | Bearer | Query outcomes by location and date range |

### User management (ADMIN only)

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/api/users` | ADMIN | List all users |
| `POST` | `/api/users` | ADMIN | Create a user |
| `PUT` | `/api/users/{id}/enabled` | ADMIN | Enable or disable a user |
| `PUT` | `/api/users/{id}/role` | ADMIN | Change a user's role |

---

## Running tests

```bash
# Backend (JUnit 5, 80% coverage enforced by JaCoCo)
cd backend && ./mvnw test

# Frontend component tests
cd frontend && npm test

# Frontend end-to-end (requires app running)
cd frontend && npm run test:e2e
```

---

## solar-utils dependency

[solar-utils](https://github.com/gregochr/solar-utils) is a shared library published to GitHub Packages. It is pulled automatically by Maven — no manual build required. See the one-time setup section above for the token requirement.

---

## Made with ☕ by Chris

[Buy me a coffee](https://buymeacoffee.com/gregorychris) · [GitHub Sponsors](https://github.com/sponsors/gregochr)
