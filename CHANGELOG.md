# Changelog

All notable changes to Golden Hour are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

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
