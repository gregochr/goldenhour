# Met Office weather warnings (backend, W1a)

Behaviour as built in phase W1a of `docs/engineering/weather-warnings-plan.md` (the plan and its §8 review
amendments remain the product and design record). W1a is **backend only**: ingest, storage, the active-at-T rule,
location coverage and three endpoints. The briefing/location-row `warningIds` overlay and the Operations panel are
W1b. Nothing here touches a rating, verdict, Best pick, count, heat field or Claude prompt (no Claude calls at all).

Everything is behind `photocast.warnings.enabled` (default **false**); the flag stays off in production until the
guides ship (W5) and the owner has answered the licence question.

## Pieces (all under `service/warnings/` unless stated)

| Piece | Role |
|---|---|
| `V224__weather_warning_version.sql` | Insert-only table `weather_warning_version`, unique `(warning_id, warning_version)`, plus the `weather_warning_poll` scheduler row (seeded `DISABLED_BY_CONFIG`, FIXED_DELAY 300000 ms, `Europe/London`). |
| `entity/WeatherWarningVersionEntity` | Every column `updatable = false`. No `@Enumerated` column, so no `LocalH2EnumWidener` line. |
| `repository/WeatherWarningVersionRepository` | Extends the bare `Repository` (not `JpaRepository`): `save`, `existsBy...`, a read query, `count`. A test pins that nothing else (no delete, update, saveAll) is declared. |
| `config/WarningsProperties` | `photocast.warnings.*`. `toString()` omits the key. |
| `client/MetOfficeWarningClient` | `@HttpExchange`; both calls take an absolute `URI`. Bean in `AppConfig` built from `restClientBuilder()` (shared 10 s / 30 s timeouts) with default header `x-api-key` when a key is set. No Resilience4j retry: the next poll is the retry. |
| `WarningFeedParser` | **The only class that reads the feed or detail wire format** (see "Assumed feed shape"). |
| `WarningPollingJob` | The scheduler target (`registerJobTarget("weather_warning_poll", ...)`). |
| `WarningPollStatus` | In-memory record of the last attempt, last success, counts, failure reason, unknown-type tallies. Resets on restart. |
| `WarningTimeline` | Pure, `Clock`-injected active-at-T rule and the upgrade flag. |
| `WarningCoverage` / `WarningGeometry` | Pure ray-cast point-in-MultiPolygon (holes handled, malformed geometry tolerated; no JTS on the classpath) and deterministic vertex thinning. |
| `WarningService` | Assembles the shared and admin views; inserts simulated warnings. |
| `controller/WarningsController`, `controller/WarningsAdminController` | Thin; no business logic. |

## The poll

`WarningPollingJob.poll()` self-gates on the flag, refuses an overlapping run (an `AtomicBoolean`; Run Now plus the
schedule can collide) and reports a `SchedulerFireOutcome`. One poll: read the Atom feed; for each entry whose
`(warning_id, version)` is **not stored**, fetch its detail and insert it in **its own transaction** (a
`TransactionTemplate`). No watermark; idempotency comes only from the unique key. A collision with a racing writer
(`DataIntegrityViolationException`) counts as already stored. `received_at` is the poller's real clock.

- **Never deletes. Never infers a cancellation from absence** in the feed: a cancellation is a stored `CANCELLED`
  version. A stored cancellation with no earlier versions shows nothing.
- A detail that answers **404 or 410** (the Met Office expires an update after about a day) is skipped and counted,
  not a failure. A **malformed** entry or detail is skipped and counted, never fails the poll.
- A failure ends the poll as FAILED with a reason kept for Operations: `NOT_CONFIGURED`, `UNAUTHORISED` (401 or
  403), `RATE_LIMITED` (429), `UNREACHABLE`, `MALFORMED_FEED`, `HTTP_n`. On 401/403/429 during details the loop stops
  at once; any other detail failure lets the remaining entries be stored and the first reason stands. A failed poll
  never records a success and **never blanks stored warnings**.
- `ServedBriefingEpoch.bump()` is called **once, after the last insert has committed, and only when a version was
  stored** (no transaction is open at that point, so the bump is immediate). The ETag is body-hashed and follows by
  itself. Time-driven transitions (`valid_from` reached, `valid_to` passed) never bump; they lag by at most the 30 s
  briefing memo. (The W1b overlay is what makes the epoch matter; check whether the Ask snapshot memo is
  epoch-keyed when the overlay lands.)
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
normally sees `stale: true` (known and accepted). Simulated rows are excluded entirely unless the caller asks (admin). Results are ordered most severe
first, then by warning id. Levels and weather types are strings, never enums.

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

Simulation (`POST /api/admin/warnings/simulate`) inserts a clearly simulated row (`simulated = true`, id `SIM-xxxxxxxx`,
`source_status = SIMULATED`, a square of 0.5 degrees around the given point) and bumps the epoch. It is visible only
through `GET /api/admin/warnings/status`.

## Assumed feed shape (UNVERIFIED against the live API)

No NSWWS key was available when W1a was written, and the public documentation page does not state the wire
format. Everything below is an assumption confined to `WarningFeedParser`; a mismatch with the real API is a
one-class fix (and a fixture update under `backend/src/test/resources/warnings/`, which are hand-written to this shape,
not recordings).

- The feed is Atom; entries are `<entry>` in any namespace. The feed URL is `photocast.warnings.base-url`
  (supplied with the key); detail links are taken from the entries (https only).
- Each entry has a `<link href>` to the GeoJSON detail; a link with a `type` containing `json` wins, then
  `rel="alternate"` or rel-less, then the first with an href.
- **The warning id and version are the last two path segments of that link** (`.../{warningId}/{warningVersion}`,
  version an integer of at most 9 digits), falling back to the entry `<id>`. This is the weakest assumption: if the
  real link does not carry them the prefilter finds nothing identifiable (every entry counts as malformed, visibly).
- The detail is a GeoJSON `FeatureCollection` (or one bare `Feature`); the first feature's `geometry` is a
  `MultiPolygon` (or `Polygon`) and its `properties` are `warningId`, `warningVersion`, `warningStatus`,
  `warningLevel`, `warningLikelihood`, `warningImpact`, `weatherType` (array; a lone string is accepted),
  `validFromDate`, `validToDate`, `issuedDate`, `modifiedDate` (ISO-8601 with an offset or Z), `warningHeadline`,
  `whatToExpect` (array), `warningFurtherDetails`, `affectedAreas` (array).
- A detail whose own `warningId` or `warningVersion` disagrees with its entry is refused (counted malformed).
- Any `warningStatus` containing "cancel" (any case) is a cancellation; everything else is live. A live warning
  needs a level and a `validToDate`; a cancellation needs neither.
- The known weather types (glyph set), kept in `WarningFeedParser.KNOWN_WEATHER_TYPES`: WIND, RAIN, SNOW, ICE,
  THUNDERSTORM, FOG, EXTREME_HEAT, LIGHTNING (case and punctuation ignored). The handoff's weather-type table was not
  available to the builder; reconcile this constant against it.
- The `Accept` headers sent are `application/atom+xml` (feed) and `application/geo+json, application/json` (detail).
- XML is parsed with DOCTYPEs refused (no XXE).

## Tests

Fixtures under `backend/src/test/resources/warnings/` (Yellow wind, an Amber upgrade pair, a cancellation, SNOW+ICE, an
unknown type, a polygon with a hole, a malformed entry). The live feed is never called; the client is mocked, and its
header wiring is proven against a loopback server. The migration is proven only in CI
(`WeatherWarningVersionMigrationTest`, Testcontainers; no Docker locally).
