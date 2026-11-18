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
| **W3b** | Coming up entries (built: `ComingUpWarningMerger` at the controller edge, not in `ComingUpAssembler`; see `reference/weather-warnings.md` "Coming up, W3b") | Beyond the scored days; upgraded entry floats to top of its day |
| **W4** | Map warning chip row (`MapWarningChips`, in `.wf-map-chrome-tl` under the topic chips) | Not in the hot-topics bar. **No outline, legend entry or geometry layer:** the dashed no-fill outline of the original plan is superseded by §12 and §13 (style guide v1.4: map geometry must be colour-filled and must not be overlaid with other information), so the chip is the whole map presence. Built; see `reference/weather-warnings.md` "The client, W4" |
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

**Frontend notes for W2–W4.** The Met Office link is an anchor (`target=_blank`, `rel="noopener noreferrer"`), never a layer that competes with a dialog. The map chip is a row of Met Office links with no layer and no history entry (`dialogHistory.js` untouched); the canvas outline in the original plan was dropped by §12/§13 and is not built.

## 10. Met Office reply and DataHub findings (2026-11-17)

The Met Office replied (Brandon Birch, Data Provisioning) with a pointer, not answers: sign up for the NSWWS API key yourself on **Weather DataHub (Warnings Overview)**; read the Style Guide and the FAQs under warning products. From the DataHub FAQ page:

- **Cost and limits:** free within usage limits; **20,000 calls per 24 h (resets 00:00 UTC), burst 200 req/s**. A 5-minute poll is 288 feed calls a day plus detail fetches: comfortably inside. Interval stays at 5 minutes.
- **Attribution:** applications must include "**Powered by Met Office data**"; warnings must always be called "**Met Office Weather Warnings**" (the W5 acknowledgements and every warning surface's source line use these exact words, replacing the plan's "Source: Met Office").
- **Licence:** commercial use is permitted subject to the licence terms; non-commercial is not separately addressed. One search result described the NSWWS API as "business use only": **unverified, owner to confirm on the DataHub terms before the flag goes on in production.**
- **Header name:** the DataHub FAQ says `apikey`; the NSWWS Public API page says `x-api-key`. W1a used `x-api-key`. Make the header name a config value and confirm against the real v1.1 docs once the key exists (W1c).
- **Not retrievable here:** the v1.1 base URL/paths/field names, the Style Guide's exact level colours and icon permission (the PDF is compressed and no PDF renderer is installed). Owner to read the Style Guide (v1.3) and say whether its icons may be reused; until then the generic triangle and our own glyphs stand, and level colours stay the handoff's placeholders.
- **W1c (new, small, after the key exists):** configurable header name, base URL and entry-link parsing aligned to the real v1.1 feed; fixtures re-recorded from real responses.

## 9. Owner decisions (2026-11-17)

- **Poll interval:** 5 minutes (the scheduler row default). Revisit only if the Met Office call limit forbids it.
- **Q1 levels:** show Yellow, Amber and Red everywhere. Narrowing Yellow later is a display change only.
- **Q2 types:** every type (earlier decision).
- **Q3 scope:** only warnings covering at least one enabled location; global, not per reader.
- **Q4 likelihood/impact:** shown only in the window popup row ("medium likelihood · low impact"); chips and lanes stay level plus type.
- **W0 request:** API key, licence, attribution and icon permission requested from the Met Office on 2026-11-17 (asked for a 30-minute poll in the message; we intend 5, adjust to their reply). Awaiting the reply.
- **Popup copy:** "N of M places in this area" (replacing "in reach"), approved by the owner 2026-11-17.

## 11. The real v1.1 feed (recorded from the owner's key, 2026-11-17) — W1c

Base `https://data.hub.api.metoffice.gov.uk/nswws/v1.1`; **header `apikey`** (HTTP 200; `x-api-key` untested). Paths end in a slash. Samples were saved to the owner's `~/nswws-sample` (feed, an empty `issued` snapshot, one `updated` document with one EXPIRED snow warning) and copied for the W1c build.

What W1a guessed wrong (the flag is off; nothing live is affected):
- **Entry ids are change-event uuids**, not warning ids. `warningId` (uuid) and `warningVersion` come from inside the GeoJSON feature properties.
- **`warningVersion` is a decimal string** ("3.0"), not an integer. Store NUMERIC and order numerically (V225 alters the column).
- **An `updated/{uuid}` document is a FeatureCollection of N warnings**, not one. Each feature is one version row.
- **`warningStatus` is ISSUED, CANCELLED or EXPIRED.** ISSUED = live; CANCELLED and EXPIRED both mean ended (store both as the ended status, keep the source word).
- **`warningLikelihood` and `warningImpact` are integers** (3 and 2 in the sample), not words. Serve them raw; word mapping (the Met Office matrix) waits for the Style Guide, never guessed.
- `warningLevel` is upper case (`YELLOW`). Extra useful fields: `warningUpdateDescription`, `whatShouldIDo`, `affectedAreas[].regionCode` (NE, NW, YH, ...). There is no per-warning URL in the feed: the "Read it on the Met Office site" link is a configured public warnings page until the owner confirms a better target.
- The feed's `rel="related"` link is the **issued snapshot** (all currently valid warnings, latest version of each; empty today). Use it on the first poll after a start and after a gap longer than the feed's 24 h memory; otherwise process only unseen update entries.
- Atom root, namespace, entry `alternate` link and 404-on-expired-update all match W1a's assumptions.

Sequencing: W1c runs after W1b merges (it changes the entity's version type and the timeline's ordering, which W1b reads) and never concurrently with another Maven run.

## 12. Met Office NSWWS style guide v1.4 (Oct 2026): what it changes (owner supplied the PDF, 2026-11-17)

The guide is binding for third parties; items marked "Required by the Licence" are licence terms (the Open Licence (Version 1) - NSWWS itself is a separate document the owner accepts at subscribe; read it for the business-use question in §10). These supersede the handoff design wherever they differ.

**Colours (alternatives need Met Office agreement):** RED `#ca0a37` (white text), AMBER `#ff9900` (black text), YELLOW `#ffe923` (black text). Replace the handoff's placeholder hues (`#E0201B`, `#FFA500`, `#FFD700`). The level word is also stated in text (already planned). Coloured text must match its warning colour or be neutral, never another level's colour.

**Attribution (Required by the Licence):** always "Met Office Weather Warnings" and "Met Office Weather Warnings © Crown Copyright 2026" (year of publication) when redistributing in the UK; the logo may be shown only under Met Office brand guidelines (ask `enquiries@metoffice.gov.uk`; do not use it until agreed). A link to the UK warnings overview, `https://www.metoffice.gov.uk/public/weather/warnings/`, is the Met Office link on every warning (the feed has no per-warning URL). Optional "What should I do" link, strongly encouraged for v1.1, must go to `https://www.metoffice.gov.uk/weather/warnings-and-advice/seasonal-advice`, prefixed "What should I do", suggested text "If you would like further advice and guidance on what to do in severe weather, click here". Also the DataHub FAQ's "Powered by Met Office data" on the acknowledgements page.

**Timeliness (Required by the Licence):** a new or updated warning viewable within 5 min on average and never more than 10 min after the Met Office shows it; expired and cancelled warnings removed within 5 min on average, never more than 10. So: the 5-minute poll is the ceiling (not 30), the served path must not add minutes (no browser max-age on `/api/warnings`; the briefing serve memo is 30 s; the Coming up merge is per request after the day cache; client refresh cadence for any warning surface must be checked in W2/W3), and time-driven expiry is computed at serve time against `valid_to`, which W1a already does. A feed outage beyond ~10 min must show the stale line (§8).

**Text content:** weather type(s), headline, what to expect and (v1.1, guidance) what should I do must be included **in full** wherever warning text is assembled. So the window popup row shows the full headline and the full `whatToExpect` list (not "first line"); `warningFurtherDetails` if shown is headed "Further details"; `warningUpdateDescription` may be shown. Weather type must be stated in text and visible on first access, including for overlapping warnings. Multiple types are listed in the order received (priority Rain, Thunderstorm, Wind, Snow, Lightning, Ice, Extreme Heat, Fog).

**Status:** every warning clearly shows its status (issued, **updated**, cancelled) and severity; an updated warning is `ISSUED` with version >= 2.0 and **must be clearly labelled updated**. This is separate from the handoff's 24 h "↑ upgraded" flag: show "Updated" (with `warningUpdateDescription` in the popup) on any version >= 2.0, and keep "↑" only for a level rise. Cancelled and expired warnings are removed, not shown.

**Ordering:** overlapping warnings sort by level (RED > AMBER > YELLOW), then `warningImpact`, then `warningLikelihood`, then `validFromDate`. W1c changes `WarningTimeline`'s sort (it is level then id today) and the chip order and the card triangle follow it.

**Time:** UK local time, converted by the consuming app (BST/GMT suffix allowed).

**Likelihood/impact:** optional; if shown, only as the Met Office's own matrix with its labels and palette (matrix image on page 9 of the PDF; the numeric-to-label mapping is not in the extractable text). **Decision: do not show likelihood or impact words in v1** (serve the raw numbers only). This replaces owner decision Q4's popup line until the matrix is reproduced from the PDF.

**Map (conflicts with the handoff's W4):** warning geometry is optional, but if drawn it must be **colour-filled** in the level colour, labelled with type and severity when zoomed in, differentiated from non-UK land, and **must not be overlaid with any other information, including meteorological**. A dashed outline over the heat field is therefore not compliant. **Decision (planner default, owner may overrule): W4 ships the map warning chip only; no warning geometry on the map.** The server still stores geometry and does all matching.

**Icons:** weather-type icons are optional and need prior design agreement with the Met Office; text is recommended. Until agreed, no type glyphs and no logo. The handoff's generic triangle in the level colour is also an "icon": text chips in the level colour are the safe form; ask the Met Office whether the triangle on a window card is acceptable and ship text-only until they say yes.

**Inconsistent visual messaging (3.1c):** Red and Amber warnings must not be shown alongside inconsistent visual messaging; Yellow warnings (recommended to show) should avoid it. Whether our own star rating or "Go" style verdict beside an Amber or Red warning counts is **an open question for the Met Office** (the handoff's principle 1 keeps the rating beside the warning, unchanged). Mitigation is a display decision, never a rating change.

**Other:** no forward propagation of warnings or the feed to other organisations (`/api/warnings` stays signed-in only, never public, no third-party sharing); emphasising weather must not imply a warning has been or will be issued; push notifications are out of scope (if ever built, Amber and Red are mandatory); a dedicated warnings page would need a seven-day overview (we have none); developers must follow Met Office changes to the service (3.2f).

**Owner actions from this:** (1) reply to the Met Office with the placement mock and ask: the 3.1c question, whether text chips plus a generic level triangle are acceptable, and logo/brand guidelines; (2) read the Open Licence for non-commercial use; (3) screenshot page 9 of the PDF (the impact matrix) if likelihood/impact words are wanted later.

### 12.1 The impact matrix (owner supplied PDF page 9, 2026-11-17): supersedes the "no likelihood/impact in v1" decision in §12

The Met Office matrix carries **no words**: a 4×4 colour grid, axis labels "Likelihood" (vertical arrow up) and "Impact" (horizontal arrow right), and a tick on the warning's cell. So the Q4 owner decision stands in a compliant form: **show the matrix graphic in the window popup row only**, with the tick at (`warningLikelihood`, `warningImpact`), axis labels exactly "Likelihood" and "Impact", and an accessible text alternative ("Likelihood 3 of 4, impact 2 of 4"). Never invent words like "medium" or "minor". The grid (read from the page image; **verified against the PDF page 9 at the W3 build, 2026-11-18: correct, no change**; likelihood 1 bottom to 4 top, impact 1 left to 4 right), using the level palette plus matrix green `#c4e90c`:

| Likelihood | Impact 1 | Impact 2 | Impact 3 | Impact 4 |
|---|---|---|---|---|
| 4 | green | yellow | amber | red |
| 3 | green | yellow | amber | amber |
| 2 | green | green | yellow | amber |
| 1 | green | green | yellow | yellow |

Consistency check against the recorded feed sample: likelihood 3, impact 2 is yellow, matching that warning's `warningLevel` YELLOW. A test asserts that the served level agrees with this grid for the fixtures (never recolour a warning from the grid; the served `warningLevel` always wins, the grid only draws the cell). The same grid orders overlapping warnings by impact then likelihood (§12 ordering). The page image is in the owner's PDF; do not copy Met Office artwork into the repo, draw the grid from these values.

## 13. W0 closed with defaults (owner, 2026-11-17): no further Met Office contact

The owner chose to proceed on sensible defaults rather than wait for the Met Office. Defaults, all reversible:
- **Marks:** text chips in the official level colours (§12). No triangle, no type glyphs, no Met Office logo (all need their agreement). W2/W3 build the chip as text plus colour only; the accessible name is the full phrase ("Yellow warning of wind").
- **Ratings beside warnings:** unchanged (principle 1). For Amber and Red the warning is the more prominent element on its row and card; ratings are never altered or hidden. Revisit only if the Met Office objects to clause 3.1c.
- **Licence:** the owner subscribed on Weather DataHub (accepting its terms). The "business use only" remark stays unverified; the production flag-on remains the owner's decision, taken with the Open Licence in hand.
- **Likelihood/impact:** the Met Office matrix graphic in the popup row only (§12.1).
- **Map:** chip only, no geometry (§12).
- **Attribution and links:** exactly as §12.

### 13.1 W3 note (2026-11-18)

The Plan card marker is text ("Amber snow +1": level word, first type, count of the others). It cannot also carry "Updated" or the
full phrase without growing the card, so the lane above the cards is the first-access place for "Updated" and the full phrase; the
popup row has everything. Its prominence relative to the verdict word is not measured or guaranteed.

## 14. W6: Ask PhotoCast mentions warnings (owner approved, 2026-11-17; after W5)

Owner example: asking Ask about fresh snow at Hadrian's Wall, the answer says an Amber warning of snow is in force so expect a lot of snow.

- **Scoring prompts never see warnings** (Sky Eval, batch evaluations): principle 1; a warning would silently mark down snow and big-sea scenes and invalidate the prompt-regression baselines.
- **Ask gets the active warnings as read-only facts** in its snapshot (Pro and Admin, behind `photocast.ask.enabled`): level, types, headline, validity and the places or areas covered, from `WarningTimeline`/`WarningCoverage` (simulated excluded). Style guide rules for the prompt and the answer: quote the Met Office's level, type and headline verbatim (never paraphrase or soften a warning); never imply a warning exists, or will, when none is in force (§1.4); carry "Met Office Weather Warnings" attribution and the overview link; a cancelled or expired warning must be gone within the snapshot memo (30 s, inside the 10-minute licence bound); ratings and ranking in the answer are exactly as served.
- **Tests:** an Ask golden test for the same question with and without a warning (only the warning sentence differs, no served rating changes), a no-warning-implied test (an answer never mentions a warning when none is in force), and an Ask prompt-regression case added only as a NEW case (never edit an existing assertion; the real-API run waits for the owner's approval).
- **Separate, no model:** the "Fresh snow" hot topic and Coming up cross-reference ("Amber warning of snow covers this area") is deterministic server-side text from W3b.
