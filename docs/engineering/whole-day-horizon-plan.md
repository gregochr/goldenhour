# Whole-day render horizon (At a Glance and the Map agree on which days exist)

Owner decisions, 2026-11-10:

- At a Glance and the Map must show the same days: **today to T+3, whole days**.
- The Map drops its T+4 and T+5 sunrise/sunset rows (night rows untouched).
- A pick (BEST BET / ALSO GOOD) may land on a T+3 window. The invariant that matters to the owner
  is that **a pick is never bound to a window the reader cannot see**; that holds because
  `selectPicks` draws only from the rendered set, and T+3 is now always rendered on both tabs.
- The best-bet advisor's system prompt is reworded count-free.

Two phases, one PR each: **P1** the served horizon (backend, plus frontend comments and tests),
**P2** the Map's date cut (frontend). Reviewed by three adversarial lenses before any code
(backend readers; frontend and Map; picks, advisor, Ask and cost); their findings are folded in below.

## 1. The problem

The briefing renders the **leading six non-past solar events** (`PlanRenderLimits.MAX_VISIBLE_EVENTS`,
applied by `PlanWindowProjector.renderHorizon`). Six events is three whole days before today's
sunrise and three and a half after it, so:

- At a Glance shows T+0..T+2 before sunrise, then grows a fourth column (T+3 sunrise only, with a
  "past the end of the forecast" hole) once this morning has gone.
- The Map's window control lists every date `GET /api/forecast` returns (to T+5) but paints a heat
  field only for a window in `renderedEvents`. A window outside the six is a D-13 filler row: chips
  hold stars over an empty field.

Seen by the owner at 06:25 on Tuesday 2026-11-10: At a Glance stopped at Thursday; the Map's Friday
sunrise had 4★ chips and no field.

## 2. The rule

**Render every non-past solar event whose date is in `[today, today + 3]`, in payload order.**

- Before today's sunrise: 8 events, four full columns.
- After this morning has gone: 7 events; the "this morning has gone" hole stays.
- After today's sunset (plus afterglow) until UK midnight: 6 events, three columns (T+1..T+3).
- A briefing more than a day old carries fewer dates and shows fewer columns, as today.

Why T+3: it is the last horizon Gate 4 scores (`NightlyEligibilityPolicy`;
`CoverageReasons.NEVER_SCORED_FROM_DAYS_AHEAD = 4`), and "leading eight events" would render T+4's
sunrise every afternoon. The briefing carries T+0..T+4 when built
(`ForecastHorizon.BRIEFING_WINDOW_DAYS = 5`), so T+3 survives one night of ageing.

**The lower bound is part of the rule.** An aged briefing's first day is yesterday, and
`hasPassed` treats a null time as current, so "date ≤ T+3" alone would render an untimed
yesterday event as a leading fifth column.

## 3. One owner

`service/PlanHorizon` already owns "the Plan's days": `PLAN_OWNED_DAYS = 4`, `lastPlanDate(today)`
= today + 3, used for the hot-topic window. It stays the owner. No new day constant.

`PlanRenderLimits` becomes a thin reader of it:

- `MAX_VISIBLE_EVENTS = PlanHorizon.PLAN_OWNED_DAYS * 2` (a compile-time constant; it is used in
  an annotation at `BriefingController:126` through `BriefingDigestService.DEFAULT_LIMIT`).
- `static boolean withinHorizon(LocalDate date, LocalDate today)` =
  `!date.isBefore(today) && !date.isAfter(PlanHorizon.lastPlanDate(today))`.
- Class Javadoc rewritten: it currently argues against a date-counted horizon. `PlanHorizon`'s
  Javadoc (lines 18-21, "deliberately not the rendered horizon") is rewritten too.

## 4. P1: the served horizon

### 4.1 Backend changes

| Place | Change |
|---|---|
| `PlanWindowProjector.apply` / `renderHorizon` | `apply` gains a `LocalDate today` parameter. `renderHorizon` filters `!isPast && withinHorizon(d.key().date(), today)`; the `.limit(...)` goes. **Never** `now.toLocalDate()` (wrong for an hour a night under BST). |
| `ServedBriefingAssembler.assembleForPlan` (`:178-198`) | Read the clock **once**: `Instant i = clock.instant()`; `now = LocalDateTime.ofInstant(i, UTC)`; `today = ForecastHorizon.civilDate(i)`; reuse that `today` for `BriefingVersion` at `:198`, so the rendered set and the version hash cannot disagree across UK midnight. |
| `BriefingRollupBuilder` (`:131-176`) | Replace both `eventCount >= MAX_VISIBLE_EVENTS` breaks with `withinHorizon(day.date(), today)` (it already holds `today = ForecastHorizon.today(clock)`). Delete the count; do not keep a second rule. Travel-day skip and its own past test unchanged. |
| `BriefingDigestService` (`:103-118`) | It does not read `renderedEvents`; it takes the leading N non-past summaries itself. Filter by `withinHorizon` (with `ForecastHorizon.today(clock)`) **before** applying `limit`; otherwise the default of 8 returns T+4's sunrise every afternoon. |
| `BestBetPromptText.java:37` | "the next 6 upcoming solar events" → "the upcoming solar events". Nothing else in the prompt changes. |
| Unchanged code, now up to eight | `ReachVerdictService`, `AskSnapshotBuilder`, `BriefingVersion`, `selectPicks`, `dayPeak`, `reproject`, tide and big-seas builders (all set-driven; verified by review). |

The rollup and the projector keep **different pastness tests** (the rollup: today only, first slot
time, no afterglow, build time; the projector: earliest slot, 30-minute afterglow, serve time).
That is pre-existing and out of scope. The claim this plan makes, and tests, is the narrow one:
**at one `today`, every solar event the rollup names is inside the date bound the projector
renders** (the appended `<today>_aurora` event is not a solar window and is excluded from that
claim). The advisor's `bestBets` have no reader-facing renderer (admin runs view and replay only);
reader-facing picks come from `selectPicks`, which is restricted to the rendered set.

With travel days on T+0..T+2 the rollup today holds T+3 and T+4; after this change it holds T+3
only. That is correct: T+4 nodes carry no ratings and a T+4 pick is dropped by
`BestBetRanker.dropUnevaluatedPicks`.

### 4.2 Frontend changes (P1)

No logic change: the client renders `renderedEvents` (review confirmed every count, column and
row is derived from it; CSS tracks are `repeat(var(--dc))` with no rule keyed on holes).

- New tests: `buildWindowMatrix` and the heat-strip component with 8 events on 4 days (no holes,
  `renderEmpty` count 0), 7 events (first-day am hole only, no `pmEmpty`), and 6 events on 3 days.
- `windowFirstMatrix.js` keeps the `beyondForecast` copy (still reachable from a six-event payload
  in the SWR cache or from an old backend mid-deploy); its class comment is rewritten.
- Stale "six" / "three or four days" comments corrected: `utils/mapEvents.js:32,356-358,839`,
  `utils/mapTopics.js:11-12`, `utils/mapTideFit.js:65-69`,
  `components/WindowFirstMapPane.jsx:136,724-730`, `utils/windowFirstCards.js:739`,
  `utils/windowFirstAway.js:11-13`, `utils/locationSheet.js:28,37-38,667`,
  `utils/planSearch.js:14,405-406`, `utils/reachLens.js:133`,
  `components/WindowSheetDialog.jsx:492,673-675`,
  `context/WindowFirstBriefingContext.jsx:238,876,1016-1020`, `index.css:1490,1903-1906`,
  `components/WindowFirstLensBar.jsx:151-156`.

### 4.3 Backend tests (P1)

`test-improvement-standards.md`; pin every stub argument (only `never()` and lambdas may use `any()`).

- `PlanWindowProjectorTest`: 8 before sunrise; 7 after sunrise + afterglow (first is today's
  sunset, last is T+3 sunset); 6 after sunset (T+1..T+3, no T+4); T+4 never rendered; an untimed
  yesterday event in an aged briefing is not rendered; a BST 23:00-23:59 UTC case where `today`
  (UK) differs from `now.toLocalDate()`; a pick can bind to T+3 and never to T+4.
  `horizonCountsSixEventsNotFourDates` (`:788`) pinned the opposite rule: **invert it, do not
  delete it**. Also `:700-709`, `:825`, `:1207-1209`, `:1228`, `:1283`, `:1312`.
- The `apply` signature change touches about 22 call sites: `PlanWindowProjectorTest`,
  `ReachVerdictServiceTest`, `PlanWindowProjectorNeighbourGuardTest`, `LitePickSentinelTest`,
  `WindowTideFactsPayloadTest`, `ServedBriefingAssemblerTest`.
- `BriefingBestBetAdvisorTest:1509-1610` (`limitsToSixEvents`, `exactlySixEvents_allPassThrough`,
  `pastEventsSkippedBeforeCounting`): rewrite to the date rule. Add: travel T+0..T+2 → only T+3
  events, each inside the bound; travel T+0..T+3 → empty rollup; a day before today is excluded.
- `BriefingDigestServiceTest`: 7 rendered + a T+4 summary → default returns 7; default limit
  equals `PlanHorizon.PLAN_OWNED_DAYS * 2`.
- `ServedBriefingAssemblerTest`: a clock on the BST midnight hour proves one clock read.
- `AskSnapshotBuilderTest:133-152` and `AskFixtures:240,251`: reshape to what the server can now
  produce (eight rendered, T+4 excluded).
- **Ask's character budget at eight windows**: `AskToolsTest:1470-1491,1627-1637`,
  `AskToolsLookUpPlaceTest:612,847,1165,1280`, `AskToolsNamedAreaTest:409-419,563` are proven at
  six. Add eight-window versions of the worst cases against `RESULT_CHAR_CAP` (9,000) and the
  4,500 half-cap, and report the measured `list_windows` size at six and at eight. If eight
  crosses the half-cap, **stop and report**; do not raise a cap.
- **Never edit an assertion** in a prompt-regression class. There is no `regression/` directory;
  the classes are `BestBetAuroraPromptRegressionTest`, `ValleyMistPromptRegressionTest`,
  `SkyRatingRegressionTest` and `service/ask/Ask*RegressionTest`. The prompt reword changes
  `BestBetAuroraPromptRegressionTest`'s input only; the owner approves that paid job on the PR.

### 4.4 Docs (P1)

`reference/plan-tab.md`, `reference/map-tab.md`, `reference/architecture-decisions.md`,
`reference/api-endpoints.md` (digest default), `reference/ratings-stores.md:345`,
`reference/ask-photocast.md`. Javadoc: `PlanWindowProjector:259-272,388,430,446`,
`model/BriefingWindow:29`, `model/PlanRenderedEvent:17`, `model/DailyBriefingResponse:41`,
`model/BriefingDigestResponse:82`, `BriefingHierarchyBuilder:109`,
`ask/AskSnapshotBuilder:60,290,624`, `ask/AskSnapshot:115`, `ask/AskReadyFreshness:17`,
`BriefingDigestService:29-36`, `BriefingRollupBuilder:241-244`, `EvaluationViewService:1169`,
`BriefingService:116` (the fifth date is still never rendered).
`changelog.d/20261110-whole-day-horizon.md`.

### 4.5 Cost (P1)

No new model calls; no change to which slots are scored. The advisor rollup gains up to two
events (an unscored region node is about 85-90% the size of a scored one, so this is roughly
+15-30% advisor input, estimated at tens of cents a month). Ask's `list_windows` grows by about a
third, which enlarges the Ready precomputes the ledger already has an open item on
(`claude-cost-ledger.md:171-181`). Record the measured rollup and `list_windows` sizes, before and
after, in the ledger and reference that item.

### 4.6 Accepted behaviour changes (P1)

- A pick may bind to a T+3 window all day (owner accepted; the sample gate still applies).
- Ask can answer about T+3 all day; `BEST_WEEKEND` is offered a day earlier in the week, and an
  evening-built Ready answer whose window set changes at UK midnight is withheld until the
  overnight precompute.
- The digest's default is up to eight windows.

## 5. P2: the Map ends at T+3

Bound the upper end of `allDates` in `App.jsx:519-522` to `d <= lastPlanDateStr(today)`
(`utils/comingUpHandoff.js:55`, the existing client mirror of `PlanHorizon`; no new constant;
`today` through `appNow()`). One edit covers the filler rows, `solarRowPredicate`,
`resolveMapDate`, the older-day loader and the Coming up and day-tile Map doors (which withhold
on `forecastDates.includes(date)`), so nothing can route to a date with no row.

- Also bound the degrade walk in `WindowFirstBriefingContext.selectUpcomingEvents` (`:249-266`,
  used while `renderedEvents` is absent) to the same date, or a T+4 card can appear for seconds.
- Lost on the Map: the T+4 tide strip, big-seas stamp and daylight High-water row, and hides
  drawn on T+4/T+5. Big seas on T+4 still shows in Coming up. Hide comfort in the hourly sheet is
  unaffected.
- Night rows are untouched; a T+4 or later night becomes "kept local" (an existing path) and its
  preview best reads "—" until selected.
- To verify before building: Ask-to-Map links cannot name a T+4 date; no persisted
  `selectedDate` can strand the Map on a dropped date.
- Tests: `mapEvents` D-13 filler tests, `comingUpFeed` and `fourDayTiles` door tests using T+4
  dates, `window-stepping.spec.js` (`:74`, `:107`; the forward loop ends at T+3).
- This is a licensed client derivation only in the sense of a date filter over a served list with
  an existing mirrored constant; add it to the named list in `architecture-decisions.md`.

## 6. Verification

Local gates (backend ladder with the integration exclusion; the frontend's four commands; E2E
smoke; changed-lines check). Browser on port 8083 with Rewind, each state seen and reported as
seen or only tested:

1. Before sunrise: four full columns; topic lanes fit; phone stacks four days.
2. After sunrise: seven cells, one worded hole.
3. After sunset: three columns.
4. Map T+3 sunrise and sunset paint a **field**, not only chips. ⚠️ Chips fall back to forecast
   rows; the field reads only `/api/briefing/evaluate/scores` joined over the rendered events
   (`WindowFirstBriefingContext:951`). If T+3 has stars and still no field, the scores endpoint is
   the next thing to read, and P1 is not done.
5. P2: the window control ends at T+3 sunset; night rows beyond it still open.

## 7. Out of scope

Gate 4 scoring policy; the briefing window length; pick ranking by horizon (far-term ratings are
measured as inflated, `claude-cost-ledger.md:118-131`; that exposure exists today for T+2 and is
its own decision); the rollup/projector pastness mismatch; travel-day wording.
