# Performance review, October 2026: plan

Status: **plan, not built.** Draft 2, 2026-10-28, after an adversarial review (two prosecutor lenses, backend and
frontend, each refuting its own charges) and an index audit; §11 records what changed. Draft 1, 2026-10-28. Written against `main` at `45804b3b`. Line numbers are from
that commit; re-find them after any earlier phase (or another series) has merged.

The last performance series was July 2026 (#287–#304: ETags, SWR forecast cache, PWA shell, slim list DTO, Plan
N+1s, batched cloud verification), plus `TideExtremeSnapshot` in October (#1113). About 1,270 commits have landed
since. This plan comes from a read-only review in four lenses (backend serve path, backend background work, frontend
load, frontend runtime). The planner re-read the code behind every finding in §1. Nothing was measured on production.

## 0. Rules for every phase

- One PR per phase (a phase with sub-steps may split further where §7 says so). Built by a Sonnet subagent in its own
  worktree, per CLAUDE.md *How every code change is made*. A `changelog.d/YYYYMMDD-<slug>.md` entry per PR.
- **No behaviour change unless a step says so.** Every step that claims "same output" proves it with an equivalence
  test (old path vs new path, whole-object equality), the shape `BriefingTideSnapshotEquivalenceTest` set.
- **Record the number.** Each step names its metric (queries per request, KB gzip, ms per frame, calls per run). The
  PR body gives before and after. Backend query counts are taken in a test with Hibernate statistics
  (`SessionFactory.getStatistics().getPrepareStatementCount()`) or a counting mock, not estimated.
- Backend gate: the build ladder in CLAUDE.md, ending with `clean verify` excluding `**/integration/**`. Frontend gate:
  `npm run lint && npm test && npm audit --audit-level=high && npm run build`.
- **Never trigger a forecast run, batch or briefing build locally to measure** (real Anthropic spend). Use tests and
  fixtures.
- Mutation checks are restored with `cp`, never `git checkout` / `git restore`.
- **Any memo invalidated by a database write is invalidated after commit**, never inside the transaction.
- UI-touching phases (P2–P4) follow *UI Work — Review Cadence* and say which claims were seen in a browser.

## 1. Findings, by phase

| # | Finding | Evidence | Phase |
|---|---|---|---|
| F1 | Hot topics recomputed on every `/api/briefing` serve; `SlotSignalReader.read` runs 7× (4 queries each) for the same range | `BriefingService.java:287`; strategies Bluebell:120, StormSurge:79, Mist:111, Dust:94, Inversion:152, SnowFresh:115, SnowTops:78 | P1 |
| F2 | About 12 full location-roster loads per serve inside hot topics and the enricher | Supermoon:110, SpringTide:144, KingTide:139, Eclipse:124, LunarEclipse:127, Equinox:83, Aurora:264, Meteor:144, `HotTopicEventEnricher:111-114`, `WindowTideRollupBuilder:179`, `EvaluationViewService:1059` | P1 |
| F3 | `ServedBriefingAssembler.assembleForPlan` runs ~25 more queries per serve; their answers change only with a build, a rating write, a simulation toggle or the clock | `ServedBriefingAssembler`, `BriefingRegionSnapshotService.java:274,280`, `WindowTideRollupBuilder.seas()` :396 (one `marine_wave` query per window) | P1 |
| F4 | The ETag is shallow: a 304 saves bytes, not server work | `HttpCachingConfig` (`ShallowEtagHeaderFilter`) | P1 (context) |
| F5 | `/api/aurora/status` calls NOAA on the request thread; solar wind TTL is 1 min, no single-flight | `AuroraController.java:154-165`, `NoaaSwpcClient.java:58-60` | P1 |
| F6 | `/api/regions/drive-times` does `findAll()` per request | `RegionDriveDurationService.getMatrix()` :165 | P1 |
| F7 | Leaflet JS and `leaflet.css` load before first render for everyone | `vite.config.js:90-92` `manualChunks` pulls React into `leaflet`; built `index.html` modulepreloads it | P2 |
| F8 | Scores fetched only after the briefing returns; scores have no SWR cache | `WindowFirstBriefingContext.jsx:503` | P2 |
| F9 | Initial tab's lazy chunk starts downloading only after the shell renders | `App.jsx:51`, `utils/initialTab.js` | P2 |
| F10 | ~120 KB raw of rarely-needed code in the entry chunk (Ask 52, settings modal 39, auth pages 31) | `App.jsx:5-16,39` | P2 |
| F11 | Precache misses the entry's static imports; one glob matches nothing | `vite.config.js:57-61` | P2 |
| F12 | Startup requests: four per-user settings reads; Lite calls NLC and gets 403 | `useReaderSettings.js:233`, `useTodaysLight.js:42`, `WindowFirstBriefingContext.jsx:598`, `useAskAllowance.js:53`, `useNlcSighting.js:52`, `AskContext.jsx:242` | P2 |
| F13 | Every window focus refetches briefing, scores, aurora, NLC with no gap; an unchanged reply still makes new objects and rebuilds the provider chain, thumbnails, map | `WindowFirstBriefingContext.jsx:506`, `AuroraStatusContext.jsx:113`, `useNlcSighting.js:72`; no poll pauses when hidden | P3 |
| F14 | Health SSE re-renders `AppInner` every 30 s for every role | `useHealthStatus.js:56` (`checkedAt: new Date()`), read at `App.jsx:285-290` | P3 |
| F15 | `MapView` aurora effect re-runs on each new status object; `setAuroraScores({})` makes a new object | `MapView.jsx:2195-2211`, `useAuroraScores.js:63-75` | P3 |
| F16 | Viewline polled by two hooks (4 GETs / 5 min in an alert); polyline lat-lngs rebuilt each render | `MapView.jsx:1829`, `AuroraBanner.jsx:746`, `AuroraViewlineOverlay.jsx:64` | P3 |
| F17 | Heat field fully redrawn every frame of a pan/zoom | `MapHeatLayer.jsx:552`, `useHeatCanvas.js:374`, `heatField.js:1247`; 9 ms median measured at heat P4 (Aug), before outline/veil/hatches | P4 |
| F18 | `MapLabels` sorts, forces layout and runs O(n²) placement every pan frame | `MapLabels.jsx:340-413,495,520`; `mapLabels.js:414` copies `boxes` per placement | P4 |
| F19 | `BoundsTracker` re-renders all of `MapView` on every `moveend`, though its readers need only a few derived values | `MapView.jsx:643,1771,6649` | P4 |
| F20 | `servedMapEvents`, `mapEvents`, `evVerdicts` are plain expressions, so downstream `useMemo`s never hit | `MapView.jsx:3559-3650,3690` | P4 |
| F21 | Chip tooltip re-renders all labels on each `pointermove` | `MapLabels.jsx:679-724` | P4 |
| F22 | Body-wide `MutationObserver` runs a selector query per DOM mutation, tile inserts included | `WindowFirstMapPane.jsx:100-106` | P4 |
| F23 | Ask engine sends no `cache_control`; Ready precompute runs (regions+1)×8 questions up to 6×/day | `ClaudeAskEngine.java:322-347`, `AskReadyPrecompute.java:282-305` | P5 |
| F24 | Briefing gloss: one synchronous Haiku call per region × day × event, every build, STANDDOWN included, nothing reused | `BriefingGlossService.java:210-236` | P5 |
| F25 | Marine refresh: one HTTP call per coastal location in series; find + save per row; high-water query per structure site | `MarineWaveRefreshService.java:107-115,223,245,254` | P5 |
| F26 | Batch triage reads `tide_extreme` per candidate (#1113's shape, other path) | `ForecastDataAugmentor.java:203-206` → `TideFactDeriver.java:116-119,247` | P5 |
| F27 | `findById` per batch result (~4 queries each with EAGER collections) | `BatchResultProcessor.java:332` | P5 |
| F28 | `completeRun` hydrates whole `api_call_log` rows (TEXT bodies) to sum cost | `JobRunService.java:607`, `BatchSummaryDeriver.java:81` | P5 |
| F29 | Batch weather prefetch dedupes by exact coordinate, not grid cell | `BatchWeatherPrefetcher.java:80-85` | P5 |
| F30 | Tables that grow without limit (accepted by the owner, 2026-10-28): `api_call_log`, `cached_evaluation`, `evaluation_delta_log`, `marine_wave`, `job_run`, `forecast_batch`, `pipeline_run*`, `stability_snapshot`, `topic_daily_log`, `ask_usage`. Only a query that scans them without an index matters | no delete path; index audit §7.1 | P6 |
| F31 | No JVM heap limit in production compose; `-Djdk.tracePinnedThreads` is a no-op on JDK 25 | `docker-compose.yml:55` | P6 |

Checked and fine (do not re-review): `/api/almanac` (day cache), `/api/ask/ready` (30 s memo), `/api/forecast`,
`/api/briefing/evaluate/scores`, `/api/outcome/ratings`, user settings endpoints, `TideService.getTideStats`,
`LocationPointProvider`, context value memoisation, the Plan matrix, listener/interval cleanup, briefing Open-Meteo
fetches (batched, grid-deduped), batch sky prompts (cached), virtual-thread pinning (JEP 491).

## 2. P1 — the briefing serve path (backend)

Metric: prepared statements per `GET /api/briefing` on a fixture of 250 locations × the plan horizon, counted in a test
before any change. Targets: a memo **miss** drops by at least 24 (six repeated `SlotSignalReader.read`s × 4) plus the
roster loads P1a removes plus the per-window `seas()` queries; a memo **hit** issues none. The PR records before and
after.

### P1a — hot topic inputs read once (F1, F2)

- New `HotTopicInputs` (package `service`), built by `HotTopicAggregator.getHotTopics` once per call, from `Supplier`s
  so any caller can build one from the repositories it already has (no constructor change for a strategy). Lazy,
  memoised accessors; each runs its query the first time it is asked for and returns an **unmodifiable** list:
  - `slotSignals()` → `SlotSignalReader.read(from, to)` (4 queries)
  - `coastalLocations()` (`findCoastalLocations`), `enabledLocations()` (`findAllByEnabledTrueOrderByNameAsc`; order
    matters), `darkSkyLocations()` (`findByBortleClassIsNotNullAndEnabledTrue`), `bluebellLocations()`
    (`findBluebellLocations`)
  - `isTravelDay(LocalDate)`, memoised per date.
  - Documented single-thread: the aggregator's stream is sequential; nothing shares an instance across requests.
- `HotTopicStrategy` gains `default List<HotTopic> detect(LocalDate from, LocalDate to, HotTopicInputs inputs)` that
  calls the existing `detect(from, to)`. The aggregator calls the three-argument form. Every strategy listed in F1/F2
  overrides it and reads from `inputs`; its two-argument `detect` builds a fresh `HotTopicInputs` and delegates, so
  direct callers and existing tests keep working.
- `HotTopicEventEnricher.enrich` takes `inputs` (it loads four rosters at :111-114), in **both** branches of
  `getHotTopics`, the simulation branch included. The aggregator's private `actionableOutsideTravel` uses
  `inputs.isTravelDay`.
- **Equivalence test:** `HotTopicInputsEquivalenceTest` runs every real strategy against one H2 fixture both ways and
  asserts list equality of the aggregator's output (real and simulation branches), plus the statement count: 4 for
  `slotSignals` and one per roster, each at most once.
- Not changed: `WindowTideRollupBuilder:179` and `EvaluationViewService:1059` roster loads (P1b covers them on a hit).

### P1b — memo of the served briefing (F3, F4, and `/api/briefing/digest`)

What the served briefing depends on, besides the build (review B1): `ServedBriefingAssembler.reEnrichVerdicts`
(:376-406) re-reads live `cached_evaluation` and serve triage on every request by design (the region label and the
`/scores` badge stay coherent); `PlanWindowProjector.apply` reads the clock for pastness; the best-bet fallback judges
freshness per request; hot topics and aurora are live overlays. So the memo needs **both** a time limit and explicit
invalidation.

- `BriefingService.getCachedBriefingForApi()` returns a memoised result for **30 s** (`SERVED_MEMO_SECONDS`, the value
  and clock style of `AskSnapshotBuilder.MEMO_SECONDS`), keyed by: the identity of `cache.get()`, the UK civil date
  from `ForecastHorizon`, and a **serve epoch**.
- Concurrency: the method is `synchronized`, as `AskSnapshotBuilder.current()` is (:185), so concurrent misses build
  once.
- **Read the epoch before** `getCachedBriefing()` and store that value with the result, so a result assembled across a
  write is never stamped with the newer epoch.
- `ServedBriefingEpoch` (an `AtomicLong` bean) is bumped **after commit** (`TransactionSynchronizationManager`
  `afterCommit`, or `@TransactionalEventListener(AFTER_COMMIT)`) by every write that changes a serve; in-memory
  setters bump directly. The list, which the builder extends by grep and states in the PR body:
  - in-memory, not transactional: `HotTopicSimulationService.setEnabled` / `setTypeActive` (:42, :61; this also covers
    the lunar-eclipse simulation, `EclipseSightAssembler.java:154`), `AuroraStateCache` activate / clear / reset
  - `cached_evaluation` writes: the end of each `BatchResultProcessor` result batch, and the retraction path
    (`ratings-stores.md`)
  - travel day create / delete
  - every `@Modifying` method on `LocationRepository` (7, including `enabled` and failure auto-disable) and
    `RegionRepository`, and `save` / `saveAll` / `delete` on `LocationEntity` and `RegionEntity` (rosters, region
    names, location type and tide type feed hot topics)
- **Rewind bypasses the memo** (`Rewind.isActive()`), exactly as `AskSnapshotBuilder.current()` does.
- Accepted staleness, ≤ 30 s: live aurora summaries, pastness at a window's edge, and any `cached_evaluation` write the
  list missed (so Plan labels may trail `/scores` badges by up to 30 s in that case).
- What is memoised is the result **before** role shaping and `LitePickShaper`; those stay per request.
  `getCachedBriefingForApi` takes no user, so personal data never enters the memo.
- `BriefingDigestService` gets the memo for free. `AskSnapshotBuilder` keeps its own 30 s TTL (it also reads travel days, the
  almanac and night forecasts) but is keyed on the identity of the served briefing it was built from, so a serve-epoch
  bump reaches Ask on the next call. Misses are single-flight per (cache, date, epoch), Rewind bypasses the memo and the
  lock, and a result is stored only if no newer one is. `getCachedBriefing()` (the unfiltered internal
  path, read by the batch collector and SSE drill-down) is **not** memoised.
- Tests: hit within 30 s (one assembly); miss after 30 s via a mutable clock; miss on a new cache object; miss on date
  roll; miss after each epoch source (one test per item in the list); a write inside a rolled-back transaction does not
  bump; the epoch is read before assembly (a bump during assembly leaves the result stale-stamped and the next call
  rebuilds); Rewind always assembles.
- **Docs in the same PR** (it changes a documented invariant): CLAUDE.md's two-question line, `architecture-decisions.md`
  and `hot-topics-tides-eclipse.md` say "recomputed per serve, memoised up to 30 s; simulation toggles and rating
  writes invalidate it"; `BriefingService`'s javadoc (:269) is updated to match.
- **Owner decision D1:** the 30 s window. Recommendation: 30 s.

### P1c — `seas()` in one query (F3)

- New derived query on `MarineWaveRepository` (`findByLocation_IdAndEvaluationDateBetween` does not exist today).
  `WindowTideRollupBuilder.seas()` (:396) loads the representative location's rows for the whole date range once and
  reads from a map keyed `(date, event)`. Equivalence test over every window in the fixture.

### P1d — aurora status from cache (F5)

- New `NoaaSwpcClient.getOrRefreshAsyncKp()`, `getOrRefreshAsyncOvation()`, `getOrRefreshAsyncSolarWind()`, used
  **only** by `AuroraController` (:154-165). Each returns the cached value and, when it is older than its TTL, starts
  one refresh on a virtual thread (single-flight per feed). With nothing cached at all (cold start), the call waits for
  the one in-flight fetch.
- `fetchKp` / `fetchOvation` / `fetchSolarWind` / `fetchAll` stay synchronous and unchanged: the aurora state machine's
  poller decides on them and must not see stale data.
- Tests: concurrent stale callers start exactly one fetch; a cold cache waits; a failed refresh keeps the stale value
  and retries on the next call; `fetchAll` still fetches synchronously.

### P1e — drive-time matrix memo (F6)

- `RegionDriveDurationService.getMatrix()` keeps the last result, as an **unmodifiable** map, in an `AtomicReference`.
- Cleared after commit inside `RegionDriveTimeWriter.replaceForRegion` and `clearForRegion` (:39, :62; the latter is
  also called from `RegionService:219`), and on location or region delete (the FK cascades, V145:41-42).
- Tests: two reads, one query; each writer and each delete clears; a rolled-back write does not.

## 3. P2 — frontend load

Metric: entry closure KB gzip (JS + CSS) from `dist/index.html`; time-to-ratings on a cold and a warm load in the
browser with 4× CPU throttle and Fast 4G.

### P2a — chunk groups (F7)

- Replace `manualChunks` in `vite.config.js` with priority groups under `build.rolldownOptions.output.codeSplitting.groups`
  (correct for the installed rolldown 1.2.15; `advancedChunks` is deprecated):
  - `react` `/node_modules[\\/](react|react-dom|scheduler)[\\/]/` priority 30
  - `leaflet` `/node_modules[\\/](leaflet|react-leaflet|@react-leaflet)[\\/]/` priority 20
  - `geo` (the existing d3-geo/d3-array rule, keep its ordering comment) priority 15
  - `recharts` (`recharts`, other `d3-*`, `victory-vendor`) priority 10
- Correct the stale comment about leaflet loading only with the Map view.
- Proof: a node script `frontend/scripts/check-entry-closure.mjs`, run after `npm run build` in CI's frontend job,
  fails if `dist/index.html` has a `leaflet` modulepreload or a `leaflet-*.css` link. The scratch prototype used
  `advancedChunks`; the PR re-verifies with `codeSplitting`. Expected: leaflet 45 KB gzip and 6.4 KB blocking CSS
  leave the entry.

### P2b — briefing and scores in parallel (F8)

- `WindowFirstBriefingContext.jsx:503` starts both fetches without awaiting one before the other. The request-number
  guards already handle arrival order (`WindowFirstBriefingFetchOrder.test.jsx:753` covers scores-first). New test:
  both requests are in flight before the briefing resolves.
- **No SWR cache for scores** (review B5). `swrCache.js` is `localStorage`; its own notes (:115-142) record that the
  briefing (~2.6 MB as UTF-16) and the forecasts already do not both fit iOS's ~5 MB. Scores (~5.4 MB as UTF-16 at 204
  locations) cannot fit at all, and on desktop a scores write would starve the briefing's, which is the instant paint
  that matters. Recorded, not built.

### P2c — start the initial tab's chunk early (F9)

- New `utils/preloadInitialTab.js` (testable; `main.jsx` is untested by design), called from `main.jsx`:
  - only when a stored `goldenhour_token` exists (a signed-out reader on the login page fetches no map);
  - `'map'` → `import('./components/WindowFirstMapPane.jsx')`; `'plan'` → the heat strip chunk. Fire-and-forget;
    `lazy()` reuses the module promise.
- Tests: called once per tab value, never without a token, never for other tabs.
- Expectation is modest: `MapHeatLayer` and `MapLabels` are further `lazy()` hops (`MapView.jsx:171,180`).

### P2d — lazy entry code (F10)

- `lazy()` the auth pages (Register, Login, Forgot, Reset, ChangePassword) as one route chunk. `PostcodeCapture` stays
  eager: `auth/PostcodePromptOverlay.jsx:5` imports it statically for every signed-in reader.
- `lazy()` `UserSettingsModal` on first open (keep the sub-region picker with it).
- Ask: keep `AskContext` eager; lazy the dock and conversation behind `enabled` where possible. Known static importers
  that keep pieces eager: `WindowFirstShell` imports `ASK_DOCK_ID`/`ASK_SHEET_LABEL` from `AskDock`/`AskSheet` (move
  those constants to a small `askIds.js`), and `MapPeekAsk` imports `AskConversation`/`AskUpsell` statically. The
  builder lists every static importer first; the PR states the real saving (it will be under 52 KB).
- Every new `Suspense` boundary has a fallback that keeps layout, and lazy dialogs keep the dialog rules (`plan-tab.md`:
  at most two stacked, the focus recorder sees the opener, `selectTab` closes them).
- Metric: entry KB gzip before/after.

### P2e — precache the entry closure (F11)

- vite-plugin-pwa's globs are fixed at config time, so use `workbox.manifestTransforms`: keep every `assets/*.js` and
  `*.css` that `dist/index.html` references (script, modulepreload, stylesheet), as today's `index-*`/`react-*` globs
  do, and drop the dead `rolldown-runtime-*` glob.
- Proof: the P2a node script also asserts every `modulepreload` href in `index.html` is in the generated precache list.

### P2f — fewer startup requests (F12)

- `useNlcSighting`: no request for Lite (gate on role, the same predicate the backend 403 uses).
- `/api/regions` and `/api/regions/drive-times` join the SWR cache only if the budget allows: both are small, but the
  builder measures them and states the bytes, given the iOS ceiling above.
- **Not changed:** `/api/user/settings/ask` still runs for every role (review B1). It is the client's only source for
  the Ask flag, and Lite needs it to reach the `'locked'` upsell (`AskContext.jsx:462-467`, `api-endpoints.md`).
- **Owner decision D2:** a combined `GET /api/user/settings/boot` replacing four reads. Recommendation: **defer**.

### Not doing in P2 (recorded)

- Replacing axios with `fetch` (~15 KB gzip): the single-flight 401-refresh behaviour is load-bearing.
- Splitting `index.css` (44.6 KB gzip) per lazy component: revisit if P2d's measurement shows CSS is now the largest
  blocking item.
- SWR for scores (see P2b).

## 4. P3 — polling and unchanged payloads (frontend)

Metric: renders of `AppInner`, `WindowFirstShell`, `MapView` and canvas paints over a 10-minute idle session with two
focus events, counted with the React Profiler in the browser. Target: zero re-renders and zero paints when nothing
changed.

### P3a — `useVisiblePoll` (F13)

- New `hooks/useVisiblePoll.js`: `useVisiblePoll(fetchFn, intervalMs, { minGapMs = 60000, enabled })`.
  - Clears the interval while `document.visibilityState === 'hidden'`; on `visible` refetches only if the last fetch
    is older than `minGapMs`, then restarts the interval. Window `focus` follows the same rule.
  - **The gap is measured with `Date.now()`, never `appNow()`** (review B2: under Rewind `appNow()` is frozen, so the
    gap would never elapse). Rewind needs only the request header `axiosClient` already adds.
  - A change of `fetchFn` identity or of `enabled` fetches **at once**, ignoring the gap: sign-in, a role change, an
    upgrade and `AuroraStatusContext`'s `isLite` re-key all rely on an immediate fetch today through effect deps.
- Adopt in `WindowFirstBriefingContext` (briefing + scores together), `AuroraStatusContext`, `useNlcSighting`,
  `useAuroraViewline`, `MapView`'s Lite aurora nights poll. Operations-tab admin polls are left alone.
- Tests: `WindowFirstBriefingFetchOrder.test.jsx:188` (`refocus()`) and about 12 other suites drive races through
  immediate focus refetches. They advance a fake clock past `minGapMs` before each refocus; the PR lists every suite
  changed and why. New tests: hidden pauses, visible-after-gap fetches, visible-within-gap does not, identity change
  fetches at once, Rewind does not stop refetches.

### P3b — keep the previous object when the reply is unchanged (F13, F15)

- **The public return contracts of the API functions do not change** (about 35 test files mock them). Inside each API
  module (`getDailyBriefing`, `getAllEvaluationScores`, `getAuroraStatus`, Lite's `/api/aurora/alert`,
  `getNlcSighting`, the viewline), request `responseType: 'text'` and parse with a per-URL memo: when the raw text
  equals the last raw text for that URL, return the identical parsed object; otherwise `JSON.parse` and remember both.
  - Do it in the function, **not** with a custom `transformResponse` (which would also run on error bodies, so
    `err.response.data` would stop being parsed JSON).
  - Handle the 204 briefing (`raw === ''`) exactly as today.
- Callers then skip `setState` when `Object.is(next, prev)`, but **still** advance `briefingAppliedRef` /
  `scoresAppliedRef` and refresh the SWR `ts`, so a current cache does not age out.
- `MapView`: a module-level `EMPTY_SCORES` constant replaces `setAuroraScores({})`; the aurora-scores effect depends on
  `status?.level` and `status?.active`, not on `status`.
- `AuroraStatusContext` value wrapped in `useMemo`.
- Tests: an identical second response yields the identical object and no provider value change (`Object.is` on the
  context value) and no thumbnail paint; a changed response does; an error body is still parsed JSON in the catch.

### P3c — health status off the app tree (F14)

- `setHealth(prev => same(prev, next) ? prev : next)` comparing every field except `checkedAt`.
- Health moves into its own small context. The pill (`HealthIndicator.jsx:188` renders `checkedAt`) subscribes to the
  full value, `checkedAt` included. `App` subscribes only to a boolean `isDown` selector, which it still needs for the
  down banner and `contentDisabled={isDown}` (`App.jsx:782, 948`).
- **Sequencing:** `token-log-redaction-plan.md` step 4 changes `createEventSource` and `useHealthStatus`. Whichever
  lands second rebases; P3c must not change `createEventSource`'s contract.

### P3d — one viewline source (F16)

- One shared poll serves `MapView` and `AuroraBanner`. Its enable predicate is the **union** of the two today:
  `AuroraBanner.jsx:744` (not dismissed, not mobile, `field !== null`, not overcast) OR `MapView.jsx:1817` (not Lite,
  alert-worthy). It **never runs for Lite** (`aurora.md`: the Lite banner makes no viewline request).
- Keep B3's rule: consumers depend on the scalar `lineLat`, never the object (`aurora-banner-plan.md` B3).
- `AuroraViewlineOverlay` memoises its lat-lngs on `viewline`.
- Tests: each consumer's predicate alone enables the poll; Lite never does; one request per interval with both mounted.

## 5. P4 — map interaction (measure, then fix)

### P4a — measure first (no code merged)

- Browser run on the dense fixture (≈260 locations, the one heat P4 used), 390×844 and 1440×900, CPU 4× throttle:
  drag-pan 10 s and **pinch**/wheel zoom 10 s; record ms per frame (median, p90, p99) for `drawTiles` and
  `MapLabels.paint` + layout separately (`performance.mark`).
- **Gate:** P4b runs if `drawTiles` p90 > 10 ms at 390 px; P4c runs if `MapLabels` paint + layout p90 > 6 ms. P4d–P4f
  run regardless. The numbers go in this doc's phase log.

### P4b — heat field: no full redraw mid-gesture (F17)

- The canvas already rides `_mapPane`'s translate during a drag, so the mechanism is: **skip the repaint on `move`**;
  full `drawTiles` on `moveend` / `zoomend` (where `settle` already repaints). `resize` and `viewreset` still repaint.
- **Pinch** (Leaflet 1.9.4 `Map.TouchZoom.js:100`) calls `map._move(..., {pinch: true})` every frame, firing `zoom` +
  `move` at fractional zoom; `zoomanim` fires only at the end. On `zoom`, transform the last bitmap by
  `map.getZoomScale(z, drawnZoom)` about the drawn origin (or keep the full redraw on `zoom` if that measures fine).
- **Declared behaviour change:** the field is hidden today during `zoomanim` (`leaflet-zoom-hide`,
  `MapHeatLayer.jsx:323-330`). P4b keeps that unless the owner chooses a scaled field during wheel/button zoom
  (**owner decision D7**; recommendation: keep it hidden, change nothing there).
- **No canvas padding** in the first cut: 25% padding is 2.25× the pixels per settle paint and shifts the frame that
  `clipDx/Dy`, the coastline translate and `drawReachRings`' `homePt` use. A blank edge for one gesture is accepted.
- `drawTiles` itself is not changed: the Plan thumbnails share it.
- Must preserve: the marker fade at the z8→z13 handover, the clip-threshold behaviour under `zoomSnap`
  (`map-tab-v2-plan.md` §234), the dedupe-by-view settle from heat P4.

### P4c — labels: no repaint mid-gesture (F18)

- The label root is in a Leaflet pane that is already translated during a drag (`MapLabels.jsx:662-665`), so the
  mechanism is: **skip `paint` on `move`**; re-measure and re-place on `moveend` / `zoomend`. Pinch: as P4b.
- **Declared behaviour change:** location chips are in this layer, so a chip entering from an edge appears at
  `moveend`, not during the drag. Owner accepts or rejects in **D7**.
- `placeLabelPass` (`mapLabels.js:414`): copy `obstacles` once, then push into that copy instead of spreading per
  placement (`obstacles` is documented as never mutated, and `MapLabels.jsx:623` reuses it). Equivalence test on
  placement output.

### P4d — `MapView` stops re-rendering on every pan end (F19)

- `tideViewBounds` is read by the tide strip **and** the phone tide-cue gate (`hasCoastalInViewForCues` → `tideCuesOn`,
  `MapView.jsx:4042-4058`) **and** the tide-peek pulse arm/seed effect (:4200-4212, whose null-then-first-report seed
  is load-bearing). So the bounds cannot simply move into the strip.
- Instead: `BoundsTracker` keeps reporting, but `MapView` stores only the **derived** values its readers need
  (`hasCoastalInView`, the strip model's counts) and sets state only when one changes. Desktop/tablet, where
  `tideCuesOn` short-circuits, may let the strip own raw bounds. The first-report seed stays.
- Tests: a pan that changes nothing derived does not re-render `MapView` (render counter); a phone pan inland turns the
  tide cues off; the peek seed still fires on first report.

### P4e — make `MapView`'s memos hit (F20)

- Wrap `servedMapEvents`, `mapEvents`, `evVerdicts` in `useMemo` with their real inputs, **including time**:
  `buildMapEvents` defaults `now = appNow()` (`mapEvents.js:852`), high-water expiry re-renders through
  `setHwExpiryTick` (`MapView.jsx:4627`), and `isEventTimePast` is per render. Name `hwExpiryTick` and a pastness tick
  in the deps. Hoist `ukDateStrOffset(1)` and the inline aurora date spread first.
- Tests: identical props keep `Object.is`; `hwEventSweep.test.jsx` passes unchanged; a tick advances past rows.

### P4f — small ones (F21, F22)

- Chip tooltip becomes its own component positioned through a ref + transform, rAF-throttled; `MapLabels` no longer
  re-renders on `pointermove`.
- `WindowFirstMapPane`'s `MutationObserver` **keeps `subtree`** (review B4: `shared/Modal.jsx` renders inline in
  `#root`, not portalled, and the `stacked` `aria-modal` toggle needs `attributes` with `subtree`). Instead it filters
  in the callback: ignore records whose target is inside `.leaflet-pane` / `.leaflet-tile-container`; run
  `foreignModalOver` only for added/removed nodes that are or contain `[role=dialog]`, or for `aria-modal` changes.
  Tests: a nested `Modal` mount and a `stacked` toggle are still seen; a tile insert runs no query.

## 6. P5 — Anthropic cost and the batch path (backend)

**Sequencing:** `sync-engine-retirement-plan.md` deletes parts of the synchronous engine. P5 starts after its phases that
touch `ForecastService`, `ForecastDataAugmentor` and `JobRunService` have merged; the builder re-finds every line.

### P5a — Ask prompt caching (F23)

- Ask's model is `photocast.ask.model` (`AskProperties.java:76`, default Haiku 4.5), not a `model_selection` row.
  Haiku 4.5's minimum cacheable prefix is 4,096 tokens.
- `AskPromptBuilder` (:262-290) appends a per-question tail (date, scope regions, covered windows, context window) to the
  system prompt, so caching the whole system block would never hit across Ready questions. Split it:
  - system block 1: the static RULES + WITH_USER / USER_LESS text, with a `cache_control` breakpoint (the tools render
    before the system prompt, so this breakpoint caches tools + static system together);
  - system block 2: the dynamic tail, no breakpoint;
  - a rolling breakpoint on the latest tool-result message, every turn. That is the large saving: tool results are
    resent on each of up to 4 turns. A breakpoint below the minimum is ignored at no cost, so there is no "stop" rule.
  - At most 4 breakpoints in total.
- Tests: the built params carry the breakpoints in the right places, ≤ 4, and the rendered prompt text is
  byte-identical to today's when the blocks are concatenated.
- Proof of saving is from production after deploy: `cache_read_input_tokens` in `api_call_log` for Ask rows (cost
  accounting already handles cache tokens, `CostCalculator.java:44,100`). Prompt-regression assertions are not touched.

### P5b — gloss reuse (F24)

- `BriefingGlossService`: key = SHA-256 of `SYSTEM_PROMPT` + a new `GLOSS_VERSION` constant (bumped whenever the
  post-processing changes: `sanitizeBrand`, `truncateToWords(7)`, the leaked-field drop, :409-445) + model id +
  `buildUserMessage(item)`.
- Keep a `Map<key, gloss>` from the last build, replaced each build (no growth). Store only items with a non-null
  headline and detail; failures and exceptions are never stored.
- Seeding from the persisted `lastKnownGood` at startup is allowed only with the version in the key, and needs a test
  that a JSON round trip of a region yields the same `buildUserMessage`. If that test cannot pass, start cold after a
  restart instead.
- Fix the misleading GO/MARGINAL text at :190 (log) and `BriefingService.java:572`.
- **Owner decision D3:** templated text for STANDDOWN regions (visible prose change). Recommendation: **no** for now.
- Metric: Haiku calls per build, before/after, in a test.

### P5c — marine refresh batched (F25)

- New list-returning exchange on `OpenMeteoMarineApi` (today `getMarine` takes single doubles, :29-35). Batch 20 exact
  coordinates per request, **no grid-cell dedupe** (`gridCellKey()` is the forecast model's grid, not the marine one).
- Failure isolation matches today's per-location behaviour (`MarineWaveRefreshService.java:107-115`): a failed chunk
  persists nothing for its locations but still runs their high-water refresh.
- Bulk-load existing `marine_wave` rows by location ids and dates; `saveAll` the upserts.
- High waters: read from the build's `TideExtremeSnapshot` when one is passed; live query otherwise.
- Equivalence test with an injected clock (`now` is persisted): same rows as the per-location path.

### P5d — triage tide snapshot (F26)

- The chain is `ForecastTaskCollector` → `ForecastService.fetchWeatherAndTriage` (:289/:327) → `augmentWithTideData` →
  `TideFactDeriver.derive`. Build one snapshot for the candidates' coastal ids over the date window and thread it down.
  `TideExtremeSnapshot.load` is package-private (:97) and the collector is in `service.batch`: expose a public factory
  on `TideService` rather than widening `load`. Equivalence test as in #1113.

### P5e — batch result locations in one read (F27)

- Results are streamed and iterated once (`BatchResultProcessor.java:181-183`), so ids cannot be collected first.
  Before the loop, load `Map<Long, LocationEntity>` with one `findAll()` (a few hundred rows; `default_batch_fetch_size:
  32` covers the EAGER collections). A missing id behaves exactly as today's `orElse(null)`.

### P5f — cost sum in SQL (F28)

- `ApiCallLogRepository`: `@Query("select coalesce(sum(c.costMicroDollars), 0) from ApiCallLogEntity c where
  c.jobRunId = :id")`; `JobRunService.completeRun` uses it. `BatchSummaryDeriver` reads a projection without the TEXT
  columns. Test: same totals as the old path on a fixture.

### P5g — prefetch by grid cell (F29)

- `BatchWeatherPrefetcher` dedupes on `loc.gridCellKey()` when `hasGridCell()`, and fans the result out to every
  coordinate key in the cell, so `prefetchBatchCloudPoints` (~:125) never misses a lookup.
- **Declared input change:** Open-Meteo downscales temperature by a point's elevation, so two coordinates in one cell
  can differ today. This adopts the assumption `BriefingWeatherLoader` already makes (:67). Tests: fan-out covers every
  location; a location without a grid cell keeps its own fetch.

## 7. P6 — indexes and runtime config

### P6a — index audit for queries on growing tables (F30)

**Owner decision (2026-10-28): table growth is accepted; no retention jobs.** What matters instead is that no query
gets slower as those tables grow. Checked by the planner: the queries this plan leans on are covered —
`forecast_score` (`idx_forecast_score_type_date`, V108), `slot_atmosphere` (`idx_slot_atmosphere_date`),
`api_call_log.job_run_id` (`idx_api_call_log_job_run`), `tide_extreme` (`idx_tide_extreme_location_time`),
`marine_wave` (unique `(location_id, evaluation_date, event_type)`), `outcome_rating`, `briefing_region_snapshot`.
P6a adds the indexes §7.1 marks "yes".

### 7.1 Index audit result (2026-10-28)

A read-only audit of every repository query on the growing tables (there is no JdbcTemplate or native SQL outside
`repository/`). The planner re-checked rows 1–3 against the migrations. Existing pruners (`slot_atmosphere`,
`forecast_run_disposition` 30 days, `briefing_region_snapshot`) **stay**: the owner's decision is "no new retention",
not "remove what exists".

| # | Index | Serves | How often | Why it is missing today | Do it |
|---|---|---|---|---|---|
| I1 | `api_call_log (batch_id) WHERE batch_id IS NOT NULL` | `ApiCallLogRepository.findCustomIdsByBatchId` :75, `findBatchCallOutcomes` :93, `findFailedBatchCalls` :64 | every batch completion, every pipeline cycle (`CycleLocationOutcomeResolver:200`), every retry | no index contains `batch_id`; on the largest table this is a near-full scan | **yes** |
| I2 | `forecast_evaluation (forecast_run_at) WHERE batch_state = 'PENDING'` | `ForecastEvaluationRepository.abandonPendingOlderThan` :282 | hourly (`evaluation_abandonment_sweep`, V150) | nothing indexes `batch_state`; V149's own comment anticipates this index. The column is `@Enumerated(STRING)`, so the literal is `'PENDING'` | **yes** |
| I3 | `forecast_score (pipeline_run_id)` | `ForecastScoreRepository.findScoredComponentsByPipelineRun` :128 | each pipeline cycle | no index leads on it; also backs the unindexed FK to `pipeline_run` | **yes** |
| I4 | `forecast_evaluation (target_date, target_type)` | `findByTargetDateAndTargetTypeIn` :147 | nightly (`TopicDailyLogJob:278`) | every index on the table leads on `location_id` | **yes** (nightly full scan of the biggest table) |
| I5 | `forecast_run_disposition (evaluation_date, disposition)` | `findLatestNonCachedDispositions` :218 outer scan (every `/api/briefing` serve), `findLatestStabilitySkipTimestamps` :101 | per serve (P1b's memo cuts this to ≤ 2/min) | outer range on `evaluation_date` has no leading index. Bounded by the 30-day pruner; V160 measured the whole query at 34 ms | optional; include only if `EXPLAIN` in the Testcontainers test shows a seq scan worth removing |
| I6 | `job_run (run_type, started_at)` | `JobRunRepository` :28, :46, :59; the subquery in `ApiCallLogRepository.sumCostMicroDollarsByRunTypeStartedSince` :107 | several per day + Ask | V29 dropped `idx_job_run_name_started` and nothing replaced it; table is small | yes, cheap |

- One migration (next free `V` number, read from the tree), plain `CREATE INDEX`, following V160's precedent and its
  comment style (Flyway runs in a transaction, so not `CONCURRENTLY`). The builder records the production row count of
  `api_call_log` and `forecast_evaluation` **from the owner** before merge: on a multi-million-row table a plain build
  holds a write lock for the build's duration at startup; if it is more than a few seconds, the owner picks a quiet
  deploy window.
- An integration test (Testcontainers, CI only) creates the indexes and asserts each query's `EXPLAIN` uses its index on
  a seeded table, the way V128 and V160 were proven.
- Not added (small or rare): `forecast_batch` status, `pipeline_run_pick.recorded_at`, `cached_evaluation` heartbeat
  counts.
- **Possibly redundant indexes** (write cost only; **owner decision D6**, recommendation leave them alone in this
  series): `idx_tide_extreme_location_time` duplicates `uq_tide_extreme`; `idx_topic_daily_log_type_date` is a prefix of
  its unique key; `idx_forecast_batch_pipeline_run` is a prefix of `idx_forecast_batch_pipeline_run_retry`;
  `idx_api_call_log_service` / `_failed`, `idx_eval_delta_stability_age` / `_location_basis` and
  `idx_cached_evaluation_region_date` have no reader in `src/main`.

### P6b — JVM (F31)

- `docker-compose.yml`: `-XX:MaxRAMPercentage=` (value is **owner decision D5**, depends on the host's RAM and what else
  runs there); remove `-Djdk.tracePinnedThreads`. Production deploy config, so the owner applies and watches it.

## 8. Sequencing

| Order | Phase | Depends on | Size |
|---|---|---|---|
| 0 | P6a (one index migration) | — | small |
| 1 | P1a, P1b (one PR each), then P1c–P1e (one PR) | — | medium |
| 2 | P2a, P2b+P2c, P2d, P2e+P2f | — (independent of P1) | medium |
| 3 | P3a+P3b, P3c, P3d | P2b (same context file) | medium |
| 4 | P4a measure, then P4b–P4f | P3 (stable identities make the measurement honest) | medium-large |
| 5 | P5a, P5b, P5c, P5d–P5g | sync-engine retirement phases touching the same files | medium |
| 6 | P6b (owner) | — | small |

P1 and P2 touch disjoint files and may be built in parallel worktrees; each still merges on its own green CI and Codex 👍.

## 9. Owner decisions

| # | Decision | Recommendation |
|---|---|---|
| D1 | Served-briefing memo window (TTL is mandatory; the epoch handles writes) | 30 s |
| D2 | Combined settings boot endpoint | defer |
| D3 | Skip gloss for STANDDOWN regions (visible prose change) | no, for now |
| D4 | ~~Retention windows~~ — decided 2026-10-28: no retention; index audit instead (§7.1) | — |
| D5 | Production heap percentage | owner sets from host RAM |
| D6 | Drop the possibly redundant indexes (§7.1) | leave them, not in this series |
| D7 | P4b/P4c behaviour: chips entering from an edge appear at `moveend`; field stays hidden during `zoomanim` | accept both (**accepted by the owner 2026-10-30**) |

## 10. Phase log

| phase | branch | PR | date | before → after |
|---|---|---|---|---|
| plan + P6a | `perf/p6a-indexes` | #1487 | 2026-10-28 | five indexes (V197); I5 skipped |
| P2a | `perf/p2a-chunk-groups` | #1485 | 2026-10-28 | entry closure 286.75 → 236.33 KB gzip; `check:entry` gate in CI |
| P2b+c | `perf/p2bc-parallel-fetch-preload` | #1489 | 2026-10-28 | briefing and scores fetched in parallel; opening tab's chunk preloaded from `main.jsx` |
| P1a | `perf/p1a-hot-topic-inputs` | #1490 | 2026-10-28 | per serve: slot-signal statements 28 → 4, roster statements 9 → 4 (`HotTopicInputs`) |
| P2d | `perf/p2d-lazy-entry` | #1491 | 2026-10-28 | entry closure → ~219.4 KB gzip; auth pages, settings and Ask UI lazy behind `ChunkErrorBoundary` |
| P1c–e | `perf/p1cde-seas-aurora-drivetimes` | #1492 | 2026-10-29 | one marine range read per rollup; aurora status single-flight from cache; drive-time matrix memo with after-commit invalidation |
| P5b | `perf/p5b-gloss-reuse` | #1497 | 2026-10-29 | identical region input reuses its gloss (0 Haiku calls); cold after restart |
| P5a | `perf/p5a-ask-prompt-caching` | #1498 | 2026-10-29 | Ask: static prompt + tools cached, rolling breakpoint on latest tool result; static prefix ≈3.5–3.8k tokens (below Haiku's 4,096 minimum — check `cache_read_input_tokens` after deploy) |
| P2e+f | `perf/p2ef-precache-startup-requests` | #1499 | 2026-10-29 | precache 23 → 39 entries (whole entry closure + AuthPages static closure); NLC poll off for Lite; regions + drive-time matrix cached as one SWR pair |
| P1b | `perf/p1b-served-briefing-memo` | #1500 | 2026-10-29 | `/api/briefing` on a memo hit: 0 statements (assembler not called); a miss assembles as before. 30 s memo, keyed by cache identity, UK date and `ServedBriefingEpoch`; misses single-flight; Ask's snapshot memo keyed on the served object |
| P5f+g | `perf/p5fg-cost-sum-grid-prefetch` | #1501 | 2026-10-29 | run cost summed in SQL; batch summary reads a 5-column projection; batch weather fetched once per grid cell |
| P5c | `perf/p5c-marine-batch` | #1502 | 2026-10-29 | marine refresh 20 coordinates per request, one bulk read + `saveAll`; high waters from the build's tide snapshot |
| P5d | `perf/p5d-triage-tide-snapshot` | #1503 | 2026-10-29 | one tide-extreme snapshot per triage pass instead of a query per candidate |
| P5e | `perf/p5e-batch-locations-one-read` | #1504 | 2026-10-29 | batch result locations: one `findAll` per run instead of `findById` per result |
| P3c | `perf/p3c-health-off-app-tree` | #1505 | 2026-10-29 | a `checkedAt`-only health tick no longer re-renders `AppInner` |
| P3a+b | `perf/p3ab-visible-poll-same-object` | #1506 | 2026-10-29 | polls pause while hidden, 60 s refocus gap; unchanged replies keep the identical object (no consumer re-render) |
| P3d | `perf/p3d-one-viewline-source` | #1507 | 2026-10-29 | one viewline poll for map + banner (was two); overlay positions memoised |
| P4f | `perf/p4f-chip-tooltip-observer` | #1508 | 2026-10-29 | `MapLabels` no commit on `pointermove`; modal observer ignores Leaflet churn |
| P4d+e | `perf/p4de-mapview-renders-memos` | #1509 | 2026-10-29 | a pan that changes no derived answer does not re-render `MapView`; event memos hit between clock boundaries |
| P4a | — | — | 2026-10-30 | **measured; both gates fire.** 4× CPU throttle, 219 heat points, system Chrome via Playwright. `drawTiles` p90 at 390 px: **32.7–35.0 ms** (gate 10; the median of 25–26 ms is also above it). `MapLabels` paint + layout p90 per frame: **13.4–14.7 ms** on phone drag and pinch (gate 6), 8.6–9.8 ms at 1440. Layout (the forced reflow plus `placeLabelPass`) is most of it, about 10 ms median; paint alone is about 1.5–3 ms. Detail below the table |
| P4b, P4c | — | — | — | **gated open, not built**: waiting on the owner's D7 answer (chips from an edge appear at `moveend`; field hidden during `zoomanim`) |
| P6b | — | — | — | **owner**: `docker-compose.yml` heap percentage (D5) and removing `-Djdk.tracePinnedThreads` are production config |

### P4a measurement detail (2026-10-30)

How it was run: a local backend with an invalid Anthropic key, so nothing could bill. The roster was the main checkout's H2 copy plus
`scripts/dev-seed-locations.sh --dense` (220 locations), with synthetic `cached_evaluation` ratings for today to +3 inserted by SQL.
Temporary `performance.measure` marks went round the `drawTiles` call in `MapHeatLayer` and round `MapLabels`' `paint` and its
layout effect; they were not committed. Each gesture ran for 10 s, twice. The phone context was 390×844 with touch: drag used CDP
`synthesizeScrollGesture` and zoom was a real CDP pinch, covering z 6.3–11. The desktop context was 1440×900: mouse drag, and wheel
zoom covering z 7.6–8.4. Figures are count / median / p90 / p99, in ms.

| viewport | gesture | run | drawTiles | labels paint | labels layout | paint+layout / frame | rAF delta | frames > 50 ms |
|---|---|---|---|---|---|---|---|---|
| 390 | drag | 1 | 126 / 26.1 / 32.8 / 49.3 | 126 / 1.5 / 2.4 / 4.2 | 73 / 9.7 / 11.1 / 12.4 | 107 / 9.3 / 13.4 / 21.2 | 174 / 33.3 / 100 / 283 | 64 of 174 |
| 390 | drag | 2 | 129 / 25.6 / 32.7 / 44.5 | 129 / 1.6 / 2.7 / 6.7 | 66 / 9.8 / 11.3 / 13.4 | 110 / 8.6 / 14.7 / 24.3 | 182 / 33.3 / 100 / 283 | 62 of 182 |
| 390 | pinch | 1 | 74 / 25.2 / 34.5 / 70.6 | 81 / 1.6 / 2.8 / 4.8 | 34 / 10.6 / 14.3 / 28.9 | 72 / 4.6 / 14.6 / 30.8 | 330 / 16.7 / 66.7 / 183 | 39 of 330 |
| 390 | pinch | 2 | 74 / 25.7 / 35.0 / 44.9 | 81 / 1.8 / 2.9 / 3.6 | 33 / 10.1 / 13.9 / 14.9 | 75 / 4.2 / 14.2 / 15.9 | 327 / 16.7 / 66.7 / 183 | 40 of 327 |
| 1440 | drag | 1 | 76 / 40.6 / 62.8 / 86 | 76 / 1.8 / 3.3 / 4.8 | 76 / 7.9 / 9.7 / 12.2 | 148 / 4.7 / 9.4 / 12.2 | 301 / 33.3 / 50 / 83 | 29 of 301 |
| 1440 | drag | 2 | 77 / 41.8 / 48.9 / 84.9 | 77 / 1.9 / 3.6 / 6.4 | 77 / 7.5 / 9.8 / 11.3 | 146 / 4.7 / 9.4 / 12.0 | 307 / 16.8 / 50 / 83 | 35 of 307 |
| 1440 | wheel | 1 | 20 / 42.8 / 49.9 / 50.7 | 40 / 1.9 / 3.5 / 3.9 | 20 / 7.1 / 8.8 / 9.4 | 40 / 5.2 / 8.6 / 9.4 | 412 / 16.7 / 33.3 / 167 | 22 of 412 |
| 1440 | wheel | 2 | 20 / 43.6 / 65.3 / 86 | 40 / 1.7 / 4.2 / 5.8 | 20 / 7.2 / 10.4 / 11.4 | 41 / 5.9 / 9.6 / 12.7 | 424 / 16.7 / 33.3 / 167 | 24 of 424 |

Caveats:
- The synthetic phone swipes run slowly under throttle, which inflates the phone-drag rAF figures.
- The per-frame label figure counts paint-only frames as well, which is why its median sits below the layout median.
- Layout fires only when a new frame object is produced.
- The marks add a little overhead.
- The roster is the dev seed: it is denser in chips and thinner in regions than production.

What the numbers say about P4b and P4c:
- **P4b:** `drawTiles` alone costs more than a 60 fps frame on every `move`, at both widths.
- **P4c:** the label cost is dominated by the forced reflow plus placement, not by `paint`. Skipping the pass on `move` removes most of
  it; the `obstacles` copy-once change in `placeLabelPass` would trim only the remainder.

## 11. What the review changed (draft 1 → draft 2)

Every charge below was re-checked against the code by the planner before it was folded in.

- **P1b (blocker):** the served briefing is not a function of the build alone. Ratings are re-read live, and pastness
  and the fallback read the clock. So the TTL is now mandatory, the epoch also covers rating writes and retractions,
  the epoch is read before assembly and bumped after commit, concurrent misses are `synchronized`, the in-memory
  simulation setters are named, and the docs that state "recomputed live on every serve" change in the same PR.
- **P1a:** the simulation branch does load rosters (through the enricher); `HotTopicInputs` takes suppliers, returns
  unmodifiable lists and keeps roster ordering. The "≤ 10 statements" target was unreachable and is replaced by a
  measured delta.
- **P1d:** a cached-only controller path would never refresh, and making `fetchX` stale-while-revalidate would feed
  stale data to the aurora state machine. Now new controller-only methods; `fetchX` unchanged.
- **P1e:** names the real writers, the cascade deletes and after-commit invalidation.
- **P2b (blocker):** SWR for scores dropped; it cannot fit iOS's `localStorage` and would starve the briefing's cache.
- **P2f (blocker):** `/api/user/settings/ask` stays for every role; it is how Lite reaches the Ask upsell.
- **P2c/P2d/P2e:** a token gate and a testable util for preloading; `PostcodeCapture` stays eager; known Ask static
  importers named; the precache mechanism is `workbox.manifestTransforms`.
- **P3a (blocker):** the min-gap is timed with `Date.now()`, not `appNow()` (frozen under Rewind). Identity changes fetch
  at once. Suites that drive races through focus are named.
- **P3b:** API return contracts stay the same (about 35 test files mock them); the raw-text memo lives inside the API
  functions, not in `transformResponse` (which also runs on error bodies).
- **P3c/P3d:** the pill keeps `checkedAt`; `App` keeps an `isDown` selector; the viewline poll's predicate is the union
  of today's two and never runs for Lite.
- **P4b/P4c:** pinch fires `zoom` + `move` per frame and is now handled; the canvas and labels already ride the map
  pane's translate, so the fix is "skip the mid-gesture repaint". No canvas padding. Behaviour changes declared (D7).
- **P4d (blocker):** the bounds also gate phone tide cues and the peek seed; now only derived values go into state.
- **P4e:** memos name the time inputs (`hwExpiryTick`, pastness).
- **P4f (blocker):** `Modal` renders inline, not in a portal; the observer keeps `subtree` and filters in its callback.
- **P5a:** Ask's model comes from `photocast.ask.model`; the system prompt is split into a static part and a
  per-question tail; the rolling tool-result breakpoint is always added; proof comes from production `api_call_log`.
- **P5b:** the gloss key includes the system prompt and a post-processing version; failures are never stored.
- **P5c:** no grid-cell dedupe (there is no marine grid key); a new list exchange; failure isolation defined.
- **P5d/P5e/P5g:** the real call chain and a public snapshot factory; results are streamed, so one `findAll()` before
  the loop; grid-cell prefetch declared as an input change.
- **P6:** retention dropped (owner, 2026-10-28) and replaced by the index audit (§7.1).
