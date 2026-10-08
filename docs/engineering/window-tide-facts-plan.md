# Window tide facts: serving per-location tide separately from slots

Status: **plan, not built.** Owner decision 2026-10-08 (option 3 of three offered). Draft 1.

## 1. The defect

On 2026-10-08 the Map tab showed no tide strip for Sunday sunrise (2026-10-11), and the map held only
three wildlife hides ("3 of 263 shown · 0 rated"). The tide facts existed: the persisted briefing
cache carried 225 Sunday-sunrise slots, 59 of them with a `tideState`, and every coastal location in
Northumberland & Tyneside and North York Moors & Coast had four stored extremes for that day.

`GET /api/briefing` served **zero** slots for that window. Measured in the browser the same morning:

| Window | Slots served | With `tideState` |
|---|---|---|
| Thu 8 sunrise, sunset (travel day) | 0 | 0 |
| Fri 9 sunrise | 225 | 59 |
| Fri 9 sunset | 182 | 27 |
| Sat 10 sunrise | 29 | 16 |
| Sat 10 sunset | 82 | 27 |
| Sun 11 and Mon 12, both windows | 0 | 0 |

**Cause.** `BriefingHonestyFilter.fullRewrite` (`service/BriefingHonestyFilter.java`) runs on the API
read path and, for any region with zero Claude coverage on a window, replaces the region's slot list
with `List.of()`. The filter was written as failure-defence (a failed batch), but zero coverage is
also the *designed* state of every window Gate 4 does not score (T+3 except SETTLED, all of T+4, and
every travel day, whose batch is skipped). Emptying the slots deletes the per-location tide facts with
them, although those facts come from stored tide tables and have nothing to do with Claude.

Downstream on the Map tab: `buildTideAlignmentIndex` finds no slot, so `getTideOnLightForLocation`
returns null for every coastal location; the rating stage's tide exemption (`MapView.jsx`,
`visibleLocations`) never fires; the 3★ floor removes every coastal location; `stripModel` finds no
coastal spot in view and hides the strip. Only pure-wildlife hides survive, because they bypass the
floor.

The same blindness affects every per-location tide reader on the API path (§3).

## 2. The design

**Tide facts are a property of (location, window), computed deterministically from stored tides. They
must not live inside a structure whose visibility depends on Claude coverage.**

### 2.1 What is served

A new nullable component on `BriefingEventSummary`:

```java
@JsonInclude(JsonInclude.Include.NON_NULL) List<LocationTideFact> tideFacts
```

One entry per location **with a served `tideState`** in that window (the same population
`buildTideAlignmentIndex` admits today; an inland or no-extremes location has no entry, which the
client already reads as "not a coastal slot with a served tide state").

`LocationTideFact` (new record, `model/LocationTideFact.java`) carries the identity join keys and the
tide fields the API readers use, **copied verbatim** from the slot's `TideInfo`, never re-derived:

| Field | From | Note |
|---|---|---|
| `locationId` | slot | NON_NULL; legacy slots may lack it |
| `locationName` | slot | the name-fallback join key `lookupForWindow` already uses |
| `tideState` | `TideInfo` | never null in this list by construction |
| `tideAligned` | `TideInfo` | preference axis |
| `tideAlignmentQuality` | `TideInfo` | NON_NULL |
| `tideOnTheLight` | `TideInfo` | NON_NULL; type-blind axis, kept separate (two-tide-axes rule) |
| `nearestSolarOffsetMinutes`, `nearestExtremeKind`, `nearestSolarOffsetPhrase` | `TideInfo` | NON_NULL |
| `tideLevel`, `tideDirection`, `tideHeight`, `tideShortfall`, `tideFitPhrase` | `TideInfo` | NON_NULL |

Deliberately **not** carried: the size and lunar fields (`heightAboveP95`, `heightAboveSpringThreshold`,
`lunarTideType`, `lunarPhase`, `moonAtPerigee`, `nearestHighTide*`), because no API-path per-location
reader uses them (hot topics read the raw cache; see §3). Add one only with a reader. Also not
carried: `skyRating`, `claudeRating`, `evaluationGate`. Those are evaluation outputs, not tide facts,
and the honesty filter is right to withhold them on an unscored window.

### 2.2 Where it is computed: serve time, from the persisted slot facts, before any filter

A new package-private `WindowTideFactProjector` in `service/`, applied as the **first** step of
`ServedBriefingAssembler.assembleWithoutPlan`, before `reEnrichVerdicts`, the best-bet fallback and the
honesty filter:

```
honesty(fallback(reEnrich(attachTideFacts(snapshot))))
```

It walks each event summary's regioned and unregioned slots and builds the list from each slot's
`TideInfo`. It reads no repository and no clock.

Why serve time, not build time:

- **One source of truth stays one.** `BriefingSlotBuilder.calculateTideData` remains the only writer of
  tide facts. The projector is a reshape, so the two can never disagree.
- **No persisted-shape change, no legacy window.** A cache built before deploy serves facts on the
  first request; nothing waits for the next briefing build.
- **No carrier rule applies.** CLAUDE.md's "build-time enrichment must ride a carrier" rule is about
  hot topics recomputed live; tide facts are already persisted in the slots.
- **Cost:** one pass over about 2,000 slots per serve, no I/O, the same order as the existing
  `reEnrichVerdicts` walk. Measured in a test if it is to be claimed.

Build-time readers (best-bet rollup, gloss, verdict evaluator, hot-topic strategies, force-eval
selector) keep reading `slot.tide()` from the raw or cached briefing and are untouched.

### 2.3 Surviving the rebuilds

Every pass that rebuilds a summary must carry `tideFacts` through:

- `BriefingEventSummary`: add the component to the canonical constructor (with an immutable copy that
  tolerates null), keep the 3-arg convenience constructor defaulting it to null, and make
  `withRegions`, `withUnregioned` and `withWindow` copy it. Add `withTideFacts`.
- Audit every `new BriefingEventSummary(` call site in `src/main` (hierarchy builder, honesty filter,
  simulated eclipse overlay, projector, Ask fixtures) and confirm none drops it. A test pins the
  honesty filter's `rewriteEvent` path specifically, because that is the pass the facts exist to
  survive.

### 2.4 Contract

- JSON name `tideFacts`, an array of flat objects, NON_NULL omission on the summary and on optional
  entry fields. Pinned in `DailyBriefingResponseJsonTest` next to the existing flat-slot test.
- Not personal data: identical for every reader, so it may ride the ETag'd `GET /api/briefing`
  (CLAUDE.md "Panel data contracts"). It changes the body hash once at deploy, which is expected.
- `GET /api/briefing/digest` is unaffected (it reads `window` only).

## 3. Readers to move, and what each gains

Every per-location tide reader on the API path moves to `tideFacts`. Readers of the raw or cached
briefing do not move.

### 3.1 Frontend (all read `briefing.days` from `GET /api/briefing`)

| Reader | Today | After |
|---|---|---|
| `utils/locationSheet.js` `buildTideAlignmentIndex` | walks slots; skips null `tideState` | walks `summary.tideFacts`; same entry shape |
| `skyRating` and `gated` on that index's entries | read off the same slot | joined from the slot when one exists, else null (they are evaluation outputs) |
| Map tab: `MapView` chip, rating-stage exemption, `stripModel`, drilldown rows, `MapCallout`/`TideFitBlock`, `nextAlignedRow` | via the index | unchanged code; fed by the new index |
| `LocationFourDaySheet` (both hosts) | via the index (`sheetTideAlignmentIndex`) | unchanged code |
| `utils/askModel.js` pick cards | via the index | unchanged code |
| `utils/windowFirstSpots.js` `buildWindowSpots` | copies `tideState`, `tideAligned`, `tideAlignmentQuality` off each slot | joins them from a facts index by location; spots still come from slots |
| `utils/windowFirstCards.js` `tideFit`, `bestReach.tideAligned`; `windowFirstTideRun.js`; `WindowFirstHeatStrip`; `WindowSheetDialog` | read the spot fields above | unchanged code |
| `HeatmapGrid.jsx:505`, `tierUtils.js:47`, `briefingDisplay.js:60` | read `slot.tideAligned` | read through a shared facts lookup |

`buildEvaluationGateIndex` and `buildSlotIndex` stay on slots: they are evaluation and timing facts.

### 3.2 Backend API-path readers

| Reader | Path | After |
|---|---|---|
| `AskSnapshotBuilder.toSlot` (feeds `AskTools`, `ReadyQuestion`, `StubAskEngine`) | API-filtered | joins `tideState`, `tideAligned`, `tideFitPhrase` from `tideFacts` |
| `CloseToHomeService.tideLabel` | served (filtered) | reads `heightAboveP95`/`heightAboveSpringThreshold`, which §2.1 deliberately excludes. **Left on slots**; the endpoint has no caller (CLAUDE.md, v1 retirement §8.3). Recorded, not moved. |

### 3.3 Not moved (read the raw or cached briefing, unaffected by the filter)

`KingTideHotTopicStrategy`, `SpringTideHotTopicStrategy`, `CoastalTideFactsBuilder`,
`BriefingRollupBuilder`, `BriefingGlossService`, `BriefingVerdictEvaluator`,
`ForceEvalHeadlineSelector`, `BriefingModelTestService`.

## 4. Behaviour this changes, deliberately

1. **Map tab, any window the filter blanks** (unscored T+3/T+4, travel days, a failed batch): coastal
   locations with a tide fact pass the rating stage again, the strip finds them and shows, chips carry
   their tide glyph and tier, and the callout and four-day sheet show their tide block. This is the
   owner's defect.
2. **Plan card tide chip on a blanked window**: still absent. The card's pool comes from slots, and a
   blanked region contributes none, so `tideFit` has no coastal spots to count. Making the chip count
   coastal locations with no slot is a separate product decision (the chip's population is the
   reach-gated *spot* pool by the licensed-derivation rule, `tide-plan-card-plan.md`). **Open
   question Q1.**
3. **Ask**: `rank_spots` with a tide filter and the pick cards read facts that survive the filter. Picks
   still need a rating, so a blanked window still yields no picks; only tide wording on otherwise
   eligible picks is affected (none today on blanked windows). No prompt change; the tool output keeps
   its field names. Prompt-regression fixtures are not touched.
4. **Payload**: about 60 entries per window of about 12 short fields, roughly 10 windows, so on the
   order of 60–90 KB uncompressed on a payload the SWR comment puts at about 1.3 MB. Measured in P1,
   with the number recorded in the PR.

## 5. Out of scope, recorded

- **The honesty filter's wording on by-design-unscored windows.** It labels a T+4 or travel-day window
  "Too unsettled to forecast", which is false: nothing was attempted. A separate defect; owner
  decision Q2.
- **Removing tide fields from API slots** (the duplicate copy). Correct in the long run, but the API
  (Jackson 3) and the cache (Jackson 2) share these records and the shared annotation package, so a
  naive `@JsonIgnore` would also stop persisting the facts the projector reads. Needs its own design
  (a Jackson 3 mix-in, or a DTO). Owner decision Q3, after P2–P4 have moved every reader.
- **Unscored-window empty states on the Plan tab** beyond the tide chip.

## 6. Phases

Each phase is one PR, reviewed under CLAUDE.md's UI cadence where it touches the UI, and mergeable on
its own. Order matters: the backend must serve before any client reads.

### P1 — serve `tideFacts` (backend only, additive)

- `LocationTideFact` record, `BriefingEventSummary.tideFacts` with constructors and withers (§2.3).
- `WindowTideFactProjector` and its wiring first in `assembleWithoutPlan`.
- Tests:
  - projector unit tests: regioned + unregioned slots; inland and no-`tideState` slots excluded;
    canopy (woodland, `TideInfo.NONE`) excluded; every field copied verbatim; a legacy slot with no
    `locationId`;
  - **the defect, end to end:** a briefing whose region has zero Claude coverage on a window still
    serves that window's `tideFacts` after `BriefingHonestyFilter` (through `BriefingServiceTest` or an
    assembler-level test, since `ServedBriefingAssembler` has none of its own);
  - every `BriefingEventSummary` rebuild path keeps `tideFacts` (`withRegions`, `withUnregioned`,
    `withWindow`, the filter's `rewriteEvent`, the eclipse overlay);
  - JSON contract in `DailyBriefingResponseJsonTest`: the name, the flat entry shape, NON_NULL
    omission, and a legacy payload with no `tideFacts` deserialising to null;
  - a payload-size measurement recorded in the PR.
- Mutation check: drop the projector call; place it after the filter; skip unregioned slots; drop
  `tideFacts` in `withRegions`.
- Docs: a CLAUDE.md sentence under the Map tab and Plan bullets naming `tideFacts` as the per-location
  tide source on the API path, and why it sits outside slots.

### P2 — the frontend tide index reads `tideFacts`

- `buildTideAlignmentIndex` walks `summary.tideFacts`; `skyRating` and `gated` joined from the slot
  when present.
- Update `locationSheet.test.js`'s index tests, and keep the entry shape identical so every consumer is
  unchanged.
- **The defect, end to end in the client:** extend `MapViewFillerTideStrip.test.jsx` (or a sibling)
  with a briefing whose window has `tideFacts` but **empty `regions[].slots`**, exactly the production
  shape. Assert a coastal location passes the 3★ floor through its tide fact, appears on the map,
  carries its chip tier, and the strip shows with a dimmed count.
- Mutation check: read slots instead of facts; drop the name fallback; drop the `skyRating` join.

### P3 — the Plan pool and grid read facts

- `buildWindowSpots`, `HeatmapGrid`, `tierUtils`, `briefingDisplay` read tide through a facts lookup.
  Unchanged visible behaviour on scored windows; pinned by the existing suites.
- Depends on Q1 only if the owner chooses to widen the chip's population.

### P4 — Ask reads facts

- `AskSnapshotBuilder.toSlot` joins tide from `tideFacts`. `AskSnapshotBuilderTest`, `AskToolsTest` and
  `ReadyQuestionTest` must pass unchanged; add one test where the slot's own tide fields are absent and
  the facts supply them.

### P5 (decision Q3) — stop duplicating tide on API slots

Only after P2–P4. Not planned in detail here.

## 7. Tests that must keep passing unchanged

`BriefingHonestyFilterTest`, `TideSurfaceAgreementTest`, `BriefingSlotBuilderTest`,
`KingTideHotTopicStrategyTest`, `SpringTideHotTopicStrategyTest`, `CoastalTideFactsBuilderTest`,
`WindowTideRollupBuilderTest`, `PlanWindowProjectorTest`, `HttpCachingConfigTest`, and the frontend
tide suites (`mapTideFit`, `MapTideStrip`, `MapPeekTideSection`, `TideFitBlock`, `MapCallout`,
`LocationFourDaySheet`, `WindowFirstHeatStrip`, `windowFirstCards`, `windowFirstSpots`,
`windowFirstTideRun`, `askModel`, `askPlan`, `MapViewFillerTideStrip`).

## 8. Open questions for the owner

- **Q1.** On a blanked window, should the Plan card's "N on tide" chip count coastal locations within
  reach that have a tide fact but no slot? Default: no (unchanged).
- **Q2.** Should the honesty filter stop saying "Too unsettled to forecast" on windows that are unscored
  by design? Default: separate work.
- **Q3.** After P2–P4, remove the duplicated tide fields from API slots? Default: decide then.
