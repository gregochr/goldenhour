# Window-first Plan tab — implementation plan

**Status:** agreed 2026-08-01. **P0 built and reviewed 2026-08-02**; P1 is next.
**Reviewed:** adversarially, 2026-08-01 — two rounds, 29 charges raised, 16 upheld after refutation.
Every upheld finding is applied below. Where a finding reversed an earlier decision, the reversal and
its reason are recorded rather than silently overwritten. Each phase gets the same treatment before it
lands — see CLAUDE.md § UI Work — Review Cadence.
**Spec:** `docs/design/window-first/` (the handoff bundle — README.md is the written spec,
`Plan Window First v2.html` the design of record). Copy it in as step P0.

The design inverts the Plan tab's loop: **time is the outer loop, features are attributes**. One card
per solar window carrying its own verdict, narrative, tide state, snow state, badges and nearby spots.
Tides and aurora stop being page sections and become properties of the window they affect.

---

## 0. Decisions taken up front

| Question | Decision |
|---|---|
| How it lands | **Build alongside, flag-switched.** New components; `DailyBriefing.jsx` untouched until the new view is proven. |
| Heatmap / hot topics / Close to home | **Re-parented behind the two doors**, not deleted. Regional planner door = `HeatmapGrid` + full regional briefing. Hot topics door = `HotTopicStrip`. Close to home dissolves into the per-window spot strip, and its filmstrip machinery and the peek's placement/timer helpers are **copied** and reused — its geometry constants are not (§1), and `CloseToHome` itself is not rewired (§5, P6). |
| "Coming up" (90 days) | **Built as part of this work** — needs a new almanac-only aggregation (§3). |
| Where windows are assembled | **Backend projection.** Shared data is derived server-side and served ready to render. The per-user reach join stays on the client, because it must not ride the cached briefing payload (§2.2). |

---

## 1. What already exists

**Tokens are close to the design's palette, but the list is not three items.** The `@theme` block in
`frontend/src/index.css` carries `#181210` / `#221A15` / `#3A2C23` / `#F2E7D3`, the three verdict
colours, `--color-tide`, and `--color-close-to-home: #C9A24B` (the design's `--home`). Missing:

- `--nlc` (`#9B8FD4`) and `--snow` (`#B7CBD8`), plus the badge-text variants.
- **`--panel` (`#1E1712`)** — the *card* surface, used ten times in the design (`.win`, `.day`, `.tab`,
  `.seg`, `.chip`, `.mapv`) and absent from the tree entirely. Decide it once at P0: either add
  `--color-plex-panel` alongside the two above, or state that panel collapses onto
  `--color-plex-surface`. Both are legitimate at (4,3,3) apart — the point is that P5's window card and
  P8's lens bar must not each pick a substitute independently.
- **IBM Plex Mono 600** in `frontend/src/fonts.js` — the design's kickers are mono/600 and only 400/500
  are bundled, so the browser is currently synthesising it.

Do **not** mechanically diff the design's `:root` against `@theme`: that table also lists
`page: #0e0b09`, which is the mock's device-frame backdrop, not a product token.

**Several of the README's Known Traps are already solved here.** Reuse rather than re-derive — but
reuse the *mechanism*, and take every geometry constant from the spec:

| Trap | Solved in |
|---|---|
| Snapping scroller eats the horizontal gutter | The padding + negative-margin + `scroll-padding` technique in `.cth-window-grid`. **The numbers there are wrong for this design** — see P6. |
| Peek node destroyed on re-render | `CloseToHome` renders **one** peek node at the panel root, sibling to every window block, driven from panel-level state (`CloseToHome.jsx:805`, `:1215`). *That* is the lifetime guarantee. `position: fixed` is a separate concern and only stops a scroller's `overflow` clipping it (`index.css:459-467`). Note the React-specific trap the README omits: a `transform`, `filter` or `will-change` on any ancestor re-bases `position: fixed` onto that ancestor. |
| Footer claims a sort/count the cards contradict | `CloseToHome` already pins `cards.size() == withinReach` with a test |
| Click-to-map must carry the window | `buildMapOverlay` + `MapOverlay` already carry window, date, focus and a route back |

Other direct reuse: `StarRating`, `ScoreBar`, `Modal`, `BottomSheet`, `useLocalStorageState`,
`briefingScoreIndex`, `briefingDisplay` (`formatDriveDuration` / `formatTime` / `weatherCodeToIcon`),
`confidenceUtils` (`scaleRgbaAlpha`, `confidenceTreatment`) and `components/chart/solarDayGeometry.js`
— already the 24-hour axis the tide sparkline needs.

**Not** direct reuse, despite first appearances: `VerdictPill` and `ProvisionalMark` have no site in the
new Plan surface as drawn (the window card's verdict is a badge, the rail's is a coloured text line).
They keep their existing homes behind the regional-planner door. See §2.7 for how confidence is carried
instead.

---

## 2. The data gaps, and how each is closed

### 2.1 Free or nearly free

- **Window badges** (NLC "clearest in 11 nights", aurora Kp, tide, snow). `HotTopic` already carries
  `date`, `eventType`, `eventTime`, label and detail — and `eventType` is assigned centrally by
  `HotTopicEventEnricher`, which runs unconditionally at the end of `getHotTopics` and covers every
  named type but STORM_SURGE. A badge is a topic bucketed into its window. This is where Hot Topics
  goes, and it is the cleanest re-parenting in the design.
- **Away days.** `travelDayApi` + `fetchTravelDayRanges` exist and `DailyBriefing` already reads them.
  The impersonal copy variant already ships at `HeatmapGrid.jsx:1097-1116` with tests behind it.
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
`/api/user/settings/drive-times`, which `DailyBriefing` still consumes as a flat id→minutes map, and
deliberately not `close-to-home`, which is wrong three ways: its gate is a *distance* radius
(`localRadiusMiles`) rather than the design's drive-time tiers, it would need one uncacheable request
per tier behind a four-way instant control, and its `qualifies()` gate (`CloseToHomeService:549-550`)
drops exactly the below-floor spots the drilldown's rating filter has to be able to show. (Its `Card`
does carry both miles and minutes — the payload is not the problem, the gate is.)

### 2.3 Per-window Best Bet and "Also good"

`bestBets` today is a *global* rank-1/rank-2 list that can span days, with `relationship`/`differsBy`.
The design forbids a cross-day "also good" outright — a cross-day alternative is simply the Best Bet
of its own window.

`BriefingRegion.glossHeadline` / `glossDetail` is already per-region-per-event, so:

- Best Bet for a window = the top region's gloss, with that region's name.
- Also good = the **second region of the same window**, subject to the floor below, or nothing.

Enforce in the backend mapping, not in the view. Label it "Also good" — drop the "· same window"
qualifier, which this section's own argument makes redundant.

**The quality floor is not optional, and it already exists.** `BestBetPromptText.java:181-215` and
`BriefingBestBetAdvisor.PICK_TWO_RATING_FLOOR = 3.0` require pick 2 to be within 0.5 of pick 1 and ≥ 3.0
absolute — "an honest silence is better than a padded recommendation". That rule lives on the `bestBets`
path this section abandons, and the replacement source has no floor of its own: gloss is now produced
for weather-STANDDOWN regions too (`BriefingGlossService.java:42-43`), and `BriefingHierarchyBuilder`
never orders regions at all — `buildEventSummary` (`:99-101`) appends them in
`RegionGroupingUtils.groupByRegion`'s `LinkedHashMap` insertion order, and the class's one `.sorted()`
(`:158`) picks a median-temperature slot *within* a region. **So P1 must define the window's region
ranking (by `averageRating` descending) as well as apply the floor** — there is no quality ordering to
inherit. So: **Also good is emitted only when the
second region is within 0.5 of the top region's `averageRating` and ≥ 3.0 absolute, and is null
otherwise.** Gate on the *average*, never the header's `best 4.0★` (`maxRating`) — that is looser than
today's rule and lets one 3★ outlier carry an otherwise poor region. Lift the existing floor into a
shared predicate the window mapping calls, so the two paths cannot drift.

**The gloss is nullable by design, and the mapping must handle it.** The serve path drops it when
read-time re-enrichment moves a region across a verdict band (`BriefingService.java:738-740`), it is
never generated for an all-hard-constrained region (`BriefingGlossService.java:203`), and the whole pass
is skipped when `succeeded == 0` (`BriefingService.java:494-495`); `generateGlosses` has one call site,
the build path. When the top region's gloss is null the mapping emits **no Best Bet** for that window
and the card omits the block, leading with verdict, rating, badges and the ranked spot strip. Never
substitute the region `summary` — it is a roster count ("clear at 3 of 7 locations"), banned by §6 — and
never regenerate at serve time, which is a Claude call per region per request.

### 2.4 Per-window tide rollup

Pieces exist across `BriefingSlot.tide` (state, aligned, nearest HW) and `TideRunDay` (curve points,
range, range-vs-mean, formatted clock times), but `TideRunDay` only exists when a spring-tide topic
fires. A per-window rollup is new derivation: state + direction, nearest HW/LW **and its offset from
the window**, range, range-vs-average, sea state, sparkline points, **and the coastal location it
describes**.

**Derive it at serve time, inside P1's window projection. Do not build a carrier.** An earlier draft of
this plan mandated the in-memory `AtomicReference` pattern used by `NlcClarityService` /
`MeteorClarityService` / `SurgeCurveService`. That was a misapplied import, and the correction matters
because it deletes a phase's worth of plumbing:

- The overwrite hazard is specific to `HotTopic`. `getCachedBriefing` recomputes topics live and rebuilds
  from them, and its equality shortcut compares **only** `auroraTonight`, `auroraTomorrow` and
  `hotTopics` (`BriefingService.java:267-269`); the rebuild passes `cached.days()` straight through
  (`:281`). A field hung off the **window** therefore rides `days` safely.
- The precedent is already in this codebase: `BriefingRegion.confidence` is computed at serve time in
  `enrichWithCachedScores` and rides the `daily_briefing_cache` JSON with no migration.
- Every field listed above comes from `tide_extreme`, which `TideRunBuilder.java:131-132` frames as "a
  DB-only read — never an API call, so this is safe to call from a hot-topic strategy". Sea state comes from `marine_wave` (V123), which was given a table
  precisely so refresh-time-fetched marine data survives a restart.

Two rules that **do** still bind:

1. **Series must be `List<Double>`, never `double[]`.** The rollup sits inside records compared by
   `equals`; a component compared by identity makes the cache-equality check false on every request and
   rebuilds the whole response each time.
2. **Never synthesise.** No resolvable representative location, or no extremes → no rollup, and the row
   falls back to `BriefingSlot.tide`'s per-location fact line.

**The rollup names its representative coastal location.** The row asserts `HW 19:28 · 1h43 before
sunset` and `4.9 m · 1.2 m above an average tide` over a set its own action line calls "61 coastal
locations". Alignment differs ~20–30 min across a coastline and the design states offsets to the minute
— enough to flip the sign of the one claim a coastal photographer acts on. This project already argued
itself into the answer for tide runs: one representative for the whole run — the configured
`photocast.tide-run.preferred-anchor` when it is in the run and drawable, otherwise the biggest
single-day range — **named** in the footer. The anchor exists *because* biggest-range is not neutral
across a coastline: `TideRunBuilder:238-247` records a 53-location roster running Bamburgh to
Bridlington anchoring every spring run to a cove on Flamborough Head, ~90 miles from the reader. So
reuse `selectRepresentative` (`:261-284`, calling `findAnchor` at `:263`) rather than reimplementing the
rule — both are `private`, so P2 extracts them. It is already a hard precondition there (`:183` returns
`Map.of()` when none resolves). Render the name in the dim fact span the row already has (`4.9 m · 1.2 m above an average tide · at Whitby`), so no grid column changes.
One test pins the fail-soft.

### 2.5 Reach lens

CloseToHome gates by `localRadiusMiles` with a client chip cycling 15/30/45 min. The design wants
45 min / 1h 30 / 2h 30 / Any as a **time** lens over the whole roster. Same primitive, wider gate —
fed by §2.2's endpoint. The default reach is a pure function of the date (weekend vs weekday), so it
needs no storage, no column and no owner.

Two rules make the lens honest when the per-user data is absent — which is the **normal first-run
state**, not a failure (`CloseToHomeService.java:147-148` already says so in a comment):

1. **A lens is not a gate when it has no data.** A location with no drive time is *unknown, not out of
   reach*: it passes every tier and renders without its drive line. The lens becomes a visible no-op
   rather than silently emptying the page.
2. **Counts count spots, not drive times.** With no reach data the header reads "23 spots", not "23
   within reach" — the word *reach* drops when nothing was gated.

Do not add a `{homeSet:false}` flag or a 204: `GET /api/user/settings` already returns `homePostcode`
and `driveTimesCalculatedAt`, and a second source of truth can disagree with the first. Do not suppress
the lens bar — that makes a `position: sticky` element appear per-user. The prompt goes in the rail
footer slot the design already reserves: `Home · <place>` reads "Home not set", and "Edit reach" opens
`UserSettingsModal`.

### 2.6 Promoted strip

A *coincidence* — two attributes landing on the same window — earns the full-width strip. **At most
one strip; highest rarity wins; enforced in code, not by convention.** The rarity rank is a shared
backend derivation, so it belongs in P1 alongside the badges. For the pilot, rarity is a **fixed ordinal
over topic kinds** — not the mock's computed "first coincidence since 2 Mar", which is an unscheduled
historical scan and sits against §6's own ban on counts of our own data.

### 2.7 Confidence

The Plan screen's confidence channel must survive the re-parenting. Two of its four current render
sites go behind the regional-planner door and `BriefingSummaryStrip` is replaced outright, so without
this the new card has none. The failure case is not the horizon (the rail is four days and
`ConfidenceDeriver.MEDIUM_HORIZON_MAX_DAYS = 3`) but the spread path: since
`V137__region_groupings_regain_statistical_weight.sql` the smallest voting roster is 33, so
`ratingRange >= 2` is the live downgrade and MEDIUM + wide spread → **LOW** routinely on T+2 and T+3.
Those are windows 3–6 of the six P9 builds, and their badge would otherwise be pixel-identical to
tonight's.

**One field, one render site.** P1's window projection carries a nullable `confidence`, derived by the
existing `ConfidenceDeriver` from the window's **Best Bet region** — the region the card already names,
so badge and narrative describe the same thing. Render it as **fill decay on the window card's verdict
badge**: `.bdg.good` / `.bdg.verd` are already `rgba()` literals, which is exactly what
`scaleRgbaAlpha` consumes, so `scaleRgbaAlpha(fill, confidenceTreatment(tier).fillScale)` gives the
1.0 / 0.72 / 0.5 gradient with no new glyph. Leave the hex text colour unscaled, as `HeatmapGrid` does.

No marker glyph — the badge already carries `◎`, and a second hollow circle is noise §6 bans. No second
render site: not the rail, not the drilldown header. Marking the same fact twice breaks "one uniform
channel" as surely as omitting it.

---

## 3. "Coming up" — the 90-day almanac feed

`HotTopicAggregator` runs `today .. today+3` (`BriefingService:264`, `:550`). Widening it is not enough,
and the reason is worth stating precisely, because the mock does not.

**Ephemeris-driven strategies run 90 days out unchanged.** Spring/king tide *dates* are also free —
`LunarPhaseService.classifyTide(LocalDate)` is pure arithmetic over two reference epochs, with no DB
read and no horizon, already injected into `MeteorHotTopicStrategy` and `SupermoonHotTopicStrategy`.

**What is not free past T+13 is any height, range, clock time or solar-alignment claim.** Those need
stored extremes, and `tide_extreme` carries T+0..T+13 forward (`TideService.java:53-54` —
`FETCH_LENGTH_SECONDS = 14L * 24 * 3600`; `:198` logs "T+0 to T+13"), decaying to ~T+7 by the weekend
under the Monday cron. The 12-month backfill supplies the **threshold** (`SPRING_TIDE_FACTOR` × average
high, P95 for king — `TideService:517-537`), not forward coverage: it writes strictly into the past
(`:234-237` — `today.minusMonths(12)` … `chunkStart.isBefore(today)`).

**Degrade rule.** Beyond the tide fetch window a Coming-up row states the date and the run position and
nothing numeric. No metre figure and no alignment verdict is ever rendered for a date with no stored
extremes. Never synthesise. The handoff mock's "16–18 Aug · Spring tide run 3/3 · 4.6 m range ·
alignment falls on sunrise" — dated 1 Aug — is exactly the row this rule governs.

**Or extend the horizon.** `refreshTideExtremes` sends `length` as a query parameter, one request per
coastal location per week (`ScheduledForecastService.java:74-92`, `TideService.java:141-150`), so a
longer horizon changes response size, not request count. Raising `FETCH_LENGTH_SECONDS` to ~97 days
(90 + a week's slack, so the window still reaches T+90 the day before the next Monday refresh) is
viable subject to WorldTides' per-response length limit and billing model. Decide at P12; the windowed
delete at `:187-190` scales off the same constant and needs no change. Do not do neither.

**The feed needs its own detection path.** `SpringTideHotTopicStrategy.java:92` reads
`briefingService.getCachedDays()`, capped by `BRIEFING_WINDOW_DAYS = 5`, for consistency with the
heatmap. A 90-day feed has no heatmap to agree with, so it runs its own **two-source** detection:
`LunarPhaseService.classifyTide(LocalDate)` identifies which dates across the whole 90 days are
spring/king, unbounded; within the tide fetch window those dates are then enriched from `tide_extreme`
against the per-location threshold (range, heights, clock times, alignment). Beyond the window the
lunar date stands alone under the degrade rule above. The range plumbing already exists:
`HotTopicStrategy.detect(from, to)` and `HotTopicAggregator.getHotTopics(from, to)` both take arbitrary
ranges.

**Eligible (Almanac):** spring/king tide, meteor showers, NLC season bounds, supermoon,
equinox/solstice. **Not eligible (Forecast):** aurora, inversion, snow, dust, bluebell, storm surge —
these stay on the existing ~3-day path and appear in Coming up only for the days the aggregator covers.

Shape: `AlmanacEvent {startDate, endDate, kind ALMANAC|FORECAST, title, detail, meta, regions}`, served
from `GET /api/almanac?days=90`, **Bearer, no role gate** (it is almanac data already visible in the
forecast UI — state it explicitly rather than leaving it to be inferred). No Claude call, so it is
cheap; cache daily. The tab's tag vocabulary is exactly two words — **Almanac** (fixed) and **Forecast**
(firms up ~3 days ahead).

**The rule that keeps Plan small:** seasons live in Coming up permanently; Plan only ever carries
tonight's *delta*. If that split erodes, Plan re-grows to 2,600px.

---

## 4. Flag mechanism

A localStorage key (`photocast.planLayout`, `'v1' | 'v2'`) via `useLocalStorageState`, toggled from
`UserSettingsModal`. No backend change, no build-time env var — the five pilot users need to be able to
switch back without a deploy, and you need to compare both views against the *same* night's data.

The branch sits at App level, and **the v2 subtree owns its own shell, tab state and single
`/api/briefing` fetch** in a provider it owns — the `AuroraStatusContext` shape. So `ViewToggle` and
`DailyBriefing` are never touched, and the design's tabs (a different control from the segmented
toggle) do not have to be reconciled with the existing one.

Default stays `v1` until the §6 sweep passes, then flips. Bump the key if its schema changes, so a
stale value cannot resurrect under a new meaning.

---

## 5. Build order

Backend first for anything shared, so the frontend stays a render layer.

| Phase | Work | Notes |
|---|---|---|
| **P0** | Handoff into `docs/design/window-first/`; tokens (incl. the `--panel` decision); Mono 600; flag scaffold | Handoff copied **verbatim**. The five demo buttons and the annotation cards stay in the reference — they show the states (six solar events, a winter day, an away week) August cannot produce — and are stripped per screen as each is built. §6 is the enforcement; nothing under `docs/` reaches the build |
| **P1** | Backend: window projection — verdict, best rating, nullable `confidence` (§2.7), **region ranking by `averageRating` desc** (nothing upstream orders regions), Best Bet / Also good from region gloss with the ≥3.0 + within-0.5 floor, badges **and promoted-strip rarity rank** from bucketed topics | Assembled at **serve time**, like `enrichWithCachedScores`; rides `days`, no carrier. Test: a window whose top-region gloss was nulled by `verdictChanged` projects with no Best Bet, the rest intact |
| **P2** | Backend: tide rollup derivation — `TideExtremeRepository` extraction, interpolation, `selectRepresentative`, `marine_wave` sea state (§2.4) | Part of P1's serve-time assembly. `List<Double>`, never `double[]`; no representative → no rollup |
| **P3** | Backend: `GET /api/user/settings/reach` (§2.2) | Whole roster, not radius-gated |
| **P4** | Shell — masthead, **inert** day rail, tabs. **Moves the flag branch out of `<main>`** and suppresses the app header for v2 | `border-bottom-width: 0`, never `border-bottom: none`. P0 left the branch inside `<main className="max-w-4xl">` — 896px, ~200px under the design's 1080px frame, and below the app's own `<header>`. Both must move together, since the design's masthead carries its own ⚙ and Sign out. Open at that point: the design separates frame from page with `body{background:#0e0b09}`, which §1 rules out as a product token, while this app's page is already `--plex-bg` |
| **P5** | Window card — header, verdict badges + confidence fill decay, Best Bet, footer | Verdict colours identical everywhere they appear; the score-bar `width` transition gets a `prefers-reduced-motion` clause |
| **P6** | Spot film strip | **Copy** `CloseToHome`'s filmstrip machinery into a new shared component; `CloseToHome` is not rewired. **Geometry from the spec, not `.cth-window-grid`** — see below. No `ScrollRail`. One shared comparator (rating desc, then drive asc); footer states what is *drawn* |
| **P7** | Attribute rows — tide, then snow | Cap at two per window; anything further folds into a badge |
| **P7b** | Promoted strip — both mocked variants; chart as a 42px curve with the 16px label band **below** it, never overlapping | Single-strip cap enforced in code. After P7, because the cap is what decides whether a second attribute stays a row or folds into a badge |
| **P8** | Lens bar + reach filtering + persistence policy | Reach expires at the day roll; rating floor persists; type never persists |
| **P9** | Collapse/expand, six-window case, away-day row, **the two doors** | Verify ~1,500px with six windows, two open. Door sub-lines need no endpoint: "4 regions" from the briefing payload, "3 live" from P1's bucketed topics; `Modal` is already on the reuse list |
| **P10** | Peek + click-to-map | New `WindowSpotPeek` — see below |
| **P11** | Drilldown sheet | Same cards, same comparator; type filter resets on close |
| **P12** | Backend: almanac feed (§3) | Includes the fetch-horizon decision and its fallout — see below |
| **P13** | Coming up tab | |
| **P14** | Responsive pass — real media queries | Keep control labels at 9px; never hide them |
| **P15** | Pre-pilot sweep (§6), then flip the flag default | |

**P6 — copy, don't extract, and take the geometry from the spec.** An extraction would rewire
`CloseToHome` to consume it, which breaks the one thing §4 rests on: the v1 arm of the flag comparison
staying byte-identical while both views are judged against the same night's data. So copy the machinery
into a new shared component and leave `CloseToHome` alone — P10 applies the same rule to the peek.
Reconverge the two copies only after the flag default flips.

`.cth-window-grid` disagrees with the design
at every breakpoint that matters: `scroll-snap-type: x mandatory` vs the spec's `proximity`;
`flex-basis: calc((100% - 3*8px)/4.5)` vs `flex: 0 0 calc((100% - 24px)/3.5)`; `flex: 0 0 100%` below
900px vs `72%`. So: 14px gutter on the `.strip` **wrapper** (which needs `position: relative` anyway for
the edge-fade insets), no horizontal padding on the scroller, `proximity`, 3.5 across desktop and 72% on
phone. Reuse the hidden-scrollbar and snap-padding *technique*, not the numbers. `ScrollRail` stays out:
the design's affordance set is native scroll, edge fades, `‹ ›` and the "5 of 7 loaded" readout, and
`index.css:888` justifies the rail as "the only handle a mouse user has" — a premise that is false here.

**P10 — the peek is a new component.** `CardHoverPreview` is `aria-hidden` under an explicitly
conditional licence ("the card stays a button — the peek is a shortcut, never the only route"), and its
Javadoc lists the score bars, header bar and footer as *deliberately removed*. The spec restores all
three. So: new `WindowSpotPeek`, reusing `previewPlacement`, the fixed-position escape hatch and the
open/hold/dismiss timers, leaving `CardHoverPreview` and `CloseToHome` untouched so the v1 arm of the
flag comparison stays as it is. **On touch the card itself stays the map activation** — the spec's
"first tap opens the peek" would otherwise make an `aria-hidden`, pointer-only panel the sole route to
the spot-centred deep link. The full-width phone peek renders through `BottomSheet` (`role="dialog"`,
`aria-modal`, focusable close), never `CardHoverPreview`. Timings: 140ms open, 160ms strip-leave, 120ms
panel-leave.

**P12 — the tide fetch horizon and its fallout.** If `FETCH_LENGTH_SECONDS` is extended (§3):
`TideServiceTest.java:365-367` pins the window at 13–14 days; "14 days" / "T+0 to T+13" prose lives at
`TideService.java:96, :111, :198`, `ScheduledForecastService.java:69` and
`docs/pipeline-reference.html:1471`; and the `scheduler_job_config` description seeded by
`V68__scheduler_job_config.sql:22` can only be corrected by an UPDATE migration. Either accept it as
stale deliberately or write the migration — and if the latter, the "none expected" below stops being
true.

**Migrations: none expected**, since everything rides existing tables and payloads — with the P12
exception above. If one is needed, read the latest number off the tree on **main** —
`ls backend/src/main/resources/db/migration/ | sort -V | tail -1` — never from a written-down number,
including this one.

Read `docs/engineering/test-improvement-standards.md` before writing any test class. CI's gates
(JaCoCo 80% per class, SpotBugs, Checkstyle, the Testcontainers integration classes, and the
exit-code-not-grep rule) are documented in CLAUDE.md and are not restated here.

---

## 6. Pre-pilot sweep

- No demo buttons, no annotation cards anywhere in the shipped build.
- Signal copy is **stateless** — deltas computed from data alone ("clearest in 11 nights", "biggest
  tides of the month"). No per-user exposure counter: every pilot user must see the same page on the
  same night so bug reports reproduce, and silence must never mean "you have been told before".
- **The promoted strip renders when a coincidence exists, and never more than one.** Stated this way
  deliberately: "at most one" passes vacuously on a page that never built the strip at all.
- No invented vocabulary. No counts of our own data ("11 aligned" is a fact about the database, not
  about tonight). No jargon needing a glossary.
- Verdict colours consistent in every location — rail, badge, pill, drilldown. Confidence decay
  appears on the window card's verdict badge and **nowhere else** (§2.7) — check that it is absent from
  the rail, the drilldown header and the spot strip, not merely consistent with them.
- Every footer's claimed sort and count matches what is rendered.
- The page is coherent for a user with **no home postcode** — no control gates on data that does not
  exist, and no count describes a set that was never filtered.
- **No control whose only visible effect is an `aria-hidden` panel.**
- Usage is measured by conversation with the five pilot users and by the operator's own 05:00 use; **no
  telemetry is built for the pilot.** If click data is genuinely wanted instead, it is a device-local
  `track(name)` counter in one namespaced localStorage key, declared once in P0 so later phases call it
  as they are built — no endpoint, no table, no migration, no consent decision.

**Ongoing rule:** before adding anything to the Plan tab, read `Adversarial Review.html` in the
handoff. New elements earn their place against that list, and something should usually come out when
something goes in.

---

## 7. Deviations from the spec, and why

- **Status pill.** The design shows "● UP v2.17.7" unconditionally in the masthead. `HealthIndicator`
  is admin-only today and stays that way — build version and service health are not a pilot user's
  business. The rail footer's "forecast 52m ago **by Sonnet**" is the same call: the model name is
  admin-only today (`DailyBriefing.jsx:1294`) and is dropped or gated, leaving the age.
- **The tide rollup names its representative location**, which the spec's data contract does not carry.
  Alignment differs ~20–30 min across a coastline and the row states offsets to the minute, so an
  unattributed HW time is a claim we cannot make.
- **The peek restores the two score bars** that `CardHoverPreview` deliberately dropped. Reconciliation:
  in the window-first view the peek is the only route to the scores, and it fires off a strip the user
  is already scanning, so the weight is earned where it was not before. Tripwire: if the pilot reports
  the panel feeling like a glitch, the bars are the first thing back out.
- **On touch the card activates the map**, not the peek. The spec's phone flow would make an
  `aria-hidden` panel the sole route to the spot-centred deep link.
- **Role gating.** The lens bar is a PRO control and takes CLAUDE.md's LITE treatment (`opacity: 0.45`,
  `pointer-events: none`, "Pro" pill). Best Bet and the peek need no new gating — `glossHeadline` is
  ungated today and `freemium_ui_strategy.md:79-80` lists scores and the Claude summary as
  LITE-included. Open question for P7: `HotTopicStrip` blurs tide metres for LITE today, and the tide
  row states them — decide which way that goes rather than letting the two surfaces disagree.
- **Styling.** CLAUDE.md says Tailwind only, no inline styles; the spec is written in exact px
  (12.5 / 10.5 / 9.5). Follow the existing precedent — `index.css` component classes plus tokens, with
  inline style for one-off exact values, which is what `CloseToHome`, `TideRunRow` and `ViewToggle`
  already do. Do not introduce a third way.
