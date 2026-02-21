# CLAUDE.md — The Photographer's Golden Hour

## Project Overview

A full-stack application that evaluates the potential for a colourful sunrise or sunset at a given location. It consists of:

1. A **Spring Boot REST API** (backend) that:
   - Calls the **Open-Meteo Forecast API** (free, no API key) to retrieve atmospheric and cloud cover data
   - Calls the **Open-Meteo Air Quality API** (free, no API key) to retrieve aerosol, PM2.5, and dust data
   - Uses **solar-utils** (shared library extracted from plex-webhooks) to calculate precise sunrise and sunset times
   - Passes pre-processed forecast data to **Claude (Anthropic Java SDK)** to produce a 1-5 colour potential rating with reasoning
   - Persists every forecast evaluation to **PostgreSQL** for historical analysis
   - Runs on a **schedule** to automatically evaluate T through T+7 twice daily
   - Allows recording of **actual outcomes** on days the photographer goes out shooting
   - Sends **daily notifications** via email (HTML), iOS push (Pushover), and macOS toast

2. A **React frontend** (web UI) that:
   - Displays the forecast timeline from T through T+7
   - Shows star ratings, Claude's summary, and pictorial weather data (cloud cover bars, visibility, wind direction/strength)
   - Allows recording of actual outcomes via the UI

3. A future **macOS menu bar widget** (Tauri) that:
   - Reuses React components from the web UI
   - Shows the current day's rating at a glance from the menu bar
   - Expands to show T through T+7 on click
   - Talks directly to the Spring Boot REST API
   - Delivers macOS toast notifications

---

## Monorepo Structure

```
golden-hour/
├── backend/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── com/gregochr/goldenhour/
│   │   │   │       ├── GoldenHourApplication.java
│   │   │   │       ├── controller/
│   │   │   │       │   ├── ForecastController.java
│   │   │   │       │   └── OutcomeController.java
│   │   │   │       ├── service/
│   │   │   │       │   ├── OpenMeteoService.java
│   │   │   │       │   ├── SolarService.java
│   │   │   │       │   ├── EvaluationService.java
│   │   │   │       │   ├── ScheduledForecastService.java
│   │   │   │       │   └── notification/
│   │   │   │       │       ├── NotificationService.java
│   │   │   │       │       ├── EmailNotificationService.java
│   │   │   │       │       ├── PushoverNotificationService.java
│   │   │   │       │       └── MacOsToastNotificationService.java
│   │   │   │       ├── model/
│   │   │   │       │   ├── ForecastRequest.java
│   │   │   │       │   ├── AtmosphericData.java
│   │   │   │       │   ├── SunsetEvaluation.java
│   │   │   │       │   └── ActualOutcome.java
│   │   │   │       ├── entity/
│   │   │   │       │   ├── ForecastEvaluationEntity.java
│   │   │   │       │   └── ActualOutcomeEntity.java
│   │   │   │       ├── repository/
│   │   │   │       │   ├── ForecastEvaluationRepository.java
│   │   │   │       │   └── ActualOutcomeRepository.java
│   │   │   │       └── config/
│   │   │   │           ├── AppConfig.java
│   │   │   │           └── CorsConfig.java
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       ├── application-example.yml
│   │   │       ├── templates/
│   │   │       │   └── forecast-email.html
│   │   │       └── db/migration/
│   │   │           ├── V1__create_forecast_evaluation.sql
│   │   │           └── V2__create_actual_outcome.sql
│   │   └── test/
│   │       └── java/
│   │           └── com/gregochr/goldenhour/
│   │               ├── controller/
│   │               │   ├── ForecastControllerTest.java
│   │               │   └── OutcomeControllerTest.java
│   │               ├── service/
│   │               │   ├── WindyServiceTest.java
│   │               │   ├── SolarServiceTest.java
│   │               │   ├── EvaluationServiceTest.java
│   │               │   ├── ScheduledForecastServiceTest.java
│   │               │   └── notification/
│   │               │       ├── EmailNotificationServiceTest.java
│   │               │       ├── PushoverNotificationServiceTest.java
│   │               │       └── MacOsToastNotificationServiceTest.java
│   │               └── repository/
│   │                   ├── ForecastEvaluationRepositoryTest.java
│   │                   └── ActualOutcomeRepositoryTest.java
│   └── pom.xml
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   │   ├── ForecastTimeline.jsx
│   │   │   ├── ForecastCard.jsx
│   │   │   ├── StarRating.jsx
│   │   │   ├── CloudCoverBars.jsx
│   │   │   ├── WindIndicator.jsx
│   │   │   ├── VisibilityIndicator.jsx
│   │   │   └── OutcomeForm.jsx
│   │   ├── api/
│   │   │   └── forecastApi.js
│   │   ├── App.jsx
│   │   └── main.jsx
│   ├── package.json
│   └── vite.config.js
├── docker-compose.yml
└── CLAUDE.md
```

---

## Backend Dependencies

- **Spring Boot 3.x**
- **spring-boot-starter-web** — REST API
- **spring-boot-starter-webflux** — WebClient for Open-Meteo API calls
- **spring-boot-starter-data-jpa** — database persistence
- **spring-boot-starter-cache** — caching layer
- **spring-boot-starter-mail** — email via SMTP
- **spring-boot-starter-thymeleaf** — HTML email templating
- **caffeine** — in-memory cache
- **postgresql** — JDBC driver
- **flyway-core + flyway-database-postgresql** — database migrations
- **anthropic-java** (Anthropic Java SDK) — Claude API calls
- **solar-utils** — shared local Maven library (extracted from plex-webhooks)
- **lombok** — reduce boilerplate
- **jackson-datatype-jsr310** — LocalDate/LocalDateTime serialisation
- **spring-boot-starter-test** — JUnit 5, Mockito, AssertJ
- **reactor-test** — WebFlux testing
- **h2** (test scope) — in-memory DB for repository tests
- **jacoco-maven-plugin** — code coverage (80% minimum)

---

## Frontend Dependencies

- **React 18**
- **Vite** — build tool
- **Recharts or ApexCharts** — cloud cover bars, wind, visibility visualisations
- **Axios** — REST API calls
- **Tailwind CSS** — styling
- **Vitest** — unit testing framework (native Vite integration)
- **React Testing Library** — component testing
- **Playwright** — end-to-end browser testing

---

## Frontend Testing Strategy

The frontend testing approach is designed for someone who is not a UI developer. The focus is on testing what the user sees and does, not React internals.

### Testing Stack

**Vitest + React Testing Library** — for component-level tests. Tests that individual components render correctly given certain data. Keep these minimal and focused on critical components only (e.g. `StarRating`, `ForecastCard`).

**Playwright** — the primary testing tool. End-to-end tests that run against the real app in a real browser. Tests read like plain English and are straightforward to write and maintain without deep React knowledge.

### Playwright Test Coverage

Focus on critical user journeys rather than exhaustive coverage:

**Forecast timeline loads correctly:**
```javascript
await page.goto('http://localhost:5173');
await expect(page.getByText('Today')).toBeVisible();
await expect(page.getByText('Tomorrow')).toBeVisible();
await expect(page.getByTestId('forecast-card')).toHaveCount(8);
```

**Star ratings display:**
```javascript
await expect(page.getByTestId('sunrise-rating')).toBeVisible();
await expect(page.getByTestId('sunset-rating')).toBeVisible();
```

**Windy data visualisations render:**
```javascript
await expect(page.getByTestId('cloud-cover-bars')).toBeVisible();
await expect(page.getByTestId('wind-indicator')).toBeVisible();
await expect(page.getByTestId('visibility-indicator')).toBeVisible();
```

**Outcome recording flow:**
```javascript
await page.getByTestId('record-outcome-button').first().click();
await expect(page.getByTestId('outcome-form')).toBeVisible();
await page.getByTestId('actual-rating-3').click();
await page.getByTestId('went-out-yes').click();
await page.getByTestId('outcome-notes').fill('Beautiful warm light on the cathedral.');
await page.getByTestId('outcome-submit').click();
await expect(page.getByText('Outcome saved')).toBeVisible();
```

**API error handling — graceful degradation:**
```javascript
// With backend unavailable, app should show a friendly error not a blank screen
await expect(page.getByTestId('error-message')).toBeVisible();
```

### Test Data Attributes

Add `data-testid` attributes to all key UI elements during development. This makes Playwright selectors stable and independent of styling or layout changes:

```jsx
<div data-testid="forecast-card">
<div data-testid="sunrise-rating">
<div data-testid="cloud-cover-bars">
<button data-testid="record-outcome-button">
```

### Running Tests

```bash
# Component tests
cd frontend && npm run test

# End-to-end tests (requires app running)
cd frontend && npm run test:e2e

# Playwright UI mode (visual, great for debugging)
cd frontend && npx playwright test --ui
```

### package.json Test Scripts

```json
"scripts": {
  "test": "vitest",
  "test:e2e": "playwright test",
  "test:e2e:ui": "playwright test --ui"
}
```

---

## Data Model

### forecast_evaluation
Stores every forecast run. Multiple rows per target date enable accuracy tracking over time.

| Column | Type | Description |
|---|---|---|
| id | BIGSERIAL | Primary key |
| location_lat | DECIMAL | Latitude |
| location_lon | DECIMAL | Longitude |
| location_name | VARCHAR | Human-readable location name |
| target_date | DATE | The date being forecast |
| target_type | VARCHAR | SUNRISE or SUNSET |
| forecast_run_at | TIMESTAMP | When this evaluation was made |
| days_ahead | INT | target_date minus forecast_run_at date |
| low_cloud | INT | Low cloud cover % (0-3 km) |
| mid_cloud | INT | Mid cloud cover % (3-8 km) |
| high_cloud | INT | High cloud cover % (8+ km) |
| visibility | INT | Visibility in metres |
| wind_speed | DECIMAL | Wind speed m/s |
| wind_direction | INT | Wind direction in degrees |
| precipitation | DECIMAL | Precipitation mm |
| humidity | INT | Relative humidity % |
| weather_code | INT | WMO weather condition code |
| boundary_layer_height | INT | Boundary layer height in metres |
| shortwave_radiation | DECIMAL | Incoming solar radiation W/m² |
| pm2_5 | DECIMAL | Fine particulate matter µg/m³ |
| dust | DECIMAL | Dust concentration µg/m³ |
| aerosol_optical_depth | DECIMAL | Aerosol optical depth (dimensionless) |
| rating | INT | Claude's rating 1-5 |
| summary | TEXT | Claude's plain English explanation |

### actual_outcome
Recorded by the user via the web UI or REST API.

| Column | Type | Description |
|---|---|---|
| id | BIGSERIAL | Primary key |
| location_lat | DECIMAL | Latitude |
| location_lon | DECIMAL | Longitude |
| location_name | VARCHAR | Human-readable location name |
| date | DATE | Date of the actual event |
| type | VARCHAR | SUNRISE or SUNSET |
| went_out | BOOLEAN | Whether photographer went out |
| actual_rating | INT | Photographer's own 1-5 rating |
| notes | TEXT | Free text observations |
| recorded_at | TIMESTAMP | When this record was created |

---

## Backend Key Components

### ForecastController
- `GET /api/forecast` — return stored ratings for T through T+7 for all configured locations
- `GET /api/forecast/history?from=&to=&location=` — return historical evaluations for a date range
- `POST /api/forecast/run` — trigger an on-demand forecast run

### OutcomeController
- `POST /api/outcome` — record an actual observed outcome
- `GET /api/outcome?lat=&lon=&from=&to=` — retrieve outcomes for a date range

### OpenMeteoService
- Calls **Open-Meteo Forecast API**: `https://api.open-meteo.com/v1/forecast` (free, no API key)
- Calls **Open-Meteo Air Quality API**: `https://air-quality-api.open-meteo.com/v1/air-quality` (free, no API key)
- Requests the following hourly parameters around the ±30-minute solar event window:
  - **Weather**: `cloud_cover_low`, `cloud_cover_mid`, `cloud_cover_high`, `visibility`, `wind_speed_10m`, `wind_direction_10m`, `precipitation`, `weather_code`, `relative_humidity_2m`, `surface_pressure`, `shortwave_radiation`, `boundary_layer_height`
  - **Air Quality**: `pm2_5`, `dust`, `aerosol_optical_depth`
- Pre-processes response — extracts only values for the ±30-minute window around sunrise/sunset
- Returns lean `AtmosphericData` — do NOT pass raw API JSON to Claude

### SolarService
- Wraps the `solar-utils` jar
- Given latitude, longitude, and date — returns precise sunrise and sunset times as `LocalDateTime`

### EvaluationService
- Receives pre-processed `AtmosphericData` and solar times
- Builds a concise, token-efficient prompt (see Prompt Design below)
- Calls Claude via Anthropic Java SDK
- Parses response into `SunsetEvaluation`
- Persists result to `forecast_evaluation` table

### ScheduledForecastService
- Runs twice daily (06:00 and 18:00) via Spring `@Scheduled`
- For each configured location evaluates sunrise and sunset for T through T+7
- Persists all evaluations automatically
- After the 06:00 run, triggers `NotificationService` to send daily digest

---

## Notification System

### NotificationService
Orchestrates all notification channels. Each channel is independently enabled/disabled via `application.yml`. On trigger, builds a `DailyForecastDigest` (today's sunrise and sunset rating, summary, and key conditions) and passes it to each enabled channel.

### EmailNotificationService
- Uses `spring-boot-starter-mail` with SMTP
- Renders HTML email via Thymeleaf template (`forecast-email.html`)
- Email contains: today's sunrise and sunset star rating, Claude's summary, cloud cover, visibility, wind
- Configurable recipient address and send time via `application.yml`

### PushoverNotificationService
- Calls the Pushover REST API (`https://api.pushover.net/1/messages.json`) via WebClient
- Simple POST with app token, user key, and message body
- Message format: `"🌅 Sunrise: ★★★ | 🌇 Sunset: ★★★★ — Some mid-level cloud, clear horizon likely. Worth watching."`
- Pushover is a one-off £5 purchase on the App Store — delivers reliably to iPhone

### MacOsToastNotificationService
- Executes a local shell command via `ProcessBuilder` to trigger a macOS notification
- Uses the `osascript` command: `display notification "..." with title "Sunset Forecast"`
- Only relevant when the Spring Boot app is running on macOS
- In future this will be handled natively by the Tauri menu bar widget instead

### Notification Configuration

```yaml
notifications:
  schedule:
    cron: "0 30 7 * * *"   # Send at 07:30 each morning (or after forecast run)
  email:
    enabled: true
    smtp-host: smtp.gmail.com
    smtp-port: 587
    username: YOUR_EMAIL
    password: YOUR_APP_PASSWORD
    recipient: YOUR_EMAIL
  pushover:
    enabled: true
    app-token: YOUR_PUSHOVER_APP_TOKEN
    user-key: YOUR_PUSHOVER_USER_KEY
  macos-toast:
    enabled: true
```

---

## Prompt Design (Token Efficiency)

Keep prompts lean — every token costs money. Java does the data wrangling; Claude only receives essentials.

**System prompt (static):**
```
You are an expert sunrise/sunset colour potential advisor for landscape photographers.
Rate colour potential 1-5 and explain briefly.

Key criteria:
- Clear horizon is critical — high low cloud (>70%) = poor (1-2)
- Mid/high cloud above a clear horizon = ideal canvas for colour
- Thin broken cloud scores higher than thick uniform overcast
- Post-rain clearing is often vivid — clean air + upper cloud
- Moderate aerosol/dust enhances red scattering; smoke or heavy haze reduces it
- High humidity (>80%) mutes colours; low humidity sharpens them
- Low boundary layer traps aerosols near surface — can enhance warm tones
- Fully clear sky = limited drama (2-3); total overcast = 1
```

**User prompt (dynamic, built in Java):**
```
Location: Durham UK. Sunset: 17:32 UTC.
Cloud: Low 15%, Mid 40%, High 70%
Visibility: 22km, Wind: 8mph SW, Precip: 0.0mm
Humidity: 62%, Pressure: 1018hPa, Weather: Partly cloudy
Boundary layer: 1200m, Shortwave radiation: 180 W/m²
Air quality: PM2.5 8µg/m³, Dust 2µg/m³, AOD 0.12
Rate 1-5 and explain in 2-3 sentences.
```

Use `claude-haiku-4-5` model — sufficient for this task and significantly cheaper than Sonnet.

---

## Sunset/Sunrise Evaluation Criteria

- **Clear strip near the horizon is critical** — high low cloud (>70%) likely rates 1-2
- **Mid and high cloud above a clear horizon** — ideal canvas for colour
- **Thin/broken cloud scores higher** than thick uniform overcast
- **Post-rain clearing** — one of the strongest positive indicators; clean air + upper cloud
- **Moderate aerosol loading** (AOD 0.1–0.25) enhances red/orange scattering
- **Dust preferred over smoke** — dust warms tones, smoke creates grey/brown haze
- **Low humidity** (40–65%) sharpens and saturates colours; high humidity (>80%) mutes them
- **Low boundary layer** traps aerosols near the surface, enhancing warm tones
- **Good visibility** (>15km) amplifies colour intensity
- **Fully clear sky** = limited colour, rating 2-3 (glow but no drama)
- **Total overcast** = rating 1

---

## Frontend UI Design

### Main View — Forecast Timeline
- Header showing location name and current date
- Cards displayed vertically: T at top through T+7 at bottom
- Each card shows:
  - Date and day label (e.g. "Today", "Tomorrow", "Wed 25 Feb")
  - Sunrise and sunset rating side by side (star rating out of 5)
  - Claude's summary text
  - Pictorial Windy data: cloud cover bars (low/mid/high), wind direction compass + speed, visibility indicator
  - If actual outcome recorded — shows actual rating alongside predicted

### Outcome Recording
- Each card has a "Record Outcome" button
- Opens a simple form: went out (yes/no), actual rating (1-5 stars), free text notes
- Submits to `POST /api/outcome`

---

## Grafana Integration

PostgreSQL is the Grafana data source. Key dashboard panels:

**Forecast convergence chart** — for a selected target date, how the rating changed as the date approached. x-axis: days_ahead, y-axis: rating.

**Predicted vs actual scatter** — predicted rating vs actual_rating where both exist.

**Rolling calendar heatmap** — colour-coded calendar of ratings.

**Accuracy by horizon** — average deviation between predicted and actual, grouped by days_ahead.

Sample query for convergence chart:
```sql
SELECT forecast_run_at, days_ahead, rating
FROM forecast_evaluation
WHERE target_date = '2026-02-27'
AND target_type = 'SUNSET'
AND location_name = 'Durham UK'
ORDER BY forecast_run_at ASC;
```

---

## Future: macOS Menu Bar Widget (Tauri)

To be built as a separate Tauri application reusing React components. Key points:

- Tauri wraps a web view — React components from `frontend/src/components/` can be reused directly
- Menu bar icon shows today's rating at a glance (e.g. ★★★)
- Click expands to show T through T+7 list view
- Delivers native macOS toast notifications (replacing the `osascript` approach in `MacOsToastNotificationService`)
- Keep shared React components decoupled from Vite/browser-specific APIs to ease reuse in Tauri

---

## Unit Testing Strategy

All backend services and controllers must have comprehensive unit tests targeting >80% line coverage (enforced by JaCoCo).

**Testing stack:** JUnit 5, Mockito, AssertJ (via `spring-boot-starter-test`)

**Controller tests** — use `@WebMvcTest` with MockMvc. Mock service layer. Test happy path, bad input (400), not found (404), and error (500) scenarios for all endpoints.

**Service tests** — use `@ExtendWith(MockitoExtension.class)`. Mock all external dependencies. Key scenarios:
- `OpenMeteoServiceTest` — mock WebClient, test data extraction, time window filtering, wind vector conversion
- `EvaluationServiceTest` — mock Anthropic SDK, verify prompt construction, test response parsing, verify persistence
- `SolarServiceTest` — test sunrise/sunset calculation for known dates and locations
- `ScheduledForecastServiceTest` — verify T through T+7 loop, verify correct persist call count, verify notification trigger
- `EmailNotificationServiceTest` — mock JavaMailSender, verify template rendering and recipient
- `PushoverNotificationServiceTest` — mock WebClient, verify correct POST payload construction
- `MacOsToastNotificationServiceTest` — mock ProcessBuilder, verify osascript command construction

**Repository tests** — use `@DataJpaTest` with H2 (test scope). Test custom queries.

---

## API Keys & Configuration

Never commit `application.yml`. Commit only `application-example.yml` with placeholders.

```yaml
anthropic:
  api-key: YOUR_ANTHROPIC_API_KEY
  model: claude-haiku-4-5

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/goldenhour
    username: YOUR_DB_USER
    password: YOUR_DB_PASSWORD
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
  flyway:
    enabled: true
  mail:
    host: smtp.gmail.com
    port: 587
    username: YOUR_EMAIL
    password: YOUR_APP_PASSWORD
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true

notifications:
  schedule:
    cron: "0 30 7 * * *"
  email:
    enabled: true
    recipient: YOUR_EMAIL
  pushover:
    enabled: true
    app-token: YOUR_PUSHOVER_APP_TOKEN
    user-key: YOUR_PUSHOVER_USER_KEY
  macos-toast:
    enabled: true

forecast:
  locations:
    - name: Durham UK
      lat: 54.7753
      lon: -1.5849
  schedule:
    cron: "0 0 6,18 * * *"

server:
  port: 8081
```

---

## solar-utils Library

Extracted from the `plex-webhooks` project. Build and install locally before starting this project:

```bash
cd solar-utils
mvn clean install
```

---

## Docker Compose (Local Development)

```yaml
version: '3.8'
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: goldenhour
      POSTGRES_USER: sunset
      POSTGRES_PASSWORD: sunset
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    volumes:
      - grafana_data:/var/lib/grafana
    depends_on:
      - postgres

volumes:
  postgres_data:
  grafana_data:
```

---

## Build & Run Order (First Time Setup)

1. Build and install `solar-utils`: `cd solar-utils && mvn clean install`
2. Start infrastructure: `docker-compose up -d`
3. Build backend: `cd backend && ./mvnw clean install`
4. Run backend: `./mvnw spring-boot:run` (Flyway migrations run on first start)
5. Install and run frontend: `cd frontend && npm install && npm run dev`
6. Trigger initial forecast: `curl -X POST http://localhost:8082/api/forecast/run`
7. Configure Grafana at `http://localhost:3000` (admin/admin) — add PostgreSQL data source using host `host.docker.internal:5432`
8. Install Pushover on iPhone and register for an account to obtain user key and app token

---

## CORS Configuration

Backend must allow requests from the React dev server (`http://localhost:5173`) and any production frontend URL. Configure in `CorsConfig.java`.

---

## Code Standards

This project is part of a professional portfolio and must reflect production-quality standards throughout.

### Backend — Java

**Checkstyle** is configured with `checkstyle.xml` in the project root. The build fails if violations are found. Key rules:
- Javadoc required on all public classes and methods
- No unused imports
- Consistent indentation (4 spaces)
- Line length maximum 120 characters
- Meaningful variable and method names — no single letter variables outside loops

**SpotBugs** runs static analysis on every build (`mvn verify`). Threshold is set to Medium — all medium and high priority bugs must be resolved before committing.

**General principles:**
- No magic numbers — use named constants
- Services should have a single responsibility
- No business logic in controllers
- All external API calls must handle errors gracefully and never propagate raw exceptions to the API response

### Frontend — React

- ESLint configured with React and accessibility rules
- Prettier for consistent formatting
- All components must have `data-testid` attributes on key elements
- No inline styles — use Tailwind classes only
- PropTypes or TypeScript types on all components

### Commit Standards

Conventional commits enforced throughout:
- `feat:` — new feature
- `fix:` — bug fix
- `chore:` — build, config, dependency updates
- `test:` — adding or updating tests
- `docs:` — documentation only

Commits should be small and focused. Never commit commented-out code.

---

## GitHub Actions CI Pipeline

A CI pipeline runs on every push and pull request to `main`. The build must be green before merging.

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  backend:
    name: Backend — Build, Test & Quality
    runs-on: ubuntu-latest

    services:
      postgres:
        image: postgres:16
        env:
          POSTGRES_DB: sunsetforecast_test
          POSTGRES_USER: sunset
          POSTGRES_PASSWORD: sunset
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven

      - name: Build and test
        run: cd backend && mvn clean verify
        env:
          SPRING_DATASOURCE_URL: jdbc:postgresql://localhost:5432/sunsetforecast_test
          SPRING_DATASOURCE_USERNAME: sunset
          SPRING_DATASOURCE_PASSWORD: sunset

      - name: Upload coverage report
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-report
          path: backend/target/site/jacoco/

  frontend:
    name: Frontend — Build & Test
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up Node
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: frontend/package-lock.json

      - name: Install dependencies
        run: cd frontend && npm ci

      - name: Lint
        run: cd frontend && npm run lint

      - name: Unit tests
        run: cd frontend && npm run test

      - name: Install Playwright browsers
        run: cd frontend && npx playwright install --with-deps chromium

      - name: Build app for E2E
        run: cd frontend && npm run build

      - name: Run Playwright E2E tests
        run: cd frontend && npm run test:e2e
```

Add a CI badge to the README:
```markdown
![CI](https://github.com/gregochr/golden-hour/actions/workflows/ci.yml/badge.svg)
```

---

## README.md

The repository README should include:
- Project name and brief description
- CI badge
- Screenshot of the UI
- Tech stack summary
- Prerequisites (Java 21, Node 20, Docker)
- How to run locally (step by step)
- Link to solar-utils dependency

A well presented README matters for hiring managers reviewing the repository.

---

## Community & Funding

This project is open source and self-funded. Add the following to encourage community support:

### Buy Me a Coffee & GitHub Sponsors

Add to the README below the CI badge:

```markdown
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-yellow?logo=buy-me-a-coffee)](https://buymeacoffee.com/gregorychris)
[![GitHub Sponsors](https://img.shields.io/github/sponsors/gregochr?logo=github)](https://github.com/sponsors/gregochr)
```

### Web UI Footer

The React app footer should include a small, unobtrusive line:

```jsx
<footer>
  <p>Made with ☕ by Chris — 
    <a href="https://buymeacoffee.com/gregorychris" target="_blank">
      Buy me a coffee
    </a>
  </p>
</footer>
```

### macOS Widget About Screen

Include a "Support this project" link in the Tauri widget's about or settings screen when built.

### Notes

- Keep donation prompts subtle and unobtrusive — one mention in the UI footer is enough
- Buy Me a Coffee signup: https://buymeacoffee.com
- GitHub Sponsors setup: https://github.com/sponsors
- Revenue goal: self-funding — API costs, Philips HUE lights, camera accessories, future Mac upgrade

---

## Git & Project Conventions

- Branch naming: `feature/`, `fix/`, `chore/`
- Commit style: conventional commits (`feat:`, `fix:`, `chore:`)
- Java package root: `com.gregochr.goldenhour`
- Never commit `application.yml` — only `application-example.yml`
- `.gitignore` must include: `application.yml`, `*.env`, `node_modules/`, `target/`
- No commented-out code committed
- No magic numbers — use named constants

---

## CHANGELOG.md

Maintain a `CHANGELOG.md` in the repository root using the "Keep a Changelog" format (keepachangelog.com). Update it with every meaningful change — do not leave it until release.

Structure:

```markdown
# Changelog

## [Unreleased]
### Added
- Brief description of what's been added

## [0.1.0] - YYYY-MM-DD
### Added
- Initial release notes here
```

Categories to use: **Added**, **Changed**, **Fixed**, **Removed**, **Security**

**Rules:**
- Always have an `[Unreleased]` section at the top
- When cutting a release, rename `[Unreleased]` to the version number and date
- Start a fresh `[Unreleased]` section above it
- Every feature branch merge should add at least one line to `[Unreleased]`

---

## Release Tagging

Tag every release in Git using semantic versioning:

```bash
# Create an annotated tag
git tag -a v0.1.0 -m "Initial release - core forecast API working"

# Push the tag to GitHub
git push origin v0.1.0
```

GitHub automatically creates a **Release** page from tags. Add release notes on GitHub to match the CHANGELOG entry.

**Semantic versioning convention:**
- `v0.1.0` — first working version, still early
- `v0.2.0` — new feature added
- `v0.2.1` — bug fix on an existing version
- `v1.0.0` — production ready, all core features complete

**Suggested release milestones:**
- `v0.1.0` — Spring Boot API working, Open-Meteo + Claude evaluation, data persisting to PostgreSQL
- `v0.2.0` — React frontend working with forecast timeline and weather visualisations
- `v0.3.0` — Notifications working (email, Pushover, macOS toast)
- `v0.4.0` — Outcome recording and Grafana dashboards working
- `v1.0.0` — All features complete, tested, CI green, README polished
