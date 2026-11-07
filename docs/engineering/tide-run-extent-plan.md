# Tide run extent — the lanes' "began earlier" / "continues after" edges for tides

Follow-up to `days-ahead-lanes-plan.md` (gregochr/goldenhour#1769). One PR.

## Problem

At a Glance's lanes draw a dotted left edge (`startsBefore`) and a faded right edge (`endsAfter`).
`buildGlanceLanes` (`frontend/src/utils/fourDayTiles.js`) derives both from served `briefing.hotTopics`
dates only. The briefing serves hot topics for today … `PlanHorizon.lastPlanDate(today)` (today+3), and
the tide strategies build the run (`TideRunBuilder.build`, `dayNumber`/`dayCount`) over the candidate
days inside that window. So a spring or king run that began yesterday, or runs on past today+3, shows no
edge, and `eventIdOf` keys the run on `date - (dayNumber-1)`, a windowed start.

## Decisions

- **D1 — the server states the run's real extent on `TideRunDay`**: two new nullable `LocalDate` fields,
  `runStart` and `runEnd` (`@JsonInclude(NON_NULL)`, ISO `yyyy-MM-dd` on the wire like `HotTopic.date`).
  Every day of one run carries the same pair. No extra topics are served; `dayNumber`/`dayCount` (the
  windowed `RUN n/N` count) are unchanged.
- **D2 — membership of a walked day**. Candidates (the days that carry a topic) stay exactly as today:
  `findTidalSlot(day) != null` and `nearestSyzygyIsPerigean(date) == king`, over the window's cached days.
  A **walked** day `d` (outside the window, see D3) belongs to the run when
  `nearestSyzygyIsPerigean(d) == king` **and** (`lunarPhaseService.classifyTide(d) != REGULAR_TIDE` **or**
  `TideSizeIndex.measure(...).springOn(d)`). One rule for every walked day, cached or not (review: mixing
  the slot test on a cached today+4 with the index beyond it was non-monotone and depended on cache
  freshness). `kingOn` is not OR-ed in (spring threshold is the "big water" test; pin with a test that a
  king-only-height day is not needed). The index arm is roster-wide and ungated by the solar ±90 min slot
  test, so it can call an edge day tidal that the Plan's slot test would not; that is the edge-day
  disagreement `SpringTideHotTopicStrategy.isSpringNotKing` already documents, and it only ever adds an
  edge, never a topic. `tide_extreme` is not pruned (yesterday is present); beyond the ingested horizon the
  index is false and only the lunar arm applies.
- **D3 — the walk only steps outside the window** (`fromDate`..`toDate`, the detect window). Inside the
  window the candidates are the truth, so a non-candidate in-window day must never extend the run, or a
  lane starting tomorrow would claim "began earlier" over a today column that shows no tide.
  - `runStart` = first candidate, and only if the first candidate **is** `fromDate`, walk back from
    `fromDate - 1` while D2 holds.
  - `runEnd` = last candidate, and only if it **is** `toDate`, walk forward from `toDate + 1` while D2
    holds.
  - Each walk is capped at `MAX_RUN_REACH_DAYS = 7`; a capped walk stops at the cap date.
  - Computed from ALL candidates, before the solar-freshness drop (as `build` already is), so a today
    dropped by freshness still yields `runStart = today`.
  - One `TideSizeIndex.measure(coastal, fromDate - 7, toDate + 7)`: the same fixed window for both
    strategies, so one cache key; pure lunar arithmetic otherwise. No Anthropic, no WorldTides call.
- **D4 — where it lives**. A new `@Component TideRunExtent` (`service/`) with
  `Extent extentOf(List<LocalDate> candidates, boolean king, LocalDate fromDate, LocalDate toDate,
  List<LocationEntity> coastal)` → `record Extent(LocalDate start, LocalDate end)` (null when candidates
  are empty). Both strategies call it once with the same candidate list they pass to
  `tideRunBuilder.build` and attach it to every run day with a new
  `TideRunDay.withRunExtent(LocalDate start, LocalDate end)`. `TideRunBuilder` is unchanged.
- **D5 — the record**. `TideRunDay` gains the two components at the END of the canonical constructor
  (which keeps `@JsonCreator`); keep an 18-argument secondary constructor delegating with `null, null` so
  the ten existing test call sites do not churn. Old cached `daily_briefing_cache` JSON lacks the keys and
  deserialises to null (record already `@JsonIgnoreProperties(ignoreUnknown = true)`); the client then
  falls back to today's served-dates rule.
- **D6 — the client**. In `fourDayTiles.js`:
  - `eventIdOf`: for a tide topic whose `tideRun.runStart` is a string, the id is
    `TIDE:${runLabel}:${runStart}`; otherwise the existing `dayNumber`/`dayCount` form.
  - The tile chip carries `runStart`/`runEnd` from `topic.tideRun` (tide topics only).
  - `buildGlanceLanes`: for a lane whose first chip has `runStart`/`runEnd`, `startsBefore =
    runStart < firstDate` and `endsAfter = runEnd > lastDate` (ISO string compare); otherwise the existing
    served-dates rule. Never the almanac (D14 of the lanes plan).
  - **Per edge, typed**: `startsBefore = typeof runStart === 'string' ? runStart < first : <served rule>`,
    and `endsAfter` likewise with `runEnd`, so a half-served pair never loses the served-dates edge. The
    chip's `runStart`/`runEnd` are read from the same `first` topic that supplies `eventId`.
  - The edges stay client-side because they depend on the reader's visible window (first/last live
    column, gaps), which the server does not know. This stays inside the lanes' licensed derivation;
    add "edge booleans from served `runStart`/`runEnd`" to its entry in
    `reference/architecture-decisions.md` ("Backend-heavy").
  - Update the `buildGlanceLanes` JSDoc ("a backend follow-up, not guessed at").
  - `WindowGlanceLanes.jsx` already renders `data-starts-before` / `data-ends-after` and the spoken
    "began earlier" / "continues after <day>" tail; no component change expected. Check that "continues
    after <day>" names the lane's last day (not `runEnd`).
- **D7 — gating and caching**. `tideRun` is astronomy, not personal data, so riding the ETag-shared
  `/api/briefing` is within the rule. **Lite is unchanged and gets no edges**: `HotTopic.forLite()` nulls
  `tideRun` on tide topics (pinned by `TideLightLiteWireTest`), so Lite lanes stay on the served-dates rule.
  Whether Lite should get tide edges (a separate kept field) is an owner call, named in the PR as a
  follow-up, not done here. The new components join `TideRunDay.equals`, so the first serve after deploy
  (old cache, null extent) takes the overlay path once; harmless. Persistence (Jackson 2, `AppConfig`)
  writes `LocalDate` as an array, the wire (Jackson 3) as ISO; test each on its own path.

## Tests

Backend:
- `TideRunExtentTest` (new): walk back across a lunar-only yesterday; across a height-only yesterday
  (index `springOn`); stop at a non-tidal day; stop at a day of the other kind (perigean flips); forward
  past `toDate` by index/lunar; first candidate after `fromDate` → `runStart` = first candidate with NO walk
  even when `fromDate` would qualify (and same for the end); the 7-day cap; empty candidates → null; index
  `UNMEASURED` → lunar arm only; a `kingOn`-only day does not extend. Pin every stub argument (no bare `any()`, per Codex).
- Spring and king strategy tests: each served topic's `tideRun` carries the extent the component
  returned, identical across the run's days; the component is called with the same candidate dates
  `build` got.
- `TideRunDay` round-trip with and without the new keys on the Jackson 2 persistence path (array form),
  and the wire format (ISO string) in `HotTopicJsonTest` or `DailyBriefingResponseJsonTest`.
- Lite: `TideLightLiteWireTest` still passes unchanged (no `tideRun` for Lite).
- `PersistedBriefingRemovedKeysTest` keeps passing.

Frontend (`fourDayTiles.test.js`, per `frontend-test-standards.md`):
- run began yesterday: four-day lane with `runStart` = yesterday → `startsBefore` true, `endsAfter` per
  `runEnd`; run runs past the last column → `endsAfter` true; run wholly inside → both false even though
  the served-dates rule would also say false; today's topic dropped by freshness (lane starts column 2,
  `runStart` = today) → `startsBefore` true.
- `eventIdOf` with `runStart`: two days whose windowed `dayNumber` would give different starts still share
  one lane; gap case (Sat, Mon) gives two lanes, Mon `startsBefore`, Sat `endsAfter`.
- No `runStart` (old cache) → existing behaviour; keep one case asserting the old
  `TIDE:<label>:<count>:<start>` id. The existing id assertion (`fourDayTiles.test.js` ~line 173) keeps the
  fallback fixture; add a sibling fixture with `runStart`/`runEnd` asserting `TIDE:<label>:<runStart>`.
- Lite shape (`tideRun` null) → served-dates fallback.
- Only one of `runStart`/`runEnd` set → that edge from it, the other from served dates.
- `WindowGlanceLanes` test: `runEnd` past the grid → spoken "continues after <lane's last day>", not `runEnd`.
- Backend pins that every day of a run (capped walk included) carries the identical pair, and that the
  extent is computed from all candidates before the freshness drop.

## Docs

- `reference/plan-tab.md` §At a Glance lanes: replace "serving a run's real extent is a backend follow-up"
  with the `runStart`/`runEnd` rule.
- `reference/hot-topics-tides-eclipse.md` tide runs: add `runStart`/`runEnd` and D2's predicate.
- `days-ahead-lanes-plan.md`: note the follow-up shipped.
- `changelog.d/20261107-tide-run-extent.md`.
