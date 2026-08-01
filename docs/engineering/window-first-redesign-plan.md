# Window-first Plan tab — implementation plan

**Status:** plan, agreed 2026-08-01. No code written yet.
**Spec:** `docs/design/window-first/` (the handoff bundle — README.md is the design of record's spec,
`Plan Window First v2.html` the design of record itself). Copy it in as step P0.

The design inverts the Plan tab's loop: **time is the outer loop, features are attributes**. One card
per solar window carrying its own verdict, narrative, tide state, snow state, badges and nearby spots.
Tides and aurora stop being page sections and become properties of the window they affect.

---

## 0. Decisions taken up front

| Question | Decision |
|---|---|
| How it lands | **Build alongside, flag-switched.** New components; `DailyBriefing.jsx` untouched until the new view is proven. |
| Heatmap / hot topics / Close to home | **Re-parented behind the two doors**, not deleted. Regional planner door = `HeatmapGrid` + full regional briefing. Hot topics door = `HotTopicStrip`. Close to home dissolves into the per-window spot strip, and its filmstrip/rail/peek machinery is *extracted and reused*, not rewritten. |
| "Coming up" (90 days) | **Built as part of this work** — needs a new almanac-only aggregation (§3). |
| Where windows are assembled | **Backend projection.** Shared data is derived server-side and served ready to render. The per-user reach join stays on the client, because it must not ride the cached briefing payload (§2.2). |

---

## 1. What already exists

**Tokens are already the design's palette.** The `@theme` block in `frontend/src/index.css` carries
`#181210` / `#221A15` / `#3A2C23` / `#F2E7D3`, the three verdict colours, `--color-tide`, and
`--color-close-to-home: #C9A24B` (the design's `--home`). Only three things are missing: `--nlc`
(`#9B8FD4`), `--snow` (`#B7CBD8`), the badge-text variants, and **IBM Plex Mono 600** in
`frontend/src/fonts.js` — the design's kickers are mono/600 and only 400/500 are bundled, so the
browser is currently synthesising it.

**Several of the README's Known Traps are already solved here.** Reuse rather than re-derive:

| Trap | Solved in |
|---|---|
| Snapping scroller eats the horizontal gutter | `.cth-window-grid` — padding + negative margin + `scroll-padding` |
| Peek node destroyed on re-render | `CardHoverPreview` is `position: fixed` with JS placement, escaping every ancestor's overflow |
| Footer claims a sort/count the cards contradict | `CloseToHome` already pins `cards.size() == withinReach` with a test |
| Filmstrip needs a visible affordance | `ScrollRail` (draggable), 4.5-across flex-basis, hidden scrollbar |
| Click-to-map must carry the window | `buildMapOverlay` + `MapOverlay` already carry window, date, focus and a route back |

Other direct reuse: `StarRating`, `ScoreBar`, `VerdictPill`, `ProvisionalMark`, `Modal`,
`useLocalStorageState`, `briefingScoreIndex`, `confidenceUtils`, `briefingDisplay`
(`formatDriveDuration` / `formatTime` / `weatherCodeToIcon`), and
`components/chart/solarDayGeometry.js` — which is already the 24-hour axis the tide sparkline needs.

---

## 2. The data gaps, and how each is closed

### 2.1 Free or nearly free

- **Window badges** (NLC "clearest in 11 nights", aurora Kp, tide, snow). `HotTopic` already carries
  `date`, `eventType`, `eventTime`, label and detail. A badge is a topic bucketed into its window.
  This is where Hot Topics goes, and it is the cleanest re-parenting in the design.
- **Away days.** `travelDayApi` + `fetchTravelDayRanges` exist and `DailyBriefing` already reads them.
  Maps directly onto the dashed "N windows not generated" row.
- **Snow row.** `SnowFreshHotTopicStrategy` / `SnowTopsHotTopicStrategy` already produce the facts.

### 2.2 The one architectural collision: the spot strip is a two-contract join

Shared per-window data (rating, region, Claude summary, fiery/golden scores) is already in
`briefing.days[].eventSummaries[].regions[].slots[]`.

Per-user data (drive minutes, distance miles) **must never ride `/api/briefing`**. `HttpCachingConfig`
ETag-revalidates that path, which requires `Cache-Control: private, no-cache` and therefore persists
the body to a browser HTTP cache that JavaScript cannot evict on logout. `/api/user/settings*` is
excluded from the filter for exactly this reason, and `HttpCachingConfigTest.personalDataPathsAreNeverFiltered`
pins it. See `docs/engineering/plan-panel-data-contracts.md`.

So: **shared window content from `/api/briefing`, per-user reach from a per-user endpoint, joined on
the client by `locationId`.** That join is the licensed exception, not a licence for more.

The reach contract: add `GET /api/user/settings/reach` returning `[{locationId, driveMinutes,
distanceMiles}]` for the **whole roster** — deliberately not radius-gated, because reach is a lens over
everything, where `localRadiusMiles` gates only "Close to home". A new endpoint rather than reshaping
`/api/user/settings/drive-times`, which `DailyBriefing` still consumes as a flat id→minutes map.

### 2.3 Per-window Best Bet and "Also good"

`bestBets` today is a *global* rank-1/rank-2 list that can span days, with `relationship`/`differsBy`.
The design forbids a cross-day "also good" outright — a cross-day alternative is simply the Best Bet
of its own window.

`BriefingRegion.glossHeadline` / `glossDetail` is already per-region-per-event, so:

- Best Bet for a window = the top region's gloss, with that region's name.
- Also good = the **second region of the same window**, or nothing.

Enforce in the backend mapping, not in the view.

### 2.4 Per-window tide rollup

Pieces exist across `BriefingSlot.tide` (state, aligned, nearest HW) and `TideRunDay` (curve points,
range, range-vs-mean, formatted clock times), but `TideRunDay` only exists when a spring-tide topic
fires. A per-window rollup — state + direction, nearest HW/LW **and its offset from the window**,
range, range-vs-average, sparkline points — is new backend derivation.

Two rules it must obey, both already learned the hard way in this codebase:

1. **It cannot ride the `daily_briefing_cache` payload.** Hot topics are recomputed live on every
   serve and the response is rebuilt from them; anything derivable only during `refreshBriefing` is
   serialised on the build path and thrown away on the first request. Worse, a persisted-but-not-live
   field makes the record's `equals` fail and *forces* the overwrite. Use an in-memory
   `AtomicReference` carrier populated in `refreshBriefing` and read inside the serve path — the shape
   `NlcClarityService`, `MeteorClarityService` and `SurgeCurveService` already use.
2. **Series must be `List<Double>`, never `double[]`.** A record component compared by identity makes
   the cache-equality check false on every request and rebuilds the whole response each time.

Accepted cost: after a restart the carrier is empty until the next cycle and the tide row degrades to
its fact line. Never synthesise to fill that gap.

### 2.5 Reach lens

CloseToHome gates by `localRadiusMiles` with a client chip cycling 15/30/45 min. The design wants
45 min / 1h 30 / 2h 30 / Any as a **time** lens over the whole roster. Same primitive, wider gate —
fed by §2.2's endpoint. The default reach comes from the selected day (weekday vs weekend), never a
constant, so the rail and the lens cannot disagree.

### 2.6 Promoted strip

A *coincidence* — two attributes landing on the same window — earns the full-width strip. **At most
one strip; highest rarity wins; enforced in code, not by convention.** New derivation: rank the topics
that bucket to the same window by rarity.

---

## 3. "Coming up" — the 90-day almanac feed

`HotTopicAggregator` runs `today .. today+3` (`BriefingService:264`, `:550`). This is **not a widened
window**: weather-driven strategies cannot run 90 days out. Only the ephemeris- and tide-table-driven
ones can.

- **Eligible (Almanac):** spring/king tide (from `tide_extreme`, which has 12-month backfill), meteor
  showers, NLC season bounds, supermoon, equinox/solstice. Fixed by orbital mechanics — plan months out.
- **Not eligible (Forecast):** aurora, inversion, snow, dust, bluebell, storm surge. These stay on the
  existing ~3-day path and appear in Coming up only for the days the aggregator already covers.

Shape: `AlmanacEvent {startDate, endDate, kind ALMANAC|FORECAST, title, detail, meta, regions}`, served
from `GET /api/almanac?days=90`. No Claude call, so it is cheap; cache daily. The tab's tag vocabulary
is exactly two words — **Almanac** (fixed) and **Forecast** (firms up ~3 days ahead).

**The rule that keeps Plan small:** seasons live in Coming up permanently; Plan only ever carries
tonight's *delta*. If that split erodes, Plan re-grows to 2,600px.

---

## 4. Flag mechanism

A localStorage key (`photocast.planLayout`, `'v1' | 'v2'`) via `useLocalStorageState`, toggled from
`UserSettingsModal`. No backend change, no build-time env var — the five pilot users need to be able to
switch back without a deploy, and you need to compare both views against the *same* night's data.

Default stays `v1` until the §6 sweep passes, then flips. Bump the key if its schema changes, so a
stale value cannot resurrect under a new meaning.

---

## 5. Build order

Backend first for anything shared, so the frontend stays a render layer.

| Phase | Work | Notes |
|---|---|---|
| **P0** | Handoff into `docs/design/window-first/`; tokens; Mono 600; flag scaffold | Strip the five demo buttons and the annotation cards — they are marked not-for-production |
| **P1** | Backend: window projection — verdict, best rating, Best Bet/Also good from region gloss, badges from bucketed topics | Assembled at **serve time**, like `enrichWithCachedScores` |
| **P2** | Backend: per-window tide rollup on an in-memory carrier (§2.4) | `List<Double>`, not `double[]` |
| **P3** | Backend: `GET /api/user/settings/reach` (§2.2) | Whole roster, not radius-gated |
| **P4** | Shell — masthead, **inert** day rail, tabs | `border-bottom-width: 0`, never `border-bottom: none` |
| **P5** | Window card — header, verdict badges, Best Bet, footer | Verdict colours identical everywhere they appear |
| **P6** | Spot film strip | Reuse `.cth-window-grid` + `ScrollRail`; one shared comparator (rating desc, then drive asc); footer states what is *drawn* |
| **P7** | Attribute rows — tide, then snow | Cap at two per window; anything further folds into a badge |
| **P8** | Lens bar + reach filtering + persistence policy | Reach expires at the day roll; rating floor persists; type never persists |
| **P9** | Collapse/expand, six-window case, away-day row | Verify ~1,500px with six windows, two open |
| **P10** | Peek + click-to-map | Reuse `CardHoverPreview` and `buildMapOverlay` |
| **P11** | Drilldown sheet | Same cards, same comparator; type filter resets on close |
| **P12** | Backend: almanac feed (§3) | |
| **P13** | Coming up tab | |
| **P14** | Responsive pass — real media queries | Keep control labels at 9px; never hide them |
| **P15** | Pre-pilot sweep (§6), then flip the flag default | |

**Migrations: none expected.** Everything rides existing tables and payloads. If one turns out to be
needed, read the latest number off the tree on **main** — `ls backend/src/main/resources/db/migration/
| sort -V | tail -1` — never from a written-down number, including this one.

Read `docs/engineering/test-improvement-standards.md` before writing any test class.

---

## 6. Pre-pilot sweep

- No demo buttons, no annotation cards anywhere in the shipped build.
- Signal copy is **stateless** — deltas computed from data alone ("clearest in 11 nights", "biggest
  tides of the month"). No per-user exposure counter: every pilot user must see the same page on the
  same night so bug reports reproduce, and silence must never mean "you have been told before".
- At most one promoted strip; highest rarity wins; enforced in code.
- No invented vocabulary. No counts of our own data ("11 aligned" is a fact about the database, not
  about tonight). No jargon needing a glossary.
- Verdict colours consistent in every location — rail, badge, pill, drilldown.
- Every footer's claimed sort and count matches what is rendered.
- Instrument every control. The pilot is looking for two things: what people ask about, and what
  nobody touches.

**Ongoing rule:** before adding anything to the Plan tab, read `Adversarial Review.html` in the
handoff. New elements earn their place against that list, and something should usually come out when
something goes in.

---

## 7. Deviations from the spec, and why

- **Status pill.** The design shows "● UP v2.17.7" unconditionally in the masthead. `HealthIndicator`
  is admin-only today and stays that way — build version and service health are not a pilot user's
  business.
- **Styling.** CLAUDE.md says Tailwind only, no inline styles; the spec is written in exact px
  (12.5 / 10.5 / 9.5). Follow the existing precedent — `index.css` component classes plus tokens, with
  inline style for one-off exact values, which is what `CloseToHome`, `TideRunRow` and `ViewToggle`
  already do. Do not introduce a third way.
