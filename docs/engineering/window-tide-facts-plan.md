# Window tide facts: serving per-location tide separately from slots

Status: **plan, not built.** Owner decision 2026-10-08 (option 3 of three, "build it correctly", with the
adjacent technical debt paid down in the same series). Draft 3: adversarially reviewed (four lenses,
three refutation passes, then a final check); §10 records what the review changed.

## 1. The defect

On 2026-10-08 the Map tab showed no tide strip for Sunday sunrise (2026-10-11), and the map held only
three wildlife hides ("3 of 263 shown · 0 rated"). The tide facts existed: the persisted briefing cache
carried 225 Sunday-sunrise slots, 59 of them with a `tideState`, and every coastal location in
Northumberland & Tyneside and North York Moors & Coast had four stored extremes for that day.

`GET /api/briefing` served **zero** slots for that window. Measured in the owner's browser that morning:

| Window | Slots served | With `tideState` |
|---|---|---|
| Thu 8 sunrise, sunset (travel day) | 0 | 0 |
| Fri 9 sunrise / sunset | 225 / 182 | 59 / 27 |
| Sat 10 sunrise / sunset | 29 / 82 | 16 / 27 |
| Sun 11 and Mon 12, both windows | 0 | 0 |

**Cause.** `BriefingHonestyFilter` (`service/BriefingHonestyFilter.java`) runs on the API read path.
`rewriteRegionByCoverage` sends a region to `fullRewrite`, which replaces its slot list with `List.of()`,
when the region has at least one slot that could carry a rating (`BriefingSlot.couldCarryRating`) and
`scoredLocationCount == 0`. All-canopy and all-gated regions keep their slots, and `unregioned` slots are
never touched. The filter was written as failure-defence (a failed batch), but zero coverage is also the
*designed* state of every window Gate 4 does not score (T+3 except SETTLED, all of T+4) and of every travel
day (its batch is skipped, `BriefingCandidateCollector`). Emptying the slots deletes the per-location tide
facts with them, although those facts come from stored tide tables and have nothing to do with Claude.

Downstream on the Map tab: `buildTideAlignmentIndex` (`utils/locationSheet.js`) finds no slot, so
`MapView.getTideOnLightForLocation` returns null for every coastal location; the rating stage's tide
exemption (`MapView.jsx`, `visibleLocations`) never fires; the 3★ floor removes every coastal location;
`stripModel` finds no coastal spot in view and hides the strip. Pure-wildlife hides bypass the floor, so
only they survive. The window-level tide (`BriefingWindow.tide`) is present (Sunday's representative is
St. Mary's Lighthouse), so the strip has a curve; it lacks a coastal spot.

## 2. The design

**Tide facts are a property of (location, window), computed deterministically from stored tides. They must
not live inside a structure whose visibility depends on Claude coverage.**

### 2.1 What is served

A new nullable component on **`BriefingWindow`** (`model/BriefingWindow.java`), the serve-time,
never-persisted, per-window object that already carries the window's tide rollup (`tide`):

```java
@JsonInclude(JsonInclude.Include.NON_NULL) List<LocationTideFact> tideFacts
```

One entry per location **with a non-null `tideState`** in that window, the same population
`buildTideAlignmentIndex` admits today. A window with no coastal slot carries `null`, omitted on the wire
(never an empty array). One entry per location per window; if two slots name the same location (they
cannot today), the first in region order wins and a test pins it.

`LocationTideFact` (new record, `model/LocationTideFact.java`). **Every field has a named reader**; a field
without one is not carried:

| Field | Type | Readers |
|---|---|---|
| `locationId` | `Long`, NON_NULL | every join (id first) |
| `locationName` | `String` | the name fallback `lookupForWindow` already uses (legacy slots lack an id) |
| `tideState` | `String`, never null here | index `state`, spots, Ask |
| `tideAligned` | `boolean` | index `aligned`, spots, `HeatmapGrid`, `computeCellTier`, `slotSortKey`, Ask |
| `tideAlignmentQuality` | `Double`, NON_NULL | spots (`tideFit.meanQuality`) |
| `tideOnTheLight` | `Boolean`, NON_NULL | index `onTheLight` |
| `nearestSolarOffsetPhrase` | `String`, NON_NULL | index `phrase` |
| `tideLevel`, `tideDirection`, `tideHeight`, `tideShortfall` | as on `TideInfo`, NON_NULL | index `level`/`direction`/`height`/`shortfall` |
| `tideFitPhrase` | `String`, NON_NULL | index `fitPhrase`, Ask |

Not carried, deliberately: the size and lunar fields (`heightAboveP95`, `heightAboveSpringThreshold`,
`lunarTideType`, `lunarPhase`, `moonAtPerigee`, `nearestHighTide*`; their only API-path reader is the
callerless `CloseToHomeService`, which reads the unstripped `getServedBriefing`, see §3.3),
`nearestSolarOffsetMinutes` and `nearestExtremeKind` (no API reader), and the evaluation outputs
`skyRating`, `claudeRating` and `evaluationGate` (the honesty filter is right to withhold those on an
unscored window).

The **only** construction path is a static factory `LocationTideFact.from(BriefingSlot slot)` (returns null
for a slot whose `tide()` is null or whose `tideState` is null). A reflection test asserts every
`LocationTideFact` component other than the two identity fields maps to a same-named `TideInfo` accessor, so
the two records cannot drift.

### 2.2 Where it is computed

A new package-private `WindowTideFactProjector` in `service/`, a static utility like `PlanWindowProjector`:

```java
static Map<PlanWindowProjector.WindowKey, List<LocationTideFact>> project(List<BriefingDay> days)
```

It walks each summary's regioned and unregioned slots (in that order) and builds the map with
`LocationTideFact.from`. It reads no repository and no clock, and it never mutates its input.

`ServedBriefingAssembler.assembleForPlan` calls it on the **raw cached `snapshot.days()`**, the only point
where slots are still unfiltered (`assembleWithoutPlan` empties them inside, and returns only the filtered
result). Slot tide is never touched by re-enrichment or the fallback, so the snapshot's tide is the served
tide. The pipeline becomes:

```
snapshot ─► WindowTideFactProjector.project(snapshot.days())  ──────────────┐ tideFacts map
        └─► assembleWithoutPlan (re-enrich, fallback, honesty filter)       │
             └─► attachMovement ─► PlanWindowProjector.apply(filtered, now, tides, tideFacts)
```

`PlanWindowProjector.apply` gains the `tideFacts` argument beside `tides`; `Draft.toWindow` sets it. The
projector stays pure (the map is an argument, exactly as `tides` is), and `apply` already attaches a window
to **every** summary of every day: blanked, travel-day, past and unrendered windows included
(`PlanWindowProjector` pass 1 drafts every summary; only picks and the day peak are scoped to rendered
events). `PlanWindowProjector.WindowKey` is the key, as its javadoc requires of any second producer of
per-window data.

`assembleWithoutPlan` / `getServedBriefing` (Close-to-home only) gets no facts and needs none.

**Why serve time, from slots.** `BriefingSlotBuilder.calculateTideData` stays the only writer of tide facts;
the projector is a reshape, so the two cannot disagree. Windows are never persisted, so there is no
cache-shape change and no legacy window. **Accepted ceiling:** facts exist only where a slot exists, that is
enabled colour locations whose weather fetch succeeded at the last build, inside the briefing's five dates.
A coastal location with no slot gets no fact and degrades exactly as an inland one does (tested).

### 2.3 Contract

- JSON: `window.tideFacts`, an array of flat objects; NON_NULL omission on the window and on optional entry
  fields. Not personal data, so it may ride the ETag'd `GET /api/briefing` (CLAUDE.md "Panel data
  contracts"). `GET /api/briefing/digest` does not expose it (it copies scalars off the window).
- **Wire test (Jackson 3):** a full-context test extending `AbstractControllerTest` (no Docker; H2 test
  profile), modelled on `JsonDateFormatContractTest.getBriefing_pinsEclipseSightDateFormat`. It stubs
  `BriefingService.getCachedBriefingForApi` and asserts via `jsonPath` the field names, NON_NULL omission,
  and that a window with no facts has no `tideFacts` key. `DailyBriefingResponseJsonTest` is Jackson 2 (the
  cache shape) and does not prove the wire.
- **Stale clients:** a payload from before deploy has no `tideFacts`. The client treats absence as "no tide
  facts" (null-safe) and draws as it does for an unscored window until the first revalidation replaces
  state and cache. **No slot fallback** (it would be two sources and would hide a missing-facts bug in
  tests) and no SWR key bump: the ETag is body-derived (`HttpCachingConfig`), so a pre-deploy ETag can never
  revalidate into a pre-deploy body. The stale window is the first paint after deploy, up to the SWR cache's
  12 hours only if the fetch fails.

## 3. Readers, and the single source

The series ends with **one source of per-location tide on the wire**: `window.tideFacts`. API slots stop
carrying tide (P5). Every API-path reader therefore moves first.

### 3.1 Frontend (all read `briefing.days` from `GET /api/briefing`)

| Reader | Today | After | Phase |
|---|---|---|---|
| `utils/locationSheet.js` `buildTideAlignmentIndex` | walks slots | walks `summary.window?.tideFacts`; entry shape unchanged | P2 |
| …its `skyRating` and `gated` entry fields | read off the slot | joined from that window's slot by `locationId`, then name; `skyRating` null and `gated` false when no slot exists | P2 |
| Map tab (`MapView` chip and rating-stage exemption, `stripModel`, `mapDrilldown`, `MapCallout`/`TideFitBlock`, `nextAlignedRow`), `LocationFourDaySheet` (both hosts), `askModel` pick cards | through the index | unchanged code | P2 |
| `utils/windowFirstSpots.js` `buildWindowSpots` | copies `tideState`, `tideAligned` and `tideAlignmentQuality` (renamed `tideQuality` on the spot) off each slot | copies them from the window's facts by location, same names on the spot; the spot population still comes from slots (the one-population rule in the comment at `windowFirstSpots.js:242–257` is kept) | P3 |
| `windowFirstCards` `tideFit`/`bestReach`, `windowFirstTideRun`, `WindowFirstHeatStrip`, `WindowSheetDialog` | spot fields | unchanged code | P3 |
| `HeatmapGrid.jsx` `alignedCount` (:505), `computeCellTier` (`tierUtils.js:47`, called at `HeatmapGrid.jsx:842/846`), `slotSortKey` / `sortedSlotsByTidePriority` (`briefingDisplay.js:60–62`, used at `HeatmapGrid.jsx:145`) | `slot.tideAligned` | `HeatmapGrid` already receives `briefingDays`; it builds `buildTideAlignmentIndex(briefingDays)` once with `useMemo` and passes an `alignedOf(slot, date, targetType) => boolean` predicate down through `HeatmapCell` (:474), `HeatmapDrillDown` (:294) and `LocationSlotList` (:113). New signatures: `computeCellTier(region, alignedOf)`, `slotSortKey(slot, aligned)`, `sortedSlotsByTidePriority(slots, alignedOf)`. `alignedOf` is required, with no `slot.tideAligned` default. (`getSubCellData` is module-local in `HeatmapGrid.jsx:44`.) | P3 |

`buildEvaluationGateIndex`, `buildSlotIndex` and `buildEclipseIndex` stay on slots (evaluation, timing and
eclipse facts).

### 3.2 Backend API-path readers

| Reader | After | Phase |
|---|---|---|
| `AskSnapshotBuilder.toSlot` (feeds `AskTools`, `ReadyQuestion`, `StubAskEngine`) | joins `tideState`, `tideAligned`, `tideFitPhrase` from the summary's `window.tideFacts`. Must land before P5: `toSlot` is null-safe, so a strip without it silently makes every Ask slot non-coastal | P4 |
| `BriefingDigestService` | reads `window` scalars only; unaffected | — |

### 3.3 Not moved

Readers of the raw or cached briefing, unaffected by the filter and the strip: `KingTideHotTopicStrategy`,
`SpringTideHotTopicStrategy`, `CoastalTideFactsBuilder`, `BriefingRollupBuilder`, `BriefingGlossService`,
`BriefingVerdictEvaluator`, `ForceEvalHeadlineSelector`, `BriefingModelTestService`, `ForecastTaskCollector`.
`CloseToHomeService.tideLabel` reads the size fields from `getServedBriefing`, which is not stripped: a
deliberate, documented asymmetry while the endpoint has no caller.

## 4. Behaviour this changes, deliberately

1. **Map tab, any window the filter blanks** (unscored T+3/T+4, travel days, a failed batch): coastal
   locations with a tide fact pass the rating stage, the strip finds them and shows, chips carry their tide
   tier, and the callout and four-day sheet show their tide block. On such a window the map is a coast-only,
   unrated set (tide cues, no stars), strictly better than today's hides-only map; the footer still reads
   "N of M shown · 0 rated".
2. **Honest heading on an unassessed miss.** "Wrong water, not wrong light" is shown only when the window was
   assessed for that location; otherwise a miss reads "Tide misses the light here". A match is unchanged.
   Where it lives: `TideFitBlock.jsx` (inline heading at :127; assessed = `fact.skyRating != null ||
   combinedRating != null`, both already available) and `mapTideFit.tideTierHeading(tier, assessed)`, called
   from `MapLabels.jsx:857` and `PinsLayer.jsx:540` with a new `tideAssessed` boolean on the `MapView` spot
   (`MapView.jsx` ~3829–3855, set from `fact.skyRating != null || <the spot's rating> != null`).
   `tideAccessibleClause` is **not** changed: its miss text ("wrong water", "wants the water higher") makes no
   claim about the light. A recorded deviation from `tide-window-plan.md` §4 #8's verbatim headings; it also
   makes a weather-triaged slot with no sky rating honest.
3. **Plan card "N on tide" chip on a blanked window:** still absent (no slots, so no spots). **Q1.**
4. **Phone, Auto tide mode:** still no tide cues on a window with no served verdict tier (`mapPeek.tideVisible`
   requires WORTH_IT or MAYBE). Accepted, owner decision 2026-10-08. Always mode gains the cues.
5. **Payload.** About 400–450 bytes an entry; about 86 entries a day (59 per sunrise, 27 per sunset
   measured), so ~150–270 KB raw over five days and ~20–35 KB gzipped, on a payload the SWR comment puts at
   ~1.3 MB. P5 removes the slot copy, which is larger, so the series ends smaller than today. The SWR cost
   (UTF-16 in `localStorage`, ~5 MB iOS ceiling) matters more than the wire.

## 5. Technical debt paid down in this series

| Item | Phase |
|---|---|
| **Record-copy guard.** One reflection test over `DailyBriefingResponse`, `BriefingRegion`, `BriefingEventSummary` and `BriefingWindow`: fill every component with a sentinel, call each `with*`, assert every other component survives. Convert the three positional rebuilds of `DailyBriefingResponse` that silently drop `renderedEvents`/`previousGeneratedAt`/`bestBetsWithdrawn` (`BriefingHonestyFilter`, `ServedBriefingAssembler.applyBestBetFallback`, `BriefingService.getCachedBriefing`) to withers. | P0 |
| **`LocationTideFact.from` + drift test** (§2.1). | P1 |
| **Stale comments:** `BriefingHonestyFilter` class javadoc ("policy-driven zero-coverage cases are rare" is false for T+4 and travel days; name them), `BriefingHonestyFilter` `rewriteRegionByCoverage` and `locationSheet.js` "today the tide gate" (the tide gate was lifted 2026-09-18). | P1, P2 |
| **One window walk and one key per format on the client.** In `utils/locationSheet.js`: `windowsOf(days)` yields `{date, summary, tail}`; `indexByWindow(days, entriesOf)` calls `entriesOf({date, summary})`, which returns an iterable of `{locationId, locationName, value}`, and builds `{byId, byName}` (first entry wins, keys `${id}|${tail}` and `${name}|${tail}` exactly as `index()` does today). `buildSlotIndex`, `buildEvaluationGateIndex`, `buildTideAlignmentIndex` and `buildEclipseIndex` each become an `entriesOf` over it (the tide builder's `entriesOf` builds its slot-join map once per summary). **Two key formats exist and are NOT merged:** `mapEvents.solarWindowKey` and `heatSpots.windowKey` (both `date:targetType`, identical) collapse into one exported function; `locationSheet.tailOf` and `solarEventTimes.keyFor` (both `date\|targetType`) collapse into one exported `windowTail` (the `\|` form is baked into index keys and built by hand in `MapViewStaleWindow.test.jsx` and `mapTideFit.test.js`). The remaining inline `date:targetType` literals elsewhere are a later sweep. | P2 |
| **API slots stop duplicating tide** (P5). | P5 |

Separate PRs, not in this series (recorded so they are not lost): the duplicated `tide_extreme` queries on
the build path (`BriefingSlotBuilder.curveFacts` re-reads what `TideFactDeriver` just read, several times per
coastal slot); a coverage-reason model for the honesty filter so "not attempted" and "failed" stop sharing
one rewrite and its "Too unsettled to forecast" wording (**Q2**); removing the always-null `evaluationGate`
chain (owner decision: it is a deliberately kept seam); deleting the callerless Close-to-home stack (owner
decision).

Rejected, recorded so it is not re-proposed: **keeping slots in `fullRewrite` with the evaluation fields
stripped** (option 1 of three). Plan pools, spread denominators and `bestReach` all treat slots as the scored
population, so that change would alter every Plan surface.

## 6. Phases

One PR per phase, each mergeable alone, built by a Sonnet subagent, under CLAUDE.md's review cadence (build,
tests, adversarial review with read-only reviewers, fix, re-verify, commit). Backend and frontend deploy
together from one repo; P1 alone changes nothing a reader can see. Each PR adds a `changelog.d/` entry.

**Local gates, gated on the exit code:**
- backend: `cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false` and `./mvnw checkstyle:check` (no JDK 25 on this Mac: run on JDK 23 with `-Djava.version=23`; CI proves JDK 25);
- frontend: `npm run lint && npm test && npm audit --audit-level=high && npm run build`.
- JaCoCo is 80% per class (cover the new record and projector with real assertions), and every new public
  type, method and constructor needs Javadoc.

### P0 — record-copy hygiene (backend, no behaviour change)

- Add the withers the three rebuilds need, which do not exist today (only `withDays`, `withMovement` and
  `withPlan` do): `withBestBets(bestBets, bestBetsWithdrawn)` for the honesty filter and the best-bet
  fallback, and `withLiveOverlays(auroraTonight, auroraTomorrow, hotTopics)` for `getCachedBriefing`. Convert
  the three sites. At all three, `renderedEvents` and `previousGeneratedAt` are null today, so there is no
  behaviour change.
- The guard enumerates each record's `with*` methods by reflection and builds sentinel instances from a
  per-type factory map (canonical constructors normalise some components, e.g. `List.copyOf`).
  `BriefingWindow` has no withers, so the guard covers it through its constructors only.
- Mutants: drop one component in each `with*`.

### P1 — serve `window.tideFacts` (backend, additive)

- `LocationTideFact` with `from` and the drift test; `BriefingWindow.tideFacts` (keep a constructor overload so
  the ~15 existing `new BriefingWindow(` sites compile unchanged); `WindowTideFactProjector`; the
  `assembleForPlan` and `PlanWindowProjector.apply` wiring (§2.2). `assembleForPlan` returns early on a null
  snapshot as today, so the projector is only ever called on `snapshot.days()`. `apply` gains the parameter
  with no overload; its one main caller (`ServedBriefingAssembler`) and every `PlanWindowProjectorTest` call
  (grep `PlanWindowProjector.apply(`; about nine) pass the map (an empty map in tests that do not care).
- Tests:
  - `WindowTideFactProjectorTest`: regioned and unregioned slots; inland, `tideState`-null and canopy
    (`TideInfo.NONE`) slots excluded; fields copied verbatim (fixture with a deliberately inconsistent
    `tideAligned`, to catch re-derivation); a legacy slot with no `locationId`; duplicate location; input not
    mutated; a summary with no coastal slot maps to no key.
  - **New `ServedBriefingAssemblerTest`** (it has none today): the real `assembleForPlan` with the real
    `BriefingHonestyFilter`, a stubbed score enricher and tide-rollup builder. Fixture: a region with coastal
    slots and zero Claude coverage. Assert, on the same summary: `regions[0].slots()` is empty after the
    filter, `window` is non-null, `window.tideFacts` has the expected entries verbatim. Assert the cached
    snapshot is unchanged afterwards.
  - The Jackson 3 wire test (§2.3) and a Jackson 2 round-trip asserting the cache JSON is byte-identical
    (windows are not persisted).
  - `PlanWindowProjectorTest`: facts reach every window, including a past and an unrendered one.
- Mutants: build the map from `filtered.days()` instead of `snapshot.days()`; skip unregioned slots; drop
  `tideFacts` in `toWindow`; include a `tideState`-null slot; emit `[]` instead of null; swap the id and name.
- **Acceptance**, measured on a production-shaped synthetic briefing (about 225 slots a sunrise, 182 a
  sunset, 59/27 coastal, five days) through the real Jackson 3 mapper and recorded in the PR: raw growth at
  most 300 KB and 25%; gzip growth at most 40 KB; the projector pass under 20 ms. If exceeded, drop
  `tideOnTheLight` and `nearestSolarOffsetPhrase` before shipping.
- Docs: CLAUDE.md, a sentence in the Map tab (v2) bullet naming `window.tideFacts` as the per-location tide
  source on the API path and why it sits outside slots.

### P2 — the tide index reads `window.tideFacts`; client walk/key cleanup; honest heading

- `buildTideAlignmentIndex` over `windowsOf` / `indexByWindow`, reading `summary.window?.tideFacts`;
  `skyRating`/`gated` joined from the window's slot (§3.1). The other three index builders and the key helpers
  consolidated (§5).
- The honest heading (§4.2) in `TideFitBlock` and `tideTierHeading(tier, assessed)` with the spot's
  `tideAssessed`; update the assertions in `TideFitBlock.test.jsx`, `MapCallout.test.jsx` and
  `LocationFourDaySheet.test.jsx` only where their fixtures are unassessed, and add an unassessed-miss case for
  the block and for the `MapLabels` and `PinsLayer` tooltips.
- **The defect end to end:** extend `MapViewFillerTideStrip.test.jsx` (real `MapView`, real `MapTideStrip`)
  with the production shape: a rendered window with `row.tide` present, `window.tideFacts` present, and
  every region's `slots` empty. Assert a coastal location passes the 3★ floor through its fact, the counts
  footer reads "1 of N shown · 0 rated", its chip carries a tier, the strip shows with a dimmed count, and a
  location in `locations` with no fact gets no tide.
- `buildTideAlignmentIndex` on a payload with no `tideFacts` returns an empty index and does not throw.
- Mutants: read slots instead of facts; drop the name fallback; drop the `skyRating` join; heading ignores
  `assessed`.
- **Browser check** (owner signs in; a session cannot): a local fixture reproducing "facts present, slots
  empty" on the Map tab. State what was seen versus what was tested.

### P3 — the Plan pool and grid read facts

- `buildWindowSpots` copies tide from the window's facts (population unchanged). `HeatmapGrid` takes the tide
  index as a prop from `WindowFirstRegionalPanel`; `alignedCount`, `computeCellTier` and `slotSortKey` /
  `sortedSlotsByTidePriority` take a tide lookup instead of reading `slot.tideAligned`.
- Update `test/askFixtures.js` and the other slot-tide fixtures to carry facts.
- No visible change on scored windows, pinned by the existing suites (§7).
- Mutants: spot copies from the slot; `alignedCount` reads the slot; a missing fact counted as aligned.

### P4 — Ask reads facts

- `AskSnapshotBuilder.toSlot` joins tide from `window.tideFacts`. Today it is a `private static` method used
  as `AskSnapshotBuilder::toSlot` (:203) and cannot see the window: `toRegion` and `toSlot` take the window's
  facts, through a `locationId`-then-name lookup map built once per window. `AskSnapshotBuilderTest`, `AskToolsTest`,
  `ReadyQuestionTest` pass unchanged; add a case where the slot's `tide` is null and the facts supply it, and
  one where both exist and agree.

### P5 — API slots stop carrying tide

- At the tail of `assembleForPlan`, after `PlanWindowProjector.apply`, set each slot's `tide` to **null**
  (never an empty `TideInfo`: its primitive booleans would serialise `tideAligned:false` on every slot). The
  persisted cache and every raw/cached reader are untouched; `getServedBriefing` is not stripped.
- Mechanics: add `BriefingSlot.withTide(TideInfo)`, and a private `stripSlotTide(response)` in
  `ServedBriefingAssembler` that rebuilds through `BriefingRegion.withSlots`,
  `BriefingEventSummary.withRegions`/`withUnregioned`, `BriefingDay.withEventSummaries` and
  `DailyBriefingResponse.withDays`, preserving each day's `peak`, every window and `renderedEvents`.
- Every `.tide()` dereference reachable after the strip on the API path is null-safe today
  (`AskSnapshotBuilder.toSlot`); the build-path and raw-cache readers never see a stripped slot. A test pins
  that `toSlot` copes with a null tide.
- Contract tests: a stripped slot carries none of the unwrapped tide keys (Jackson 3, `@JsonUnwrapped` with a
  null value), `window.tideFacts` still present; the payload-size delta recorded.
- Before merging: confirm no other API client reads slot tide (the frontend is the only one today; check for
  a native widget client of `/api/briefing`).

## 7. Tests that must keep passing unchanged

`BriefingHonestyFilterTest`, `TideSurfaceAgreementTest`, `BriefingSlotBuilderTest`,
`KingTideHotTopicStrategyTest`, `SpringTideHotTopicStrategyTest`, `CoastalTideFactsBuilderTest`,
`WindowTideRollupBuilderTest`, `HttpCachingConfigTest`, `CloseToHomeServiceTest`,
`BriefingDigestServiceTest`, and the frontend tide suites (`mapTideFit`, `MapTideStrip`, `MapPeekTideSection`,
`MapCallout`, `LocationFourDaySheet`, `WindowFirstHeatStrip`, `windowFirstCards`, `windowFirstSpots`,
`windowFirstTideRun`, `HeatmapGrid`, `askModel`, `askPlan`, `MapViewFillerTideStrip`). Fixture-only edits
are allowed where a fixture builds slot tide that the reader no longer reads; an assertion changes only where
§4 says the behaviour changes. `tierUtils.test.js` and `briefingDisplay.test.js` get **call-site edits** for
P3's new signatures (passing `alignedOf` / `aligned`) and no assertion changes.

## 8. Open questions for the owner

- **Q1.** On a blanked window, should the Plan card's "N on tide" chip count coastal locations within reach
  that have a fact but no slot? Default: no.
- **Q2.** A coverage-reason model so the honesty filter stops calling a T+4 or travel-day window "Too
  unsettled to forecast"? Default: a separate PR.

## 9. Not verifiable locally

No Docker (integration tests run in CI only), no JDK 25, no production data. The payload figures are
estimates until measured on the synthetic briefing (P1) and on production after deploy. The browser checks
need the owner to sign in.

## 10. What the review changed (draft 1 → draft 2)

- Facts moved from `BriefingEventSummary` to `BriefingWindow` (no persisted shape, no 190-site constructor
  change, no rebuild trap; the existing `tides` pattern).
- The map is built from the raw snapshot in `assembleForPlan`, not inside `assembleWithoutPlan`.
- Field list cut to fields with a reader; `LocationTideFact.from` plus a drift test.
- P5 became a planned phase (serve-time null strip, not a Jackson mix-in), which makes P3 and P4 necessary;
  `computeCellTier` is live (not dead) and moves in P3.
- Wire contract pinned with a Jackson 3 full-context test; Jackson 2 test kept for the cache.
- No client slot fallback, with the reason.
- Added: the honest heading, the phone-Auto gap, the coast-only note, payload acceptance thresholds, a focused
  `ServedBriefingAssemblerTest`, P0 record hygiene, the client walk/key consolidation, and corrected trigger
  wording for the filter.

**Draft 3 (final check):** the two client key formats (`date:targetType` and `date|targetType`) are kept
apart, not merged; P3's grid plumbing is in `HeatmapGrid` itself, with explicit new signatures; the honest
heading's call sites and the spot's `tideAssessed` are named, and `tideAccessibleClause` is left alone; P0's
missing withers and the guard's sentinel factory are specified; P4's `toSlot` signature and P5's strip
traversal and `withTide` are spelled out.
