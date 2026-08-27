# CLAUDE.md — The Photographer's Golden Hour

## Project Overview

A full-stack app that evaluates sunrise/sunset colour potential at configured locations.

- **Backend**: Spring Boot 4 REST API — Open-Meteo weather + air quality, WorldTides tide data, Claude (Anthropic SDK) evaluation, H2/PostgreSQL persistence, scheduled runs, notifications (email/Pushover/macOS toast).
- **Frontend**: React 19 + Vite + Tailwind — map view (Leaflet), Plan matrix (day × event heatmap, popup and location sheet), Coming up feed, Operations tab, JWT-authenticated. Outcome recording is API-only (no UI since 2026-02-27 — `docs/engineering/v1-retirement-plan.md` §8).

---

## What's Built

**Core pipeline**: Open-Meteo → Claude → H2 | Scheduled evaluations (06:00 + 18:00 UTC, T through T+7) | Notifications (email, Pushover, macOS toast)

**Scoring**: Two scores (Fiery Sky 0–100, Golden Hour 0–100) + 1–5 star rating | Dual-tier: enhanced (directional cloud) for PRO/ADMIN, basic (observer-point) for LITE | 3-point cone cloud sampling at 113 km offset (azimuth ±15°) via `GeoUtils` + `DirectionalCloudData` | Far-field sample at 226 km for horizon strip vs blanket detection | Cloud approach risk detection (`CloudApproachData`, `SolarCloudTrend`, `UpwindCloudSample`) | Cloud inversion scoring (`InversionScoreCalculator`, 0–10 from temp-dew gap/wind/humidity/low cloud) | Solar-aware slot selection (`findBestIndex()`) | Sahara Dust badge (AOD > 0.3 or dust > 50 µg/m³ with PM2.5 < 35) | Rising tide warning badge (high tide within ±90 min of solar event)

**Evaluation**: Single `ClaudeEvaluationStrategy` parameterised by `EvaluationModel` | `PromptBuilder` + `MetricsLoggingDecorator` (GoF Decorator) | `NoOpEvaluationStrategy` for wildlife | `AnthropicApiClient` with `@Retryable` | Composable `AtmosphericData` (5 sub-records + `DirectionalCloudData` + `CloudApproachData`)

**Aurora photography**: NOAA SWPC polling (`AuroraPollingJob`, 5 min, night-only) via `NoaaSwpcClient` (Kp, forecast, OVATION, solar wind Bz, G-scale alerts; per-endpoint caching) | `AuroraStateCache` FSM (**two** states: IDLE ⇄ ACTIVE — the alert *level* QUIET/MINOR/MODERATE/STRONG is a separate field, not a state) | `WeatherTriageService` (3-point northward transect triage, any-clear-hour pass at < 75% overcast) | `ClaudeAuroraInterpreter` (single `claude-haiku-4-5` call for all viable locations; returns 1–5★ + summary + detail) | `AuroraOrchestrator` (orchestrates full pipeline; dual-signal `deriveAlertLevel()` using Kp + OVATION) | `MetOfficeSpaceWeatherScraper` (Jsoup scraper, 60 min refresh) | Bortle enrichment via lightpollutionmap.info (`LightPollutionClient`, `BortleEnrichmentService`, sb_2025 dataset with SQM conversion) | Map filter + popup aurora section | `AuroraBanner` React component (shows Kp reading) | Aurora viewline endpoint (OVATION nowcast southernmost visibility boundary, `AuroraViewlineOverlay` with colour-coded zones) | Alert levels: QUIET/MINOR/MODERATE/STRONG

**Daily Briefing / PhotoCast Planner**: "Go or movie night?" pre-flight check | `BriefingService` scheduled at 04:00/14:00/22:00 via `@Scheduled` | Open-Meteo weather + DB tide lookup for all enabled colour locations | Per-slot verdict (GO/MARGINAL/STANDDOWN) based on low/mid cloud, precipitation, visibility, humidity thresholds + building trend checks | Region rollup via majority vote | Lunar-driven tide classification (spring/king via `TideClassificationService`) | `AtomicReference` in-memory cache + `daily_briefing_cache` DB persistence | Served to the Plan tab's `HeatmapGrid` — event-based columns (next 6 solar events), sunrise/sunset sub-columns, aurora grid integration (the "quality slider" component itself was orphaned at `b4b96d4b` and deleted in v1 retirement D2; its gating machinery — `qualityTier`/`isCellVisible`, pinned to always-show since the orphaning — was deleted with it in D3, so every cell is visible unconditionally now) | `BriefingBestBetAdvisor` still runs at build time, but the `/api/briefing` `bestBets`/`bestBetStatus`/`bestBetsWithdrawn`/`bestBetModel` fields it populates have **no frontend renderer** — the advisor itself keeps three live admin consumers (`BriefingModelTestService` → `BriefingModelTestView`, `PipelineRunPickEntity` → `PipelineRunsView`'s picks, `AdvisorReplayController`); see `docs/engineering/v1-retirement-plan.md` §8.1 | `DailyBriefingResponse.stale`/`partialFailure`/`failedLocationCount` also lost their only client reader in v1 retirement D2 — v2 shows the forecast's age only, a v2 "this briefing is partial" surface is a product call (§8.4); `auroraTonight`/`auroraTomorrow` keep a live reader (`WindowFirstDoors.jsx`) | Briefing evaluation (`BriefingEvaluationService` + `BriefingEvaluationController`, `GET /scores` + `DELETE /cache` — the SSE endpoint is gone) with drill-down scoring per region | Briefing model comparison test: `BriefingModelTestService` calls Haiku/Sonnet/Opus with same rollup, persists to `briefing_model_test_run/result` tables, `BriefingModelTestView` with agreement highlighting

**Plan tab (the day × event MATRIX)**: The Plan tab — no flag, no default, no frozen comparison
control (the earlier "v1" layout and its `usePlanLayout` toggle were retired in full,
`docs/engineering/v1-retirement-plan.md`). The pane is
**six pictures**, not a list: `WindowFirstHeatStrip` draws a day-column × sunrise/sunset-row grid of
heat-field thumbnails (`utils/windowFirstMatrix.js` for the grid maths, `utils/heatField.js` for the
kernel), each card carrying the served verdict word and tint, a star-band spread histogram, the
best-reachable line, its topics and the served `BEST BET`/`ALSO GOOD` legend; empty cells say why
(*this morning has gone* / *past the end of the forecast*) and a travel day is a div, never a button.
Clicking a card opens **one popup** (`WindowSheetDialog`) holding everything the deleted accordion
held — big field with greedily-placed location chips, region rail, an always-rendered prose slot,
topic rows, the tide row, the ranked spot strip. The origin control, the search trigger and the light
times are the masthead's **tick line** (`MastheadTickLine`); a place's own six windows are a
**location sheet** (`LocationFourDaySheet`) stacked over the popup. Below the matrix sit the two
doors (Hot topics, Regional planner), which have no other v2 home. ⚠️ **At most one dialog may claim
to be the modal, and the guard is per-route rather than global**: the shared `Modal` takes a
`stacked` opt-in (M5) that makes a covered layer `inert` and drops its `aria-modal`, and the shell
refuses to open a THIRD layer at all (search over a sheet over the popup was reachable only through
the masthead button, and it painted underneath the sheet — every `Modal` is `fixed inset-0 z-50`, so
paint order is DOM order). The supported stack is two deep. ⚠️ The property holds because every
route that could break it was closed one at a time — the search button and the settings cog were
both reachable by Tab from an open dialog, and `UserSettingsModal` is a SIBLING of the shell in
`App`, invisible to `stackedOverPopup` and taking no opt-in, so the cog closes every Plan dialog
before it opens. A new route out of the plan has to do the same; nothing enforces it structurally.
It is deliberately **not** a focus trap; `useDialogFocus` records why containment is refused
app-wide, and Tab still leaves the topmost dialog and cycles to the rest of the page.
`docs/engineering/plan-matrix-plan.md` is the port plan and its §4 records every place the design
bundle and the codebase were made to disagree on purpose.

**Plan-screen confidence**: One uniform, quiet confidence channel across the Plan screen so a far-horizon "Worth it" reads more provisional than a same-day one — layered *alongside* the star (quality) signal, never replacing it | Backend `ConfidenceDeriver` derives a per-region `Confidence` (HIGH/MEDIUM/LOW) from forecast horizon (dominant term) + rating spread (`BriefingRatingStats` min/max/`ratingRange`) + coverage; **null** when unknown (zero coverage → reads provisional, not falsely confident) | Computed at serve time in `BriefingService.enrichWithCachedScores` (both build + serve paths, request-time `today`), rides the `daily_briefing_cache` JSON on `BriefingRegion.confidence` (no migration; legacy payloads fail-soft to null) | Frontend `confidenceUtils` (`resolveConfidence` fail-soft, `CONFIDENCE_TREATMENT` fill-scale + provisional flag, `scaleRgbaAlpha`) — grid cells dim the verdict fill by tier, the grid's drill-down `VerdictPill` shows a marker-on-low via `ProvisionalMark`, the popup's and location sheet's own `ProvisionalMark` mounts do the same, and the field/matrix/map read a `confidenceScalar` off the same derivation; **stars are never touched** (Best Bet, the summary-strip pills, and the mobile region rows this channel also used to reach died with `DailyBriefing`/`CloseToHome` in v1 retirement D2 — see `docs/engineering/v1-retirement-plan.md`) | Durable per-evaluation `forecast_evaluation.confidence` (V127, horizon-only `ConfidenceDeriver.fromHorizon`) for analytics | Hot-topic certainty **vocabulary** — an orthogonal axis (`topicCertainty` + `CertaintyChip`) labelling each topic **almanac** (tides/astronomy — fixed) / **forecast** (weather-driven) / **chance** (NLC — unforecastable), derived client-side from topic type

**Command pattern**: `ForecastCommand` → `ForecastCommandFactory` → `ForecastCommandExecutor` | `RunType` enum (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM, WEATHER, TIDE, LIGHT_POLLUTION, BRIEFING) | Per-run-type model config (Haiku/Sonnet/Opus via Admin UI)

**Optimisation strategies**: 7 toggleable strategies (SKIP_LOW_RATED, SKIP_EXISTING, FORCE_IMMINENT, FORCE_STALE, EVALUATE_ALL, NEXT_EVENT_ONLY, TIDE_ALIGNMENT) per run type | `OptimisationSkipEvaluator` | Mutual exclusion validation | Active strategies snapshot on each `job_run`

**Cost tracking**: Token-based micro-dollar pricing from Anthropic SDK | `ExchangeRateService` (Frankfurter API, ECB data) | `CostCalculator` with cache/batch discount | Frontend shows GBP + USD costs

**Job metrics**: `job_run` + `api_call_log` tables | Admin dashboard (7-day stats, per-service breakdown) | Per-location failure tracking, auto-disable after 3 failures

**Resilience**: Resilience4j (`@Retryable` on `AnthropicApiClient` (529/content filter) and `OpenMeteoClient` (5xx/429) | `@ConcurrencyLimit(8)`) | Dead-letter mechanism | `RequestLoggingInterceptor` | SSE auto-reconnect after backend restart

**Locations**: Multi-location with map view (Leaflet/OSM) | Metadata: `SolarEventType`, `TideType` (H/M/L multi-select), `LocationType` (LANDSCAPE/WILDLIFE/SEASCAPE/WATERFALL) | Regions (geographic grouping) | Sunrise/sunset azimuth lines | Marker clustering (`react-leaflet-cluster`) | Star rating + location type filters | Emoji chip UI for metadata | Editable lat/lon

**Tide runs (Hot Topics)**: A spring/king tide is a multi-day **run**, not n unrelated days | `TideRunBuilder` → `TideRunDay` (nullable `HotTopic.tideRun`, no migration — rides the `daily_briefing_cache` JSON) derives per day: range vs the location's own mean, every extreme in the **Europe/London** local day, that day's sunrise/sunset, sea state, and a plain-language alignment verdict | `TideRunRow.jsx` draws a 24-hour cosine-interpolated curve against the solar events (night shaded, amber solar rules) and the pill carries a `SPRING RUN n/N` chip | One representative coastal location for the **whole run** (biggest single-day range), named in the footer as the caveat | Chart **replaces** the fact chips; days with no derivable range fall back to them | **The pill's headline is derived from the same run row the chart draws** — `KingTideHotTopicStrategy.alignmentInfo(day, nonExpired, unaligned, passed)` reads `TideRunDay.alignedEvent`, and a null run row yields *silence* rather than a denial. Two rules keep the two lines from arguing, and both were adversarial-review findings against the first cut: `alignedEvent` follows **the water the verdict names**, not `usefulPoint` (verdict rules 2 and 3 name the *other* extremum when the useful water misses the light and that one does not — keying off `usefulPoint` alone printed "no alignment" above `HW 06:18 · 58m after sunrise`), and an alignment with an **already-passed** event gets its own wording (`KING_ALIGNMENT_PASSED` / `SPRING_ALIGNMENT_PASSED`) rather than collapsing onto the denial, because the chart, the verdict and the aligned-day editorial line are all still on that card. `aligned` and `phrase` stay keyed to the useful water — they mark what a reader of *that* run type came for. | **The headline names its own scope, from a roster-wide tally computed off the same geometry** — `TideRunBuilder.rosterAlignment` measures every coastal location at *its own* sunrise/sunset with the same rule the representative's row uses, carried as `TideRunDay.RosterAlignment(sunriseAligned, sunsetAligned, measured)`, so the pill reads `tide aligned with sunrise at 47 of 61 coastal locations` rather than pairing one coastline's geometry with the roster's size. Identical rules are load-bearing: they make the representative a member of its own tally, so an aligned chart can never print above a zero. Costs **no new queries** — `fetchExtremes` already holds every location's extremes; only the per-location solar times are new. ⚠️ **Do not "fix" `forecast_evaluation.tide_aligned` and go back to counting it.** That was considered and rejected twice: it is written only by the *synchronous* engine the batch consolidation is retiring, and it answers a **preference-weighted** question (did the tide *state* match each location's configured `TideType` — production has 42 of 61 accepting `HIGH`) where the badge asks an astronomical one true at all 61. The `countTideAlignedByTargetType` query and both strategies' `ForecastEvaluationRepository` dependency are gone; the drill-down's `TideMetrics` is fed from the same tally | **The denominator is the coastal roster, not the measured subset** — they diverge when a location has no stored extremes, and "of 52" on an aligned card beside "· 61 coastal locations" on the unaligned card of the same run is two totals for one roster. An unmeasured location counts as not aligned: it understates the fraction, which is the safe direction | **`HotTopicEventEnricher.resolveTideEvent` treats the run row as authoritative** and takes `SolarEventFreshness`. Both rules were review findings against the first cut: falling through to the tally when `alignedEvent` is null printed a lead and a window badge above the headline's own denial (harmless only while the tally came from an empty column), and the tally is **clock-free**, so without the freshness check a card saying "tide alignment already passed" still pointed a lead at that morning. A null `eventType` makes `PlanWindowProjector` drop the topic silently — no badge, no day — which is how all of this surfaced It was a `forecast_evaluation.tide_aligned` count, which asks a different question (did the tide *state* match each location's own `TideType`, in a golden/blue-hour-sized window) and cannot distinguish "nobody aligned" from "no rows written" — so on a king tide, where the useful water is HIGH, every LOW-preference location counted for nothing and the card read "no tide alignments" above a chart saying `HW 04:58 · 39m before sunrise`. The counts still drive the **drill-down** (`TideMetrics` sunrise/sunset counts), which is the question they actually measure | A king run also carries `highWaterRank` — distance to the highest water on record there (`"0.4 m off the record"` / `"highest recorded here"`). Not metres over mean high water: `springTideThreshold = 1.25 × avgHigh`, so that figure is the spring excess plus a per-location constant, and not a percentile either, since the king classification is itself an above-P95 test. Gated on `p95HighMetres != null` (one spring–neap cycle, the same gate the spring chip rides) **and** `dataPoints ≥ 700` (~6 months) — a location added last fortnight has a maximum, not a record

**Tide data**: WorldTides API, weekly refresh, `tide_extreme` table | `TideService` derives state/next tides from DB at evaluation time | Tide history preservation (windowed merge, not delete-all) | 12-month backfill capability | Tide stats endpoint (avg/max high, avg/min low) | SEASCAPE-filtered refresh | Spring/king classification is **lunar only** (`LunarPhaseService`), integrated into PromptBuilder and BriefingBestBetAdvisor

**Wildlife UI**: Hourly comfort rows (temp/wind/rain) between sunrise–sunset | Green 🐾 marker | No Claude call

**Waterfall UI**: Colour forecast AND hourly comfort rows | 💦 marker | Scores excluded from cluster marker averages (waterfall photography ≠ sky colour)

**Auth**: JWT (HMAC-SHA256, 24h access, 30-day refresh) | ADMIN / PRO_USER / LITE_USER roles | Self-registration with email verification + Turnstile CAPTCHA | First-login password change gate | Session expiry warnings | Marketing email opt-in

**Storm surge**: Weather-driven storm surge calculation for coastal tidal locations | `StormSurgeService` (inverse barometer effect + wind setup) | Coastal parameters on locations (V60) | Surge forecast columns on forecast_evaluation (V61) | Integrated into forecast pipeline and prompt

**Cloud inversion**: `InversionScoreCalculator` — likelihood scoring (0–10) from three independent surface terms (temp-dew gap 0–5, wind 0–3, low cloud deck 0–2) behind three gates: low cloud outside 15–80%, no measured temperature reversal aloft, or a stable layer deeper than the viewpoint each cap the score below the band it would otherwise reach | Vertical profile via `temperature_925hPa`/`temperature_850hPa` — **STRONG requires a measured reversal**, and no profile caps at MODERATE (never zero: `ForecastDataAugmentor` logs a WARN so the feature can't die silently) | Location elevation + overlooks_water metadata (V65) | `inversion_score` + `inversion_potential` columns on forecast_evaluation (V66) | Prompt states the measured reversal alongside the score, and tells Claude to weigh it over the score when they disagree

**Astro conditions**: `AstroConditionsService` — nightly observing quality scores for dark-sky locations | Template-scored (cloud cover, visibility, moonlight modifiers) | `AstroConditionsController` with `/api/astro/conditions` and `/api/astro/conditions/available-dates` endpoints | `astro_conditions` table (V64)

**User settings**: `UserSettingsService` + `UserSettingsController` — home location (postcode via `PostcodesIoClient` geocoding, lat/lon) and per-user drive times | `DriveTimeResolver` abstraction (replaces per-location `drive_duration_minutes`) | `user_drive_time` table (V67)

**Dynamic scheduler**: DB-backed scheduler management (`scheduler_job_config` table, V68) | `DynamicSchedulerService` — registers job targets via `@PostConstruct`, schedules on `ApplicationReadyEvent`, pause/resume/trigger/reschedule | `SchedulerConfig` with dedicated `ThreadPoolTaskScheduler` (pool=5) | `SchedulerController` (ADMIN-only, `/api/admin/scheduler`) | `SchedulerView.jsx` in the Operations tab's Scheduler sub-tab | Aurora jobs auto-disabled when `aurora.enabled=false` (`DISABLED_BY_CONFIG` status) | Replaces all `@Scheduled` annotations (tide refresh, daily briefing, aurora polling, Met Office scrape, run progress cleanup)

**Admin features**: User management | Expandable health status widget with live SSE service probes (mail, Claude API, Open-Meteo, tides) | Model comparison test harness (A/B/C across regions) | Prompt test harness (async, replay, comparison) | `ManageView`'s own `#manage/<tab>` hash round-trip at mount (the app-level `viewMode`/`#plan`/`#map`/`#manage` hash navigation died with v1 retirement D1 — `#plan`/`#map`/`#manage` deep links are no-ops, `docs/engineering/v1-retirement-plan.md` §8.11) | Client-side pagination | Confirmation dialog before Claude evaluation with cost estimate

**Deployment**: Docker (alpine, health checks, non-root) on a **Linux host** (not macOS — production moved off the Mac ~2026-03-16) | Cloudflare Tunnel (`photocast.online`) | Postgres 17 bind-mounted at `/home/gregochr/goldenhour-data/postgres` | Daily backups (keep last 7, systemd timer) | Persistent logs at `/home/gregochr/goldenhour-data/logs/` (volume-mounted to `/app/logs` in container; rolling `goldenhour.log` 50MB×30 days + `surge-calibration.log` 90 days)

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
│       └── db/migration/            V1–V99 Flyway migrations
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

# Run all tests — ALWAYS with this exclusion; see the Docker note below
cd backend && ./mvnw clean verify -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false

# Prompt regression tests (requires ANTHROPIC_API_KEY)
cd backend && ANTHROPIC_API_KEY=... ./mvnw test -Pprompt-regression

# Frontend tests
cd frontend && npm run test
```

### ⚠️ There is no Docker on this machine — and that is not going to change

**Do not tell anyone to start Docker Desktop, and do not spend a session looking for it.** This
guidance previously said "Docker must be running to run the backend test suite", which sent session
after session hunting a daemon that has never been installed here. What follows is the truth.

**Six** classes extend `IntegrationTestBase` (`backend/src/test/java/.../integration/`) and start a
`postgres:17-alpine` Testcontainer with `spring.flyway.enabled=true`. There is no failsafe plugin
and no surefire exclusion, so they run in the ordinary `test` phase — meaning a bare `./mvnw test`
or `./mvnw clean verify` fails here with an opaque `Could not find a valid Docker environment`,
not a skip.

**So the local gate always carries the exclusion.** This is the normal command, not a workaround:

```bash
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress \
  -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
```

**⚠️ CI is the only place a migration is proven BEFORE it merges.** Be precise about this, because
the sloppy version of the claim is wrong: `application-dev.yml` and `application-prod.yml` both set
`spring.flyway.enabled: true` against Postgres, so **production runs every migration at startup** —
that is what migrations are for — and a `dev`-profile run against a real Postgres executes them too.

What is true is narrower, and is the part that matters when reviewing a PR:

- The **default local paths never run migrations at all.** `application-local.yml` sets
  `spring.flyway.enabled: false` (H2 + `ddl-auto: update`), and the default test profile does the
  same (H2 + `create-drop`). So the ordinary edit-compile-test loop cannot exercise one.
- The only **pre-merge** execution is `IntegrationTestBase`, which turns Flyway on against a
  Postgres 17 Testcontainer — and that needs Docker, which this machine does not have.
- CI's `Backend — Build, Test & Coverage` job runs `./mvnw clean verify` with **no exclusion** on a
  runner that does, so CI is where a migration is proven before merge.

If you do have a native Postgres to hand, running the `dev` profile is a genuine local verification
route — it just is not the default and does not exist on this machine.

Two consequences worth internalising, because they end the recurring conversation:

- **A migration PR is not "unverified" locally — it is *pending CI*.** Write the Testcontainers
  test, run the local gate above, open the PR, and read CI's Backend job before merging. Saying
  "I couldn't verify the migration" is only half the story; the other half is that nobody can,
  here, and CI already does.
- **Postgres-specific SQL cannot break local dev**, because local dev never runs migrations at all.
  Write the migration for Postgres and do not contort it for H2.

Running the app locally needs no Docker either (H2 file DB).

Default local credentials: `admin` / `golden2026`

H2 console: `http://localhost:8082/h2-console` (JDBC: `jdbc:h2:file:./data/goldenhour`, user: `sa`, pass: empty). Reset: delete `backend/data/goldenhour.mv.db` and `.lock.db`.

---

## Key Architecture Decisions

- **Forecast DTO** — `ForecastController` returns `ForecastEvaluationDto` via `ForecastDtoMapper`. Role-based score selection: LITE gets basic scores, PRO/ADMIN get enhanced. `basic_*` entity columns never appear in API response.
- **⚠️ Two Jackson object graphs, and the API uses the one you are not looking at.** `AppConfig.objectMapper()` is a **Jackson 2** bean (`com.fasterxml.jackson`) injected into ~a dozen services for internal serialisation — `OpenMeteoClient`, `EvaluationViewService`, `RunProgressTracker`, the model/prompt test services. But this is Boot 4 / Framework 7, and `@RestController` responses are serialised by an **auto-configured Jackson 3** (`tools.jackson`) `JsonMapper`: there is no `MappingJackson2HttpMessageConverter` in the handler adapter's converter chain at all. 48 files are on Jackson 2, 8 on Jackson 3. Consequence: **`AppConfig.objectMapper()` is not in the HTTP response path**, so its configuration — notably that it never disables `WRITE_DATES_AS_TIMESTAMPS` — cannot affect the API wire format. A review that reads that bean and concludes the API is at risk has read the wrong graph; this has already happened once (an architecture review flagged it, and the fix hunt landed on the wrong mapper). The wire format is pinned by `backend/src/test/java/com/gregochr/goldenhour/controller/JsonDateFormatContractTest.java` (landed in #666), which asserts through the full Spring context precisely because a hand-built mapper in a test would reproduce the same mistake.

- **Backend-heavy** — all calculations on backend. Frontend is a pure render layer. All four of the Plan-tab exceptions this bullet used to list are now **discharged**: `BriefingDay.peak` has been write-only since the day rail was retired at P2 (the matrix replaced it — `docs/engineering/v1-retirement-plan.md` §8.2), the grid cell reads `BriefingRegion.meanRating` computed at serve time by `PlanWindowProjector`/`enrichWithCachedScores` (Phase 3 of `docs/engineering/plan-verdict-consolidation-plan.md`), "Close to home"'s client-side proximity ranking died with `CloseToHome.jsx` in v1 retirement D2 (the backend endpoint survives with zero callers — §8.3), and the v1 arm's own copies of the roll-up and the cell derivation — kept deliberately byte-identical because v1 was the pilot's frozen comparison control — were retired with it. What is left is a **reach-scoped** class of client-side derivation, licensed rather than exceptional: P8's location sheet, which takes a max over **one location's own** windows for its `◎ best here` tag and counts its own rows for its lead line; the matrix's spread histogram (star-band counts) and its best-reachable line, both over each window's reach-gated pool (plan-matrix A10/A11); the page-level conflict message's ceiling (`planConflicts.ceilingOf` — a max across every window and every location, sanctioned by plan-matrix §5, and the one member that *does* aggregate across locations); and the popup prose slot's "this region's own best window". None of these re-derive a *server-owned* verdict or Best-pick math — the ban in plan §2.12 is on that, and each of these reads rather than recomputes. ⚠️ **That class is NAMED and has a recorded exit; it is not a precedent for client aggregation.** Reach is per-user (`/api/user/settings/reach`, never ETag'd), so "best *within reach*" has no servable answer on the shared `GET /api/briefing` payload at all — the same reasoning that put "Close to home" on its own per-user contract. They are counts of *places you could drive to*, never "N of M scored" (§6 clause 4), and ⚠️ **none of them may say "within reach" unless a drive time exists to have gated on** — `BriefingWindow`-derived `card.reachMeasured` is the single producer of that answer and six surfaces read it, because an unknown drive passes every tier and a reader with no postcode was being told six times over that a filter had run. The exit is plan-matrix §8's optional **O-4**, a never-cached per-user endpoint; until then it is debt with a name. A third client rule *filters* rather than derives: `windowFirstTopics.js` intersects each topic's served `regions` with the origin scope, exempting whole-sky types by a client type map, because `regions` means something different per strategy (A8). `docs/engineering/plan-panel-data-contracts.md` records the rest.
- **Panel data contracts split by data ownership, not by panel** — the Plan-tab panels that are views of the *same* shared forecast snapshot derive from one `GET /api/briefing` payload, because two panels fetching independently can disagree about the same location. A panel earns its own REST contract only when it answers a different question about **differently owned** data. "Close to home" was the one that qualified (per-user home postcode + drive times) — its client died with `CloseToHome.jsx` in v1 retirement D2 (`GET /api/briefing/close-to-home` survives with no caller, `docs/engineering/v1-retirement-plan.md` §8.3), and the live per-user contracts the same principle now protects are `/api/user/settings/reach` and `/api/user/settings/light`. Personal data must never ride the shared briefing payload, because ETag revalidation requires `Cache-Control: private, no-cache`, which persists the body to a browser HTTP cache that JavaScript cannot evict on logout. `HttpCachingConfig` excludes `/api/user/settings*` for exactly that reason and `HttpCachingConfigTest.personalDataPathsAreNeverFiltered` pins it. See `docs/engineering/plan-panel-data-contracts.md`.
- **Command pattern** — `ForecastCommand` → `ForecastCommandFactory` → `ForecastCommandExecutor`. Controllers/schedulers are thin wrappers.
- **Evaluation strategy** — single `ClaudeEvaluationStrategy` parameterised by `EvaluationModel`. `EvaluationConfig` produces `Map<EvaluationModel, EvaluationStrategy>` bean. `NoOpEvaluationStrategy` for wildlife.
- **Directional cloud sampling** — 3-point cone sampling (azimuth ±15° at 113 km offset, the geometric horizon distance for low cloud) to smooth Open-Meteo grid-cell boundary effects. Falls back to single-point. Prompt rules: solar low cloud >60% = blocked, 40-60% = penalise, <20% = clear. `findBestIndex()` avoids post-sunset/pre-sunrise slots. Far-field sample at 226 km (2× horizon) along the solar azimuth detects strip vs blanket: if solar horizon low cloud ≥50% and the far reading drops ≥30pp → THIN STRIP, soften penalty; if both ≥50% → `[FAR CORRIDOR ALSO CLOUDY]`, which **corroborates and carries no penalty of its own** — the near reading alone sets the ceiling, and with substantial mid/high canvas the rating may reach 3. THIN STRIP wins when a pair satisfies both, matching `isThinStrip`'s else-if (its prose used to claim an absolute `far ≤30%` arm the code has never tested — the prose moved onto the code, since changing `isThinStrip` would move the strip override's thresholds). ⚠️ **Never give the far reading escalatory force again.** 285 of 532 promptable blanket calls (**53.6%**) sat over an ERA5-open corridor, and the error is **bimodal** — +2.9pp when the far reading softens, ~+68pp when it hardens — so it may soften and must never confirm. Both cheap alternatives are dead: no threshold retune reaches a 3pp forecast drop, and no constant bias correction fits a two-mode error.
- **Cloud approach risk** — detects cloud approaching the solar horizon that a single event-time snapshot would miss. Two signals: (1) `SolarCloudTrend` — hourly low cloud at the solar horizon from T-3h to event time; a peak-vs-earliest increase of 20+ pp triggers a `[BUILDING]` label that tells Claude to penalise fiery_sky by 15–30 points. (2) `UpwindCloudSample` — current low cloud at an upwind point along the wind vector vs the model's event-time prediction; if current is much higher, the model may be too optimistic about clearing. `CloudApproachData` record composes both signals into `AtmosphericData`. `ForecastDataAugmentor` assembles the data from Open-Meteo; `PromptBuilder` formats it as a `CLOUD APPROACH RISK:` block in the user message. V51 migration adds persistence columns. The **combined signal** (both `[BUILDING]` and upwind current ≥60%) used to be an absolute 1–2★ ceiling that additionally told Claude to disregard a clear horizon, a present canvas and favourable aerosol, to ignore the at-event upwind value, and to write the summary about nothing else; it is now a **bounded penalty** (fiery_sky −20..−30, rating cap 3) with those evidence-nullification clauses and the summary gag removed. Measured basis: over the **545** firings a prompt was actually built for in six months (the other 85% of trigger-positive slots were stood down by triage before any prompt existed), observed horizon cloud separated by only **+3.4pp** — 36.3% fired vs 32.9% not — on skies averaging 36% observed cloud. ⚠️ **The demotion is unconditional and is NOT F2.** F2 keyed an exemption on the *coned* horizon reading — the exact reading Copt Hill 2026-03-11 proved misleading — and stays rejected; the cap applies whenever both signals stand, conditioned on nothing. Copt Hill still lands one band above its recorded outcome under the demotion: that is the stated price, not an oversight.
- **Cloud inversion scoring** — two rules worth not re-deriving. **Moisture is counted once**: the temp-dew gap and relative humidity are the same measurement (a gap ≤ 1 °C implies RH 92.7–94.2 % across −5 to 25 °C), so scoring both put 6 of 10 points on one reading and made every calm, saturated dawn total 9 before cloud was considered. **STRONG requires evidence, not inference**: an inversion is temperature *rising* with height, so no combination of surface readings alone may reach the band — `InversionScoreCalculator` demands a measured reversal from `temperature_925hPa` (fallback `850hPa`) against `temperature_2m`, and with no profile at all caps at MODERATE rather than claiming or abandoning the call (`ForecastDataAugmentor` logs a WARN so the feature cannot die silently). Low cloud is a **gate**, not a garnish — outside 15–80 % you are either inside the murk or there is no cloud to form a sea, and both used to score STRONG. The prompt states the measured reversal alongside the score because handing Claude a number and asking for it back makes the "evaluation" an echo of the calculator.
- **Aerosol proxy** — AOD + PM2.5: high AOD + low PM2.5 = dust (warm tones ✓); high AOD + high PM2.5 = smoke (haze ✗). No competitor does this.
- **Virtual threads** — `spring.threads.virtual.enabled: true`; `forecastExecutor` uses `newVirtualThreadPerTaskExecutor()`.
- **RestClient** — synchronous `RestClient` everywhere (no WebFlux). Open-Meteo via `@HttpExchange` + `HttpServiceProxyFactory`.
- **Declarative resilience** — Resilience4j; `@Retryable` with `MethodRetryPredicate` implementations; `@ConcurrencyLimit(8)`.
- **Location metadata** — production locations DB-managed via Admin UI (no YAML seeding). `application-local.yml` has locations for local dev.
- **JWT** — stateless HMAC-SHA256; refresh token stored hashed (SHA-256) in `refresh_token` table.
- **Freemium UI** — breadcrumbs not paywalls. See `docs/product/freemium_ui_strategy.md`.
- **Two tide axes, and neither may answer for the other.** *What kind of event* is **lunar**: spring = syzygy ±1 day, king = a perigean spring (syzygy ±1 d and perigee ±1.5 d). *Which dates* the water is biggest is **measured from stored heights** (`TideSizeIndex`), because a port's peak lags syzygy by 1–2 days — the age of the tide, a coastline property no epoch arithmetic recovers. *How big* it is comes from the same heights against that port's own thresholds. ⚠️ **Never OR the height test into the king label again.** `BriefingSlot.TideInfo.heightAboveP95`/`heightAboveSpringThreshold` are *size* tests that were once named `isKingTide`/`isSpringTide` and OR'd with `lunarTideType` in four places, so a big spring tide printed a card about "the moon's closest approach" 4.5 days from perigee. The size chips (`highWater`, `highWaterAnomaly`, `highWaterRank`) are likewise gated on **size**, not on the run's label — king-only was the same conflation one level down. ⚠️ **That rule is scoped to KING, and over-applying it to SPRING emptied the Plan tab for a week.** "King" names a *cause* (perigean syzygy), so labelling by outcome is a false claim; "spring" claims only that the water is big, which the height test measures directly. `SpringTideHotTopicStrategy.isSpringNotKing` and `CoastalTideFactsBuilder.buildSpring` therefore qualify on **either** axis, and the two must stay identical — the second builds the chips *under* a pill the first has already shown, so a narrower rule there strands the card rather than suppressing it. Detection cannot be lunar-only because the lunar window is 2–3 dates wide from noon while a run is 5–6: against the Aug 2026 new moon (12 Aug ~17:38 UTC — the *lunation* of that date's total solar eclipse; the eclipse anchors the day, not the minute, since greatest eclipse is ~17:46 and London's max 18:13 UTC) it covered 12–13 Aug and the measured run was 12–17 Aug peaking on the **14th**, which read REGULAR at 1.77 d out. The "a port clears its threshold for reasons that are weather" defence is **false** — `tide_extreme` holds WorldTides *harmonic predictions*, no surge — so the height arm is astronomy taken at the coastline, the only place the age of the tide is visible. **King trumps spring per DAY, never per window**: window-wide suppression let a king tide on one date delete a spring card three days later. The invariant is *at most one tide topic of either kind on any one date*. **A run keeps ONE name for its whole length, and the name comes from its syzygy.** `LunarPhaseService.nearestSyzygyIsPerigean(date)` answers "is the run this date belongs to perigean" *without* `classifyTide`'s ±1-day gate; the two strategies partition every tidal day on it, so no date can land on both cards and none can fall between them. `KingTideHotTopicStrategy.isTidal` is the shared "is there a tide here" test (syzygy on either axis, or water above this port's own spring threshold); the label is a separate question asked once per date. ⚠️ **This is NOT the forbidden height-into-king conflation** — height still decides no part of the king label, and a huge non-perigean tide stays SPRING (pinned by `detect_bigWaterOnANonPerigeanRun_emitsNothing`). It widens only *which dates may ask the moon*, which is the same split this bullet already draws: kind of event from the moon, which dates from the water. It replaced a per-date test that labelled a king run's first days KING and its lagging peak SPRING — one tide, two names on adjacent cards. ⚠️ **Both strategies must fetch their representative slot through `findTidalSlot`, never through `findKingTide`/`findSpringTide`**: on a run's lagging days the date's own `lunarTideType` is REGULAR, so the narrower accessors return null and the next line dereferences it. `CoastalTideFactsBuilder`'s two selectors take `isTidal` for the same reason — they build the chips *under* a card whose label the caller has already settled, so re-deriving it there can only disagree. `TideSurfaceAgreementTest` pins the Plan tab and the "Coming up" feed to the same answer, driving both from one height and one threshold so neither fixture can pre-satisfy its own predicate — which is exactly how the last regression hid.
- **`LunarPhaseService` owns no astronomy — it delegates to `solar-utils`.** It used to count mean synodic/anomalistic months from two reference epochs, and both were wrong: the perigee epoch by 3.45 d, the new-moon epoch by 0.761 d, with the mean-month assumption adding drift on top (real perigee intervals run 24.6–28.6 d, not a constant 27.55). Measured error: syzygy ±1.15 d, illumination ±7.1 pp, **perigee ±4.65 d** — against a 0.5 d window, making king classification noise (one hit in twelve months, on the wrong date). `LunarCalculator` was *already a bean* and already accurate (its extrema match Meeus within 0.7 h); `SupermoonHotTopicStrategy` was calling its illumination on one line and the broken perigee on the next. Nearest syzygy/perigee are now found by locating turning points of illumination/distance, memoised per date (~1.4 ms/date uncached). ⚠️ **`classifyTide` measures perigee from the *syzygy instant*, not per date** — per-date, the days on the perigee side of a syzygy qualify while the far side does not, splitting one event's run across two labels. ⚠️ The old tests asserted `getLunationFraction(REFERENCE_NEW_MOON) ≈ 0` and `isMoonAtPerigee(REFERENCE_PERIGEE)`, both **true by construction for any epoch** — they pinned the bug. Every date in the suite is now an externally checkable event.
- **Hot topics are recomputed LIVE on every serve, so build-time enrichment must ride a carrier — never the briefing payload.** `BriefingService.getCachedBriefing` calls `hotTopicAggregator.getHotTopics` unconditionally per request and rebuilds the response from those live topics; `cached.hotTopics()` is read only as the equality comparand and as the exception fallback. So a field derivable only during `refreshBriefing` and written into `daily_briefing_cache` is serialised on the build path and thrown away on the first request. Worse, because `HotTopic` is a record whose `equals` compares every component, a persisted-but-not-live field makes that equality shortcut fail and **forces** the overwrite — persisting it *guarantees* it is discarded. The working shape is an in-memory `AtomicReference` populated in `refreshBriefing` and read back inside `detect()`: `NlcClarityService`, `MeteorClarityService`, and now `SurgeCurveService`. Refresh over the **5-day** `dates` list, not the 4-day topic window — serve time uses the *request* day, so a briefing built Monday and served Tuesday asks for T+4 relative to the build. Series must be `List<Double>`, never `double[]`: a record component compared by identity makes the cache-equality check false on every request and rebuilds the whole response each time. Accepted cost: after a restart the carrier is empty until the next cycle and the pill degrades to its fact chips — never synthesise to fill that gap.
- **Every surge magnitude on the pill names its own instant** — the quantity is sampled at two different moments and only one is ever on screen, so a bare number gives the reader no way to tell a timing difference from a disagreement. The curve's verdict reads `peak +0.72 m at 14:00`; the persisted chip (shown only when the chart is suppressed) reads `0.6 m above normal at high water`. The chip's wording is **qualitative on purpose**: `ForecastDataAugmentor` samples at `nextHighTideTime`, so "at high water" is true by construction, while the exact clock time is unrecoverable — `SurvivorSignals.Readings` stores no timestamp and a day has two high waters, so naming one would be a guess dressed as a measurement. Do not turn it into a specific time without persisting the instant it was taken at.
- **The surge chart is a sibling of the tide chart, and drawn as a forecast** — `TideRunRow` maps a boolean (`high ? HIGH_Y : LOW_Y`) because it plots shape, and a 1 m and a 6 m range render identically; `SurgeRunRow` plots **metres**, so it maps a domain to a range. A different function, not a parameter. Only pure geometry is shared (`components/chart/solarDayGeometry.js` — axis, clock parsing, edge pinning); the JSX frame is deliberately duplicated so `TideRunRow`'s tests passing *unedited* proves the extraction was pure. The trace is **dashed** because a tide is an almanac and a surge is a weather forecast, and adjacent charts must not imply equal confidence. The zero line is the **predicted astronomical tide**, not sea level — the tide datum is undocumented upstream (`TideService` sends no datum parameter), so no absolute water level may be claimed. A **0.5 m domain floor** prevents a quiet day being auto-scaled into drama, and a null hour **breaks the path** rather than being drawn as calm. The curve and the pill's "N m above normal" chip agree by construction (same input field, same pure function); a whole-day **envelope** check catches a stale carrier, and is deliberately not an equality test at a guessed hour, because `SurvivorSignals.Readings` carries no sample timestamp.
- **Tide runs are ranked by emphasis, never reordered** — Hot Topics' ordering spine is **time**, so a multi-day tidal run stays one card per day in date order. A single multi-row card for a four-day event sits at one point in a chronological list and breaks the flow for every topic around it (tried, reverted); demoting or collapsing the badly-timed days introduces a second, competing ordering into one list and hides that the run *continues* on exactly the days someone planning Thursday needs. Only the aligned day's verdict takes the topic accent. The **run** is carried on each day (`dayNumber`/`dayCount`) rather than as its own object, so the `SPRING RUN 2/4` chip ties the cards together without a second payload shape. One representative location for the whole run — choosing per day lets the curve jump coastlines mid-run, so a reader comparing Tuesday to Thursday compares two places — and it is **named** in the footer, since alignment differs by ~20 min across a coastline a topic may span. A **one-day run claims no peak**: every day is trivially the biggest, and the badge would assert a comparison never made. All clock times and metres are formatted **on the backend** (the chart's axis is a local day; converting on the client would put the timezone rule in two places). The chart is `aria-hidden` and the verdict string is the entire accessible answer — do not hide it.
- **"Close to home" — backend only now.** `GET /api/briefing/close-to-home` + `CloseToHomeService`/`CloseToHomeResponse` survive with zero callers since `CloseToHome.jsx`/`CardHoverPreview.jsx` died in v1 retirement D2 (`docs/engineering/v1-retirement-plan.md` §8.3). The payload's design (`cards.size()`/`withinReach` definitionally equal and pinned by test, `CloseToHomeResponse.NextWindow` carrying the `Card` record itself rather than a copy) still stands as documentation of the endpoint; a v2 route is an owner decision, not a mechanical port of the deleted UI.
- **Plan-screen confidence** — a per-region `Confidence` (HIGH/MEDIUM/LOW) derived by `ConfidenceDeriver` from forecast horizon (T+0/1 HIGH, T+2/3 MEDIUM, T+4+ LOW), downgraded one band (floor LOW) on wide rating spread (`ratingRange ≥ 2`) or thin coverage (< half the roster scored), and **null for zero coverage**. ⚠️ **Its rating stats are the region's VOTING slots** (`BriefingSlot.votingSlots` — non-canopy, all-canopy fallback), so the channel qualifies the verdict it is attached to and a rated wood is not counted as sky locations disagreeing; the coverage DENOMINATOR stays `scoreable`, which is canopy-inclusive where rated, so the ratio is slightly pessimistic for a wood-bearing region — downgrade-only, the safe direction. And a region that has SOMETHING scored but nothing that votes is floored at **LOW explicitly** rather than left null: null is not silence on the client (see the `MAX_INFERRED_TIER` cap below), so it would have read *less* provisional exactly where the evidence is thinnest. **Horizon is no longer the only dominant term**: a region whose *voting* roster is ≤ `DEGENERATE_VOTING_ROSTER` (5) is floored at LOW outright regardless of horizon, and one below `FRAGILE_VOTING_ROSTER` (10) is downgraded a band — so a T+0 region *can* read LOW. Both thresholds are derived from `rollUpVerdict`'s arithmetic rather than chosen: GO fires at 20% of the **viable** slots, so one GO carries any region with ≤ 5 viable, and reaching that band takes fewer than half the roster standing down exactly when the roster is under 10. ⚠️ **The floor keys on the roster; the degeneracy keys on the runtime viable count**, which is per-day and per-event — so it catches a *structurally* small region and cannot catch a large one that has nearly all stood down. `BriefingService.rosterOf` supplies the two denominators as a `RegionRoster`: **scoreable** (coverage — excludes a canopy slot only while it holds no rating) and **voting** (verdict — non-canopy, falling back to the full list for an all-canopy region). They differ for any wood-bearing region in bluebell season, and since the canopy fix `scoreable` is a denominator alone. `rollUpVerdict` itself has no minimum-n term. The **null for zero coverage** is deliberate: an unknown signal must read provisional rather than falsely confident (unlike `Confidence.fromString`'s MEDIUM default). ⚠️ Null is reserved for **nothing scored at all** — it is not a general "we don't know" chute, because the frontend's fail-soft inference turns it into a horizon tier, not into silence. Computed at briefing **serve time** in `BriefingService.enrichWithCachedScores` (runs on both build + serve paths with request-time `today`, so the horizon stays fresh on re-serve) and rides the `daily_briefing_cache` JSON on the nullable `BriefingRegion.confidence` component — **no migration**; legacy payloads deserialize to null. The frontend treats it as **one uniform, quiet channel layered on the verdict** — `confidenceUtils.resolveConfidence` is fail-soft (prefers the backend field, falls back to a horizon-only tier **capped at `MAX_INFERRED_TIER` = medium**). That cap is load-bearing: an absent field means either a payload cached before the field existed *or* a live one where `ConfidenceDeriver` deliberately returned null (zero coverage), and the frontend cannot tell them apart — uncapped, both resolved to `high` at T+0, so an unscored small region rendered at full confidence, the exact failure the channel exists to prevent. A backend-supplied `high` is still believed; only inference is capped. Under-reporting is the safe direction; grid cells carry the full fill-decay gradient, and the grid's drill-down `VerdictPill`, the popup's and location sheet's `ProvisionalMark` all show a marker-on-low, while the field/matrix/map instead read a `confidenceScalar` off the same derivation to feed the heat kernel's continuous haze (Best Bet, the summary-strip pills and the mobile region rows this channel also used to reach died with v1 retirement D2); the **star/quality signal is never touched**. A durable, horizon-only per-evaluation `forecast_evaluation.confidence` (V127, `ConfidenceDeriver.fromHorizon`, written at the sole `ForecastService.buildEntity` site) records the same for analytics but gates nothing. Hot topics carry an **orthogonal certainty vocabulary** (`topicCertainty`: almanac / forecast / chance) derived client-side from topic type — a separate axis from horizon decay, since a fixed tide, a weather forecast, and an unforecastable NLC display are different *kinds* of certainty.
- **Evaluation eligibility (Gate 4)** — per-`daysAhead` policy in `NightlyEligibilityPolicy` (`service/batch/`): T+0/T+1 all stabilities (NEAR=Sonnet); T+2 SETTLED+TRANSITIONAL (FAR=Haiku); T+3 SETTLED only; T+4+ never. **Both engines consult it**: the batch pipeline via `resolve()`, and the synchronous `ForecastCommandExecutor.applyStabilityFilter` via `permitsHorizon()` (unified July 2026 — the former `evaluationWindowDays()` policy-proxy read is gone; that method is display-only everywhere). Grid-cell classification + `stability_snapshot` publishing has a single producer, `GridCellStabilityService`, shared by both engines. Note: the stability filter is bypassed entirely for manually triggered admin runs (deliberate — the user explicitly requested those slots). Per-cycle `[BATCH ELIG]` INFO log shows included/excluded by `(daysAhead × stability)`.
- **`daysAhead` is measured from the UK civil date, and `util/ForecastHorizon` is its only home.** A target date names a solar event at a UK location, so "today" is `Europe/London` — under BST the UTC date is a day behind between 23:00 and 00:00, which made every horizon one too many for that hour. It was derived on two calendars: London in the batch collector's window/past-date/disposition path, UTC in `ForecastService`. Inert until `IntradayEligibilityPolicy` became the first consumer to **branch** on it (`hasGuaranteedLaterLook` = `daysAhead > 0 && SUNSET`), at which point tonight's sunset read as tomorrow's and was skipped as though two further looks were guaranteed. The audit damage was **not** a row contradicting its own reason (an adversarial review killed that claim — the stability-skip row was written from the same UTC value the policy was handed, so it agreed with it): it was that `ForecastTaskCollector` fed its rows from *both* variables depending on branch — triage/error rows London, stability-skip/include rows UTC — so one run's trail could carry two horizons a day apart for the same date. Every horizon derivation now routes through `ForecastHorizon` with an injected `Clock`. The two qualifications a reviewer had to establish are both closed: `ForecastDtoMapper.toSparseDto`, whose serve-time horizon was also moved, turned out to have **no production caller** (orphaned by #289 — `toDto` serves `entity.getDaysAhead()`) and was deleted rather than kept on the right calendar; and the **synchronous engine's date range** moved to the UK calendar on 2026-08-12 (§8b). (Separately and on the same day, #484 collapsed four batch classes onto `ForecastHorizon` — they were already on the UK calendar, so that one was behaviourally inert.) ⚠️ **The sync engine's range had to move *because* `today` moved, not merely alongside it.** `ForecastCommandExecutor`'s already-past gate short-circuits on `!targetDate.equals(today)`, so it guards exactly one day — and this fix changes which one. Leave any caller handing that engine a UTC "today" and, in the divergent hour, it names a UK day the gate has just released and whose events are all over: a wasted slot becomes an *evaluated* one. So seven sites moved in one commit — `ForecastCommandFactory` (default range), `ForecastCommandExecutor` (the gate's `today`), `OptimisationSkipEvaluator` (FORCE_IMMINENT's same-day test, NEXT_EVENT_ONLY's search window), `ForecastController` (`POST /run`'s default date and the `GET` serve window) and `BriefingEvaluationController` (the sibling serve window, which shares two constants with that one, and whose divergence made a chip with no score behind it read as a *stand-down* rather than as unscored). **`now` stayed UTC** — `now.isAfter(eventTime)` compares instants against a UTC `LocalDateTime` from `solarService`, and is read as `LocalDateTime.now(clock.withZone(ZoneOffset.UTC))` from the same injected clock: one clock, two calendars, on purpose. **Deliberately still UTC** (an enumerated list in `ForecastHorizon`'s javadoc, because "everything routes through here" rots): FORCE_STALE, whose two sides are both stored-UTC and must share a calendar with each other; and `PromptTestService.resolveDates`, since `resolveTargetTypesForDate` in the same class takes its day from a caller-supplied UTC instant. (`ForceSubmitBatchService`'s JFDI range was a third entry, known-wrong rather than deliberate; it moved to the UK calendar in §8c. ⚠️ Two things there are worth keeping. JFDI "bypasses triage" means the **verdict** is bypassed, not triage itself — `fetchWeatherAndTriage` saves a `forecast_evaluation` row *before* returning, so a JFDI run writes rows and a UTC range wrote `days_ahead = -1` with `confidence = HIGH` into them. And the range steers a **second sink**: the dates ride `EvaluationTask.Forecast` into `cached_evaluation` via `ForecastResultHandler`, which is the rating the UI displays — so a JFDI run in that hour also wrote plan-cache rows for a day outside the 5-day window and none for T+3 inside it. Two reviewers reached opposite verdicts on whether this was user-visible and both were right about what they had checked: the mislabelled *columns* have no user-facing reader, the *dates* do.) `ForecastHorizon`'s javadoc carries a **second** list — still-UTC sites with no defence — kept apart from the deliberate list so a hedge cannot quietly become a completeness claim. Three of its four entries were then cleared: the two aurora `LocalDate.now()` twins (`ScheduledBatchEvaluationService`, `AuroraOrchestrator` — bare, so JVM-default; nothing pins `TZ` in the Dockerfile, so they were UTC only by Alpine's default) and `AlmanacService`'s feed anchor, whose UTC basis let something already over lead a list headed "Coming up" and turned its cache over an hour late. ⚠️ **The fourth was not converted to a calendar at all — it is the one case where the UK civil date is the wrong answer**, and it is now fixed by removing the calendar question rather than re-answering it. `AuroraForecastRunService`'s date names a *night*, and `computeWindowForDate` runs it dusk→dawn **across midnight**: at 00:30 BST the UTC anchor happened to point at the window in progress, while `Europe/London` would have labelled one twenty hours away as "Tonight". In GMT *both* calendars were wrong for several hours nightly — so never a timezone bug, but a "`LocalDate` cannot name a night" one. `currentNightDate()` now decides on an **instant** (`now.isBefore(dawn)` → yesterday's window is still running), the same rule `AuroraPollingJob.calculateTonightWindow()` has always used, which is why that method was right on either calendar. ⚠️ **Both** its anchors had to move together: the preview loop, and a `date.equals(today)` in `runForecast` switching between real weather triage and a fabricated flat 50% cloud — fixing only the first would have swapped live triage for made-up figures on the night just requested. Two test lessons from it: the class's `civilDusk`/`civilDawn` stub returned one fixed pair *for every date*, which is what made a date-confused selection look fine — it now answers per date, and four fixtures built on the fiction had to be rebuilt; and a triage test that **counts** calls survives the revert (triage runs once either way, just for the wrong night), so it pairs each `interpret` call's window with its cloud figure instead. Still open and separate: `WeatherTriageService`'s 6-hour lookahead means "real" triage from mid-afternoon still measures cloud outside the window it judges. See `docs/engineering/aurora-night-selection.md`. The two aurora *task* dates that did move are **labels** — nothing interprets them: their only readers are `taskKey()`, which reaches log lines, and `CustomIdFactory.forAurora` on the batch path, whose parsed date the result processor discards. Both carry comments saying so, because they are exactly what someone would later mistake for a night selector. Moving the range also lengthened the astro night window past the end of Open-Meteo's 7-day array — `AstroConditionsService.coversWholeNight` now declines to score a night whose dawn falls on a day the array never reaches, because `extractNightHours` returns a *truncated* night rather than an empty one and truncated nights score. Residual, pinned by a test so it is not refiled as a bug: an **explicitly named** past date is still evaluated — the gate has only ever guarded one day, no default range can reach such a date now, and naming one is how a backfill asks. See §8a and §8b of `docs/engineering/intraday-settled-refresh-plan.md`.

---

## Configuration

Never commit `application.yml`. Only `application-example.yml` is committed.

Key config: `anthropic`, `worldtides`, `spring.datasource`, `spring.flyway`, `spring.mail`, `notifications`, `forecast.locations`, `jwt`, `server.port`, `aurora` (enabled, poll-interval-minutes, light-pollution-api-key, noaa.*, met-office.*, triggers.*, bortle-threshold.moderate/strong).

---

## Database Migrations

| Range | Key tables/changes |
|-------|-------------------|
| V1–V3 | `forecast_evaluation`, `actual_outcome`, azimuth |
| V5–V9 | `locations` table (**plural** — `V5__create_locations.sql`, `@Table(name = "locations")`; the singular sent a hand-written query to a table that does not exist) + metadata side-tables `location_tide_type`, `location_location_type`, `location_solar_event_type` (all **singular**) |
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
| V55 | drive_duration_minutes column on locations |
| V56 | bortle_class column on locations (nullable integer) |
| V57 | aurora_forecast_result table |
| V58 | dew_point column on forecast_evaluation |
| V59 | daily_briefing_cache table |
| V60 | storm surge coastal parameters on locations |
| V61 | storm surge forecast columns on forecast_evaluation |
| V62 | sky_brightness_sqm column on locations |
| V63 | briefing_model_test_run + briefing_model_test_result tables |
| V64 | `astro_conditions` table (cloud/visibility/moon modifiers + explanations) |
| V65 | `elevation_m` and `overlooks_water` columns on locations (inversion detection) |
| V66 | `inversion_score` and `inversion_potential` columns on forecast_evaluation |
| V67 | User home location (postcode, lat/lon) + `user_drive_time` table |
| V68 | `scheduler_job_config` table + 5 seed rows (tide, briefing, aurora, met office, cleanup) |
| V89–V96 | Batch job run costs, cached evaluation, batch near/far term model, forecast triage |
| V97 | `evaluation_delta_log` table for empirical freshness threshold refinement |
| V98 | `stability_snapshot` table for persisting stability state across restarts |
| V99 | Batch observability: `custom_id`, `error_type`, `batch_id` on `api_call_log`; widen `error_message` to TEXT; partial indexes |
| V100+ | (not individually listed here — `ls backend/src/main/resources/db/migration/ \| sort -V` for the full set) |
| V127 | `forecast_evaluation.confidence` — durable horizon-derived per-evaluation confidence (nullable `VARCHAR(20)`, enum name); rides analytics, gates nothing |
| V145 | `regions.base_name/base_lat/base_lon` (nullable — a baseless region cannot be a Plan-tab origin) + `region_drive_time`, the **shared** base→roster matrix, keyed on `region_id` rather than on the region name; seeds the `region_drive_time_refresh` job at 03:10 |
| **latest** | **Deliberately not written down — every number recorded here has rotted.** Read it from the tree before adding a migration: `ls backend/src/main/resources/db/migration/ \| sort -V \| tail -1` |

---

## Where a rating lives — three stores, one of them a different engine

Reach for the wrong one and you will build something that compares two engines and calls the
difference a change. This cost a day on 2026-08-03.

| store | per-run history | written by | read by |
|---|---|---|---|
| `cached_evaluation` (briefing evaluation cache) | **no** — overwritten each run | `BriefingEvaluationService.writeFromBatch`, from the batch pipeline | `BriefingService.enrichSlot` — **this is the rating the UI displays**, keyed by location *name* |
| `forecast_score` (V108) | **no** — `uq_forecast_score_component` is UNIQUE on (forecast_type, location, date, event); latest evaluation wins, deliberately matching `cached_evaluation` semantics | the Pass-2 dual write in `ForecastResultHandler` | **`ForecastDtoMapper`** (the API DTO's Claude BLUEBELL rating) and **`SurvivorSignalReader`** (hot-topic components) — ⚠️ *not* a proving surface any more, whatever the older comments said |
| `forecast_evaluation` | **yes**, insert-only and never pruned | the **synchronous** engine | `GET /api/forecast` (the map's primary endpoint), `EvaluationViewService`, `ForecastCalibrationService` |

Two consequences worth stating plainly:

- **`forecast_evaluation` is not legacy dead weight — it is a second live engine.** The map view and
  the calibration gate both read it, and V128 (one of the newest migrations) indexes it. Do not
  "clean it up". The duplication is real and is already on the architecture review's list as the
  dual-engine consolidation; until that lands, both are load-bearing.
- **No store retains a per-location per-run history that the live pipeline writes.** Any feature
  needing "what was this rated last time" — a trend, a sparkline, a since-last-forecast diff —
  needs a new append-only sink first. `forecast_evaluation` looks like the answer and is not.
- ⚠️ **`forecast_evaluation` has stopped being a scoring table, whatever its name says.** Measured
  2026-08-03 over the preceding fourteen days: rows were written on **all fourteen**, and ratings on
  **one** (31 Jul, 207 of 268 rows; every other day zero). Roughly three quarters of the table's
  33k rows carry a null rating. It is now, in practice, a triage and base-forecast table. The batch
  pipeline does the scoring, and its two sinks (`cached_evaluation`, `forecast_score`) last wrote
  within 0.3 seconds of each other — the dual write is healthy.
- **This starves the calibration gate.** `ForecastCalibrationService` joins `forecast_evaluation` to
  `actual_outcome`; with ratings landing one day in fourteen it has almost nothing to score, before
  even reaching the fact that `actual_outcome` has no rows. Not broken — starved. Worth knowing
  before anyone cites it as evidence of forecast accuracy.

---

## Roles

| Role | Permissions |
|------|-------------|
| `ADMIN` | All endpoints + Operations tab |
| `PRO_USER` | Forecast, outcomes, locations, re-runs |
| `LITE_USER` | Read-only forecast; may also **record** outcomes (`POST /api/outcome`) |

> "Read-only" scopes to the *forecast* surface, not the whole API. Recording an outcome is deliberately
> open to any authenticated user: `actual_outcome` is the evidence base for the calibration gate, and
> observations are the scarce input, so restricting who may contribute one works against the thing the
> table exists for. Enforcement lives on the endpoints that *read* premium detail, not on this write.

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
`GET /api/locations` | `POST /api/locations` (ADMIN) | `PUT /api/locations/{name}/reset-failures` (ADMIN) | `GET|POST /api/regions` | `PUT /api/regions/{id}` | `PUT /api/regions/{id}/enabled` | `PUT /api/regions/{id}/base` (ADMIN) | `GET /api/regions/drive-times`

> **`POST /api/locations` is ADMIN**, and was not until 2026-08-26 — it was the one mutation on
> `LocationController` carrying no `@PreAuthorize`, so `/api/**` → `.authenticated()` let any LITE
> account create one. Unlike the tide and almanac endpoints this file once mis-documented as ADMIN
> (where the doc was wrong and Bearer was the intent), here the *code* was wrong: every sibling
> mutation was gated, the admin UI already mounted the creation screen behind `isAdmin`, and
> creation has real side effects — `enabled` defaults true, so the row joins the global roster
> immediately, and a coastal one spends a billable WorldTides request from inside
> `LocationService.add`.

> **`GET /api/regions/drive-times`** is the shared region-base drive-time matrix — `{regionId:
> {locationId: minutes}}`, from each region's admin-entered base town to the whole roster. Bearer
> with **no role gate**, by inheritance from `SecurityConfig`'s `/api/**` → `.authenticated()`; the
> Plan tab's origin move is ungated for the pilot, so a role gate here would break it for every
> non-admin.
>
> ⚠️ **It is ETag-revalidated, and that is only safe because it is user-independent.** "How far is
> this from Keswick" is the same answer for every reader; "how far is this from your house" is not,
> and stays on the never-cached `/api/user/settings/reach` for the reason that section records. The
> two must not be merged on the client either — `planOrigin.originReachMap` builds the away map
> from this matrix **alone** and never borrows `distanceMiles`, because those miles are measured
> from home and would put two journeys in one line on a card whose leave-by time a reader acts on.
>
> **`PUT /api/regions/{id}/base`** sets or clears a region's base town, and is deliberately its own
> endpoint rather than three more fields on the rename: a rename body carrying only `name` would
> deserialise the base fields to null, so every rename would silently clear the base and discard
> that region's whole drive-time matrix. All three null clears it; a partial base is a 400. Moving
> the base **discards** that region's stored drive times rather than leaving figures that measure a
> journey from the old town — unknown is safe, wrong is not — and the nightly
> `region_drive_time_refresh` job refills them.

### Users (ADMIN)
`GET|POST /api/users` | `PUT /api/users/{id}/enabled|role|reset-password` | `DELETE /api/users/{id}`

### Outcomes (Bearer)
`GET|POST /api/outcome`

### Admin tools (ADMIN)
`GET /api/metrics/job-runs|api-calls` | `GET|PUT /api/models` | `PUT /api/models/active|optimisation` | `POST /api/model-test/run|run-location|rerun` | `GET /api/model-test/runs|results` | `POST /api/prompt-test/run|replay` | `GET /api/prompt-test/runs|runs/{id}|results|git-info` | `GET /api/admin/calibration?from=&to=`

### Cloud verification (ADMIN)
`GET /api/admin/cloud-verification` | `POST /api/admin/cloud-verification/backfill` | `GET /api/admin/cloud-verification/backfill/status` — scores past forecasts against **ERA5 reanalysis**, needing no recorded outcome. `actual_outcome` is empty (zero rows ever), so the calibration gate has nothing to score; but every threshold the scoring rules turn on is a *cloud* claim, and cloud is machine-checkable. ⚠️ **Reanalysis is not observation.** ERA5 is a model reconstruction that assimilates observations unavailable when the forecast was issued — genuinely independent information, but still a model field, not a measurement. Where ERA5 and the forecast model share a bias (low stratocumulus, marine layers, orographic cloud) the comparison flatters the forecast. Read a disagreement as *"the forecast differs from a better-informed model"*, not *"the forecast was wrong"*. It says nothing at all about whether a sunset was beautiful — that still needs recorded outcomes. **Five archive points per evaluation** (3 solar-cone bearings + observer + 226 km far-solar), answering the two claims a forecast makes plus two questions about the sampling geometry itself: the *gap* (low cloud at the 113 km solar horizon — did the low sun get through?), the *canvas* (mid/high overhead at the observer — was there a screen to light up?), the *cone structure* (`horizon_low_min`/`max`, V142 — the forecast persists only the cone **mean**, which renders a uniform 60% deck and a 90/0/90 wall-with-gap identically), and the *far corridor* (`far_low_cloud`, V142 — the canvas-underlighting corridor the 113 km gate never reads: 226 km = √(2R·4 km) is the exact centre of a 4 km **mid** canvas's blocking corridor and only the near *edge* of an 8 km cirrus one, whose centre is ~319 km — physics corrected 2026-08-13, see the veto doc §8; also verifies the forecast's own `far_solar_low_cloud` claim for the first time). Report splits: `vetoFired`/`vetoNotFired` (`gapActuallyOpen` counts clear-horizon skies the veto suppressed), **`vetoUncapped`/`vetoCapped`** (below `MAX_UPWIND_DISTANCE_M` the upwind sample is a real advection nowcast; at the cap it is not — this is the D7 question), `byWindSunAngle`, **`byConeStructure`** (uniform/mixed/gapped by ERA5 cone spread — does gap error grow where the mean hides structure?) and **`byCorridor`** (near-vs-far divergence ≥30pp, the strip-vs-blanket threshold, with both `&midCanvas` and `&highCanvas` sub-buckets — `farCloudier&*` is the false-optimism case no current scoring rule covers. The **mid** cut is the direct one: 226 km is the geometric centre of a 4 km mid canvas's blocking corridor, where for an 8 km cirrus canvas it is only the near edge of one centred ~319 km, so `&highCanvas` is synoptic-scale proxy evidence pending a sixth archive point). The new statistics are reanalysis-internal, so the baseline offset cancels. Backfill is **async** (`202` immediately, `409` if already running) and works the backlog in committed batches of 100 — a synchronous pass cannot finish inside a proxy timeout. It is **self-healing**: rows missing *any* observation the current sampling records are cleared at the start of each run (so the first run after a sampling change deliberately re-verifies the whole history — that is the measurement pass), and a batch that returns no observations at all stops the run (`lastError`) rather than blanking the remaining backlog — those two rules only work as a pair. Gated on the archive's ~6-day lag; reads the same `findBestIndex` slot the forecast scored. See `docs/engineering/cloud-approach-veto-fix.md`.

### Calibration gate (ADMIN)
`GET /api/admin/calibration` — forecast accuracy vs **recorded outcomes**. The only non-self-referential accuracy measure in the project: prompt-regression tests compare Claude to hand-written expectations, the sky-rating eval harness to fixtures, and model-comparison to other models — all can stay green while forecasts drift from reality. `ForecastCalibrationService` joins `forecast_evaluation` to `actual_outcome` on (location, date, target type), keeps the newest run per (slot, horizon), and buckets **overall / per `daysAhead` / per model**. Each bucket carries signed mean error (separates optimism from pessimism), mean absolute error, exact-match and within-one rates, plus two decision-error counts: **missedOpportunities** (predicted ≤2, actual ≥4) and **wastedTrips** (predicted ≥4, actual ≤2). Run over a fixed window before and after any prompt or sampling-geometry change and diff the buckets — aggregation is deterministic per window. An absolute rating ceiling can only create missed opportunities, so that count gates relaxing the cloud-approach veto (see `docs/engineering/cloud-approach-veto-fix.md`).

### Aurora (Bearer / ADMIN for writes)
`GET /api/aurora/status` (Bearer) | `GET /api/aurora/locations` (Bearer) | `GET /api/aurora/viewline` (Bearer) | `POST /api/aurora/admin/enrich-bortle` (ADMIN) | `POST /api/aurora/admin/run` (ADMIN — triggers immediate NOAA cycle) | `POST /api/aurora/admin/reset` (ADMIN)

### Briefing (Bearer / ADMIN for writes)
`GET /api/briefing` | `POST /api/briefing/run` (ADMIN) | `GET /api/briefing/evaluate/scores` (Bearer) | `DELETE /api/briefing/evaluate/cache` (ADMIN) | `POST /api/briefing/compare-models` (ADMIN) | `GET /api/briefing/compare-models/runs` (ADMIN) | `GET /api/briefing/compare-models/results` (ADMIN)

> The former SSE `GET /api/briefing/evaluate` and the `/evaluate/cache[/timestamp]` GETs no longer exist — `BriefingEvaluationController` exposes only `GET /scores` and `DELETE /cache`. This matters for HTTP caching: `/api/briefing` and `/api/briefing/evaluate/scores` are ETag-revalidated (`HttpCachingConfig`), and that whitelist is exact-match precisely so it can never catch a streaming endpoint.

### Almanac (Bearer)
`GET /api/almanac?days=90` — the 90-day "Coming up" feed. Bearer with **no role gate**, by
inheritance from `SecurityConfig`'s `/api/**` → `.authenticated()`; stated here because this project
has already documented one endpoint as ADMIN that the code never enforced, and `AlmanacControllerTest`
pins LITE/PRO/ADMIN access and anonymous rejection so "no `@PreAuthorize`" cannot be read as an
oversight. ⚠️ **Do not serve this from `HotTopicAggregator`.** Its signature takes a range and ten of
its thirteen strategies ignore one — two tide strategies read a 5-day briefing cache, NLC a clarity
cache, six read survivor signals the batch never writes past T+3, aurora inspects only `fromDate` and
`fromDate+1`. It is also travel-day filtered and simulation-overridable. `AlmanacSource` is a separate
interface whose contract is *answer for the whole range or do not exist*; five implementations cover
tide runs, meteor peaks, supermoons, equinox/solstice and the NLC season. **The tide path measures
water, not the moon** — `TideSizeIndex` applies the Plan tab's own two tests (a day's biggest high
water clearing that location's `springTideThreshold`, or its `p95HighMetres`, strictly greater, *any*
coastal location — `TideFactDeriver`'s comparisons and `KingTideHotTopicStrategy.findKingTide`'s
roster-wide rule, lifted unchanged) across the whole 90 days, then `TideRunBuilder` supplies metres
and clock times. ⚠️ **`LunarPhaseService.classifyTide` was the detector and must not be restored as
one.** It qualifies a date within ±1 day of syzygy and the biggest water of a cycle arrives a day or
two *later* — the age of the tide, a property of the coastline that no epoch arithmetic recovers. In
Aug 2026 that put the feed's run on 12–13 Aug while the roster's peak was 14–15 Aug: the Plan tab
carried a tide card on the Friday and the feed showed nothing. The tell was already on screen — both
runs reported their biggest day as the *last* day of their span. `classifyTide` keeps two jobs: the
**fallback** when `Sizes.usable()` is false (a cold DB — dates a day or two early beat none, and
`UNMEASURED` must never be rendered as "no spring tides in 90 days"), and one arm of the **king**
label, since a perigean spring is the event the copy describes while P95 is a size test that says
nothing about the moon. King is **not** assumed to imply spring: the two thresholds come from one
sample but neither is defined in terms of the other. Beyond the stored-extremes window the entry
keeps its dates and carries no numbers (`AlmanacEvent.metaOf` drops anything null or blank, so the
degrade rule is mechanical rather than per-caller). Never synthesise. **Alignment is a question about
the run, and is asked of a different day from the figures** — `pickAligned` takes the day whose
`TideRunDay.alignmentPhrase` is non-null (peak day preferred, else the first that aligns) and
publishes `alignment` + `alignmentDate`; when no day aligns it publishes the `noAlignment` flag, and
the row says so rather than going quiet, because silence is indistinguishable from the empty-meta
degrade. ⚠️ **Never publish `verdict` under an "alignment" label again** — the verdict has an
*unaligned* form, so reading it always finds a sentence: taken from the biggest day it printed
`alignment  peak range · LW 2h12 after sunset`, claiming an alignment for a low water two hours into
the dark and restating a range already chipped above it. `alignmentPhrase` is built beside the
verdict from the same `Point` rather than trimmed out of its text (which would make that sentence's
punctuation load-bearing), so it keeps the clock time and never carries the `peak range · ` prefix.
ETag-revalidated; safe to share because it carries no per-user data.

### Astro Conditions (Bearer)
`GET /api/astro/conditions` | `GET /api/astro/conditions/available-dates`

### User Settings (Bearer)
`GET /api/user/settings` | `POST /api/user/settings/home/lookup` | `PUT /api/user/settings/home` |
`POST /api/user/settings/drive-times/refresh` | `GET /api/user/settings/drive-times` |
`GET /api/user/settings/reach` | `GET /api/user/settings/light`

> Previously documented as `GET|PUT /api/user-settings`, a path that has never existed —
> `UserSettingsController` is mapped at `/api/user/settings` (with a slash). The doc was wrong, not
> the controller, and the wrong form was never a live route.
>
> **Everything under this prefix is home-derived personal data, and that is what the prefix is for.**
> `HttpCachingConfig`'s revalidatable set is an exact-match allow-list, so a path here can never pick
> up the `Cache-Control: private, no-cache` that ETag revalidation requires — which would persist the
> body to a browser HTTP cache JavaScript cannot evict on logout.
> `HttpCachingConfigTest.personalDataPathsAreNeverFiltered` pins it per path, so a new route here
> must be added to that list. `/reach` and `/light` both live here for that reason rather than beside
> the payloads they are joined to on the client.
>
> Bearer with **no role gate**, by inheritance from `SecurityConfig`'s `/api/**` → `.authenticated()`.
> That includes `PUT /home`: saving a home postcode has always been open to any account, and since
> the masthead's light rule is drawn from it the frontend no longer gates it either — light times
> free, drive times and the local radius Pro. ⚠️ **Do not re-gate the postcode input.** The band's
> empty state nudges the reader to that field, so a Pro gate on it makes the nudge a dead end and
> leaves the rule permanently dim for exactly the accounts it is written for.
>
> `GET /light` answers **204** when no postcode is saved. That is the masthead's designed empty
> state, not an error — and the client must not fold a *failed* request onto the same value, because
> "you have no postcode" is a claim about the reader's account that a 502 is no evidence for.

### Scheduler (ADMIN)
`GET /api/admin/scheduler/jobs` | `PUT /api/admin/scheduler/jobs/{jobKey}/schedule` | `POST /api/admin/scheduler/jobs/{jobKey}/pause` | `POST /api/admin/scheduler/jobs/{jobKey}/resume` | `POST /api/admin/scheduler/jobs/{jobKey}/trigger`

### Tides (Bearer)
`GET /api/tides` | `GET /api/tides/stats` | `GET /api/tides/stats/all`

> Previously documented as ADMIN, which the code never enforced — `TideController` carries no
> `@PreAuthorize`, and `SecurityConfig` gates `/api/**` at `.authenticated()` only. Bearer is the intent,
> not a gap: tide extremes are astronomical data already surfaced in the forecast UI, so an ADMIN gate
> would break the app for every non-admin. The doc was wrong, not the controller.

### Push (mixed auth)
`GET /api/push/vapid-public-key` (none) | `POST|DELETE /api/push/subscribe` (Bearer)

---

## Product Strategy

See `docs/product/` for detailed reference documents.

**Differentiators**: Claude-generated "why" explanation | AOD+PM2.5 aerosol proxy | Cloud approach risk detection (temporal trend + upwind sampling) | Location type-specific UI | Outcome recording feedback loop

**Freemium split**: LITE gets basic scores, 3-day horizon, blurred metrics. PRO gets enhanced directional scores, 3-day horizon, full metrics.

### Role gating — UI pattern

Every new UI feature must be assessed for role gating.

- **Admin-only** (Operations tab, health widget, Run Forecast): hidden entirely
- **PRO/ADMIN** (all other premium features): visible but greyed out for LITE users
  - `opacity: 0.45`, `pointer-events: none`, `disabled` on interactive elements
  - Upsell text: "Pro" pill badge (inline) or "Upgrade to Pro" (standalone)
- **Exception**: elements too small for visual disable (e.g. marker SVG arcs) stay hidden; upsell goes on the nearest tappable surface (the popup)
- Backend 403 gating is the real enforcement; frontend greying is the UX layer

---

## Planned Features

- **Prediction accuracy feedback** — structured post-event feedback per user per evaluation (ACCURATE/SLIGHTLY_OFF/VERY_INACCURATE), admin breakdown by model and days_ahead
- **Web Push notifications** — replace Pushover with W3C Push API + VAPID (`webpush-java`), service worker, iOS Home Screen caveat
- **macOS menu bar widget** — Tauri app reusing React components, menu bar icon with best rating

---

## Test Standards

Read the standards for the side you are working on before writing or modifying any test class.
They share a philosophy and almost no mechanics, so the wrong one is close to useless:

| Side | Document |
|---|---|
| Backend (Java, Mockito, JUnit) | `docs/engineering/test-improvement-standards.md` |
| Frontend (React, Vitest, Testing Library) | `docs/engineering/frontend-test-standards.md` |

---

## UI Work — Review Cadence

**Every code commit that touches the UI gets an adversarial review before it lands, and the tests go
past "it renders".** Not a rubber stamp and not after the fact: the review runs against the working
tree, its surviving findings are fixed, and only then does the commit happen.

The order is: **build → tests → adversarial review of the diff → fix what survives → re-verify →
commit.**

**Why.** The UI is the product here — the forecasts only reach a user through it, and a pilot with a
handful of people gives very few chances at a first impression. Every review run on this redesign so
far has found real defects that a green test suite, clean lint and a successful build had all passed
over: Tailwind theme tokens silently pruned to the empty string, an escape hatch that disappeared on a
failed settings fetch, a plan citation that concealed a missing region ordering. Review-after-green is
where this project's defects actually live.

**Shape that works** (~15 agents): about six prosecutor lenses over the diff — runtime behaviour,
CSS/tokens, test quality, accessibility, project conventions, and what it makes harder for later
phases — then one refutation agent per charge, prompted to *refute*, defaulting to REFUTED without
citable evidence, then a synthesis pass. Report what was *not* examined as plainly as what was; a
charge that fell below a verification cut is not a finding.

**Verify in the browser rather than asserting — the local path works now, and it earns its keep.**
Run the backend with `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local` (port
**8083**, not the 8082 this file records elsewhere) and the frontend with `npm run dev`; sign in as
`admin` / `golden2026`. The first phase built with it (P4c) found three defects a green suite, a
clean lint and a successful build had all passed over — a theme token pruned to the empty string, a
12px baseline drift across a rail, and a label rendered twice — plus one that P4a's adversarial
review had explicitly **refuted**. Weigh a reviewer's confidence against a screenshot accordingly.

Where the browser cannot reach, say so rather than implying otherwise: a local DB with no evaluation
run has no ratings, so the rich states need a fixture. State which claims were seen and which were
tested.

⚠️ **Review agents must never write to the working tree.** A reviewer that probes by mutating source
— stripping a guard to see whether a test catches it, then tidying up with `git checkout --` — will
delete unstaged work it never knew was there. This has already happened once: a P0 reviewer's cleanup
destroyed uncommitted test improvements, unrecoverably, because they had never been staged. Tell
review agents to read only, and give anything that genuinely needs to mutate its own copy
(`isolation: 'worktree'` on the Agent/Workflow call). Commit or stash before starting a review that
runs mutations.

---

## Code Standards

### Backend
- Checkstyle: Javadoc on public classes/methods, no unused imports, 4-space indent, 120-char lines
- SpotBugs: `High` threshold (`<threshold>High</threshold>` in `backend/pom.xml`), FindSecBugs plugin, bound to `verify`
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

## Speeding Up the Dev Build Cycle

`./mvnw clean verify` runs Checkstyle → compile → test → JaCoCo → SpotBugs → repackage — ~6–7 min, and
**cannot run bare on this machine** — the `test` phase includes the 6 `IntegrationTestBase`
Testcontainers classes and there is no local Docker. Always pass `-Dtest='!**/integration/**'`.
Use targeted commands instead:

```bash
# Compile only — catches import/signature errors in seconds        [no Docker]
cd backend && ./mvnw compile -q

# Run a single test class — fastest inner loop   [no Docker, unless it extends IntegrationTestBase]
cd backend && ./mvnw test -pl . -Dtest=AuroraStateCacheTest -q

# Run all aurora-related tests only                                [no Docker]
cd backend && ./mvnw test -Dtest="Aurora*,ClaudeAuroraInterpreter*" -q

# Skip Checkstyle + SpotBugs (still runs all tests + JaCoCo)
cd backend && ./mvnw verify -Dcheckstyle.skip=true -Dspotbugs.skip=true \
  -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false -q

# Full verify but skip clean (shaves ~30s)
cd backend && ./mvnw verify -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false -q
```

**Checkstyle pre-flight before full verify** — run Checkstyle alone first; it fails fast (~15s) and catches line-length / unused-import violations before wasting 6 min:

```bash
cd backend && ./mvnw checkstyle:check -q
```

⚠️ **Gate on Maven's exit code, never on a grep of its output.** With `-q`, Checkstyle's violation lines are suppressed, so `| grep -E "ERROR"` finds nothing and looks clean. Worse, `$?` after a pipeline is the status of **grep**, not Maven — it is almost always `0`. That combination reported a green build twice on 2026-07-25 while Maven was in fact returning `1`, hiding both a Checkstyle violation and a genuinely failing test until CI caught them. Redirect to a file and echo the exit code as its own statement:

```bash
cd backend && ./mvnw checkstyle:check >/tmp/cs.log 2>&1; echo "exit: $?"
```

**Reproducing CI's `Backend — Build, Test & Coverage` locally** — a plain `./mvnw clean verify` cannot do this. The classes under `src/test/java/.../integration/` need Testcontainers, which needs Docker, which this machine does not have; surefire aborts on them and the build **never reaches `jacoco:check` or `spotbugs:check`** — so the two gates most likely to fail CI are exactly the two a local `verify` silently skips. Exclude them and the build gets there (⚠️ this also means the integration classes, and therefore every Flyway migration, are proven **only in CI** — read the Backend job on the PR):

```bash
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress \
  -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
grep -E "BUILD (SUCCESS|FAILURE)|Rule violated|Tests run: [0-9]+, Fail" /tmp/v.log | tail -6
```

Exit `0` is the gate; the grep is only for reading the detail. Use the `!**/integration/**` path glob rather than a list of class names — a name list rots (`HttpIntegrationTestBaseProbeTest` ends in `ProbeTest` and slips past `!*IntegrationTest`), and `-Dtest='!com.gregochr.goldenhour.integration.*'` does not work at all, as surefire ignores that form. JaCoCo coverage is then computed without those tests, i.e. slightly pessimistic — if it passes locally it passes on CI. **JaCoCo's rule is 80% line coverage per class**, which bites small new records: cover the defensive null branches with real assertions rather than deleting the guards.

**Don't use background Bash tasks for long Maven builds** — the background task output files are unreliable (often 0 bytes until the process finishes writing). Always run `./mvnw` in the foreground with an explicit `timeout` parameter so results come back immediately.

**Frontend tests are fast — run them separately:**

```bash
cd frontend && npm run test -- --reporter=dot   # ~90s, much faster than backend
```

⚠️ **`npm run test` is NOT the frontend CI job.** That job is lint → Vitest → **`npm audit
--audit-level=high`** → build, and the audit step is the one nothing local runs. It blocks on a
*transitive* advisory nobody in this repo introduced, so the PR goes red on a change that touches no
dependency file — which is exactly how P11 lost a CI round. Run all four before pushing:

```bash
cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build
```

And when `npm audit` does fire, prefer a **surgical lockfile edit** to `npm audit fix`. The fix
command also rewrites lockfile metadata to whatever the local npm emits — on npm 11 / Node 25 it
strips the `libc` field from every optional platform-specific rollup binary, 72 lines of it, and CI
runs Node 22 whose npm does not write that field. `libc` is what disambiguates the glibc and musl
builds of a native dependency, so a security patch would be carrying a change that could alter which
binary the Linux runner installs. Edit the three lines (version, resolved, integrity — take the
integrity from `https://registry.npmjs.org/<pkg>/<version>`), then prove it the way CI will:
`rm -rf node_modules && npm ci` must exit 0, report 0 vulnerabilities, and leave the lockfile
unchanged.

The core rule: `compile → single-class test → checkstyle:check → full verify` as a ladder. Only climb to `clean verify` when you're confident everything is clean — and gate each rung on the exit code, not on what the output appears to say. ⚠️ Every rung that reaches the full `test` phase must carry `-Dtest='!**/integration/**'`, because there is no local Docker and never will be; without it you get a Testcontainers stack trace instead of a test failure. The integration classes run in CI.

---

## solar-utils

Resolved from **JitPack**, not GitHub Packages. `backend/pom.xml` declares
`com.github.gregochr:solar-utils:2.1.0` against the single `<repository>` whose `<id>` is `github` but
whose URL is `https://jitpack.io` — the id is misleading. No `read:packages` token is required.

A jar is also vendored in-tree at `backend/.m2/repository/com/gregochr/solar-utils/2.1.0/` and staged by
`backend/Dockerfile`, but its groupId is `com.gregochr` — a *different* coordinate from the one the POM
asks for, so it cannot satisfy the dependency; the Docker build still resolves 2.1.0 from JitPack.
Treat the vendored copy as a leftover from the pre-JitPack local-install era.

Public API (v2.1.0, `com.gregochr.solarutils.SolarCalculator`): `sunrise`, `sunset`, `civilDawn`,
`civilDusk`, `goldenHourStart`, `goldenHourEnd`, `solarNoon`, `dayLengthMinutes`, `sunriseAzimuth`,
`sunsetAzimuth`. The jar also ships `LunarCalculator` and `MoonriseMoonsetCalculator`.
