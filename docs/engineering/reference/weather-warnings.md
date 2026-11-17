# Met Office weather warnings (backend, W1a to W1c)

Behaviour as built in phases W1a, W1b and W1c of `docs/engineering/weather-warnings-plan.md` (the plan and its §8 review
amendments remain the product and design record). W1a is **backend only**: ingest, storage, the active-at-T rule,
location coverage and three endpoints. W1b adds the serve-time `warningIds` overlay (briefing windows and
`/api/forecast` location rows) and the Operations panel. Nothing here touches a rating, verdict, Best pick, count,
heat field or Claude prompt (no Claude calls at all). The first reader-facing UI is W2 (chips on the location sheet and the
map callout, "The client, W2" below); the popup, lanes and Coming up are later phases.

W1c (this revision) rewrote the feed handling to the **real NSWWS v1.1 shape** recorded in plan section 11 and applied
the style-guide rules of section 12 (ordering, the Updated flag, the configured Met Office link and attribution).
The W1a "assumed feed shape" section is gone: the shape is now recorded, with real samples as fixtures.

Everything is behind `photocast.warnings.enabled` (default **false**); the flag stays off in production until the
guides ship (W5) and the owner has answered the licence question.

## Pieces (all under `service/warnings/` unless stated)

| Piece | Role |
|---|---|
| `V225__weather_warning_real_feed_shape.sql` | W1c: `warning_version` becomes `NUMERIC(10,2)` (the unique key and the `(warning_id, warning_version DESC)` index follow), `likelihood` and `impact` become `INTEGER`, adds `update_description` and `what_should_i_do`, drops `source_url`. `status` stays `ACTIVE` or `CANCELLED` (see "Status mapping"). |
| `V224__weather_warning_version.sql` | Insert-only table `weather_warning_version`, unique `(warning_id, warning_version)`, plus the `weather_warning_poll` scheduler row (seeded `DISABLED_BY_CONFIG`, FIXED_DELAY 300000 ms, `Europe/London`). |
| `entity/WeatherWarningVersionEntity` | Every column `updatable = false`. No `@Enumerated` column, so no `LocalH2EnumWidener` line. |
| `repository/WeatherWarningVersionRepository` | Extends the bare `Repository` (not `JpaRepository`): `save`, `existsBy...`, a read query, `count`. A test pins that nothing else (no delete, update, saveAll) is declared. |
| `config/WarningsProperties` | `photocast.warnings.*`. `toString()` omits the key. |
| `client/MetOfficeWarningClient` | `@HttpExchange`: `getFeed()` (`/objects/feed/`), `getUpdated(uuid)` (`/objects/updated/{uuid}/`), `getIssued(uuid)` (`/objects/issued/{uuid}/`); every path ends in a slash. Bean in `AppConfig` built from `restClientBuilder()` (shared 10 s / 30 s timeouts) with `photocast.warnings.base-url` as the base and the key header (`photocast.warnings.api-key-header`, default `apikey`) sent only when a key is set. No Resilience4j retry: the next poll is the retry. |
| `WarningFeedParser` | **The only class that reads the feed or document wire format** (see "The feed shape"). |
| `WarningPollingJob` | The scheduler target (`registerJobTarget("weather_warning_poll", ...)`). |
| `WarningPollStatus` | In-memory record of the last attempt, last success, counts, failure reason, unknown-type tallies. Resets on restart. |
| `WarningTimeline` | Pure, `Clock`-injected active-at-T rule and the upgrade flag. |
| `WarningCoverage` / `WarningGeometry` | Pure ray-cast point-in-MultiPolygon (holes handled, malformed geometry tolerated; no JTS on the classpath) and deterministic vertex thinning. |
| `WarningService` | Assembles the shared and admin views; inserts simulated warnings. |
| `controller/WarningsController`, `controller/WarningsAdminController` | Thin; no business logic. |
| `WarningOverlay` (W1b) | The serve-time overlay: `overlay(DailyBriefingResponse)` for briefing windows and `snapshot()` for location rows. See "The serve-time overlay". |

## The poll

`WarningPollingJob.poll()` self-gates on the flag, refuses an overlapping run (an `AtomicBoolean`; Run Now plus the
schedule can collide) and reports a `SchedulerFireOutcome`. One poll: read the Atom feed (`/objects/feed/`); each entry
is a **change-event uuid**, and for every entry not yet processed fetch `updated/{uuid}` (a GeoJSON FeatureCollection of
N warnings) and insert each feature as one version row **in its own transaction** (a `TransactionTemplate`).
Processed entry ids are tracked **in memory** (`seenEntryIds`, pruned to the ids still in the feed), so a repeat poll
fetches nothing; an entry whose fetch failed is not marked processed and is retried. Idempotency across restarts comes
from the unique key (`existsBy...` first, then a `DataIntegrityViolationException` from a racing writer counts as
already stored). `received_at` is the poller's real clock.

- **Issued snapshot.** The poll also loads `issued/{uuid}` (the snapshot of every currently valid warning, the uuid from
  the feed's `rel="related"` link, else the feed id) when no real (non-simulated) row is stored, on the **first
  successful poll since startup** (downtime unknown), and after a gap longer than the feed's 24 h memory
  (`WarningPollingJob.FEED_MEMORY`). A restart therefore re-reads the snapshot once; harmless, as the unique key makes
  it a no-op. A feed without a related link or id skips the snapshot.
- The poll needs an **API key** (`NOT_CONFIGURED` otherwise); the base URL has a default.
- **Never deletes. Never infers a cancellation from absence** in the feed: a cancellation is a stored `CANCELLED`
  version. A stored cancellation with no earlier versions shows nothing.
- An update that answers **404 or 410** (the Met Office expires an update after about a day) is skipped, counted and
  marked processed, not a failure. A **malformed** entry, document or feature is skipped and counted, never fails the poll.
- A failure ends the poll as FAILED with a reason kept for Operations: `NOT_CONFIGURED`, `UNAUTHORISED` (401 or
  403), `RATE_LIMITED` (429), `UNREACHABLE`, `MALFORMED_FEED`, `HTTP_n`. On 401/403/429 during the snapshot or updates the loop stops
  at once; any other fetch failure lets the remaining entries be stored and the first reason stands. A failed poll
  never records a success and **never blanks stored warnings**.
- `ServedBriefingEpoch.bump()` is called **once, after the last insert has committed, and only when a version was
  stored** (no transaction is open at that point, so the bump is immediate). The ETag is body-hashed and follows by
  itself. Time-driven transitions (`valid_from` reached, `valid_to` passed) never bump; they lag by at most the 30 s
  briefing memo. (The W1b overlay is what makes the epoch matter; the Ask answer is under "The serve-time overlay".)
- Unknown weather types are **kept** and counted in memory (`WarningPollStatus.unknownTypeCounts()`).
- The scheduler gate: `DynamicSchedulerService.configGateFor` now also recognises `photocast.warnings.enabled`
  (it was only the literal `aurora.enabled`). A flag flip needs a restart (the status syncs at startup).
  `DynamicSchedulerService` keeps its old 7-argument constructor (warnings off) for the many tests that build it;
  Spring uses the 8-argument `@Autowired` one.
- Catalogue entry: `Purpose.FORECAST`, service "Met Office", no Claude. Its `why` says "On a repeating interval".

## Active at T (`WarningTimeline`)

For the injected `Clock` (the rewound instant for a rewound admin GET; the poller itself is never rewound): among
versions with `received_at <= T`, take the highest version number; it is active when its status is not
`CANCELLED` **and** `valid_to > T` (a live version with no `valid_to` is never active). Hence the Rewind case: v1
Yellow received before T and v2 Amber received after T shows Yellow at T. This deliberately shows "what we had
received", not what the Met Office had published.

**Upgrade flag:** when the active version's level **rank** (Yellow 1, Amber 2, Red 3, anything else 0) is higher
than the version before it, it carries `upgradedFrom` (the previous level) and `upgradedAt` (that version's
`received_at`) for 24 h after `received_at`, against the injected clock. The flag belongs to the **latest raising
version**: a later area- or time-only version keeps it until the 24 h end, a later downgrade below the raised level
clears it, and downgrades and edits alone never raise it. `stale` also uses the injected clock, so a rewound admin
normally sees `stale: true` (known and accepted). Simulated rows are excluded entirely unless the caller asks (admin). Levels and weather types are strings, never enums.

**Versions are decimals** (`BigDecimal`, "3.0"), compared numerically everywhere (10.0 is later than 9.0). **Order**
(style guide, plan section 12): level (Red, Amber, Yellow), then `impact`, then `likelihood` (highest first, a missing
value last), then `valid_from` (earliest first, missing last), then warning id. **Updated flag:** `ActiveWarning.updated()`
is true when the version in force is 2.0 or later (an ISSUED warning that has been re-issued); it is served as
`updated` and is separate from the 24 h upgrade flag above (a level rise), which is unchanged.

## Coverage (`WarningCoverage`)

Per call, never cached (the location roster changes, a few warnings times a few hundred locations is cheap, and
Rewind bypasses memos). Input is the enabled locations' coordinates (`lon`, `lat`; GeoJSON is lon-first); output is
the location ids per warning id. **Scope (Q3, global):** `GET /api/warnings` serves only a warning that covers at
least one enabled location; the rest are stored but not surfaced. No filtering by the reader's sub-region picks
(Lite parity).

## Endpoints

See `api-endpoints.md` ("Weather warnings"). `GET /api/warnings` is in `REVALIDATABLE_READ_PATHS`
(`HttpCachingConfig`), is not personal data, and is **not** under `/api/admin/` so Rewind applies. Simulated rows
**never** appear in it, for anyone. `stale` is true when there has been no successful poll since startup, when the
last one is older than `photocast.warnings.poll-stale-minutes` (30; exactly 30 minutes is not yet stale), and when the
feature is off: an empty list must never read as an all-clear. When stale, surfaces must show "Warnings last
checked HH:MM, check the Met Office" and suppress the "none" state (frontend, W2+). Stored warnings stay shown until
`valid_to`, so a withdrawn warning can linger during an outage.

**Served fields (W1c).** `WarningDto`: `warningVersion` (a string such as "3.0"), `level` (upper case), `likelihood` and
`impact` (the **raw feed integers**, never words), `headline`, `whatToExpect` (every line, in full), `whatShouldIDo`,
`furtherDetails`, `updateDescription`, `updated`, `affectedAreas` (region codes such as NE, NW, YH), `geometry`,
`upgradedFrom`, `upgradedAt`, `locationIds`. There is no per-warning URL in the feed, so the response carries
`metOfficeUrl` (`photocast.warnings.public-url`, default the Met Office public warnings page) and `attribution`
(`photocast.warnings.attribution`, default "Met Office Weather Warnings \u00a9 Crown Copyright 2026"), both present even
when the feature is off. `/api/warnings` is revalidated (ETag), never given a browser max-age, for the licence timeliness rule.

Simulation (`POST /api/admin/warnings/simulate`) inserts a clearly simulated row (`simulated = true`, id `SIM-xxxxxxxx`,
`source_status = SIMULATED`, a square of 0.5 degrees around the given point) and bumps the epoch. It is visible only
through `GET /api/admin/warnings/status`.

## The serve-time overlay (W1b)

Follows the `bigSeas` precedent: attached **last** in `ServedBriefingAssembler.assembleForPlan` (after
`PlanWindowProjector`, `BriefingHonestyFilter`, the sample gate and the slot-tide strip, before the `briefingVersion`
stamp), derived per serve from the database, `WarningTimeline` and `WarningCoverage`, **never written to
`daily_briefing_cache`** and omitted from JSON when null (`@JsonInclude(NON_NULL)`, `PersistedBriefingRemovedKeysTest`).
`WarningOverlay` is a no-op (input returned as the same instance) when `photocast.warnings.enabled` is off, when nothing
in force covers an enabled place, or when the lookup throws (fail-soft, logged at WARN). Simulated rows and cancelled or
expired warnings never appear, for any role. "Now" is the injected `Clock`, so Rewind shows the warnings as we had them.

- **Briefing windows.** `BriefingWindow` gains `warningIds` (ids of the warnings in force whose `[validFrom, validTo]`
  spans the window's event time and that cover at least one enabled place **in a region the window lists**, derived from
  the same data as the counts so the two never disagree; a window listing no region, unregioned slots only, carries none
  because we cannot say where it is; most severe first) and
  `warningPlaceCounts` (`{warningId, regionName, covered, total}` per warning and per region the window lists, only
  where `covered > 0`; `total` is **all enabled places in that region**, so "N of M places in this area" is non-personal
  and never the reader's reach). The event time is the window's `eventTime`, else the event summary's
  `solarEventTime`; a window with neither carries nothing. Windows with a verdict blanked by the honesty filter, stood
  down, or UNRATED still carry the id (a warning is not a rating); only `BriefingWindow.withWarnings` writes the fields
  and every `withX` copies them.
- **Location rows.** `GET /api/forecast` (and `?date=`) rows are `ForecastListDto`, the rows `LocationFourDaySheet` and
  `MapCallout` read. `ForecastController.serveRange` attaches `warningIds` as its very last step, after role and
  sub-region shaping, matching the row's place (by name) against the snapshot at the row's own `solarEventTime` (or the
  place's `SolarEventFreshness` time when the row has none). `forGated`, `unscored` and `withRating` copy the field, so a
  gated or stood-down row keeps its id. A place with **no stored row for a window has nothing to carry the id**; a
  client that must show a warning there reads `locationIds` from `GET /api/warnings`.
- **Roles.** `LitePickShaper`, `BriefingRoleShaper` and the Pro/Admin paths rebuild windows and rows only through
  the withers, so Lite, Pro and Admin read identical `warningIds` and counts, `gated` slots included (tested).
- **Golden test.** `ServedBriefingAssemblerWarningGoldenTest` serves the same snapshot with the overlay off and on
  (active, cancelled and simulated warnings) and asserts that the only difference is the warning fields: every rating,
  verdict, pick, count and the `briefingVersion` are identical.
- **Epoch and the Ask memo.** The poller bumps `ServedBriefingEpoch` after the last insert commits and only when a
  version was stored; `BriefingService.servedMemo` is keyed by it, so a new or cancelled warning reaches the next
  serve (time-driven transitions lag by at most the 30 s memo). `AskSnapshotBuilder`'s 30 s memo **is invalidated by the
  epoch indirectly**: it is keyed on the identity of the served briefing it was built from, and that object changes when
  the epoch moves. Ask reads ratings and picks off the windows by named accessors and never serialises a window, so
  `warningIds` cannot reach an Ask prompt or answer; Ask must not mention warnings in this series, so nothing further was
  changed.

## Operations panel (W1b)

Operations, Testing, **Warnings** (`#manage/warnings`, `components/WarningsAdminPanel.jsx`): read-only status from
`GET /api/admin/warnings/status` (feature flag, key and feed URL set or not, **last checked** (an Operations-only
detail), last success, fresh or stale, the failure reason in words, the last poll's entry, stored, already-held,
gone and malformed counts, stored and simulated version counts, unknown weather types with counts, and every warning in
force with its simulated and upgraded flags and how many enabled places it covers) and a **Simulate a warning** form
posting to `POST /api/admin/warnings/simulate`. Not a dialog; Tailwind only; reads the real clock only to format
instants. The endpoint 403s a non-admin, and the tab sits in the admin-only Operations area.

## The feed shape (recorded from the real v1.1 feed, plan section 11)

Base `https://data.hub.api.metoffice.gov.uk/nswws/v1.1`, header `apikey` (both configurable), paths end in a slash.
Everything below lives in `WarningFeedParser`.

- **Feed:** Atom. Each `<entry>` is a change event: its `<id>` is `urn:uuid:<uuid>` (its alternate link ends in the
  same uuid) and names an `updated/{uuid}` document. An entry with no readable uuid counts as malformed. The feed-level
  `rel="related"` link is the `issued/{uuid}` snapshot.
- **Documents (`updated` and `issued`):** a GeoJSON `FeatureCollection` of N warnings (N may be 0), one version row per
  feature. Feature `properties`: `warningId` (uuid), `warningVersion` (decimal **string**, "3.0"), `warningStatus`
  (ISSUED, CANCELLED, EXPIRED), `warningLevel` (upper case), `warningLikelihood` and `warningImpact` (integers),
  `weatherType` (array), `validFromDate`, `validToDate`, `issuedDate`, `modifiedDate`, `warningHeadline`,
  `whatToExpect` (array, kept in full), `whatShouldIDo`, `warningFurtherDetails`, `warningUpdateDescription`,
  `affectedAreas` (objects; the `regionCode`s are stored). The feature's `geometry` is a MultiPolygon.
- **Status mapping:** ISSUED is live (`ACTIVE`); CANCELLED and EXPIRED both mean ended and are stored as `CANCELLED`, with
  `source_status` keeping the feed's own word (so EXPIRED stays distinguishable and the CHECK constraint is
  unchanged; no `LocalH2EnumWidener` line is needed). A feature with an unknown or missing status, no usable
  `warningId`, or a non-numeric version is skipped and counted malformed, never guessed at. A live warning needs a
  level and a `validToDate`.
- An expired `updated/{uuid}` answers 404 or 410 (counted, not a failure).
- The known weather types (glyph set), in `WarningFeedParser.KNOWN_WEATHER_TYPES`: WIND, RAIN, SNOW, ICE,
  THUNDERSTORM, FOG, EXTREME_HEAT, LIGHTNING (case and punctuation ignored); others are kept and counted.
- `Accept` headers: `application/atom+xml` (feed) and `application/vnd.geo+json, application/geo+json,
  application/json` (documents). XML is parsed with DOCTYPEs refused (no XXE).

## Tests

Fixtures under `backend/src/test/resources/warnings/`: `real/` holds the **real recorded samples** (`feed.xml`, an empty
`issued.json`, `updated-1.json` with one EXPIRED snow warning); the rest are synthetic, derived from them
(`issued-snapshot.json`, `updated-issued-v2.json`, `updated-cancelled.json`, `updated-multi.json` with versions 9.0 and
10.0 and three unreadable features, `feed-three-entries.xml`). The live feed is never called; the client is mocked, and
its URL and header wiring are proven against a loopback server. The migrations are proven only in CI
(`WeatherWarningVersionMigrationTest` for V224 and `WeatherWarningRealFeedShapeMigrationTest` for V225, Testcontainers;
no Docker locally).

## The client, W2 (chips on the location sheet and the map callout)

Phase W2 is the first reader-facing UI. It stays dark while `photocast.warnings.enabled` is false: the server then answers
`enabled: false` with no warnings and the client prints nothing (not even the attribution).

| Piece | Role |
|---|---|
| `api/warningsApi.js` | `getWarnings()`: `GET /api/warnings` through `getMemoised` (same object for an unchanged body). 401, 403 and 404 answer null; other failures throw. |
| `context/WarningsContext.jsx` | `WarningsProvider` (mounted in `App.jsx`'s authenticated tree under the rewind-keyed `AuroraStatusProvider`, so a Rewind remounts it) polls every **2 minutes while the page is visible** (`useVisiblePoll`, refetch on focus after a minute). Poller 5 min plus the 30 s briefing memo plus this 2 min keeps a new or cancelled warning inside the licence's 10 minutes. Answers apply in request order; a failed poll keeps the last answer. `useWarnings()` returns `NO_WARNINGS` outside a provider, before the first answer, on a refusal and when the feature is off, so every consumer degrades to nothing. No role is read: Lite sees what Pro sees. |
| `utils/warnings.js` | Display only: level words and the official colours (`#ca0a37` white ink, `#ff9900` and `#ffe923` black ink), `warningLabel` ("Yellow warning of wind and rain"), `warningName` (adds ", updated"), the style-guide order (`compareWarnings`: level, impact, likelihood, `validFrom`, id), `resolveWarnings` (ids to warnings), `warningsForPlaceAt` and `warningsForWindow`, `staleLead`. |
| `components/WarningChip.jsx` | `WarningChip` (text in the level colour, the level word always printed, "Updated" for the server's `updated` flag, a link to `metOfficeUrl` with `target=_blank rel="noopener noreferrer"`, no icon or logo), `WarningChips` (a labelled group), `WarningFooter` (attribution plus source link, and the stale line). |
| `components/WarningStrip.jsx` | Chips plus footer for one window (the callout). Prints nothing when off, and nothing for a clear window on a fresh feed. |

**Where the ids come from.** The window's stored forecast row (`ForecastListDto.warningIds`, reached through the roster record's
`forecastsByDate`) names its own warnings and is trusted whole, absent meaning none. Only a window with **no stored row** is matched on
the client against `GET /api/warnings` (`locationIds` plus the validity span at the slot's event instant): the doc's own door for that case,
and the one client derivation in the series. Nothing here re-derives a verdict, a pick or a count.

**Provider health.** If no poll has succeeded for ten minutes (`WARNINGS_CLIENT_STALE_MS`) the provider reports `stale: true` with the time of
its own last good reply, so a withdrawn warning cannot linger unannounced on a reader whose polls fail. While the last reply said the feature
is off it polls every 30 minutes instead of two. The stale line names the day (`Fri 14 Aug, 14:05`) when the last check was not today's UK date.

**Rules held.** A chip is never inside a rating, a disclosure button or the star row; no marker or map geometry; the stale line replaces any
"none" state (the surfaces never say "no warnings"); an unknown level is printed neutral with its own word; an unknown type is rendered from the
served value. Chips open nothing of ours (no popup yet, W3); the only action is the Met Office link. The two surfaces:
`LocationFourDaySheet` (every shown row, stood-down, unscored and travel days included: one rule with the callout) and `MapCallout` (live solar windows only, travel days included; its repaint effect is keyed on the stale flag and the chip ids so the card is re-placed when a poll changes the strip).
Tests: `warnings.test.js`, `WarningChip.test.jsx`, `WarningsContext.test.jsx`, `warningsApi.test.js`,
`LocationFourDaySheetWarnings.test.jsx`, `MapCalloutWarnings.test.jsx`.

## The client, W3 (the At a Glance lane, the card marker and the window popup row)

Phase W3 adds the three Plan-matrix surfaces. All of them read `useWarnings()` (the W2 provider), print nothing while the
feature is off and never read a role.

| Piece | Role |
|---|---|
| `utils/warnings.js` | `warningsForCard(served, card)` (which warnings belong to a window), `buildWarningLanes(days, warningsOfCard)`, the matrix (`MATRIX_GRID`, `MATRIX_COLOURS`, `matrixCell`, `matrixValue`, `matrixText`), `placeCountLines`, `validityText` (UK time), `cardMarker`. |
| `components/WindowGlanceLanes.jsx` | The warning lanes, **first in the lanes grid** (rows 1 to k, then the source line, then the topic lanes shifted down by k + 1; topic rows are untouched when no warning applies), plus the phone's per-day cell. |
| `components/WarningChip.jsx` `WarningCardMarker` | The text marker in a Plan window card's top row. |
| `components/WindowWarningRow.jsx` | The popup's WARNING row (`window-sheet-warnings`), above the topic rows, below the field and rail. |
| `components/WarningMatrix.jsx` | The Met Office likelihood and impact matrix as an SVG drawn from values. |

**Which warnings belong to a window.** Matched against the **polled** `GET /api/warnings` list, never trusted from the stored
briefing (the briefing refetches only every 10 minutes; the warnings poll is 2). A polled warning belongs to a card when it is in
force at the card's `eventIso` (the window's `eventTime`, else a slot's `solarEventTime`, bare UTC) and either the server named it
on the window (`BriefingWindow.warningIds`, W1b) or its `locationIds` include one of the window's own places (`card.placeIds`, from
`allSpots`). The second door lets a warning that arrived after the briefing reach its window inside the licence's ten minutes;
the polled list alone decides whether the warning still exists, its level and its text, so a withdrawn warning leaves at the next
poll even though the window still names it. This extends the W2 client door (place against `locationIds`) from one place to a
window's places; it is display matching over served facts, not a verdict or count. Away (travel) cards are never matched.
`windowFirstCards` carries `eventIso`, `warningIds` and `warningPlaceCounts` on every card and `windowFirstStrip` folds `eventIso`,
`warningIds` and `placeIds` onto the strip cards.

**The lane.** One lane per warning per run of consecutive live matrix columns where it applies to a window; warnings are placed
in the style-guide order (level, impact, likelihood, `validFrom`), first fit, so a Red takes the top row before span is
considered. Text in the official level colour, level word always printed, "Updated" for the served flag, no icon, triangle or
glyph. A lane is a button opening the first covered window's popup (`onGoToPlanWindow(key)`, no topic emphasis) when the strip can
open it, else plain text; its name is built from its content. The source line (attribution plus Met Office link) sits under the
lanes; the stale admission replaces any "none" state (the surfaces never say "no warnings"). On the phone each day's cell gets that
day's lanes first and the stale line is printed on the first day only.

**The card marker.** The plan's "card triangle" is **text** because the style guide forbids a Met Office icon or triangle without
agreement: the most severe level word plus "+N" ("Amber warning +1") in its colour, one line, ellipsised, `aria-hidden`. The
card's one spoken sentence names every warning in full ("Amber warning of snow, updated"), after the forecast's claims and before
the pick words. Not drawn on an away cell.

**The popup row.** Per warning, in order: the chip (with Updated), the headline, "Update" (`updateDescription`, only on an
`updated` warning), "What to expect" (every line), "What should I do", "Further details", validity in UK time ("Valid Fri 14 Aug,
12:00 to Sat 15 Aug, 08:30 (UK time)"), the places count per region from the window's served `warningPlaceCounts` ("Northumberland:
4 of 12 places in this area", only where something is covered, omitted when the served counts lack the warning), and the matrix.
Beneath all warnings: the optional "What should I do" link (the style guide's own target), the attribution and the Met Office link.
No stars or verdict colours; a warning never changes the header.

**The matrix.** Verified against page 9 of the Met Office style guide v1.4 (rendered from the PDF, 2026-11-18): plan §12.1's grid is
correct, likelihood 4 at the top, impact 1 at the left (rows 4 to 1: green yellow amber red / green yellow amber amber / green green
yellow amber / green green yellow yellow), the matrix green `#c4e90c`, axis labels exactly "Likelihood" (arrow up) and "Impact"
(arrow right), and a tick on the warning's cell. Drawn from `MATRIX_GRID`; no artwork is copied. Text alternative "Likelihood 3
of 4, impact 2 of 4"; nothing is drawn for a missing or out-of-range value, and no word such as medium or minor is ever invented.
The served `warningLevel` always wins: the grid only locates the cell, and `warningsW3.test.js` asserts the real recorded warning
(likelihood 3, impact 2, Yellow) agrees with it (the hand-made synthetic fixtures are not evidence and do not all agree).

Tests: `warningsW3.test.js`, `WarningMatrix.test.jsx`, `WindowWarningRow.test.jsx`, `WindowGlanceLanesWarnings.test.jsx`,
`WindowFirstHeatStripWarnings.test.jsx`, `WindowSheetDialogWarnings.test.jsx`, `windowFirstWarningsModel.test.js`.
