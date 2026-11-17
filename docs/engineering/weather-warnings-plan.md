# Met Office weather warnings — plan

Status: **planned, not started** (2026-11-17). Source: the owner's design handoff
(`design_handoff_weather_warnings/README.md` and the placement mock `Weather Warnings.html`).
This doc is the build plan; the handoff README stays the product spec (acceptance list, copy, weather-type
table, symbols). Where the two differ, this doc records the engineering decision.

## 1. Decisions up front

### 1.1 Source: the Met Office NSWWS Public API (v1.1 on Weather DataHub), polled

- **The Met Office does not push.** The API is an Atom feed that "needs to be regularly polled". There is no
  webhook. The feed changes whenever a warning is issued, updated, cancelled or expires, so there is no fixed
  cadence; our job reads the feed, and only when it has changed fetches the changed warnings.
- **Polling is cheap.** One small Atom GET per poll. Full warning bodies are fetched only for entries we have not
  stored (keyed by `warningId` + `warningVersion`). A 5-minute fixed delay (the `aurora_polling` pattern) means a
  cancellation reaches the app within about 5 minutes plus the briefing serve memo. A faster poll is a config
  edit on the scheduler row, bounded by the call limit that comes with the key.
- **Is it the best source?** Yes for the UK: it is the statutory source (NSWWS), carries the polygons (the whole
  point: per-place matching), levels, likelihood/impact and the official page link. Alternatives:
  - Regional RSS (`PWSCache/WarningsRSS/Region/...`): no key, no polygons, region-level only. Too coarse for a
    place card. Fallback only if the key is refused.
  - MeteoAlarm (EUMETNET): aggregates the Met Office feed, adds latency and a layer, no benefit for UK-only.
  - Open-Meteo: no warnings product.
- **Licence is the one unknown.** The NSWWS Open Licence and the style guide (v1.3, Jan 2026) must be read by the
  owner in W0: attribution wording ("Met Office Weather Warnings" is what the style guide asks for), whether
  their weather-type icons may be shown (the handoff assumes not), call limit, cost.

### 1.2 Confirmed against the code (2026-11-17)

| Piece | Where it goes |
|---|---|
| Poller | New `@Component` modelled on `service/aurora/AuroraPollingJob` (fixed delay on a `scheduler_job_config` row; fire outcome via `DynamicSchedulerService` / `SchedulerFireOutcome`). Row inserted by migration, gated like `aurora_polling` (`config_source` key `photocast.warnings.enabled`, see V68 and V126). Add the job to `SchedulerJobCatalogue` (a missing entry serves `catalogue: null`, so add one: alias, why, external service Met Office, no Claude spend). Use `Purpose.FORECAST`; do not add a new purpose unless the catalogue tests allow it. |
| Client | `MetOfficeWarningClient`, an `@HttpExchange` interface, config under `photocast.warnings.*` (`application-example.yml` has the `photocast:` block at line 165; add the keys there and in `reference/configuration.md`). The key goes in `x-api-key`; never logged. |
| Migration | Next after **V223** (`V224__weather_warning_version.sql`). Postgres first; proven in CI only (no Docker locally). |
| Serve | Shared ETag path (`/api/briefing`, per-location rows). `HttpCachingConfigTest` needs no new personal path: warnings are not personal data. `GET /api/warnings` is new, all signed-in roles. |
| Invalidation | Briefing/hot topics are memoised and keyed by `ServedBriefingEpoch`. A poll that stores a new version or a cancellation must bump the epoch, or a cancellation would wait on the 30 s memo plus the briefing cache. Warning ids ride an in-memory carrier merged per serve, never written into the briefing cache (same rule as hot-topic build-time enrichment). |
| Coming up | `service/comingup/ComingUpAssembler` builds the feed server-side; the client renders it (`utils/comingUpFeed.js`). |
| Surfaces | `components/LocationFourDaySheet.jsx`, `components/map/MapCallout.jsx`, `components/WindowGlanceLanes.jsx`, `components/WindowSheetDialog.jsx`, `components/map/MapTopicChips.jsx`. Guides live in `landing/guide/` (hub `index.html`, `guide-search.js`, `frontend/src/utils/guide.js`). |

### 1.3 Rewind is simpler than the handoff fears

The `Clock` bean is a `RewindAwareClock`, honoured for admin GETs only, up to `ForecastHorizon.SERVE_PAST_DAYS`
(3 days) back, and never visible to the scheduler or pollers. So: the poller always runs on the real clock
and writes `received_at` from it; the **serve path** computes "active at T" using the injected `Clock`, which is
the rewound instant for a rewound admin. One rule, no extra plumbing. Client clock reads go through `appNow()`.

## 2. Principles (from the handoff, all adopted)

1. A warning never changes a rating, verdict, Best bet, count, heat field or prompt. Golden test required.
2. Two-question rule: warnings are "what is happening"; never silenced by stand-down, stability skip or unscored.
3. Lite sees exactly what Pro sees.
4. Met Office language and level colours; the level word is always printed.
5. No Met Office artwork without written permission; generic triangle plus our own type glyphs.
6. Backend-heavy: the server does all point-in-polygon and window coverage; the client renders.
7. Simulated warnings never reach non-admins (`simulated` column, `isSimulated()` gate).

## 3. Data model (W1)

`weather_warning_version`, insert-only, one row per `(warning_id, warning_version)`:
status, level, weather types (JSON array text, never a closed enum), likelihood, impact, valid_from, valid_to,
issued, modified, headline, what_to_expect (JSON), further_details, affected_areas (JSON), Met Office URL,
geometry (GeoJSON text) and simplified geometry for the map, `simulated`, `received_at`.
Unique key `(warning_id, warning_version)` makes ingest idempotent (re-polling the same version is a no-op).

- **Active at T:** the latest version with `received_at <= T`, status not CANCELLED, `valid_from`/`valid_to`
  considered against T. Computed in one service (`WarningTimeline`), used by every surface and by `/api/warnings`.
- **Upgraded:** level rank rises between consecutive versions → `upgradedFrom`, `upgradedAt` served for 24 h
  after that version's `received_at`, against the injected Clock. Downgrades and area/time-only edits: no flag.
- **Never delete.** The poller has no delete path; a test asserts it. Archive (not delete) after a year only if
  size ever bites. Expected volume is tens of rows a day, so geometry text is fine.
- Enum note: no new `@Enumerated` column, so no `LocalH2EnumWidener` change. Types and levels are strings on purpose.
- Failure: feed unreachable → serve stored warnings until `valid_to`; "last checked" only in Operations; never
  synthesise "no warnings". A malformed entry is skipped and counted, never fails the poll.

## 4. Matching (W1)

`WarningCoverage`: pure, point-in-MultiPolygon (JTS if already on the classpath, else a ray-cast handling holes)
of each enabled location's coordinates. Cached per `(warning_id, warning_version)` → set of location ids.
A place is covered for a window when it is inside the polygon and the window's event time is within
`[valid_from, valid_to]`. Per-window rows carry `warningIds` and, for region/briefing windows, "N of M places in
reach" computed where the reach pool already lives. **Scope (Q3):** only warnings touching enabled locations'
regions are served; a warning that covers none is stored but not surfaced.

## 5. Phases (one PR each, each branched from updated `main`)

| Phase | Scope | Notes |
|---|---|---|
| **W0** (owner) | Request NSWWS key; read licence + style guide; ask about icons; answer Q1–Q4 | Blocks the live feed, not the fixture-driven build |
| **W1** | Migration, client, poller + scheduler row + catalogue entry, `WarningTimeline`, `WarningCoverage`, upgrade flag, `GET /api/warnings`, `warningIds` on briefing windows and location rows (carrier + epoch bump), Operations status row (last poll, active warnings, unknown types, errors), admin simulation toggle. Behind `photocast.warnings.enabled=false` | Recorded GeoJSON fixtures; **never the live feed in tests**. Docs: `api-endpoints.md`, `configuration.md`, `database-migrations.md`, new `reference/weather-warnings.md` and index line in CLAUDE.md |
| **W2** | `LocationFourDaySheet` chip (incl. stood-down and unscored rows), `MapCallout` chip | Shared `WarningChip` and `utils/warnings.js` (words, ordering, accessible names) built here, reused after |
| **W3** | `WindowGlanceLanes` lane first in the stack, card triangle, `WindowSheetDialog` WARNING row | |
| **W3b** | Coming up entries from `ComingUpAssembler` | Beyond the scored days; upgraded entry floats to top of its day |
| **W4** | `MapTopicChips` chip, dashed no-fill outline layer, legend entry | Not in the hot-topics bar. Canvas rendering, no clustering |
| **W5** | Guides (`weather-warnings.html` + sections in five existing pages + search/hub/`guide.js`/`guideSections.test.js`), landing index/FAQ/acknowledgements/terms, `GuideLink` `?` in the app | **Warnings must not reach users (flag on) before W5 ships** |

Flag stays off in production until W5 has merged. Each phase: Sonnet builds in its own worktree, runs the gates in
`CLAUDE.md` (backend ladder; frontend lint, coverage, audit, build; E2E smoke where UI), adds a `changelog.d/`
entry; the planner reviews the diff read-only; PR, wait for CI and Codex 👍 on the head commit, squash merge.

## 6. Risks and answers to the adversarial lenses

- **Stale after cancel:** the epoch bump on every changed poll, plus a test that a cancellation shows on the next serve.
- **ETag:** `warningIds` are shared data, fine; but they change the payload, so the epoch must be what invalidates it.
- **Rewind:** `WarningTimeline` is clock-parameterised; a test with a v1 Yellow and v2 Amber at a time between them shows Yellow.
- **Simulation isolation:** `simulated` rows excluded unless the caller is an admin with the toggle; test the non-admin path on every endpoint and in the Coming up assembler.
- **Rating invariance:** a golden test builds the briefing with and without a warning and asserts identical ratings, verdicts, picks, counts.
- **Unknown types:** rendered from the served value in lower case with the generic glyph; counted in Operations.
- **a11y:** word always printed; level colours never the only signal; contrast checked on `--color-plex-surface`; triangle has an accessible name listing every warning on the card.
- **Phone-only:** none of this is phone-only; desktop and tablet are in scope.
- **Cost:** no Claude calls anywhere in this series. The poll spends nothing but the Met Office call quota.

## 7. Open questions for the owner

1. **W0:** key, licence, attribution wording, icon permission. (Needed before the live feed; not before W1 fixtures.)
2. **Q1 levels:** recommend all three; fall back to Amber/Red on lanes and cards only if Yellow is noisy.
3. **Q2 types:** decided, every type.
4. **Q3 scope:** recommend only warnings touching enabled locations' regions.
5. **Q4 likelihood/impact:** recommend popup row only.
6. Poll interval: 5 minutes proposed.

Later (out of scope): drive-route warnings, Ask PhotoCast mentions, notifications.

## 8. Review amendments (2026-11-17, three adversarial reviews; these supersede §1.2, §4 and §6 where they differ)

**Serve path**
- **Overlay, not carrier.** `warningIds` are a serve-time overlay derived from the DB, `WarningCoverage` and the injected `Clock`. The precedent is `bigSeas` (`DailyBriefingResponse`, attached last in `ServedBriefingAssembler.assembleForPlan`, null on stored payloads, `@JsonInclude` omit-when-null), not the hot-topic carrier. Nothing is ever written to `daily_briefing_cache`. Attach after `PlanWindowProjector.apply` and after `BriefingHonestyFilter` / `VerdictSampleGate`, so stood-down, unrated and `verdictBlanked` windows still carry the id. Beware positional `withX` record rebuilds (see the `BriefingRegion` Javadoc) and pin `PersistedBriefingRemovedKeysTest`. `LitePickShaper` must pass the field through untouched, including on `gated` slots (a warning is not a rating); test it.
- **Epoch.** `ServedBriefingEpoch` keys only `BriefingService.servedMemo`. The ETag is body-hashed (`RevalidatableReadEtagFilter`) and follows automatically; it is not a risk. The poller bumps the epoch only when something changed (new version stored), after commit (inside the transaction or after save returns). Time-driven transitions (`valid_from` reached, `valid_to` passed) never bump; they lag by at most the 30 s memo. Check whether the Ask snapshot memo is epoch-keyed and say so in the reference doc.
- **Coverage cache** is not keyed by warning version alone (the location roster changes; `LocationService`/`LocationFailureService` bump the epoch). Compute per serve (a few active warnings × enabled locations is cheap), or key by version + epoch. Rewind bypasses the memo, so it must stay cheap.
- **"N of M places in reach" is cut from the shared payload.** Reach is per caller and must never ride `/api/briefing` (`ReachEntry`). Serve "N of M places in this area" over all enabled places in the region (non-personal). Owner to confirm the copy change from "in reach".
- **Scope (Q3) is global:** any warning covering at least one enabled location. No server-side filtering by the reader's sub-region picks (`/api/almanac` takes no user; Lite parity). The client may offer "in your picks" later from served ids.
- **Coming up (W3b) is NOT in the day-cached feed.** It is served by `AlmanacService.getFeed` (`CachedFeed` keyed by UK date, not the epoch, `/api/almanac` strong-revalidation). Warning entries are merged **per request after the cache**, at the controller edge like `ComingUpRoleShaper`, role-free and Rewind/simulation-aware. `ComingUpRoleShaper.forLite` rebuilds entries through `liteEntry`: test it passes warning entries through. Detail in the W3b brief.
- **`GET /api/warnings`** is added to `REVALIDATABLE_READ_PATHS` (exact-match set) with `HttpCachingConfigTest` updated; it is not under `/api/admin/` (so Rewind applies).

**Simulation.** Hot-topic simulation is a global in-memory flag; ours is a per-row `simulated` column (aurora pattern). Simulated rows are **kept** (insert-only) but excluded from every shared payload for everyone, including `/api/warnings`, the briefing overlay and the almanac merge. Admin-only preview goes through a separate admin endpoint (`/api/admin/warnings/...`). Because the briefing memo takes no user, nothing user-specific can live in it. Test the non-admin path on every endpoint.

**Ingest**
- **Config gate.** `DynamicSchedulerService.configGateFor` recognises only the literal `aurora.enabled`. Add a `WarningsProperties` bean, inject it, add a branch for `photocast.warnings.enabled`, seed the row `DISABLED_BY_CONFIG` (or rely on `syncConfigGatedStatus` at startup; a flag flip needs a restart) and make `poll()` self-gate on the flag. Extend `DynamicSchedulerServiceTest` and `DynamicSchedulerServiceEdgeCasesTest`.
- **Registration checklist.** V224 inserts the `scheduler_job_config` row (`FIXED_DELAY`, 300000 ms, explicit `time_zone`, copy V68's column set); `registerJobTarget("weather_warning_poll", this::poll)` in `@PostConstruct`; `SchedulerJobCatalogue` entry (`Purpose.FORECAST`; the `why` may not name a clock time or "every N minutes", say "On a repeating interval"); `SchedulerJobCatalogueSeedTest` (CI) must agree both ways; check `SchedulerJobOverviewServiceTest`, `SchedulerDayServiceTest`, `SchedulerControllerTest`, `RunsDayServiceTest`, `StartupListenerOrderTest` for hard-coded job counts.
- **Client.** `@HttpExchange` interface with its own bean in `AppConfig`, built from `restClientBuilder()` (timeouts) with `defaultHeader("x-api-key", …)`. No Resilience4j retry: the next tick is the retry. 401/403 (bad key) and 429 are recorded as the poll's failure reason in Operations; stored warnings are never blanked by a failure. The key lives in a properties class and never appears in logs or config dumps.
- **Semantics.** Never infer cancellation from absence in the feed. Active at T requires status not CANCELLED **and** `valid_to > T`. A 404/410 on a detail fetch (24 h update expiry) is skipped and counted, not a failed poll. First poll after downtime inserts every unstored version; `received_at` is the poller's clock, so Rewind shows "what we had received", stated deliberately (the timeline uses `received_at`, not `first_seen`). A stored CANCELLED version with no earlier versions is stored and shows nothing. One transaction per entry; no watermark; idempotency from the unique key alone; insert with conflict-do-nothing (or catch the unique violation); an `AtomicBoolean` guard against overlapping runs (Run Now + schedule).
- **Data model.** `TIMESTAMP WITH TIME ZONE`, `TEXT` for geometry and JSON, index `(warning_id, warning_version DESC)` and `valid_to`, CHECK on status; every column `updatable = false`; the repository exposes no delete or update (a test asserts it). No JTS on the classpath: write a small ray-cast (MultiPolygon, holes, malformed input tolerated) with a test per branch (jacoco 95%/90% per class).

**Stale feed (user-visible).** An empty state must not read as an all-clear. `/api/warnings` serves `lastCheckedAt` and `stale` (last successful poll older than a named constant, 30 min). When stale, warning surfaces show "Warnings last checked HH:MM — check the Met Office" and the "none" state is suppressed. Stored warnings stay shown until `valid_to` (a withdrawn warning could therefore linger during an outage; the stale line says so). Flag-on in production is an owner decision after the licence answer and after W5.

**Frontend notes for W2–W4.** The Met Office link is an anchor (`target=_blank`, `rel="noopener noreferrer"`), never a layer that competes with a dialog. The map chip toggles a layer with no history entry (`dialogHistory.js` untouched). Canvas outline, no clustering.
