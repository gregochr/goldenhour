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

Model selection is driven by `anthropic.model` in config — see **Evaluation Strategy Pattern** section below. Pro uses Sonnet for higher accuracy; Lite uses Haiku for lower cost.

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

## Evaluation Strategy Pattern

The application uses a **strategy pattern** to support different Claude models for evaluation. Strategy selection is **obfuscated from end users** — there is no `evaluation.strategy` config property to override. Instead, Spring profiles control which strategy bean is registered, and `anthropic.model` is the single source of truth for which Claude model is called.

### Obfuscation Design

Users cannot easily switch between Haiku and Sonnet because:

1. **No strategy property exists** — there's nothing to set in YAML
2. **`@Profile` beans** — the strategy is wired at startup based on the active Spring profile
3. **Profile is set in startup code** — for Tauri (Lite), it's hard-coded in `main.rs`; for Pro, it's set in the run command or deployment config
4. A user exploring the config will see `spring.profiles.active: pro` but won't know what that controls without reading the source

### How It Works

Strategy beans are registered conditionally via `@Profile`:

```java
@Configuration
public class EvaluationConfig {

    @Bean
    @Profile("lite")
    public EvaluationStrategy liteStrategy(AnthropicClient client) {
        return new HaikuEvaluationStrategy(client);
    }

    @Bean
    @Profile("!lite")  // Pro is the default
    public EvaluationStrategy proStrategy(AnthropicClient client) {
        return new SonnetEvaluationStrategy(client);
    }
}
```

`EvaluationService` receives the injected strategy — no model-sniffing or branching:

```java
@Service
public class EvaluationService {
    private final EvaluationStrategy strategy;

    public EvaluationService(EvaluationStrategy strategy) {
        this.strategy = strategy;
    }

    public SunsetEvaluation evaluate(AtmosphericData data) {
        return strategy.evaluate(data);
    }
}
```

Each profile's YAML sets the model string passed to the Anthropic API:

```yaml
# application.yml (Pro — default)
anthropic:
  model: claude-sonnet-4-5-20250929

# application-lite.yml
anthropic:
  model: claude-haiku-4-5-20251001
```

### Strategy Differences

| | HaikuEvaluationStrategy | SonnetEvaluationStrategy |
|---|---|---|
| Model | `claude-haiku-4-5-20251001` | `claude-sonnet-4-5-20250929` |
| Prompt suffix | `Rate 1-5 and explain in 1-2 sentences.` | `Rate 1-5 and explain in 2-3 sentences.` |
| Latency | ~320ms | ~1100ms |
| Cost per eval | ~$0.0003 | ~$0.003 |
| Accuracy | ~80% of actual | ~95% of actual |

### Configuration Per Version

| Version | Profile | Config File | Model |
|---|---|---|---|
| **Pro** (this project) | `pro` or default | `application.yml` | `claude-sonnet-4-5-20250929` |
| **Lite** (separate project) | `lite` | `application-lite.yml` | `claude-haiku-4-5-20251001` |

### Backtesting Endpoint

Compare Haiku vs Sonnet evaluations on the same forecast data (development/testing only):

```bash
GET /api/forecast/compare?lat=54.7753&lon=-1.5849&date=2026-02-28
```

Returns both evaluations side by side, allowing you to:
1. Compare accuracy against real outcomes
2. Track latency differences
3. Calculate cost savings/tradeoff
4. Make a data-driven decision on which model to ship

### Example Comparison Output

```json
{
  "date": "2026-02-28",
  "location": "Durham UK",
  "haiku": {
    "rating": 4,
    "summary": "High cirrus with clear horizon — good colour potential.",
    "latencyMs": 320,
    "estimatedCost": "$0.0003"
  },
  "sonnet": {
    "rating": 4,
    "summary": "Extensive high cloud (70%) above a clear low horizon creates an ideal canvas. Moderate AOD and low humidity will enhance warm scattering. Expect vivid oranges and pinks.",
    "latencyMs": 1100,
    "estimatedCost": "$0.003"
  }
}
```

---

## API Keys & Configuration

Never commit `application.yml`. Commit only `application-example.yml` with placeholders.

```yaml
anthropic:
  api-key: YOUR_ANTHROPIC_API_KEY
  model: claude-sonnet-4-5-20250929  # Pro uses Sonnet for better accuracy

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

## solar-utils Dependency

`solar-utils` is a shared library published to **GitHub Packages**. It is pulled automatically by Maven — no manual build step required.

Repository: `https://github.com/gregochr/solar-utils`

### One-time machine setup (per developer machine)

Maven requires a GitHub token to download from GitHub Packages even for public packages.

1. Create a GitHub personal access token with **`read:packages`** scope at `https://github.com/settings/tokens`
2. Copy `settings.xml.example` to `~/.m2/settings.xml` (or merge if one already exists) and replace `YOUR_GITHUB_TOKEN`

```bash
cp settings.xml.example ~/.m2/settings.xml
# then edit ~/.m2/settings.xml and set your token
```

### Publishing a new solar-utils release

```bash
cd solar-utils
git tag v1.0.0
git push origin v1.0.0   # triggers the publish GitHub Actions workflow
```

---

## Build & Run Order (First Time Setup)

1. Complete the one-time Maven settings setup above (GitHub Packages token)
2. Start infrastructure: `docker-compose up -d`
3. Build backend: `cd backend && ./mvnw clean install`
4. Run backend: `./mvnw spring-boot:run` (Flyway migrations run on first start)
5. Install and run frontend: `cd frontend && npm install && npm run dev`
6. Trigger initial forecast: `curl -X POST http://localhost:8082/api/forecast/run`
7. Configure Grafana at `http://localhost:3000` (admin/admin) — add PostgreSQL data source using host `host.docker.internal:5432`
8. Install Pushover on iPhone and register for an account to obtain user key and app token

---

## Running Without Docker (Local Profile)

For laptop development without a running PostgreSQL instance, use the `local` Spring profile.
This swaps PostgreSQL for a file-based H2 database — no Docker required.

```bash
# Set your Anthropic API key (required for forecast runs, not for boot)
export ANTHROPIC_API_KEY=your-key

# Start the backend with the local profile
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

**What changes in the local profile:**
- H2 file database stored at `backend/data/goldenhour` (persists between restarts, gitignored)
- Flyway migrations are skipped — Hibernate creates the schema automatically from JPA entities
- All notifications disabled (email, Pushover, macOS toast)
- H2 console available at `http://localhost:8082/h2-console` for inspecting data
  - JDBC URL: `jdbc:h2:file:./data/goldenhour`
  - Username: `sa`, Password: *(empty)*

The frontend runs the same regardless of profile:
```bash
cd frontend && npm run dev
```

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

---

## Sunset Color Scoring - MVP Approach

### Overview
The MVP sunset color scoring uses cloud type analysis rather than directional cloud positioning. This pragmatic approach captures ~80% of prediction accuracy while remaining implementable without satellite imagery.

### Core Logic

#### Sunrise vs Sunset Directionality

**Sunset** (sun setting west):
- Need clear west (solar horizon) for light penetration
- Want clouds to the east (antisolar horizon) to catch and reflect light

**Sunrise** (sun rising east):
- Need clear east (solar horizon) for light penetration
- Want clouds to the west (antisolar horizon) to catch and reflect light

#### Why not directional analysis for MVP?
Directional cloud analysis (checking if clouds are east vs west of observer) requires satellite imagery and complex positional calculations. This is deferred to v1.1.

#### Type-based proxy instead
We use cloud **altitude layers** as a proxy for cloud effectiveness:

- **Low clouds (0-3km)**: Heavy, thick clouds (stratus, stratocumulus)
  - Sit near horizon and block sunlight penetration
  - Bad for sunsets regardless of position
  - **Penalty: high**

- **Mid clouds (3-8km)**: Medium-altitude clouds (altocumulus, altostratus)
  - Mixed effect; typically positioned where they can catch light
  - Moderate for sunsets
  - **Neutral to good**

- **High clouds (8km+)**: Thin, wispy clouds (cirrus, cirrostratus)
  - Sit high in sky; naturally catch and reflect sunset light
  - Good for sunsets regardless of position
  - **Bonus: high**

### Scoring Algorithm (Simplified)

```
cloudLayerScore = 0

if highCloudCover is 30-70%:
  cloudLayerScore += 25 points

if midCloudCover is 20-60%:
  cloudLayerScore += 20 points

if lowCloudCover is >50%:
  cloudLayerScore -= 30 points

visibilityScore = (visibility > 8000m) ? 20 : 10
humidityScore = (100 - humidity) / 5    // 0-20 points
aerosolScore = scoreAerosols()          // 0-20 points

totalScore = cloudLayerScore + visibilityScore + humidityScore + aerosolScore
colorGrade = totalScore / 20            // out of 5
```

### Accuracy Tradeoff

- **Captures**: 80% of real sunset quality variation
- **Misses**: 20% edge cases where clouds are on wrong side of horizon
- **When it fails**: All clouds positioned at solar horizon (blocking light path) or all at antisolar horizon (all drama, no light penetration) - rare cases

### Data Sources

- **Cloud layers**: Open-Meteo Forecast API (free, no auth)
  - `cloud_cover_low`, `cloud_cover_mid`, `cloud_cover_high`
- **Visibility**: Open-Meteo Forecast API
- **Humidity**: Open-Meteo Forecast API
- **Aerosols**: Open-Meteo Air Quality API (free, no auth)
  - PM2.5, Aerosol Optical Depth, Dust

### Roadmap: v1.1 Enhancement

Add directional analysis:
1. Get satellite imagery (GOES/NOAA)
2. Calculate solar azimuth for sunset time
3. Score clouds in antisolar zone (opposite sun) as bonus
4. Score clouds in solar zone (toward sun) as penalty
5. Increases accuracy from 80% → 95%

### MVP Boundary

**In scope:**
- Cloud type/altitude analysis
- Humidity, visibility, aerosol factors
- REST API returning 0-5 score
- 7-day forecast grid

**Out of scope:**
- Satellite imagery direction detection
- Antisolar point positioning
- Cloud coverage by azimuth direction
- Multi-location user preferences
- Database persistence

---

## Planned Feature: Location Type

### Overview

Each location can be tagged with one or more photography types, enabling map filtering and type-specific UI behaviour.

### Location Types

| Value | Meaning |
|---|---|
| `LANDSCAPE` | Good for landscape/scenic photography |
| `WILDLIFE` | Good for wildlife/animal photography |
| `SEASCAPE` | Good for seascape/coastal photography |

A location can be multiple types simultaneously (e.g. a coastal cliff that is both SEASCAPE and LANDSCAPE).

### Implementation

Uses JPA `@ElementCollection` — Spring handles the join table for both H2 and PostgreSQL automatically, with no manual migration needed for the collection table:

```java
@ElementCollection
@Enumerated(EnumType.STRING)
private Set<LocationType> locationType;
```

The `LocationType` enum lives alongside `GoldenHourType` and `TideType` in the entity package.

### Map View Filtering

Filter toggles in the map view header for LANDSCAPE, WILDLIFE, and SEASCAPE:

- **OR logic**: show locations matching ANY selected type
- If all selected: show all locations
- If LANDSCAPE only selected: show locations that include LANDSCAPE (even if they are also tagged as SEASCAPE or WILDLIFE)
- Filters compose with other map filters (golden hour type, tide type, accessibility) using AND logic between filter groups

### Seascape-Specific UI

Any location tagged as SEASCAPE displays tide information prominently in the detail view alongside sunrise/sunset predictions. This helps photographers plan around tidal access — low tide exposing rock pools, high tide giving clean reflections.

Tide data is only fetched when `tideType != NOT_COASTAL` (existing behaviour). The SEASCAPE type tag controls UI presentation; the `tideType` field controls whether tide data is requested.

---

## Planned Feature: Authentication & JWT

### Overview

Spring Security with stateless JWT authentication. The app is home-hosted and shared with a small number of trusted users (family/friends). No public self-registration — users are created by the admin only.

### Roles

| Role | Permissions |
|---|---|
| `ADMIN` | All endpoints, plus Manage tab (user management) |
| `USER` | Forecast view, outcome recording, prediction feedback |

### Data Model — `app_user` table

```sql
CREATE TABLE app_user (
    id           BIGSERIAL PRIMARY KEY,
    username     VARCHAR(50) UNIQUE NOT NULL,
    password     VARCHAR(255) NOT NULL,   -- BCrypt hash
    role         VARCHAR(10) NOT NULL,    -- ADMIN or USER
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
```

Flyway migration: `V4__create_app_user.sql`

### JWT Design

- **Access token** — short-lived (24 hours)
- **Refresh token** — longer-lived (30 days), stored in `refresh_token` table
- Token signed with HMAC-SHA256 using a secret from `application.yml` (`jwt.secret`)
- Standard claims: `sub` (username), `roles`, `iat`, `exp`

### Token Expiry Warnings

- At 7 days remaining: show a subtle amber banner ("Your session expires in 7 days — click to refresh")
- At 1 day remaining: show a more prominent warning
- "Refresh session" button — exchanges refresh token for a new access token without re-login
- Frontend checks token `exp` on mount and after each API call

### API Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/auth/login` | None | Exchange credentials for JWT |
| `POST` | `/api/auth/refresh` | Refresh token | Issue new access token |
| `POST` | `/api/auth/logout` | Bearer | Invalidate refresh token |
| `GET` | `/api/users` | ADMIN | List all users |
| `POST` | `/api/users` | ADMIN | Create a user |
| `PUT` | `/api/users/{id}/enabled` | ADMIN | Enable or disable a user |
| `PUT` | `/api/users/{id}/role` | ADMIN | Change a user's role |

### Security Configuration

- `SecurityFilterChain` bean in `SecurityConfig.java`
- `JwtAuthenticationFilter` — validates token on every request, populates `SecurityContext`
- `/api/auth/**` — public (no authentication required)
- All other `/api/**` — requires authentication
- CSRF disabled (stateless JWT API)
- CORS configured to allow frontend origin

### Frontend Login Flow

1. App loads → checks for stored JWT in `localStorage`
2. If no token or expired → redirect to `/login`
3. Login form POSTs to `/api/auth/login` → stores JWT
4. All subsequent API requests include `Authorization: Bearer <token>` header
5. On 401 response → attempt refresh; if refresh fails → redirect to `/login`

### Backend Dependencies to Add

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

### Configuration

```yaml
jwt:
  secret: YOUR_JWT_SECRET_BASE64   # min 256-bit, generate with: openssl rand -base64 32
  access-token-expiry-hours: 24
  refresh-token-expiry-days: 30
```

### Unit Testing

- `JwtServiceTest` — token generation, validation, expiry, claims extraction
- `AuthControllerTest` — login happy path, bad credentials (401), disabled user (401), refresh flow
- `UserControllerTest` — ADMIN can create/list/toggle; USER gets 403
- `JwtAuthenticationFilterTest` — valid token passes, expired token 401, malformed token 401

---

## Planned Feature: Admin Manage Tab

### Overview

Admin-only section in the frontend. Accessible via a "Manage" tab in the navigation. Hidden entirely from `USER` role accounts.

### User Management UI

- Table of all users: username, role, enabled/disabled, created date
- Toggle enabled/disabled per user (calls `PUT /api/users/{id}/enabled`)
- Change role per user (ADMIN/USER dropdown)
- "Add User" button — simple form: username + temporary password + role

### Privacy Notice

A visible notice in the Manage tab explains that feedback (prediction accuracy) is attributed to the logged-in user and is visible to admins. This is shown once on first use of the feedback feature and stored in `localStorage` so it doesn't reappear.

---

## Planned Feature: Cloudflare Tunnel

### Purpose

Allow the home-hosted Spring Boot backend (running on Mac Mini) to be securely accessible from the iPhone via Pushover deep links and from anywhere via the web UI — without opening router ports.

### Architecture

```
iPhone / Browser
      │
      ▼
Cloudflare Edge (HTTPS)
      │
      ▼
cloudflared daemon (running on Mac Mini)
      │
      ▼
Spring Boot on localhost:8081
```

### Setup Steps

1. Install `cloudflared` on Mac Mini: `brew install cloudflared`
2. Authenticate: `cloudflared tunnel login`
3. Create tunnel: `cloudflared tunnel create goldenhour`
4. Configure `~/.cloudflared/config.yml`:
   ```yaml
   tunnel: <tunnel-id>
   credentials-file: ~/.cloudflared/<tunnel-id>.json
   ingress:
     - hostname: api.goldenhour.example.com
       service: http://localhost:8081
     - service: http_status:404
   ```
5. Add DNS record: `cloudflared tunnel route dns goldenhour api.goldenhour.example.com`
6. Run: `cloudflared tunnel run goldenhour`

### Proof of Concept First

Before full production setup, validate the concept:
1. Run a temporary tunnel (`cloudflared tunnel --url http://localhost:8081`)
2. Access from iPhone and confirm forecast data loads
3. Confirm HTTPS works end-to-end
4. Only then configure named tunnel with custom domain

### Launchd (macOS service)

Run `cloudflared` as a background service on Mac Mini startup:

```bash
cloudflared service install
```

This installs a launchd plist. Verify with: `sudo launchctl list | grep cloudflared`

### Security Considerations

- Cloudflare Tunnel provides authenticated HTTPS automatically
- JWT authentication on the Spring Boot side provides application-level security
- No ports opened on home router
- Traffic to `/api/**` requires valid JWT (only `/api/auth/login` is public)
- Consider adding Cloudflare Access policy for an extra layer of protection

---

## Planned Feature: Tide Data

### Overview

For coastal photography locations, tide state is a critical factor in composition. Low tide exposes rock pools and foreshore textures; high tide gives clean reflections. The Open-Meteo Marine API provides free tide height data.

### Open-Meteo Marine API

```
GET https://marine-api.open-meteo.com/v1/marine
  ?latitude=55.7702
  &longitude=-2.0054
  &hourly=ocean_wave_height,wave_period,ocean_current_velocity
  &daily=wave_height_max,wave_period_max
  &timezone=UTC
```

Note: Tide height (sea level) is not in the Marine API. Use the WMO standard `hourly=sea_surface_height` if available, or fall back to the **NOAA CO-OPS API** for UK tidal predictions.

**Alternative — UK Tidal API (ADMIRALTY)**:
- `https://admiraltyapi.azure-api.net/uktidalapi/` — UK-specific tidal predictions
- Requires a free API key
- Returns predicted high/low tide times and heights for named stations

**Recommended approach**: Use NOAA or Admiralty for tide times; use Open-Meteo Marine for wave height/period.

### Location Metadata — `tideType`

Each location has an optional `tideType` field indicating the photographer's preference:

| Value | Meaning |
|---|---|
| `HIGH_TIDE` | Photographer prefers to shoot at high tide |
| `LOW_TIDE` | Photographer prefers to shoot at low tide |
| `ANY_TIDE` | Both tides are suitable |
| `NOT_COASTAL` | Inland location — no tide data fetched |

Tide data is only fetched for locations where `tideType != NOT_COASTAL`.

### Location Metadata — `goldenHourType`

Each location has a `goldenHourType` indicating which solar events are worth photographing there:

| Value | Meaning |
|---|---|
| `SUNRISE` | East-facing — sunrise only |
| `SUNSET` | West-facing — sunset only |
| `BOTH_TIMES` | Good for both sunrise and sunset |
| `ANYTIME` | Interesting light at any time (e.g. diffuse cloud conditions) |

`ForecastService` uses `goldenHourType` to skip evaluations for irrelevant solar events (e.g. don't evaluate SUNRISE for a west-facing location).

### Data Model — `Location` entity

```sql
CREATE TABLE location (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    lat              DECIMAL(8,4) NOT NULL,
    lon              DECIMAL(8,4) NOT NULL,
    golden_hour_type VARCHAR(20) NOT NULL DEFAULT 'BOTH_TIMES',
    tide_type        VARCHAR(20) NOT NULL DEFAULT 'NOT_COASTAL',
    description      TEXT,
    active           BOOLEAN NOT NULL DEFAULT TRUE
);
```

Flyway migration: `V5__create_location.sql`

Locations are migrated from `application.yml` config to the database at startup if the table is empty.

### `LocationService`

- `findAllActive()` — returns all active locations
- `findByName(String name)` — lookup by name
- `shouldEvaluateSunrise(Location)` — returns true if `goldenHourType` includes sunrise
- `shouldEvaluateSunset(Location)` — returns true if `goldenHourType` includes sunset
- `isCoastal(Location)` — returns true if `tideType != NOT_COASTAL`

### `TideService`

- `getTideState(Location, LocalDateTime)` — returns current tide state (RISING / FALLING / HIGH / LOW)
- `getNextHighTide(Location, LocalDate)` — returns time of next high tide
- `getNextLowTide(Location, LocalDate)` — returns time of next low tide
- Result included in forecast evaluation if location is coastal

### Forecast Response — Tide Data

When the location is coastal, include in the evaluation response:

```json
{
  "tideState": "FALLING",
  "nextLowTideTime": "2026-02-23T14:22:00Z",
  "nextLowTideHeightMetres": 0.4,
  "tidePreference": "LOW_TIDE",
  "tideAligned": true
}
```

`tideAligned = true` when the tide state at solar event time matches the photographer's `tideType` preference.

---

## Planned Feature: Backend-Heavy / Thin UI Architecture

### Principle

All calculations, scoring, and data enrichment happen on the backend. The frontend is a pure render layer — it receives fully processed data and displays it. No business logic in the frontend.

### API Response Shape (Target)

`GET /api/forecast` returns fully enriched data per location per day:

```json
{
  "location": {
    "name": "Bamburgh Castle",
    "lat": 55.6090,
    "lon": -1.7099,
    "goldenHourType": "SUNSET",
    "tideType": "LOW_TIDE"
  },
  "forecasts": [
    {
      "date": "2026-02-23",
      "dayLabel": "Today",
      "sunrise": {
        "time": "07:12 UTC",
        "rating": 3,
        "summary": "Moderate mid-level cloud, reasonable chance of colour.",
        "cloudLow": 10,
        "cloudMid": 45,
        "cloudHigh": 60,
        "visibilityKm": 18,
        "windSpeedMs": 6.2,
        "windDirectionDeg": 225,
        "windCardinal": "SW",
        "precipitationMm": 0.0,
        "humidity": 68,
        "pm25": 6.2,
        "aerosolOpticalDepth": 0.09,
        "azimuthDeg": 121
      },
      "sunset": {
        "time": "17:34 UTC",
        "rating": 5,
        "summary": "Exceptional — high cirrus above clear horizon, moderate AOD, low humidity.",
        "cloudLow": 5,
        "cloudMid": 20,
        "cloudHigh": 75,
        "visibilityKm": 24,
        "windSpeedMs": 4.1,
        "windDirectionDeg": 200,
        "windCardinal": "SSW",
        "precipitationMm": 0.0,
        "humidity": 52,
        "pm25": 8.1,
        "aerosolOpticalDepth": 0.14,
        "azimuthDeg": 248
      },
      "tide": {
        "tideState": "FALLING",
        "nextLowTideTime": "2026-02-23T18:12:00Z",
        "nextLowTideHeightMetres": 0.3,
        "tideAligned": true
      },
      "actualOutcome": null
    }
  ]
}
```

### Backend Enrichment Responsibilities

The backend computes and includes:
- `dayLabel` ("Today", "Tomorrow", "Mon 24 Feb")
- `windCardinal` (N, NE, E … from degrees)
- `visibilityKm` (converted from metres)
- `azimuthDeg` (sunrise/sunset azimuth from `solar-utils`)
- `tideAligned` (computed from tide state + location preference)
- `actualOutcome` (joined from `actual_outcome` table if it exists)

The frontend never re-derives these values — it renders exactly what the backend sends.

---

## Planned Feature: Sunrise/Sunset Azimuth on Map

### Overview

Show the direction the sun rises or sets from a given location. This helps the photographer understand which direction to face and what background to compose against.

### Implementation

- `SolarService` already wraps `solar-utils` for sunrise/sunset times
- `solar-utils` also provides `solarAzimuth(lat, lon, dateTime)` — add this to `SolarService`
- Include `azimuthDeg` in the forecast response (see API response shape above)

### Frontend Display — Detail View Only

Azimuth is shown only in the **location detail view** (single location, expanded), not on the main forecast timeline cards. This avoids cluttering the summary view.

In the detail view:
- A compass rose or simple arrow indicating the direction of sunrise/sunset
- A short label: "Sunrise direction: ENE (72°)"

### Map View

When the map tab is the default view:
- Markers for each location
- Clicking a marker opens the location detail panel on the right
- Detail panel includes azimuth compass indicator
- The azimuth is visualised as a line radiating from the location pin in the direction of the solar event

---

## Planned Feature: Prediction Accuracy Feedback

### Overview

After a solar event has passed, users can provide structured feedback on whether Claude's prediction was accurate. This builds a ground truth dataset for evaluating model performance over time.

### Feedback Values

| Value | Meaning |
|---|---|
| `ACCURATE` | Prediction matched reality closely |
| `SLIGHTLY_OFF` | Was in the right ballpark but misjudged intensity |
| `VERY_INACCURATE` | Prediction was significantly wrong |

### Data Model — `prediction_feedback` table

```sql
CREATE TABLE prediction_feedback (
    id                    BIGSERIAL PRIMARY KEY,
    forecast_evaluation_id BIGINT NOT NULL REFERENCES forecast_evaluation(id),
    user_id               BIGINT NOT NULL REFERENCES app_user(id),
    accuracy              VARCHAR(20) NOT NULL,  -- ACCURATE / SLIGHTLY_OFF / VERY_INACCURATE
    notes                 TEXT,
    recorded_at           TIMESTAMP NOT NULL DEFAULT NOW()
);
```

Flyway migration: `V6__create_prediction_feedback.sql`

### API Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/feedback` | USER | Submit feedback for a forecast evaluation |
| `GET` | `/api/feedback?evaluationId=` | USER | Get feedback for an evaluation |
| `GET` | `/api/feedback/summary` | ADMIN | Aggregate accuracy stats (admin dashboard) |

### Attribution

Feedback is attributed to the logged-in user (`user_id` FK). Only one feedback record per user per forecast evaluation. A second submission replaces the first (upsert).

### Privacy Notice

The first time a user submits feedback, show a notice: "Your feedback is attributed to your account and is visible to the app admin. It is used only to improve forecast accuracy."

Store acknowledgement in `localStorage` so the notice doesn't appear on every submission.

### Admin Dashboard — Accuracy Stats

The admin Manage tab includes a simple accuracy summary:

```
ACCURATE:          42 (68%)
SLIGHTLY_OFF:      13 (21%)
VERY_INACCURATE:    7 (11%)

By model:
  Sonnet:  ACCURATE 71%, SLIGHTLY_OFF 21%, VERY_INACCURATE 8%
  Haiku:   ACCURATE 64%, SLIGHTLY_OFF 23%, VERY_INACCURATE 13%

By days_ahead:
  0-1 days:  ACCURATE 80%
  2-3 days:  ACCURATE 70%
  4-7 days:  ACCURATE 58%
```

---

## Planned Feature: Map as Default View

### Overview

The default tab when the app loads is a **map view** showing all configured locations as pins. The forecast timeline (list view) is a secondary tab. This makes the geographic relationship between locations clear and helps the user plan a shoot route.

### Map Implementation

- **Leaflet.js** with OpenStreetMap tiles (free, no API key) as the map library
- Each location is a pin with a colour-coded marker: red (1-2), amber (3), green (4-5), grey (no data)
- Marker colour reflects today's best rating (max of sunrise and sunset)
- Clicking a pin opens a popup with:
  - Location name
  - Today's sunrise rating + time
  - Today's sunset rating + time
  - "View details" link → opens location detail panel
- A "List view" tab switches to the existing `ForecastTimeline` component

### Navigation Tabs

```
[ Map (default) ]  [ List ]  [ History ]  [ Manage (admin only) ]
```

### Frontend Dependencies to Add

```bash
npm install leaflet react-leaflet
```

---

## Planned Feature: Canary Validation Before Wider Rollout

### Validation Plan

Before sharing with trusted users (family/friends), validate that predictions are meaningfully accurate:

1. **Self-test period (2 weeks minimum)**:
   - Run forecasts daily for all configured locations
   - Go out or observe locally on days with rating ≥ 4
   - Record actual outcomes via the UI
   - After 2 weeks, check Grafana scatter plot: predicted vs actual

2. **Acceptance criteria**:
   - At least 10 evaluation/outcome pairs collected
   - `ACCURATE` rate ≥ 60% on ratings 4-5 (high confidence predictions)
   - No systematic bias (e.g. always over-predicting on cloudy days)

3. **Canary user**:
   - Add one trusted user (admin creates account)
   - Monitor their feedback via admin dashboard
   - Tweak prompt wording or criteria if feedback shows a pattern

4. **Wider rollout**:
   - Only after canary user confirms predictions feel reliable
   - Add remaining trusted users (up to ~5)
   - Keep `enabled` flag so individual accounts can be paused

---

## Planned Feature: Docker Production Deployment

### Architecture

Spring Boot backend runs as a Docker container on a Mac Mini. Cloudflare Tunnel exposes it to the internet without opening router ports.

### Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/goldenhour-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### docker-compose.prod.yml

```yaml
version: '3.8'
services:
  backend:
    image: goldenhour-backend:latest
    build: ./backend
    ports:
      - "8081:8081"
    environment:
      SPRING_PROFILES_ACTIVE: prod
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/goldenhour
      SPRING_DATASOURCE_USERNAME: ${DB_USER}
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
    depends_on:
      - postgres
    restart: unless-stopped

  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: goldenhour
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    restart: unless-stopped

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    volumes:
      - grafana_data:/var/lib/grafana
    depends_on:
      - postgres
    restart: unless-stopped

volumes:
  postgres_data:
  grafana_data:
```

### Deployment Workflow

```bash
# On development machine — build and push image to local registry or save as tar
cd backend && ./mvnw clean package -DskipTests
docker build -t goldenhour-backend:latest .

# On Mac Mini — pull or load image, then:
docker-compose -f docker-compose.prod.yml up -d

# Cloudflare Tunnel runs as a launchd service (separate from Docker)
```

### Environment Variables on Mac Mini

Store secrets in a `.env` file (gitignored) in the deployment directory:

```bash
ANTHROPIC_API_KEY=sk-ant-...
DB_USER=sunset
DB_PASSWORD=your-secure-password
JWT_SECRET=base64-encoded-256-bit-secret
```

### Health Check

Spring Boot Actuator provides a health endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: when-authorized
```

Cloudflare Tunnel health probe: `GET /actuator/health`

### Backend Health Status Indicator

- React component in top right corner
- Only visible to ADMIN users
- Fetches `/actuator/health` endpoint every 30 seconds
- Displays: UP (green dot) or DOWN (red dot)
- Shows status only, no auto-reconnect logic
- Useful for troubleshooting from girlfriend's place
