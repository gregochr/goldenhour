# 🌅 Golden Hour

A full-stack application that predicts the colour potential of sunrise and sunset for landscape photographers. It pulls atmospheric and air quality data from Open-Meteo, passes it to Claude, and returns a 1–5 rating with plain English reasoning — updated twice daily for locations up to 7 days ahead.

[![CI](https://github.com/gregochr/goldenhour/actions/workflows/ci.yml/badge.svg)](https://github.com/gregochr/goldenhour/actions/workflows/ci.yml)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-yellow?logo=buy-me-a-coffee)](https://buymeacoffee.com/gregorychris)
[![GitHub Sponsors](https://img.shields.io/github/sponsors/gregochr?logo=github)](https://github.com/sponsors/gregochr)

---

## What it does

- Evaluates sunrise and sunset colour potential for any number of locations, T through T+7
- Rates each event 1–5 using cloud layers, visibility, humidity, aerosol optical depth, and boundary layer height
- Stores every evaluation to PostgreSQL so you can track how the forecast converges as the date approaches
- Lets you record actual outcomes (went out / actual rating / notes) for accuracy analysis in Grafana
- Sends a daily digest at 07:30 via email, Pushover (iOS), and macOS toast

---

## Tech stack

| Layer | Technology |
|---|---|
| API | Spring Boot 3, Spring WebFlux (WebClient) |
| AI evaluation | Claude via Anthropic Java SDK |
| Solar times | [solar-utils](https://github.com/gregochr/solar-utils) (GitHub Packages) |
| Weather data | Open-Meteo Forecast + Air Quality APIs (free, no key) |
| Database | PostgreSQL + Flyway migrations |
| Frontend | React 18, Vite, Tailwind CSS, Recharts, Leaflet |
| Dev database | H2 file (local profile — no Docker needed) |

---

## Prerequisites

- Java 21
- Node 20
- Docker (for PostgreSQL + Grafana in production) — or use the local H2 profile
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
# Edit application.yml — set your Anthropic API key, DB credentials, and notification settings
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

Trigger an initial forecast run:

```bash
curl -X POST http://localhost:8082/api/forecast/run
```

---

## Running with Docker

```bash
# Start PostgreSQL and Grafana
docker-compose up -d

# Build and run backend (default profile uses PostgreSQL)
export ANTHROPIC_API_KEY=sk-ant-...
cd backend && ./mvnw spring-boot:run

# Start frontend
cd frontend && npm install && npm run dev
```

Grafana is available at [http://localhost:3000](http://localhost:3000) (admin / admin). Add a PostgreSQL data source pointing at `host.docker.internal:5432`, database `goldenhour`, user `sunset`, password `sunset`.

---

## Key API endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/forecast` | T through T+7 for all locations |
| `GET` | `/api/forecast/history` | Historical evaluations by date range |
| `POST` | `/api/forecast/run` | On-demand run (optional date, location, targetType) |
| `GET` | `/api/forecast/compare` | All evaluation runs for a location+date (convergence) |
| `GET` | `/api/locations` | All persisted locations |
| `POST` | `/api/locations` | Add a new location (persists across restarts) |
| `POST` | `/api/outcome` | Record an actual observed outcome |
| `GET` | `/api/outcome` | Query outcomes by location and date range |

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
