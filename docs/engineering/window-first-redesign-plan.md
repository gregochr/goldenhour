# Window-first Plan tab — implementation plan

**Status:** agreed 2026-08-01. **P0 merged** (`aedaa5f4`) and **P1 merged** (`1d293912`).
A **second handoff arrived 2026-08-02** and reverses part of P1 — see §2.3 and §5. P1′ is next.
**Reviewed:** adversarially throughout — the plan (2 rounds, 29 charges, 16 upheld), P0 (2 rounds,
25 charges, 7 upheld), P1 (2 rounds, 30 + 21 charges, 11 upheld), and the second handoff (2 rounds,
42 + 14 findings). Every upheld finding is applied below; where one reversed an earlier decision, the
reversal and its reason are recorded rather than silently overwritten. Each phase gets the same
treatment before it lands — see CLAUDE.md § UI Work — Review Cadence.
**Spec:** `docs/design/window-first/`. ⚠️ **`DELTA.md` IS NOT VENDORED — do not go looking for it.**
It has never existed in this repo on any branch, and neither have `Change Since Last Run.html` or
`spec-plan-picks/`; only the nine shared files of the second bundle landed, `Plan Window First
v2.html` among them. Earlier revisions of this header told the reader to read DELTA.md first, which
sent them after a file that was never there.

**§2.8 and §2.3's rule box are the record of the second handoff**, and they win over anything in the
bundle. That matters because the bundle is vendored *verbatim* — so the next re-cut stays a diffable
copy — which means its `README.md` still describes the **superseded** per-window pick model at
`:118` and `:210`, with nothing beside it saying so. `Plan Window First v2.html` is the design of
record for layout; where it and this plan disagree, this plan wins.

If the second handoff's three missing files are ever recovered, vendor them **unedited** and delete
this note — a re-cut is only worth having if it is a diff.

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
  ⚠️ **P0 added it to the *plain* `@theme` block, and it was pruned to the empty string.** The
  comment beside it claimed a utility class kept it alive; nothing in the tree referenced it, so
  every card reaching for it would have rendered with no background at all and shown the frame
  through. Found by reading `getPropertyValue('--color-plex-panel')` off the running app at P4c —
  a build, a lint and 2,000 green tests all passed over it. It now lives in `@theme static` with
  the rest of the window-first palette. **The general rule: a window-first token belongs in the
  static block until it has several consumers, because "one component happens to use it" is not a
  guarantee — the next refactor to drop that one class prunes the token and nothing fails loudly.**
- **IBM Plex Mono 600** in `frontend/src/fonts.js` — the design's kickers are mono/600 and only 400/500
  are bundled, so the browser is currently synthesising it.

Do **not** mechanically diff the design's `:root` against `@theme`: that table also lists
`page: #0e0b09`, which is the mock's device-frame backdrop, not a product token.

**Several of the README's Known Traps are already solved here.** Reuse rather than re-derive — but
reuse the *mechanism*, and take every geometry constant from the spec:

| Trap | Solved in |
|---|---|
| Snapping scroller eats the horizontal gutter | The padding + negative-margin + `scroll-padding` technique in `.cth-window-grid`. **The numbers there are wrong for this design** — see P6. |
| Peek node destroyed on re-render | `CloseToHome` renders **one** peek node at the panel root, sibling to every window block, driven from panel-level state (`CloseToHome.jsx:805`, `:1215`). *That* is the lifetime guarantee. `position: fixed` is a separate concern and only stops a scroller's `overflow` clipping it. ⚠️ **The citation here was wrong** — `index.css:459-467` is the tide-run footer. The peek's own rule is `.cth-hover-preview` at **`index.css:580`**, `position: fixed` at **`:581`**, `z-index: 1200` at **`:582`**, and the reasoning that licenses all three at **`:571-579`**. Corrected at P10′, which is the phase that came here looking for exactly this. Note the React-specific trap the README omits: a `transform`, `filter` or `will-change` on any ancestor re-bases `position: fixed` onto that ancestor. |
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

### 2.3 Best Bet and "Also good" — ~~per window~~ **forecast-wide**

> ⚠️ **Superseded by the second handoff (DELTA §3), 2026-08-02.** Everything below the rule box was
> written for a per-window pick and is kept because its *reasoning* still governs the replacement —
> the gloss-usability rule, the quality floor and the degrade paths are unchanged. What changed is
> **scope**, and only scope.
>
> **The rule now:** the picks rank **every rendered window in the forecast against each other** and
> name two — one Best Bet, one Also good — each bound to the window it falls on. They may fall on
> different days. There is no per-window narrative block, and the window card carries no prose;
> the two chosen windows get a header badge instead, and the picks surface as chips on the day tile.
>
> **The old argument was valid but conditional, not wrong.** It ran: a cross-day "also good" is
> redundant *because every window already carries its own Best Bet*. DELTA §3 withdraws that premise,
> so the conclusion lapses. It was not refuted, and it should not be re-derived from scratch.
>
> **Rank over the RENDERED window set, not the whole payload.** The briefing carries five days; the
> rail renders four (`DailyBriefing.jsx` `STRIP_MAX_DAYS = 4`). An unscoped fold can select a pick
> that lands on no tile — the precise failure this note exists to prevent.
>
> **Do not route this through `bestBets`.** It looks like the obvious vehicle — it is already a
> global rank-1/rank-2 list — and it is wrong on four counts: it is build-time Claude output with a
> stale-fallback path that can be 30h old; its `event` can be `aurora_tonight` or null for a
> stay-home pick, neither of which lands on a window; it runs *before* `BriefingHonestyFilter`; and
> it is PRO-gated in the UI today, which would make the pilot's headline feature PRO-only against §7.
> Implement it as a selection pass over P1's existing per-window picks: same comparator, same floor,
> one extra fold, keeping the serve-time-after-the-filter guarantee a test already pins.


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

**The `selectRepresentative` extraction is bigger than two private methods.** `findAnchor` drags
the `photocast.tide-run.preferred-anchor` config, an exact-then-tolerant matching pass,
`normalise`/`matching`, `LogSanitizer`, and per-instance **warn-once** state
(`AtomicReference<String> warnedAnchor`). Sharing one instance between the tide run and the window
rollup changes that semantic — a misconfigured anchor would warn for whichever caller ran first and
stay silent for the other. Extract the *selection* (anchor-first, biggest-range fallback) and leave
each caller its own warn state, or accept the shared warning deliberately and say so in the class.
⚠️ `TideRunBuilder` has taken three merges recently (#396, #397, #402); read it before editing.

**Keep the projector dependency-free.** `PlanWindowProjector` is a static utility and P1′b is the
argument for keeping it that way. Tide data needs repositories, so compute the rollups in a
`@Component` and pass them in as a map, exactly as the badges are — never inject a repository into
the projector.

**Both inputs verified against production, 2026-08-03** — the check P1′b skipped, and the reason
this phase is safe to build:

| input | coverage | freshness | shape |
|---|---|---|---|
| `tide_extreme` | to **T+13** (2026-08-16), 61 locations | fetched **today** | ~4 extremes per location per day, no gaps |
| `marine_wave` | to **T+4** (2026-08-07) only | evaluated today | per (location, date, event) |

*(The tide row is a measurement taken 2026-08-03 and is left as it was recorded. P12 raised the fetch
horizon to 97 days, so a re-measurement today would read T+96.)*

⚠️ **Sea state is independently nullable.** Tides reach months ahead; waves reach T+4, exactly the
briefing window and no further. A window beyond T+4 has a full tide rollup and no sea state, so the
field must degrade on its own rather than being assumed present whenever the rollup exists. Do not let
a missing sea state suppress the rollup. **P12 widened this gap from nine days to eighty-three** — a
window with a tide rollup and no sea state is now the ordinary case rather than the far-end one, which
raises the stakes on that degrade path without changing its logic.

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

### 2.4a What P2 actually decided — read before P7 renders the row

Three of the nine upheld review findings changed behaviour rather than wording. Recorded here so P7
does not re-derive them from the row's appearance.

- **The row's two numbers measure different things, deliberately.** `range` is the day's biggest
  swing (`max(highs) − min(lows)`); `rangeAnomaly` compares the day's *mean* range against the
  location's mean-based climatology. Subtracting a mean baseline from an extreme is biased upward by
  the day's own diurnal inequality — +0.25 m on the fixture coast, five times the 0.05 m display
  threshold — and because this row renders on **every** window of every day, that bias is the normal
  case: "about average" becomes unreachable and every window claims an above-average tide. Do not
  "simplify" the two into one statistic. ⚠️ `TideRunBuilder` and `CoastalTideFactsBuilder` still use
  the biased extreme-minus-mean form. Left alone on purpose: they fire only on spring and king days,
  where the day genuinely is above average and the bias is swamped. Unifying them is a change to a
  shipped surface and belongs with whatever next touches the tide-run pill.
- **The tide state is classified at the per-slot facts' own window, not a fixed hour.**
  `TideFactDeriver.tightAlignmentWindowMinutes` — half the blue+golden span for that location, date
  and event — is ~41 minutes at 54.5°N around the equinox. Under a fixed ±60 a high water 50 minutes
  off sunset read HIGH in the row and MID in the drill-down slot directly beneath it. `TideService`'s
  3-arg `classifyTideState` is the single rule and the width is the caller's to choose; the 2-arg
  ±60 overload has **no production caller** and is test-only.
- **The window projection is behind its own accessor.** `getCachedBriefingForApi` projects windows
  and derives the tide; `getServedBriefing` is the same enriched, filtered payload without them, and
  is what `CloseToHomeService` reads. They differ by the projection alone — which only *attaches* a
  window and rewrites nothing a panel reads — so the shared-snapshot guarantee of §2.2 is intact.
  Anything rendering windows must use the former, or the Plan tab silently loses its verdict badges,
  its picks and its tide rows.

Also settled, and not worth re-opening: the **payload is renderable as pure geometry** —
`x = i/(n−1)·104`, `y = (1−curve[i])·24`, the mark at `windowPosition·104` / `(1−windowLevel)·24` —
so no backend rule leaks into the client. The design's action line ("61 coastal locations →") and
its `≈ Tide · biggest` kicker are **not** in the payload: the first is a count of our own data (§6)
and the second needs a month-wide scan §2.4 never asked for. Both are P7's call, not omissions.

### 2.5 Reach lens

CloseToHome gates by `localRadiusMiles` with a client chip cycling 15/30/45 min. The design wants
45 min / 1h 30 / 2h 30 / Any as a **time** lens over the whole roster. Same primitive, wider gate —
fed by §2.2's endpoint. The default reach is a pure function of the date (weekend vs weekday), so it
needs no storage, no column and no owner.

Two rules make the lens honest when the per-user data is absent — which is the **normal first-run
state**, not a failure (`CloseToHomeService.java:156` already says so in a comment):

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

**The second handoff makes that concrete.** The new rail tile is copied from `BriefingSummaryStrip`,
which renders a `ProvisionalMark` conditional at `:160-161`. **Delete it in the copy, and do not
carry over its two tests** (`BriefingSummaryStrip.test.jsx:44`, `:49`). The window card's verdict
badge remains the single render site. Note the channel survives the handoff intact — DELTA §3 removes
the window card's *narrative*, not its verdict badge, so the field P1 shipped still has its home.

---

### 2.8 Decisions taken on the second handoff — do not re-litigate

Each of these was argued and settled on 2026-08-02. Recorded with the reason so a later phase does
not reopen it from the handoff text alone.

- **Stable region ids: DECLINED.** DELTA §2 and `spec-plan-picks/README.md` both ask for them
  ("resolve each pick to a stable region id… do **not** hard-code names in production"). The failure
  they guard against cannot occur here: `BriefingRollupBuilder:159` builds `validRegions` from
  `region.regionName()` and `BestBetPickValidator:85` rejects any pick whose region is not in that
  set, so a served pick's region string is byte-identical to the rendered region's by construction.
  `shortRegionName` is applied *after* matching, for display only. Costing was also wrong: the other
  side of any id match is Claude-authored, so ids would mean a name→id resolution server-side, and
  the one path where names could drift — the stale fallback replaying `pipeline_run_pick.region` —
  needs a new column, i.e. a migration. The shipped feature deliberately matches on name.
- **`--blue #7C8DD6`: NOT a new token.** It ships as `--color-pick-also` (`index.css:53`), and the
  whole chip treatment ships with it — `data-pick` accents at `index.css:785`, `:790`, `:795`, `:796`, the `◎` `rn-mark`,
  both 0.6-alpha underlines, `brightness(1.14)`. Reuse them; author nothing. Under Tailwind v4
  `@theme static` a duplicate token survives review quietly, which is why this is written down.
- **Run-history sparkline: DEFERRED to P16 — and the strongest reason is how little of it would
  render.** Measured against production on 2026-08-03, over 16,416 slots in `forecast_evaluation`:

  ⚠️ **Count RATED rows, not rows.** Three quarters of `forecast_evaluation` rows are triage rows
  carrying a null rating. Counting rows overstates both features by four to five times, and the
  first pass at these numbers made exactly that mistake.

  | rated runs per slot | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 9 |
  |---|---|---|---|---|---|---|---|---|---|
  | slots | **12,319** | 2,561 | 576 | 291 | 332 | 170 | 111 | 55 | 1 |

  `runBars()` renders nothing below two points, so **90.6% of spots would show no sparkline at
  all**, and only **4.1% have the four rated runs the design draws**. That is not a feature waiting
  on a query; it is a feature with almost no input. Defer it, and revisit only if the evaluation
  rate changes.

  **The data was never the obstacle.**
  `forecast_evaluation` is **insert-only and never pruned** (V128's own comment), and V128 indexes
  `(location_id, target_date, target_type, forecast_run_at)` — exactly the key for "the last N runs
  for this slot". `findByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtAsc` already exists
  "to plot forecast convergence over time". What is missing is a last-N-per-slot read instead of the
  latest-1 (`findLatestRunPerSlotByLocationIds` takes `MAX(...)`) and a nullable `List<Integer>` on
  the projection — `List`, never `int[]`, per §2.4's identity rule. It is deferred because **the
  design bundle contradicts itself**: `Plan Window First v2.html:400` shows it shipped, `:402` says
  it "waits until after the pilot", and `Change Since Last Run.html` says hold it back. `runBars()`
  returns empty without history, so a pilot build without it is spec-conformant. Ask the designer.
- ⛔ **The change-since-last-forecast row is DEFERRED with the sparkline. Nothing the live pipeline
  writes retains a per-location history.** This was built (P1′b) and dropped on 2026-08-03 after the
  data was traced properly. Three stores hold a per-location rating and none of them can answer
  "what was it last time":

  | store | per-location | per-run history | who writes it | who reads it |
  |---|---|---|---|---|
  | briefing evaluation cache (`cached_evaluation`) | yes — this is what the card shows | **no**, overwritten per run | `BriefingEvaluationService.writeFromBatch` | `BriefingService.enrichSlot`, keyed by location **name** |
  | `forecast_score` (V108) | yes | **no** — `uq_forecast_score_component` is UNIQUE on (type, location, date, event); latest evaluation wins, by design | the batch dual-write in `ForecastResultHandler` | Pass-2 consumers |
  | `forecast_evaluation` | yes | **yes**, insert-only — but it is the **other engine** | the synchronous path | `GET /api/forecast` (map), `EvaluationViewService`, the calibration gate |

  The first attempt sourced the prior from `forecast_evaluation` because it is the one store that
  keeps history. That is comparing **two different engines' outputs and calling the difference
  movement**: the displayed rating comes from the batch cache, the prior would have come from the
  synchronous table. Three quarters of that table's rows also carry a null rating, so the row would
  have under-reported even within its own lineage.

  **Both features need an append-only per-run sink that the live pipeline writes.** That is a schema
  change and a pipeline change, not a query — so it is out of scope here and belongs with whatever
  work consolidates the two engines. Earlier notes in this section quoted 49.6% and 9.4% for the
  change row's input; both measured the legacy table and describe neither engine's live behaviour.
  Ignore them.

- **"updated 52m ago" needs no backend work.** `DailyBriefingResponse.generatedAt` is already on the
  wire; format it client-side. A server-rendered relative string would mutate the ETagged body on
  every request, and `/api/briefing` sits in `HttpCachingConfig`'s revalidatable set. Only
  "next 12:00" needs anything new, and that is the scheduler's next fire time.
- **A skipped slot reads as *held*, never as moved.** `OptimisationSkipEvaluator` (SKIP_LOW_RATED,
  SKIP_EXISTING) and the freshness thresholds mean a slot can carry an unchanged rating purely
  because it was not re-evaluated. "Since last forecast" is a window-level claim over slots that are
  not all re-evaluated on the same cycle.
- **A departure is claimed only when the location has no current evaluation for that window.** Never
  for a rank change, and never for a spot that simply was not loaded — the mock filters departures by
  reach while the strip has no rating floor, so the two readings disagree.

---

### 2.9 Two rating scales, two jobs — do not unify them

A rating gets coloured in two different places for two different reasons, and one palette cannot do
both. Say which is which here, so a later tidy-up does not "unify" them and break one.

**The medallion palette — `RATING_COLOURS` (`frontend/src/components/markerUtils.js:23-29`).**
`1 #A32D2D · 2 #D85A30 · 3 #FAC775 · 4 #97C459 · 5 #3B6D11`. Used by the map marker and by the spot
card's **rating badge**. Its job is *status*: the number is printed on the mark, so the colour
reinforces a label that is already there. It is a hue ramp, and correct as one. **Unchanged.**

⚠️ It is **not** a magnitude ramp and must never be used as one. Relative luminance runs
`0.099 · 0.221 · 0.625 · 0.468 · 0.119` — it peaks at 3★ and falls away on both sides, so the best
and worst ratings are the two dimmest. Flipping 4★ and 5★ does not fix it (it becomes a zigzag,
`0.099 · 0.221 · 0.625 · 0.119 · 0.468`), and 1★ and 5★ both sit under 3:1 against the card surface.
Considered and declined 2026-08-02: the flip buys nothing and would churn a shipped, tested palette.

⚠️ DELTA §5's citation for this is **wrong** — there is no `ratingColour()` anywhere in the repo, it
is not in `HeatmapGrid.jsx`, and its five hexes (`#B91C1C … #22C55E`) are a different palette
entirely. Follow its *intent* ("use the medallion scale, do not hard-code green") and take the values
from `markerUtils.js`.

**The run-bar ramp — new, single-hue, for the sparkline only.**

| ★ | hex | luminance | worst-case contrast |
|---|---|---|---|
| 1 | `#736B5F` | 0.150 | 3.08:1 |
| 2 | `#A19789` | 0.315 | 5.63:1 |
| 3 | `#C1B7A6` | 0.480 | 8.17:1 |
| 4 | `#DBD1BE` | 0.644 | 10.70:1 |
| 5 | `#F2E7D3` | 0.807 | 13.22:1 |

Ship as `--color-runbar-1` … `-5` with the other window-first tokens.

- **Why bone.** Every coloured family here already means something — verdicts, tide, nlc, snow,
  close-to-home. CLAUDE.md's rule is that chrome is bone and never colour, so bone is the only family
  that can carry magnitude without asserting a second meaning. Step 5 *is* `--color-plex-text`, so
  the ramp is anchored to an existing token rather than inventing an endpoint.
- **Why brighter = higher, not darker.** The canonical sequential rule is one hue light→dark; on a
  dark surface the perceptual equivalent is that contrast against the surface rises with magnitude.
  Dark mode is *selected*, never an automatic flip.
- **Why solid hexes and not opacity.** Each step is bone pre-composited over the panel and rounded to
  a literal. DELTA §5's "never apply opacity to a bar" is right — opacity composites toward the
  background and corrupts the value the colour encodes — and precomputing removes the runtime
  compositing entirely while keeping the visual family.
- **Derivation, so it can be regenerated if the panel colour changes.** Floor is the alpha at which
  bone clears 3:1 against the *lightest* backdrop a bar can sit on — `rgba(201,162,75,.06)` over
  `--color-plex-panel`, i.e. tonight's lead card, which is the case that breaks a floor tuned to the
  plain panel. The remaining four steps are spaced evenly in **luminance** (0.164–0.165 apart), not
  in alpha; even alpha steps bunch at the top. Every step clears 3:1 on every backdrop.
- Step 5 equals body text. On a 7px bar that is the maximum prominence available and is intended; if
  it ever competes with an adjacent label, lower the top step — do not recolour the scale.

---

## 3. "Coming up" — the 90-day almanac feed

`HotTopicAggregator` runs `today .. today+3` (`BriefingService:264`, `:550`). Widening it is not enough,
and the reason is worth stating precisely, because the mock does not.

**Ephemeris-driven strategies run 90 days out unchanged.** Spring/king tide *dates* are also free —
`LunarPhaseService.classifyTide(LocalDate)` is pure arithmetic over two reference epochs, with no DB
read and no horizon, already injected into `MeteorHotTopicStrategy` and `SupermoonHotTopicStrategy`.

**What is not free past the fetch window is any height, range, clock time or solar-alignment claim.**
Those need stored extremes. ⚠️ **Superseded by P12**, which raised the window to 97 days so the whole
90-day feed carries figures; the degrade rule below still governs anything beyond it, and still
governs every day if a refresh fails. As written, this said `tide_extreme` carries T+0..T+13 forward
(`TideService.java:53-54` — `FETCH_LENGTH_SECONDS = 14L * 24 * 3600`; `:198` logs "T+0 to T+13"),
decaying to ~T+7 by the weekend
under the Monday cron. The 12-month backfill supplies the **threshold** (`SPRING_TIDE_FACTOR` × average
high, P95 for king — `TideService:517-537`), not forward coverage: it writes strictly into the past
(`:234-237` — `today.minusMonths(12)` … `chunkStart.isBefore(today)`).

**Degrade rule.** Beyond the tide fetch window a Coming-up row states the date and the run position and
nothing numeric. No metre figure and no alignment verdict is ever rendered for a date with no stored
extremes. Never synthesise. The handoff mock's "16–18 Aug · Spring tide run 3/3 · 4.6 m range ·
alignment falls on sunrise" — dated 1 Aug — is exactly the row this rule governs.

**Or extend the horizon. ✅ DECIDED AND DONE at P12 — extended to 97 days.** The constant is now
derived in code from `ALMANAC_HORIZON_DAYS = 90` plus `REFRESH_SLACK_DAYS = 7` rather than written as
a literal, so the number cannot drift from the reason it has that value. The windowed delete scales
off the same constant and needed no change, exactly as predicted; its lower bound is pinned to today
independently, so backfilled history was never at risk.

⚠️ **Two things this paragraph used to say were wrong, and one of them was the argument for the
change.** They are corrected rather than deleted, because both are the kind of claim a later reader
would otherwise re-derive the same wrong way.

- **"A longer horizon changes response size, not request count" — false.** WorldTides bills
  `extremes` at **one credit per seven days of data**, not one per request, so the horizon sets the
  recurring cost: `ceil(97/7) = 14` credits per coastal location per week against the previous 2.
  Across 61 coastal locations that is **122 → 854 credits a week**, roughly 3,700 a month, which sits
  inside the cheapest paid tier (20,000) but is a 7× rise rather than a free one. Note the in-repo
  comment at `TideService.java:68-69` — *"WorldTides charges per request, so 7 days is efficient"* —
  is wrong on both halves and self-refuting: if it charged per request, 7-day chunks would be the
  least efficient choice available. The backfill's chunking is cost-neutral by accident; only its
  stated reason is wrong.
- **No maximum `length` is documented.** A claimed 604,800 s cap is refuted by this repo already
  sending 1,209,600 in production. **Still unverified: the actual server behaviour at 97 days** — the
  API validates the key before the parameters, so an unauthenticated probe cannot reach a
  length-specific error. Confirming it costs ~14 credits with a real key and is the highest-value
  outstanding check.
- **The cost dashboard will not show the rise.** `CostProperties` charges a flat
  `world-tides-micro-dollars: 3000` per call regardless of window size, so real credit consumption
  multiplies by seven while the admin figure is unchanged. The response already carries `callCount`
  ("credits used by this request") and `WorldTidesResponse` discards it.

**And one thing the paragraph never mentioned, which mattered more than either.** The spring and king
thresholds were computed over *every* row in `tide_extreme`, forward rows included
(`findHeightStatsByLocationIdAndType`, no `event_time` predicate), so the horizon and the
classification threshold were the same dial. Extending the fetch window would have silently moved
spring/king classification for every coastal location, into a persisted column
(`forecast_evaluation.surge_risk_level`), the Claude prompt, hot-topic firing and the "N m above an
average tide" copy. **That decoupling landed first and on its own** (`75217809`), so the shift it
causes is measurable in isolation, and only then was the horizon raised.

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

**Discharged 2026-08-25** — the flag flipped and was then deleted entire, along with v1
(`docs/engineering/v1-retirement-plan.md`, D0/D1). Kept below as the historical record of the
mechanism, not as a live description.

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
| **P0′** | ⚠️ **PART DONE, part impossible.** Tokens `--color-runbar-1`…`-5` shipped (§2.9), and the header's dangling `DELTA.md` pointer replaced with the truth. The re-cut itself CANNOT be done: `DELTA.md`, `Change Since Last Run.html` and `spec-plan-picks/` have never been in the repo, and authoring them would fabricate a spec rather than vendor one. Original scope: re-cut `docs/design/window-first/` **verbatim** from the second bundle — `Plan Window First v2.html`, `README.md`, `PROMPTS.md`, plus new `DELTA.md`, `Change Since Last Run.html`, `spec-plan-picks/`. Add `--color-runbar-1`…`-5` (§2.9) | Six of the nine shared files are byte-identical; leave them. **Do not hand-edit the vendored README** — verbatim is what keeps the next re-cut a diff. Its stale per-window pick text at `:118`/`:210` is neutralised by this plan's header pointer instead. Mark `Change Since Last Run.html` superseded or omit it: it contradicts the current spec on three points. **No `--blue` token** — see §2.8 |
| **P1′** | Reshape the merged projection: delete `alsoGood`; hoist the picks to a forecast-wide rank-1/rank-2 **over the rendered window set** (§2.3), each bound to its window. ⚠️ ~~add nullable `priorRating` to the slot for the change row~~ — **never shipped and should not be**: §2.8 deferred the change row, and `grep priorRating` finds nothing in the tree. Left struck rather than deleted so nobody goes looking for the field again | Keep `rank()`, `pick()`, `Pick`, `AlsoGoodFloor`, badges, rarity, and all of `73e20f5d`'s fixes. **No `regionId`** (§2.8). Update `BriefingWindow:36-37` and `PlanWindowProjector:274-275` in the *same* change as the field, never before it |
| **P2** | ~~Backend: tide rollup derivation (§2.4)~~ **DONE.** `BriefingWindowTide` on every window, built by `WindowTideRollupBuilder` and passed to the projector as a map. `selectRepresentative`/`findAnchor` extracted to `TideRepresentativeSelector` (per-instance warn state), formatters to `TideWording` | Extraction proved pure — `TideRunBuilderTest` passes unedited. Three review findings changed the design, not just the prose: the anomaly estimator, the tide-state window, and the projection's placement on the serve path (§2.4a) |
| **P3** | Backend: `GET /api/user/settings/reach` (§2.2) | Unchanged |
| **P4a** | Shell — masthead, tabs, move the flag branch out of `<main>`, suppress the app header for v2 | Unchanged in substance. `border-bottom-width: 0`, never `border-bottom: none` |
| **P4b** | Generalised popover host — one body-parented `position:fixed` panel with document-level delegation | Smaller than first assessed. The region-chip gloss **already ships** inside the component P4c copies (`BriefingSummaryStrip:196-215` + `:233-247`): `role="button"`, keyboard, focus parity, a portalled `role="tooltip"`. Copy the tile and the gloss comes with it. Pick-chip content belongs to P5. ⚠️ **P4c became the host's first caller after all**, not P5: the copied panel is placed once from a viewport rect and never recomputed, and on phone the rail is a horizontal scroller — so a swipe leaves it pointing at nothing. The host dismisses on scroll and on Escape; the copied panel does neither. Using it changes nothing in `BriefingSummaryStrip`, so the v1 arm is untouched either way |
| **P4c** | ~~Day rail as the full Plan summary tile~~ **DONE.** `WindowFirstDayRail` + `utils/windowFirstRail.js` (the copied derivation) + `WindowFirstBriefingProvider` (the arm's own `/api/briefing` fetch, §4) | Tile copied, `ProvisionalMark` deleted and its two tests not carried. Picks read `window.pick`, never `bestBets` (§2.3). **Three decisions worth not re-deriving:** the pick flag names the **event** as well as the kind, because a pick is a window and a tile is a day; every tile **reserves** the two-line chip's height or the rail's lines go ragged; and an away day **keeps its sun times** — the mock replaces them, but its own away banner says the rail keeps them, and they are almanac. LITE gating settled in §7. The rail sits **above the tab bar**: it is the screen's date context, not the Plan pane's content |
| **P5** | ~~Window card~~ **DONE.** `WindowFirstWindowCard` + `WindowPickDialog` + `utils/windowFirstCards.js`; the Plan pane renders the list | Header, verdict badge with the confidence decay, pick badge on the two chosen windows, topic badges. **Five decisions worth not re-deriving:** the header says `best N★` and **no count** — reach is P8 and the spots it would count are P6, so any number here describes a set that was never filtered (§6); **no expander** (P9's, and collapsed vs open would differ by a few pixels of padding, making it a demo control); **no footer bar** rather than an empty one, since everything the design puts in it is P6/P11; `AWAITING` renders as "Awaiting" on the **neutral** badge, never the red one; and the lead card is `index === 0 && date === todayStr`, which is the only predicate that yields exactly one and always agrees with the rail's gold tile. "Tonight" is the kicker only on a lead **sunset**; a lead sunrise carries none and keeps the day in its title. ⚠️ The Notes cell here used to describe the change-since-last-forecast row — stale since §2.8 deferred it, and the P1′ row's `priorRating` is stale in the same way (it never shipped and `grep` finds nothing) |
| **P6** | ~~Spot film strip — **and the reach data on the card**~~ **DONE.** `WindowSpotStrip` + `utils/windowFirstSpots.js`; `GET /api/user/settings/reach` gets its first consumer, fetched by the arm's own provider | Geometry from the spec, not `.cth-window-grid`; no `ScrollRail`; one shared comparator; footer states what is *drawn*. Rating badge from `RATING_COLOURS` (§2.9). **No sparkline** — P16. **Reach moved here from P8, agreed 2026-08-05**: the design has always drawn `41 min · 19 mi` on every spot card, and P3's endpoint had no consumer, so the strip would otherwise render visibly incomplete cards for two phases. P8 keeps the **gate**; P6 takes the **display**. **Seven decisions worth not re-deriving — see §5a** |
| **P7** | ~~Attribute rows — tide, then snow~~ **DONE.** `WindowAttributeRow` + `utils/windowFirstRows.js` + `components/chart/WindowTideSparkline.jsx`; `BriefingWindow.Badge` gains `facts` | Cap of two per window, and it binds. ⚠️ The old Notes cell here said "the change row is P5's and does not count against it" — **stale since §2.8 deferred that row**, which is why two rows means tide + snow and nothing else. **Nine decisions worth not re-deriving — see §5b** |
| **P7b** | ~~Promoted strip — both variants; chart 42px curve + 16px label band~~ **DONE.** `WindowFirstPromotedStrip` + `utils/windowFirstPromoted.js`; the provider derives the one strip, the shell renders it above every pane item and owns the route into the list | The cap is arithmetic, not a rule: `buildPromotedStrip` returns one descriptor or null. ⚠️ **The chart is NOT built, and that is a deviation rather than an omission** — the label band is not derivable from the window projection and the curve alone would be the fourth tide chart in this arm on one pane. `UNKNOWN_RANK`'s wire semantics are settled here. **Nine decisions worth not re-deriving — see §5j** |
| **P8** | ~~Lens bar — **reach only** + persistence policy~~ **DONE.** `WindowFirstLensBar` + `utils/reachLens.js` + `hooks/useReachLens.js`; the gate rides `buildWindowCards`, and the `far` spot variant ships with it | Shrunk: rating floor and type move to P11. Day-derived default; reach expires at the day roll. **The labels are the spec and `LIM` is mock shorthand** — the label is now *derived* from the threshold so no second number exists to drift. First gated control in the arm; `role` enters at the provider only. **Nine decisions worth not re-deriving — see §5c** |
| **P9** | ~~Collapse/expand, six-window case, away-day row + its rail variant, the two doors~~ **DONE.** `WindowAwayRow` + `utils/windowFirstAway.js`, `WindowFirstDoors` + `WindowFirstRegionalPanel` + `utils/windowFirstRegions.js`; the card gains `open`/`onToggle` and the shell owns the state | ⚠️ The old Notes cell said "the window card shrank" — **stale**, it had grown every phase since P6. Measured: six open windows are **1,969px / 2.74 viewports**, exactly the figure §5a predicted from a different direction; lead-open/rest-collapsed is **996px**, a 49.4% saving. The **rail variant already shipped at P4c** (`isAway`, `✈ Away`, `Not forecast`, and it keeps its sun times) — P9 owned only the pane row. The six-window case needed no feature: `MAX_VISIBLE_EVENTS` already caps it and the rail and cards share one evaluation. **Ten decisions worth not re-deriving — see §5d** |
| **P10′** | ~~Peek content kind 1 (spot)~~ **DONE.** `WindowSpotPeek` + `utils/windowSpotPeek.js` + `hooks/useSpotPeek.js`; the strip gains the trigger and the card drills the score index through | ⚠️ The Work cell used to add "+ click-to-map", which **shipped at P6** — §5a`:603` already corrected it. The host is **not** P4b's after all: the portal reasoning is taken and cited, the host itself does not fit (above-only placement, no slot for the panel's pointer handlers). **No phone peek** — the row named a `BottomSheet` and no trigger, and the same paragraph gives the phone's only tap to the map. Open delay stays at 180ms, not this row's 140; 160/120 adopted and split. A summary-less spot now *does* get a peek, because the bars are back. **Fifteen decisions worth not re-deriving — see §5e** |
| **P11** | ~~Drilldown sheet — **plus the rating floor and type controls** and their persistence~~ **DONE.** `WindowSpotSheet` + `WindowSpotCard` (extracted from the strip) + `utils/windowSpotBrowse.js`; the strip footer gains "See all" and the card gains one on its gated-out line | Grown by what P8 shed, then shrunk by one thing it could not honestly carry. ⚠️ The controls went into the **sheet**, not the bar — so §5c`:908`'s warning that P11 invalidates `scroll-margin-top` does **not** fire (bar re-measured at 53.5px). The mock's type taxonomy does not exist in this product; `utils/locationTypes.js` does, and the options are **derived from the population** so no chip can match nothing. The canopy debt §5a`:610` parked here is **handed on**, with the reason: a type word cannot disambiguate a badge colour, and a grid makes that collision denser than a strip did. **Eight decisions worth not re-deriving — see §5f** |
| **P12** | ~~Backend: almanac feed (§3) + the tide fetch-horizon decision~~ **DONE.** `AlmanacEvent` + `AlmanacKind`, the `AlmanacSource` interface and five implementations, `AlmanacService`, `GET /api/almanac`; horizon raised to 97 days with the threshold decoupled first | ⚠️ **§3's "the range plumbing already exists" is the sentence that would have sunk this.** The signatures take a range; ten of thirteen strategies ignore it. `AlmanacSource` is a **new interface** for that reason, and `HotTopicAggregator` is not reused — it is also travel-day filtered and simulation-overridable, either of which would corrupt a 90-day feed. **Eight decisions worth not re-deriving — see §5g** |
| **P13** | ~~Coming up tab~~ **DONE.** `WindowFirstComingUp` + `WindowComingUpRow` + `utils/comingUpFeed.js` + `hooks/useComingUpFeed.js` + `api/almanacApi.js`; the shell's tab bar becomes a real ARIA tab widget and gains its second tab | The feed's first renderer. **Two columns, not the mock's three** — the third carried a region count the wire never sends. **No kind chip on an ALMANAC row** and **no count on the tab**, both because the value never varies. The degrade caveat is gated on the TYPE as well as the absence: `datesOnly` is the healthy state for three of the five sources. **Nine decisions worth not re-deriving — see §5h** |
| **P14** | ~~Responsive pass — real media queries, including the taller rail tile on phone~~ **DONE.** `.wf-` classes for the shell's chrome and the window header, `--wf-gutter`, and the phone block for the tab bar and header; `.wf-lens` stopped wrapping | Keep control labels at 9px. ⚠️ **Both halves of the Work cell were wrong about the job.** Five of the README's nine bullets already shipped (including the spot strip's 72%, which the handover called unbuilt), one is vacuous and one names a component P7b never built — the real work was the tab bar, the window header and the 18px→14px gutter. And there is **no taller rail tile**: `git log -S` puts the phrase one day *before* the component existed, and P4c had already discharged it. **Eleven decisions worth not re-deriving — see §5i**, plus one live defect the pass found and fixed: the sticky lens bar wrapped to 77.3px between 640 and ~781px, overrunning both `scroll-margin-top` reservations |
| **P15** | ~~Pre-pilot sweep (§6)~~ **SWEPT 2026-08-11 — see §6a for the clause-by-clause result.** Four fixed (rail roster count, rail GO token, away hue, grid cell names), two left as one pricing decision, one clause (3) **untested** because P7b is unbuilt. The flag default is **still `v1`**: the owner scheduled the flip for ~a week out, with P7b in between, so the sweep landing is not the flip landing | **Two of the four handed-up items were already resolved before the sweep ran** — `--color-marginal` and `--color-dust` are both declared (`index.css:178-179`), so the list below is stale on those two and was re-derived rather than trusted. Of the rest: the away-band wording is **fixed** (`HeatmapGrid` now says "not forecast", matching the arm that argued for it); the LITE score split and the `HotTopicStrip` treatment are **one** question and stay open, unreachable for a `PRO_USER` pilot roster. Original text follows. **Four items handed up, all cross-arm and none fixable inside one phase.** From P10′ (§5e): the **LITE score split** — `freemium_ui_strategy.md:79-80` lists the two scores and the Claude summary as LITE-included and §7 relies on that, but `MarkerPopupContent.jsx:1165` gates them and `:1175` upsells them, so an ungated peek contradicts the map overlay one click away; and **`--color-marginal`, a token declared nowhere**, which leaves `CardHoverPreview`'s and `CloseToHome`'s stars silently inheriting body ink in the frozen v1 arm. From P9 (§5d): the `HotTopicStrip` LITE treatment, which the window card's ungated attribute rows already defeat for the tide and snow channels; and `HeatmapGrid`'s away band saying "no forecast generated" two elements below a row that deliberately says "not forecast". Both need one decision made once across both arms, which is the shape §2.8 settled for the pick gloss |
| **P16** | *(post-pilot, conditional)* Run history — the sparkline **and** the change-since-last-forecast row, both blocked on an append-only per-run sink the live pipeline writes | Deferred because only **4.1%** of slots have the four *rated* runs it draws and **90.6% have none** — measured, see §2.8. Not for want of data or query |

### 5a. What P6 decided — read before P8, P9, P10′ and P11

Seven decisions that changed behaviour rather than wording, recorded so a later phase does not
re-derive them from the strip's appearance.

- **No `far` variant, and that is not an omission.** The design draws a `far` spot card (tide-tinted
  border, tinted meta line) beside the ordinary one. `far` is a judgement *against a reach tier*,
  and the tier is P8's control. A card asserting "beyond weekday reach" while nothing on screen can
  widen it is the same failure as a header counting a set that was never filtered — §6 bans both,
  and §2.5's rule ("the word *reach* arrives with the filter") applies one level down. **P8 ships
  the tier and the `far` variant together.**
- **Click-to-map was pulled forward from P10′.** §5 lists it there, but the spot card is drawn as a
  button, lifts on hover and ends in `◍ Open on map →`; shipping that inert for a phase is exactly
  the demo control §6 bans. It reuses the positional `onShowOnMap(date, targetType, locationName)`
  the pick dialog already calls — nothing new. **P10′ still owns `WindowSpotPeek`**, which is the
  part that is actually new.
- **The strip reads the projector's slot population, not the region's.** Unregioned slots are
  dropped (never Claude-enriched, so always unrated) and canopy slots are dropped unless the whole
  window is canopy — mirroring `PlanWindowProjector.bestRating`/`canopyCounts` exactly. Otherwise a
  wood's 4★ out-ranks the header's own `best 3★` on the same card, and a woodland GO (heavy cloud
  and mist) sits in the same badge as a sky 4★ meaning the opposite. The cost is real and accepted:
  a bluebell wood is a good destination this strip will not list. **P11's drill-down, which can
  carry a type control, is where that belongs.**
- **The footer's sort sentence is derived, never hard-coded.** A sort key no spot carries never
  fires, so `spotOrderStatement` emits one of four sentences — "Ranked by rating, then drive time",
  "Ranked by rating", "Ranked by drive time", "Listed alphabetically". All four are reachable: no
  drive times is the first-run state, no ratings is an unevaluated briefing. `compareSpots` is
  exported for P11 so the two can never disagree.
- **No "See all N →", and no "N of M loaded".** The first opens P11's drill-down; the second needs a
  set that is loaded but not drawn, and with no reach gate and no rating floor N *is* M. **Both land
  with P11**, and the count then stops being of "what was drawn".
- **The focus-ring room comes out of the wrapper's inset, not out of a negative margin — and the
  vertical half is bigger than the ring.** `.cth-window-grid` buys its room with
  `padding: 4px; margin: -4px`, which cannot be copied: the card basis is a *percentage* of the
  scroller's content box, so a negative margin leaves `calc((100% - 24px)/3.5)` resolving against a
  box 8px narrower than the spec sized against. `.wf-strip` uses `padding: 0 10px` and the scroller
  `padding: 8px 4px 11px` — the horizontal 10 + 4 restores the spec's 14 exactly (measured 282.86px
  against the spec's 282.857), and the top is **8px, not 4**: 2px outline + 2px offset + the 2px
  `.wf-spot:hover` lift + 2px of slack. ⚠️ **4px was measured wrong twice** — flush at rest and 2px
  *outside* the clip while lifted, erasing the ring's whole top edge. It is reachable by mouse
  alone, because closing the map overlay restores focus to the card the pointer is still on.
  `.cth-window-grid` carries the same 4px-plus-lift pairing and is left alone deliberately.
- **A focused spot card is `position: relative; z-index: 3`, and that is load-bearing.** The two
  edge fades are absolutely positioned on the wrapper and end on an opaque `--color-plex-panel`,
  while `.wf-spot` is a static button — so both gradients painted *over* every card. Tabbing along
  the strip parks the focused card against the left fade's opaque band, which erased the ring's
  left stroke: a three-sided ring on a card fully inside the scrollport. 3 clears
  `back::before`'s 2. This is occlusion, not clipping, so it and the padding above are independent
  defects and neither fix covers the other.
- **Per-user reach is invalidated by `homeSettingsVersion`, never by a bare `[]`.** The provider is
  mounted for the whole life of the v2 arm and `UserSettingsModal` is its *sibling* in `App`, so
  saving a home postcode re-renders it and never remounts it — a first-run user who set one would
  have watched every reach line stay absent indefinitely, and a user who moved would have kept stale
  drive times forever. `App` already keeps the counter for exactly this, after `DailyBriefing`'s
  close-to-home fetch shipped the same bug ("the setting appeared to do nothing"). It also gives a
  swallowed boot-time failure a route back.
- **Every 10px sub-line is `--color-plex-text-secondary`, not the spec's `--ink-3`.** Measured on
  the running app: muted lands at 3.54:1 on the spot card's surface and fails AA; secondary measures
  7.03:1. This is the third time this project has made the same correction (CloseToHome's region
  line, its ordering explainer) — the design's `--ink-3` is not usable for 10px type on this
  surface.

**⚠️ Height budget, measured at P6 rather than estimated — this is P9's problem now.** Six windows,
1280×720, all expanded: each card is **201px** (header 45 + strip 115 + footer 39), the pane is
**1,285px** and the shell **1,563px** — 2.17 viewports, against P5's 645px. P7's two attribute rows
put it near **2,000px**, which is within sight of the 2,600px §3 names as the failure the whole
redesign exists to undo. So: **collapse cannot be optional and the default must be lead-open,
rest-collapsed** (201 + 5×47 + chrome ≈ one viewport). And the strip and its footer must sit *inside*
the collapsible region — a collapsed card that kept the strip would save 39px of the 150.

**Re-measured at P7, in the browser, and the prediction held.** Six windows, 1280×720, every card
carrying its tide row and two of them a snow row as well: **234px** with one row and **273px** with
two, against P6's 201px. One attribute row costs **51px** (a 40px row plus `.wf-rows`' 11px bottom
margin) and a second costs 39 more. Shell **1,864px** — but that measurement's strip is 97px rather
than P6's 115px, because the fixture carries no drive times and each spot card is a line shorter.
Normalised to P6's strip the real figures are **252px per card and a ~1,970px shell, 2.74
viewports** — the "near 2,000px" this section predicted, arrived at from a different direction.

That settles P9's default rather than merely informing it: **lead-open, rest-collapsed**, with the
rows *inside* the collapsible region alongside the strip and footer. A collapsed card that kept its
rows would give back only 150 of the 207 it now costs above the header.

On a phone (375×812) the same six cards run **2,448px**, with the chart dropped and each row's facts
stacked. The responsive pass is P14's; the number is recorded so it is not discovered there.

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
`index.css:1062` justifies the rail as "the only handle a mouse user has" (the line was cited as `:888`, which is `.map-home-control button`; corrected at P10′) — a premise that is false here.

**P10 — the peek is a new component.** `CardHoverPreview` is `aria-hidden` under an explicitly
conditional licence ("the card stays a button — the peek is a shortcut, never the only route"), and its
Javadoc lists the score bars, header bar and footer as *deliberately removed*. The spec restores all
three. So: new `WindowSpotPeek`, **copying** `previewPlacement`, the fixed-position escape hatch and
the open/hold/dismiss timers, leaving `CardHoverPreview` and `CloseToHome` untouched so the v1 arm of
the flag comparison stays as it is. ("Reusing" was impossible and this line used to say it:
`previewPlacement`, its geometry constants and both timers are module-private to `CloseToHome`, whose
only export is the component, so exporting one would be an edit to the frozen arm. Read as copying —
the rule §5a`:677` already applies to P6. Corrected at P10′, which also re-derived the height constant
rather than transplanting it; see §5e.) **On touch the card itself stays the map activation** — the spec's
"first tap opens the peek" would otherwise make an `aria-hidden`, pointer-only panel the sole route to
the spot-centred deep link. The full-width phone peek renders through `BottomSheet` (`role="dialog"`,
`aria-modal`, focusable close), never `CardHoverPreview`. Timings: 140ms open, 160ms strip-leave, 120ms
panel-leave.

---

### 5b. What P7 decided — read before P7b, P9, P11 and P14

Nine decisions that changed behaviour rather than wording. The height re-measurement is in §5a
beside P6's, so the two sit together.

- **A topic renders once: as a row when it carries numbers, as a badge otherwise.** This is the
  duplication question the design leaves open — it draws a snow badge in the header *and* a snow row
  beneath it — and the answer falls out of what each surface can hold. A badge has room for `label`;
  a row has room for `label` plus the topic's measured facts. A topic with facts is therefore
  strictly richer as a row and leaves the header; a topic with none would render a row whose whole
  content is its own label repeated forty pixels lower, which is the noise §6 bans. Nothing is ever
  lost: a topic the cap drops keeps its badge.
- **`BriefingWindow.Badge` gained `facts`, and that is what makes the rule above real.** Without it
  the "row" could only restate the badge, and the honest build would have been no snow row at all.
  It is copied verbatim in the same pass that already copies label and detail, so a badge can never
  disagree with the same topic's pill in the same response; normalised to empty rather than null, so
  the client's promotion rule is a length check in one place. No migration — the window is derived at
  serve time and never persisted.
- **Only the snow channel is promotable, and that is a budget decision.** NLC carries a clear-count,
  aurora a Kp; promoting every factful topic would put four rows on a busy window and blow the very
  budget the cap defends. The design draws two rows; P7 ships two. **Widening the set is a design
  call, not a refactor** — and P7b's promoted strip is the surface that should absorb the pressure.
- **The cap binds, and it is not a vacuous "at most two".** `SNOW_FRESH` (or `SNOW_MIST`) and
  `SNOW_TOPS` are separate strategies both anchored to SUNRISE, so a winter dawn genuinely offers a
  tide row and two snow rows. Rows order tide-first — it renders on every window of every day, so a
  card whose rows moved about would read as a different card — then by `rarityRank`, the ordering
  advice the payload already publishes for the promoted strip, so the two surfaces cannot disagree
  about which topic matters most.
- **The design's fourth grid column is not built, and both of its labels say why.** "61 coastal
  locations →" is a count of our own data, which §6 bans outright ("11 aligned is a fact about the
  database, not about tonight"); "Filter to high ground →" is P11's type control and would ship
  inert, which §6 also bans. So the tide row is three columns and the snow row two. Recorded here
  rather than left as an apparent omission — §2.4a had already called both P7's call.
- **The row is flexbox, not the design's `grid-template-columns`, and that is a correctness fix.**
  The chart column is conditional twice over — no drawable curve, and every window on a phone — so a
  fixed three-column template leaves the facts in the middle `auto` track with the `1fr` empty beside
  them whenever the chart is absent. Flex sizes from the children that are there and lands on the
  same picture when all three are.
- **Two text tones, not the design's three.** Its `.dim` is `--ink-3`, and bone at 0.42 over the
  row's surface measures **3.49:1** on a plain card and **3.38:1** over the lead card's gold wash —
  under AA at the row's 10.5px. The caveat chips take the base tone (6.57:1) and lose their
  de-emphasis, which is hierarchy rather than meaning. **This is the fourth time this project has
  made the same correction** (`CloseToHome`'s region line, its ordering explainer, §5a's spot-card
  sub-lines). Stop reaching for `--ink-3` at small sizes on these surfaces.
- **The sparkline does not reuse `solarDayGeometry.js`, and does not carry
  `preserveAspectRatio="none"`.** The payload is already normalised — `x = i/(n−1)·104`,
  `y = (1−curve[i])·24`, mark at `windowPosition·104` — so no clock is parsed and borrowing that
  module's constants would imply a shared axis these two charts do not have. The box is fixed at
  exactly its viewBox, so nothing scales and the window mark stays a circle; the design's `none`
  is inert at 1:1 and would turn the mark into an ellipse the moment a column stretched. A curve
  under two points, or carrying anything non-finite, draws **nothing** rather than a path with `NaN`
  in it, and an unplaceable instant drops the **mark alone** and keeps the trace.
- **§7's LITE question, settled: neither row is gated.** `HotTopicStrip` blurs every topic's fact
  chips for LITE — a blanket paywall tease over a promotional strip, not a judgement about tides —
  and these rows are not that surface. They are the window's own context, the equivalent of the v1
  Plan tab's tide chips and tide-aligned markers, ungated for every role today;
  `freemium_ui_strategy.md` blurs cloud-layer breakdown, aerosol metrics and the technical panel, and
  tide is almanac (the product's own `topicCertainty` says so, and `GET /api/tides` is Bearer rather
  than ADMIN for the same reason). One rule for the whole block, and no `role` reaches this arm.
  **`HotTopicStrip` is untouched**: it is not in this arm until P9 builds the hot-topics door, so
  nothing disagrees on screen today, and editing it would perturb the v1 arm §4 rests on. **When P9
  wires it in, that blanket blur is the single place to reconverge.**

**The adversarial review before this landed** (six prosecutor lenses, one refuter per charge, then
synthesis — 34 agents): **27 charges, 22 refuted, 3 real**, all fixed before the commit and all
low-to-medium. Worth carrying, because two of the three are the same species this project keeps
producing:

- A **contrast figure recorded against a surface it never renders on.** The kicker comment quoted
  `11.19:1` for the snow ink — correct arithmetic, wrong backdrop: that is the snow ink over the
  *tide* row's fill, and `.wf-frow-snow` replaces the fill, so the pairing cannot appear. The true
  figure is `10.99:1` (9.92 on a lead card). Every other number in the block reproduced exactly,
  which is precisely why the odd one out was invisible. **When two variants of a class have
  different backgrounds, measure each on its own.**
- A **new test that could not fail.** "Is not role-gated" asserted `style.filter === ''` on a
  component that takes no gate prop and never writes `filter` — true by construction. Worse, it
  watched the wrong element: `HotTopicStrip` blurs the *facts container*, one level in. Now it
  asserts the absent props (`role`, `isPro`, `isLiteUser` — two separate `not.toContain`, never the
  conjunctive `arrayContaining`) **and** the facts container, and both halves were mutation-proven.
- A **promoted topic's `optional` flag with no test at all** — every `.opt` assertion was built from
  a tide row, so deleting `Boolean(f.optional)` left the suite green while a snow row stopped
  dropping its context chip on a phone. Both snow strategies really do emit one.

Seventeen mutations were run in total against the new tests before and after the review; every one
was caught. ⚠️ Note for anyone doing the same: `-Dtest='Class#method'` on a JUnit `@Nested` method
**runs nothing and exits 0** — it looks exactly like a test that failed to catch a mutation. Run the
whole class.

**What was verified live, and what was fixtured.** The local DB has never had an evaluation run, so
`/api/briefing` returns ten windows with `{verdict, badges: []}` and no tide rollup, no badges and no
hot topics — measured again at P7, unchanged from P6. Everything below was therefore seen in a real
browser against an **injected payload**, not against live data: the row's layout and wrapping, the
sparkline and its mark, promotion and the badge that leaves the header, the cap dropping a third row
and leaving its badge, the factless topic staying a badge, the T+4 sea-state degrade, the phone
stack with the chart and the droppable chip gone, the absence of horizontal overflow, every token
resolving rather than being pruned, and the heights in §5a. What is **not** verified: any of it
against real tide data, and no screen reader, axe or Lighthouse pass was run — the same gap §5a's
own coverage note records. Chrome only.

⚠️ **A fixture keyed to the payload's array index will silently land on nothing.** The cached local
briefing was generated two days before it was read, and `selectUpcomingEvents` drops past events
before any card is built — so badges injected at `days[0]` and `days[1]` were correct on the wire,
correct in the provider's state, and invisible on screen. It looked exactly like a bug in the
promotion filter for several rounds. Key a fixture to the **local date**, not to the index.

**P12 — the tide fetch horizon and its fallout. ✅ DONE.** The horizon was extended to 97 days and
the sweep is complete. Two lessons from doing it are worth more than the list itself:

- **This list was seven sites; the real count was 34 across 17 files.** It missed the *class*-level
  Javadoc at `TideService.java:36` while catching the two method-level ones below it; the constant's
  own comment at `:53`; a third "14 days" Javadoc at `ForecastController.java:408`; the
  `@DisplayName` at `TideServiceTest.java:344`; `forecast-evaluation-architecture.md:486`; and —
  because the search never covered `.js`/`.jsx` — **four production `T+13` rationale comments**
  (`WindowTideRollupBuilder.java:52` and `:70`, `BriefingWindowTide.java:48`,
  `frontend/src/utils/windowFirstRows.js:133`) plus four more in tests. When sweeping a constant's
  prose, grep every extension, not just the language the constant is written in.
- **Exactly one assertion actually failed** (`TideServiceTest.java:367`, the 13–14 day window span).
  Everything else was a comment. A green suite is therefore no evidence at all that this sweep was
  done — which is the argument for doing it by inventory rather than by running the tests and seeing
  what breaks.

Sites deliberately **not** changed: the three `CHANGELOG.md` entries (`:162`, `:405`, `:2888`). They
are a dated record of what was true when written, and editing them would falsify it. The `:242`
evidence table in this doc is annotated for the same reason rather than rewritten.

Rephrased rather than renumbered: the four `T+13` rationale comments now say tides reach "months
ahead". Their *reasoning* survives the change untouched — the wave horizon did not move, so a rollup
with no sea state is still normal — and only the number rotted. Giving them a new number would
guarantee a third sweep.

**Migrations: one — `V139__tide_refresh_description_horizon.sql`.** It corrects the `tide_refresh`
row's description, which `SchedulerController.toDto` serves and `SchedulerView.jsx:278` renders
verbatim to an operator, so the stale text was a false statement on a screen rather than a dormant
value. Nothing in the app ever writes that column — `DynamicSchedulerService.registerJobTarget` takes
only `(jobKey, Runnable)` — so SQL is the only way to change it. ⚠️ **The day count was not the only
thing wrong with it:** "for all coastal locations" has never been true, because
`refreshTideExtremes` requires the SEASCAPE tag as well as a tide preference, so a coastal LANDSCAPE
or WATERFALL location is skipped. The same wrong sentence was in the Javadoc the seed was copied
from. ⚠️ **V139 cannot be verified locally** — `application-local.yml` disables Flyway and uses
`ddl-auto: update` with no seed data, so the row does not exist on the local profile.

Everything else rides existing tables and payloads. If another migration is needed, read the latest
number off the tree on **main** — `ls backend/src/main/resources/db/migration/ | sort -V | tail -1` —
never from a written-down number, including this one.

Read `docs/engineering/test-improvement-standards.md` before writing any test class. CI's gates
(JaCoCo 80% per class, SpotBugs, Checkstyle, the Testcontainers integration classes, and the
exit-code-not-grep rule) are documented in CLAUDE.md and are not restated here.

---

### 5c. What P8 decided — read before P9, P10′, P11 and P14

Nine decisions that changed behaviour rather than wording. P8's height figures sit at the end,
beside P6's and P7's.

- **The labels are the spec; the thresholds are 45 / 90 / 150. And the label is now DERIVED from
  the threshold rather than written beside it.** The mock contradicts itself —
  `LIM={any:9999,'30':30,'60':60,'120':120}` at `:457` against
  `RL={'30':'45 min','60':'1h 30','120':'2h 30'}` at `:494`, so every tier is wrong by a consistent
  offset and the chip reading "45 min" gates at 30. §2.5 states the intent as "45 min / 1h 30 /
  2h 30 as a **time** lens", so the labels win. Making the call was not enough: a chip that hides a
  40-minute drive is the single most damaging thing this phase could ship, so `REACH_TIERS` computes
  each label from its own `limitMinutes` through `formatDriveDuration` — the formatter every spot
  card already prints its drive line with. There is no second number left to drift, and one test
  pins the whole pairing. **Accepted deviation: the labels read `1h 30min` / `2h 30min`, not the
  design's `1h 30` / `2h 30`** — three characters, in exchange for a chip and the cards it gates
  speaking one vocabulary.
- **`far` is measured against the day's DEFAULT tier, never the selected one.** Against the
  selection it could never fire — the gate has already removed anything past it. Against a fixed
  tightest tier it marks most of a Saturday's strip, and on a day the user has said 2h 30min is
  fine, a one-hour drive is not a different kind of commitment: that turns a signal into a wash.
  Against the default it marks exactly what widening bought, which is small by construction and is
  the honest answer to "what did that control just do". One consequence worth knowing: the mark is
  therefore invisible while the bar sits on its default, and that is correct rather than a gap.
- **The day-derived default does two jobs on purpose.** It is the gate's starting point *and* the
  line `far` measures against, because both are the same judgement — "what counts as an ordinary
  outing today" — and splitting them would let a card call a drive long that the control had just
  called ordinary. Friday is deliberately a weekday: the bar is one control over up to six windows
  spanning four days, so an evening-versus-morning judgement cannot be expressed by a single
  default, and the readout names which of the two derived it so the choice is never silent.
- **The expiry needs no effect, and nothing is ever written back.** The choice is held *with the day
  it was made on* and the active tier is derived, so a stamp that stops matching simply stops
  applying — no timer, no midnight `useEffect`, no window in which the stale value is still live.
  The provider re-renders on its ten-minute poll, which is what supplies the new date. A read that
  re-persisted an expired choice would turn a today-only setting into a permanent one.
  Key: **`photocast.planReach`**, holding `{reach, reachDay}` and **nothing else**, written as a
  whole value that never reads storage back. ⚠️ **This was one `photocast.planLens` key holding
  every lens setting, merged read–modify–write so P11's fields could ride along, and it was the
  wrong shape twice over.** On design: reach expires daily and P11's controls persist, so one object
  made the policy a naming convention (the stamp had to be `reachDay` to disambiguate) where two
  keys make it structural — and one key per concern is what `PLAN_LAYOUT_KEY` already does. On
  mechanics: CodeQL's `js/clear-text-storage-of-sensitive-data` flagged the write, and correctly as
  a *shape* — it models `localStorage` as a single store with no notion of keys, so a `getItem`
  feeding a `setItem` is a conduit from every other write in the app, `AuthContext`'s
  `passwordChangeRequired` among them. A false positive about this key; a fair comment about the
  pattern, which re-persisted whatever it found, unvalidated. **P11 takes its own key**, and gets
  its own expiry policy with it.
- **LITE is pinned to "Any", so the gate is provably a no-op for it.** §7 makes the bar a PRO control
  and settles the *chrome*; what the gate then does was P8's to decide. A tier a LITE user cannot
  widen would withhold forecast content — on a weekday, every spot past 45 minutes, with no route
  to it — and CLAUDE.md's rule is breadcrumbs, not paywalls. Pinned to Any nothing is hidden, the
  greyed control describes its own true state, and the "Pro" pill offers the ability to *narrow*.
  It also keeps the override signals honest: `overridden` is false for a locked bar however far Any
  sits from the default, because "today only" and the reset mark a choice the user made.
- **The upsell sits OUTSIDE the greyed box.** Found in the browser, not by a test: with the "Pro"
  pill inside the `opacity: 0.45` wrapper it composited to **3.68:1** against AA's 4.5:1, and
  12.59:1 outside it. WCAG 1.4.3 exempts an *inactive* component, which is what licenses the greyed
  tiers at 3.12:1 — it does not exempt the call to action that replaces them, and `HotTopicStrip`
  already ships its own upsell as a sibling of the blurred content. The greying is scoped to
  `.wf-lens-controls` for exactly this.
- **`role` enters this arm at the provider and stops there.** P7 kept `role` out of the card subtree
  and has a test pinning the absence of `role`/`isPro`/`isLiteUser` props. The provider already
  reads `role` for the SWR cache key, so gating costs no new dependency, and what the cards receive
  is a **threshold**, not a role. Nothing below the shell learns anything about roles.
- **P8 earns the two counts P5 and P6 both withheld, and only where the words are true.** The header
  gains `N within reach`, non-null only when the tier carried a threshold *and* every drawn spot has
  a known drive time — §2.5 rule 1 passes an unknown through every tier, so a part-measured set is
  not a set that is within reach. Where the word is unavailable the header stays silent rather than
  printing a bare "N spots" that duplicates the footer one element lower: exactly one count when
  nothing was gated, two complementary ones when something was. The footer's count becomes the
  design's **`N of M`** only when M > N — "loaded" is dropped, since nothing here is lazily fetched.
  A fully gated window replaces its strip with a line naming the threshold *and* the count beyond
  it; a window that had no spots to begin with says nothing, because the lens never touched it.
- **A sticky bar needs `scroll-margin-top`, and the browser will not work that out for you.** Found
  in the browser, not by any reviewer or test: tabbing to a spot card sitting just above the fold
  parked it **34px underneath the bar**, hiding the card's own name and the top of its focus ring —
  the same ring §5a spent two measurements protecting from the scroller's clip, lost again by a
  different mechanism. `.wf-spot`, `.window-card-pick` and `.wf-film-btn` now carry
  `scroll-margin-top: 60px` (the bar's measured 53.5px plus the ring's 2 + 2). ⚠️ **It tracks the
  bar's height**, so P14 must re-measure; a bar that wraps to two lines makes the number short.
  ~~and P11 — which adds a rating floor and a type control to the same row~~ **P11 did not**: both
  controls went into the drill-down sheet instead (§5f), the bar still holds one control, and it was
  re-measured at 53.5px on the running app rather than assumed. jsdom has no layout and
  does not load `index.css`, so nothing can unit-test this: it is a browser-verified claim, 30
  cards tested, 0 obscured after the fix.
- **`bestRating` is not re-derived from the gated set**, so a header can read `best 5★ · 7 within
  reach` over a strip topping at 4★. Two true claims about different things — the star is the
  *window's* best, the count is what *this user* can drive to — and in that state the star usefully
  says a better spot exists further out. Re-deriving it would make one window's "best" differ per
  user for the same night and would move a quality signal when the reader touched a control about
  distance. Seen live; §2.7's rule that the star is never touched applies for the same reason.

**⚠️ Height budget, measured at P8 — and the honest finding is that the lens removes almost
nothing.** The handoff expected P8 to be the first phase that could *give height back* by gating
spots out. It cannot: the strip is a horizontal scroller, so seven cards and sixteen cards are the
same height. Measured at 1280×720 against an injected payload: **201px** per card with a strip and
no attribute rows (P6's figure exactly), **243px** with one snow row, **119px** for a window the
lens emptied, and the bar itself **53.5px, once**. So P8 is **+53.5px of sticky chrome and 0px per
card**, with a −124px saving only in the fully-gated case, which is rare by construction. Shell
1,390px for that five-window mix. On a phone (375×812) the bar is **50px**, becomes a single
horizontally scrolling row, drops the readout, and — checked — adds **no page-level horizontal
overflow** (375 = 375). P9's collapse decision is unchanged by any of this.

**The adversarial review before this landed** (six prosecutor lenses, one refuter per charge, then
synthesis — 27 agents): **20 charges, 17 refuted, 3 real**, all low and all fixed before the commit.
Every charge against the count semantics — §6's central rule, and the thing P8 changes most — was
refuted, as were three separate charges against the `far` mark for a LITE user and two against the
settings-refetch fallback. The three that survived:

- **The new `N within reach` clause shipped in `--text-muted`, in the very commit that makes the
  opposite correction twice.** 11px muted measures **3.54:1** on a plain card and **3.48:1** on the
  lead card's gold wash; secondary is 6.87 / 6.50. The rail footer was upgraded in this same change
  with that exact reasoning and the new card clause was missed. Fixing it took `best N★` with it —
  it has read muted since P5 and had the same failure, and upgrading one and not the other leaves
  two greys in one row. **Sixth instance of this correction.** Note the lead card is the *worse*
  backdrop: measuring only the plain one would have understated it.
- **The active tier's `.on` class had no test.** `.wf-seg-btn.on` is the only rule in `index.css`
  that distinguishes the selected chip — there is no `[aria-pressed="true"]` selector — and every
  test read `aria-pressed`, so deleting the class half of the ternary rendered four identical chips
  with the whole file green. The same diff pins the `far` class *twice* for exactly this reason and
  the argument was not carried across. Now pinned in both halves, and both mutations are caught.
- **Two comment citations were off.** The gated-window line cited the mock's `:478`, which is a
  closing `</div>`; the string is at `:484`. And `CloseToHomeService.java:147-148` is `@param`
  Javadoc — the "no home postcode is the normal state" comment is at **`:156`**. That second number
  came straight out of **§2.5 above, which had it wrong too**, so it was corrected there as well
  rather than left to propagate into P11.

**What the review could not see, and the browser could.** The single worst defect of the phase was
found neither by the panel nor by any test: with the "Pro" pill inside the `opacity: 0.45` wrapper
the upsell composited to **3.68:1**, and tabbing to a spot card just above the fold parked it 34px
under the sticky bar. Both are facts about *composition and scroll*, invisible in a diff. The review
itself executed nothing — no tests, build, lint, dev server or browser; every claim was source
reading plus arithmetic.

**What was verified live, and what was fixtured.** The local DB still has never had an evaluation
run: `/api/briefing` returns five days whose regions carry **zero slots**, and
`GET /api/user/settings/reach` returns all sixteen roster entries with **no** drive figures, because
no home postcode is set. That made three things verifiable on **real data**: the bar renders and is
never suppressed, the readout reads `45 min · weekday default · 0 spots across 5 windows`, and the
rail footer reads `Home not set` beside `Edit reach` — i.e. §2.5's rule 1 in its normal first-run
state. Everything else was seen in a real browser against an **injected payload**: the gate at its
boundary (45 min kept, 46 min dropped), the `far` tint and its border, `7 within reach`, `7 of 16`,
the gated-out window's line, the override pill and its named reset, persistence and the stored
`{"reach":"45","reachDay":"2026-08-06"}`, the LITE treatment end to end, sticky pinning at
`top: 0` across the scroll range with a clean ancestor chain, and every token resolving rather than
being pruned. **Not verified:** the weekend default (the clock cannot be moved from the page — it is
unit-tested only), any of it against real ratings or real drive times, and no screen reader, axe or
Lighthouse pass — the same gap §5a and §5b both record. Chrome only.

⚠️ **The Browser pane repaints only dirty regions while it is hidden**, so a screenshot taken after
a programmatic scroll shows a torn composite that looks exactly like a sticky element failing to
stick. Two screenshots reproduced it. The DOM is authoritative: read `getBoundingClientRect()` at
several scroll positions instead of believing the picture.

---

### 5d. What P9 decided — read before P11, P14 and P15

Ten decisions that changed behaviour rather than wording. P9's height figures sit at the end, beside
P6's, P7's and P8's.

- **The collapse default is "the first card", not "the lead card", and the difference is a whole
  evening.** §5a settled *lead-open, rest-collapsed* on measured heights, and the obvious reading is
  `card.lead`. But `lead` is `index === 0 && date === todayStr`, so once today's last window has
  passed **no card is lead** — and a rule keyed on it leaves six collapsed headers with nothing open,
  every evening, which is precisely when a reader is checking tomorrow's dawn. Where a lead card
  exists the two rules agree by construction, because a lead card *is* index 0. Only what the reader
  has toggled is stored, so the rule keeps applying as the list moves under it on the ten-minute
  poll; a seeded map would freeze one moment's answer and need reconciling on every poll.
- **The collapsible region is drawn wide, and the region element is never unmounted.** Everything
  below the header goes — the attribute rows, the strip, its footer, the fully-gated line — because
  §5a measured the rows alone at 207px above the header and a card that kept them would give back
  only 150 of it. But the *container* is always rendered and only its children are conditional:
  `aria-controls` is an IDREF, and unmounting would leave five of six cards in the default state
  pointing at nothing. Empty and unpadded it contributes no height, so a valid relationship costs one
  DOM node.
- **The expander's accessible name carries the window; the reservation under the sticky bar is
  bigger than everyone else's.** `aria-expanded` announces the state, but six identical "Open"
  buttons are indistinguishable without the window in the name (the visible word leads it, so WCAG
  2.5.3 holds). And `scroll-margin-top` is **76px** here against the shared 60: every other element
  in that list *is* the thing the reader is looking at, whereas the expander is a control on a card —
  and on a collapsed card the card is a single row, so parking the button at 60 put the card's own
  top edge 8.2px behind the bar. 76 = 60 + the button's measured 14.8px offset from its card.
- **The away row is chronological, not appended — and it exists because the cap is applied before the
  travel filter.** The mock renders one skipped block after the whole window list; that reads as a
  footnote and breaks the pane's only ordering spine, which is time (the same argument Hot Topics
  settled for a multi-day tide run). More importantly the row is not decoration: `selectUpcomingEvents`
  caps at six **before** away days are removed, so a travel day spends a slot and then vanishes, and
  the pane's date order skipped a day with nothing to say why. `buildWindowCards` is unchanged and
  still returns cards only; `buildPaneItems` folds the two together beside it.
- **The run folds on calendar adjacency, not on event adjacency.** The walk is over events, so a date
  contributing none of them is invisible to it — an away Wednesday and an away Friday either side of
  a Thursday with no events folded into one row labelled `Wed 5 – Fri 7`, asserting Thursday was a
  travel day when nothing said so. Found in review as an open question; the date arithmetic closes it.
- **"Not forecast", never the mock's "not generated"** — generation is exactly what *did* happen on a
  travel day; only the evaluation was skipped, and "Not forecast" is the rail tile's own word, so the
  two surfaces agree. ⚠️ **`HeatmapGrid`'s away band says "no forecast generated"** and now renders
  two doors below the row that rejects that phrase. It is v1 code and editing it perturbs the arm §4
  rests on, so it is left and recorded: **reconcile at P15**, in the same pass as the item below.
  Also dropped: the mock's multi-user/solo demo toggle, and "Mark yourself back →" (its surface is
  `/api/admin/travel-days`, ADMIN-only and unrouted from this arm — the demo control §6 bans).
- **The note is attributed per day, not per range.** Discarding un-noted ranges before testing
  coverage let a block spanned by `[{05, 'Skye'}, {06, null}]` collect exactly one note and print it —
  a sentence about Wednesday rendered against a row covering Thursday. Un-noted ranges are the
  ordinary case and the backend does not merge adjacent ones, so this is a normal shape. Note the
  naive repair (`some` → `every`) silences two adjacent ranges carrying the *same* note, which works
  and should keep working; both cases are now pinned.
- **Neither door carries a count, and a door with nothing behind it is not drawn.** "4 regions →" is
  the species §6 bans outright — the same charge that removed P7's "61 coastal locations →". "3 live"
  is arguably about tonight and is dropped anyway, for a reason about the *pair*: two tiles of
  identical construction where one carries a number and the other cannot reads as a defect in the one
  that does not. The zero case — the only thing a count would have protected the reader from — is
  answered structurally instead, which is the honest form of "3 live".
- **"Nothing behind it" has three terms for the grid, and review found two of them.** The first gate
  was `upcomingEvents.length > 0`, wrong twice. **Viewport:** everything `HeatmapGrid` renders is
  `hidden sm:*`, so below 640px the door drew a tile that opened an empty bordered box and fired one
  astro request per date for content that cannot paint — the v1 arm wraps the same disclosure in
  `hidden sm:block` (`DailyBriefing.jsx:1526`) and re-parenting dropped that guard. **Travel days:**
  the grid drops away columns itself, so an operator away across the whole horizon got a door
  promising "every region, every window" over a panel holding one dashed band. `windowCards` is the
  travel-filtered set by construction and is the honest denominator; the viewport term is a hook
  rather than CSS, because `display: none` would still mount the panel and fire the requests.
- **The panels mount once and then hide, and the reservations they need go on the panel.** `hidden`
  rather than unmount, because `aria-controls` must resolve and because the regional panel fetches one
  astro request per date on mount. And a re-parented component brings focusables the sticky bar knows
  nothing about — the grid's cells are `role="button"` divs with `min-height: 52px`, *shorter* than
  the bar's 53.5px, so an unreserved one is not partly obscured but obscured entirely. None can take a
  `.wf-` class without editing the v1 arm, so `scroll-margin-top` reaches them by descent from
  `.wf-door-panel`. The same rule fixes a stacking collision that could not exist before P9:
  `.heatmap-cell-hoverable:hover` is `z-index: 40` and `.wf-lens` is `20`, values that never met until
  the grid was put under a sticky bar — lowered to 10 **scoped to the panel**, so the v1 grid keeps 40.
- **§5b's LITE reconvergence is NOT made, because its premise is false.** The plan assigns P9 the
  `HotTopicStrip` fact blur, calling it "the single place to make it". On inspection it is one of five
  LITE treatments in that component — the pill is `opacity: 0.45`, `canExpandRich` and
  `canRevealRegions` are both forced off, `handleClick` returns early, the tide chart is blurred as
  well as the facts, and an "Upgrade to Pro" call to action replaces the lot. Editing only the blur
  leaves a greyed, inert pill carrying sharp numbers, which is strictly *more* incoherent than today;
  editing all of it is a freemium-policy change, not a layout fix — `freemium_ui_strategy.md` does not
  mention hot topics at all, so there is no written policy to appeal to, and the strip as it stands is
  exactly the treatment CLAUDE.md's role-gating pattern prescribes. **The rows were the argued
  exception, not the strip.** What is left standing, recorded rather than hidden: for a LITE user in
  this arm a tide's metres and a snow depth are readable on the window card's attribute row and
  blurred on the same topic's pill, so for those two channels the tease is already defeated.
  **Handed to P15**, decided once across both arms — the shape §2.8 settled for the pick gloss.

**⚠️ Height budget, measured at P9 — and this is the phase that finally gives it back.** 1280×720
against an injected payload. Six windows, every card open: per-card **201.3px** (strip, no rows),
**252.3px** (one row), **291px** (two rows), shell **1,969.3px = 2.74 viewports** — reproducing P7's
predicted 1,970px and 2.74 viewports *exactly*, from a different direction. The same six in the
shipped default: a **45px** collapsed card, shell **996.3px = 1.38 viewports**. **Collapse removes
973px, 49.4%.** The away row is **40.5px**, the doors **64.8px** closed, and the lens bar is unchanged
at **53.5px** (which is why `scroll-margin-top`'s 60 still holds). On a phone (375×812) the bar is
50px, the doors stack, six collapsed windows run **1,135px** against P7's 2,448px open, and there is
no page-level horizontal overflow (375 = 375). ⚠️ A collapsed card is **75–105px on a phone**, not
45px, because the badge group wraps to its own line — the mock's phone rule (`order: 7;
margin-left: auto` on `.exp`, `.best` and `.badges` on their own flex-basis lines) is **P14's**, and
the number is recorded here so it is not discovered there.

**The adversarial review before this landed** (six prosecutor lenses, one refuter per charge, then
synthesis — 38 agents): **31 charges, 25 refuted, 6 real**, all fixed before the commit. Every charge
against the count semantics was refuted, as were four separate charges against the disclosure's ARIA
and three against the collapse state model. What survived is worth carrying, because five of the six
are one species:

- **Re-parenting a component does not bring its guards.** Three of the six findings are this: the
  `hidden sm:block` wrapper the v1 arm puts around the heatmap, the `scroll-margin-top` its new
  neighbours need, and the `z-index` its hover state now collides with. None is visible in the diff,
  because the diff shows the *new* call site and the defect lives in what the *old* one did around it.
  **When re-parenting, read the v1 call site's wrapper, not just the component's props.**
- **A charge can be right about the code and wrong about the failure.** Twenty-five were refuted, and
  the refuters' most common ground was not "the code doesn't say that" but "the scenario cannot
  occur". Prompting the skeptic to default to REFUTED without citable evidence is what kept the
  signal-to-noise usable at this fleet size.
- **Two of the six could only be reasoned to, not seen.** The obscured-cell case needs a document
  tall enough to park a cell against the bar, and no fixture the browser could stage produced one:
  the fix is demonstrably *active* (neutralising it at runtime moved the worst clearance from 118.1px
  to 88.1px) but the failure itself was never reproduced. Said plainly rather than implied.

**What was verified live, and what was fixtured.** The local DB still has never had an evaluation
run — measured again at P9, unchanged since P6. Verified on **real data**: the doors' gates in their
first-run state (regional door present at 1280 and absent at 375; hot-topics door absent, because the
real payload carries zero topics), `Home not set`, and `45 min · weekday default · 0 spots across 3
windows`. Everything else was seen in a real browser against an **injected payload**: every height
above, the collapse default and its toggle, the away row's placement *between* two cards and its
copy, the rail away tile keeping its sun times (the claim the row makes about another surface), both
panels rendering, the hovered cell computing `z-index: 10` inside the panel while the v1 rule still
reads 40, and the contrast figures — expander **10.01:1**, door title **13.98:1**, door state
**9.68:1**, door description **6.75:1**, away row body **7.14:1**, away date range **15.47:1**, all
composited over their real backdrops. **Not verified:** the obscured-cell failure itself (above), any
of it against real ratings or real drive times, and no screen reader, axe or Lighthouse pass — the
same gap §5a, §5b and §5c all record. Chrome only.

---

### 5e. What P10′ decided — read before P11, P14 and P15

**The scope was one component, not two.** The §5 row's Work cell reads "Peek content kind 1 (spot)
**+ click-to-map**", and click-to-map shipped at P6 — §5a`:603` already corrects the row. P10′ owns
`WindowSpotPeek` and nothing else.

**Three decisions the plan did not make.**

- **Its own portal, not `PopoverHost`.** The row says "the host is P4b" and the portal *reasoning*
  is taken from there verbatim — `position: fixed` is necessary and not sufficient, because a
  `transform` on any ancestor re-bases a fixed descendant onto it. All three hazards are live on
  this surface and were measured: `.wf-spots` is `overflow-x: auto` (so `overflow-y` computes to
  auto and it clips on both axes), the window card sets `overflow: hidden` as an **inline** style no
  stylesheet rule can outrank, and `.wf-spot:hover` applies `translateY(-2px)` to the very button the
  panel hangs off. But the host itself does not fit: `computePopoverPlacement` anchors *above* and
  never flips, and `PopoverHost` takes only `{popover, className}` — no slot for the pointer handlers
  the 120ms panel grace needs, and no way to express an arrow offset. Using it would mean widening
  two shared files to change behaviour the day rail depends on, for the four lines that are the
  portal. So `WindowSpotPeek` calls `createPortal` itself and `useSpotPeek` re-states the
  capture-phase scroll dismissal, both citing their source.
- **No phone peek at all.** The row says "phone peek via `BottomSheet`" and names no trigger — not in
  the row, not in §5a`:689-702`, not in §7`:1277-1282` — while the same paragraph gives the phone's
  only tap to the map. Shipping the sheet with no trigger is dead code; inventing one adds an
  affordance to the screen `Adversarial Review.html` charge c2 already convicted, competing with a
  tap target that is 72% of the viewport. And the richer destination is one tap away and strictly
  better: the map overlay carries the same two scores and the whole paragraph rather than a clause.
  A consequence worth stating: `BottomSheet` still has no Escape handler (verified across
  `BottomSheet.jsx`, `useDialogFocus.js` and its test — zero hits), and P10′ deliberately did not fix
  it, because nothing P10′ ships uses it. If a phone peek is ever wanted it lands with P11's
  drill-down, which is a sheet that already has a trigger and a reason.
- **A summary-less spot now gets a peek.** `CloseToHome.jsx:835` returns early on a missing summary,
  and its Javadoc gives the reason: with the score bars removed, the rating and the drive both come
  from the card, so the panel "would restate what the reader can see and add a prompt". P10′ restores
  the bars, so the premise is gone. The rule was re-decided rather than copied: the gate is
  **"carries something the card does not"**, and under it scores-without-a-sentence qualifies. Seen
  working in the browser. Triage is deliberately *not* a fourth key — a triage message states why the
  pipeline did not look, which is a fact about the run rather than about the sky.

**A position on all eight of `CardHoverPreview`'s deliberately-removed items**, where §7 reconciles
only the first. **In:** the score bars (§7's reason, and §7's tripwire stands — if the pilot reports
the panel feeling like a glitch they are the first thing back out). **Out:** the generated-at
timestamp (the rail footer states the forecast's age once for the whole screen, and §2.7's rule
against marking one fact twice binds harder here than in the v1 arm); the region line (the spot card
prints it one element away); tide detail (P7's attribute row carries the window's tide directly above
the strip, and the payload has no per-spot tide anyway); the header bar and the ✕ (modal furniture,
and §7's tripwire is precisely that this must not read as modal weight — the panel closes on
pointer-leave and on Escape, so a close button would be a control no assistive technology can reach);
the footer bar, though the prompt it held stays as prose; and the location name, because the arrow
tethers the panel to the card that just named it — measured, the panel is **280px** against a
**283px** card, so it sits squarely under the name it would repeat.

**Timings deviate from the row, and the deviation is the point.** The row says 140/160/120ms.
160 (leaving the card) and 120 (leaving the panel) are adopted, and splitting them is new —
`CloseToHome` uses one 140 for both, and they are not the same journey. The **open delay stays at
`CloseToHome`'s 180, not the row's 140**: that Javadoc argues 180 is "the whole difference" between a
panel that answers a question and one that fires at a mouse crossing the row, and the hazard is
*larger* here — a grid is crossed one card at a time, where this strip is seven cards at 8px spacing
that one sweep towards the `‹ ›` arrows crosses all of. Shortening it on the denser surface is the
wrong direction.

**`PEEK_ESTIMATED_HEIGHT` was re-derived, because `CloseToHome`'s 170 is licensed by a premise P10′
removes** — its Javadoc says the content is "bounded by construction (a stars row, one clause, one
prompt line)". Measured on the running app with both scores: **163px** with a one-line clause and
**202px** at the three lines a 252px content box allows. 220 rounds that up. The rule the v1 CSS
records — *deliberately no `max-height`*, because the panel before it "capped at 260px with overflow
hidden, which is what cut the second score bar in half" — is the one most at risk here, since P10′
puts those bars back. Confirmed `max-height: none` on the running app.

**A defect found in the browser that no test could see: `--color-marginal` is declared nowhere.**
`grep` finds three uses (`CloseToHome.jsx:48`, `CardHoverPreview.jsx:85` and the copy this phase
started from) and no definition, and `getComputedStyle` returns the empty string — so the v1 peek's
stars have never been amber, they have always fallen back to inherited body ink and looked plausible
enough that nobody noticed. The new component names `--color-verdict-marginal`, which resolves to
#E0A542 and measures **7.30:1** on the panel. The v1 arm is frozen for the flag comparison, so it is
left alone and handed to P15.

**⚠️ Handed up to P15 — a third cross-arm item, alongside §5d's two.** §7 says the peek needs no new
gating, citing `freemium_ui_strategy.md:79` (§7 itself cites `:79-80`; an earlier draft of this
section said `:78`, which is the table's separator row), which lists "Score (Fiery Sky %, Golden Hour %)" as
LITE-included. **The shipped code disagrees with the policy doc**: `MarkerPopupContent.jsx:1165` gates
both scores behind `role !== 'LITE_USER'` and `:1175` shows LITE an explicit "Upgrade to Pro for
Fiery Sky & Golden Hour scores". So a LITE user with the flag on would read the two scores on the
peek and, one click later, be told in the map overlay that they must upgrade to see them. P10′
follows §7 rather than overturning it on the strength of one call site, because gating here would
need either a role below the shell — which P7 pins against — or the decision baked into the score
index, which is freemium policy decided in a data structure by one phase for one panel. It is the
same shape as §5d's two items: **one decision, made once, across both arms.** Note the sharpened
form: for a LITE user the peek would be the *only* place the scores appear, and it is `aria-hidden`.

**What was verified live, and what was fixtured.** The local DB still has never had an evaluation
run, and `BriefingHonestyFilter.fullRewrite` empties every region's slot list while
`scoredLocationCount` is 0 — so the strip, and therefore the peek, has **no** real data path. Even
after `POST /api/briefing/run` (which does work, ~43s, £0) the payload comes back with `slots: []`
everywhere. Everything below was seen in a real browser against an injected payload: the peek opening
on a pointer rest; the portal escaping all three clipping hazards, measured at **160px past the
scroller's bottom and 120px past the window card's**, fully visible; the below placement at a **10px**
gap with the arrow tip landing on the card's centre **to the pixel**; the above-flip at a 640px
viewport with 138px of room below, anchored from the bottom and drawing over the sticky lens bar
(z-index 60 against its 20); Escape, collapse (the strip unmounts and takes the panel with it) and
capture-phase scroll all dismissing it; the click dismissing the peek *and* opening the map overlay
with the full paragraph the prompt promises; the scores-without-a-sentence case rendering; and the
contrast figures composited over the real `#2A2019` panel — stars **7.30:1**, drive chip **6.44:1**,
score label **6.44:1**, score number **13.00:1**, clause **6.44:1**, prompt **5.99:1**, no muted ink
anywhere. All eight `.wf-peek*` rules and the reduced-motion rule were read back out of
`document.styleSheets`, because P9 lost a rule to a comment edit that lint, build and 2,636 tests all
passed over.

**What the adversarial review changed, because the pattern is worth carrying.** Six lenses filed 18
charges; 12 survived refutation, 8 distinct after dedup, and **all 8 were fixed before the commit**.
Five are worth not re-deriving:

- **An unrated spot's peek drew `☆☆☆☆☆`.** `starGlyphs(rating ?? 0)` coerced *unknown* to *zero* and
  then rendered the canonical glyph for zero — contradicting the card 10px above, which omits its
  badge precisely because "an unrated spot is one nothing has looked at, which is a different
  statement from a poor one", and contradicting the same file's own drive chip and score bars, both
  already gated on absence. Reachable rather than theoretical: the score index is fetched **once**
  while the briefing polls, so a slot can carry a score with no usable rating — the divergence
  `resolveSpotPeek` is written to expect. It was **pinned green** by a test asserting the five hollow
  glyphs under the comment "Absence, not zero", which is how it would have hardened into the baseline.
- **Closing the map overlay re-opened the peek unbidden.** `MapOverlay` runs `useDialogFocus`, which
  re-focuses whatever was active when it opened — the spot card the reader clicked — and `onFocus`
  cannot tell that from a keyboard arrival. A panel painted 180ms after the ✕, anchored to a card the
  pointer was nowhere near, and no *pointer* gesture could close it (no `mouseenter` ever fired, so no
  `mouseleave` was pending). Inherited verbatim from `CloseToHome.jsx:525-528`, which ships in v1
  today; fixed here because the hook is new and unfrozen. One `focusHandedBack` ref, cleared by the
  pointer path so a browser that never restores focus cannot strand it.
- **Two peeks could be on screen at once.** The hook's own Javadoc claimed the `focusin` listener
  bought the "exactly one panel" guarantee back. It does not: `focusin` fires on a focus *change*, so
  it covers open-by-pointer-then-Tab-away and **not** the reverse, and a pointer moving between two
  expanded windows' strips sends the first strip nothing at all. A **new** regression — v1's single
  `preview` state is structurally immune. Bought back properly with a module-scoped `openPeek`
  dismisser that every `open()` closes first.
- **A test that could not fail.** `expect(bar).toHaveTextContent('0')` for the lower clamp is a
  substring match against the whole bar *including its label*, so `"Golden Hour-20"` contains `"0"` —
  deleting `Math.max(0, …)` left all 26 tests green. Caught by the review, not by the 62-mutation
  sweep, because the sweep's own expectation string was the test name rather than the assertion.
- **Two contrast figures were wrong**, both by the same mistake this project has now made seven
  times: measuring the *token* rather than the *composite*. The drive chip carries
  `rgba(255,255,255,0.05)` of its own, which lifts its backdrop to rgb(53,43,37) and the ratio to
  **5.82:1**, not the 6.44:1 the bare panel gives; and the muted figure quoted in a code comment as
  3.9:1 is **3.46:1**, a number no backdrop in the palette produces. Both conclusions held, so nothing
  rendered wrong — but §5e is what P15 reads *instead of* re-measuring.

All eight fixes were then re-verified in the browser, since the review itself rendered nothing: the
unrated card's peek draws **no star row at all** (the drive chip, the two bars and the prompt, and
nothing where the zero-claim was), and closing the map overlay leaves the clicked card focused —
`document.activeElement` is that card — with **no panel painted**, which is exactly the state that
produced the phantom before. Sixty-five mutations were re-run afterwards and all sixty-five were
killed; two of the first run's survivors were themselves informative and are worth carrying. One was
an **equivalent mutant** (`?? 0` inside a branch now gated on `rating != null` is unreachable, so
adding it back changes nothing and should kill nothing). The other was a **test that could not fail**:
"a browser that never hands focus back" asserted that a hover opens a peek, which the guard never
gated — the assertion has to be on a focus *after* a hover. And deleting the `focusin` listener
stopped failing anything once the page-level token shipped, which meant the cross-strip test had been
the only thing pinning it; the case only that listener covers is focus landing on a card whose slot
resolves to **no** peek, where `open()` never runs and nothing hands the token on. That now has its
own test.

One charge the review raised as a possible fourth finding was **refuted by measurement**: whether
Chrome fires `focusin` when focus reverts to `<body>`, which would dismiss the panel between
`mousedown` and `click` and make its click-to-map silently do nothing. Probed on the running app —
Chrome fires `focusout` on the button and **no** `focusin`. The card's `onBlur` still starts the
160ms grace, and the click lands inside it.

**Not verified, said plainly.** Real compositor-driven scroll dismissal: a hidden Browser pane
dispatches **no** scroll events at all — `scrollLeft` moved from 0 to 291 and listeners fired zero
times at window, document *and* element level — so the dismissal was exercised with a synthetic
event, which proves the capture-phase path and not Chrome's dispatch. The same throttling freezes
`setTimeout` in a hidden pane, which is why every timing check had to be sandwiched between
screenshots; a peek that "failed to open" three times was `document.hidden`, not a defect. Touch and
coarse-pointer gating (no touch device — pinned by three unit tests), the cross-strip `focusin` rule
(only one window card is open by default — pinned by a unit test), any of it against real ratings or
real drive times, and no screen reader, axe or Lighthouse pass. Chrome only.

### 5f. What P11 decided — read before P13, P14 and P15

**The row's three things are not equally specified.** The sheet is fully specified by the mock; the
rating floor nearly so; the type control is specified against a taxonomy that does not exist in this
product. Four decisions the plan did not make, then the one it made against itself.

- **⚠️⚠️ REVERSED for the rating floor. It is now a page-wide control ON the bar**, by a later
  design handoff; the type control stays in the sheet. `utils/ratingLens.js` carries the reasoning
  and `CHANGELOG.md` the summary, but the short version belongs here because this is where the
  opposite decision is written down: the hazard the paragraph below states — a card reading
  `best 5★` over a strip the floor had emptied — **cannot happen**, because a floor removes from
  *below* and so never removes a window's best-rated spot. Either the best survives the floor, or
  the strip is empty and says which threshold emptied it. What the plan got right and is preserved:
  the sheet still has a rating control, but it now *inherits* the bar's floor and its own change
  dies with the dialog, exactly as its reach control always has. The `scroll-margin-top` consequence
  the paragraph below settles is also reversed — the bar wraps, has four measured heights, and the
  reservation is a variable the shell measures rather than a literal. **Everything from here to the
  end of this bullet is the superseded P11 record, kept because it is the record.**
- **⚠️ The rating floor and the type control went into the SHEET, not the lens bar — §5c`:908`
  assumed the opposite and that sentence is now wrong.** It reads "P11, which adds a rating floor and
  a type control to the same row", and it is the reason `scroll-margin-top: 60px` exists. The mock
  puts all three in the sheet and argues it; the deciding reason is neither's. **They have a
  different scope.** Reach is a judgement about *today* that governs every window on the page, which
  is why one sticky control serves six cards. A rating floor and a type are about the list in front
  of you — and a page-wide floor collides head-on with §5c`:913-918`, which deliberately does **not**
  re-derive a window header's `best N★` from any gated set: a global 4★ floor would leave a card
  reading `best 5★` over a strip its own control had emptied of everything below 4. Two Guilty
  charges point the same way (c2 on affordance pile-up, c5 on bar chrome), and P14's phone bar is
  already a single horizontally scrolling row that three segmented controls would make long.
  **Consequence, re-measured on the running app rather than assumed: the bar is still 53.5px, so 60
  and 76 still hold.** P11 adds one focusable to the pane (`.wf-film-all`) and it takes the same
  reservation; the sheet's own controls need none, because nothing scrolls a modal under the bar.
  P14 must still re-measure — a phone bar is 50px and may yet wrap.
- **The type control ships with the product's own words, and its options are DERIVED from the
  population.** The mock's `TYPES` (coast / river & lake / upland / landmark) is terrain vocabulary
  and not one of its five words is a `LocationType`; §6 bans inventing any. The real enum is
  LANDSCAPE / WILDLIFE / SEASCAPE / WATERFALL / BLUEBELL / **WOODLAND** (not "CANOPY" —
  `slot.canopy` is a *briefing* flag, a different thing), and `utils/locationTypes.js` already exists
  as the single source of truth for presenting it, feeding the map's own type filter, the marker
  popup and the briefing rows. So the vocabulary problem dissolves — with one correction the
  pre-build panel forced. The set is **`SKY_SUBJECT_TYPES`, not `DISPLAY_TYPES`**, and the
  difference is WOODLAND. `slot.canopy` is *not* "carries WOODLAND": `BriefingSlotBuilder:131` forks
  on `LocationEntity.isWoodlandOnly()`, which is WOODLAND *and no* LANDSCAPE/SEASCAPE/WATERFALL — so
  a wood with an open aspect is in this sheet, and `DISPLAY_TYPES` would have offered it a
  "Woodland" chip over a population the *enclosed* woods had already been removed from, with nothing
  on screen saying so. Allen Banks is the concrete case and is guaranteed by three migrations (V84
  sets `bluebell_exposure = 'WOODLAND'`, V132 deliberately leaves its LANDSCAPE alone, V134 adds the
  WOODLAND type), so this was reachable rather than theoretical. `SKY_SUBJECT_TYPES` is exactly the
  right set and already carried the reasoning: "types whose subject is the sky … WOODLAND is
  deliberately ABSENT: a location under a canopy has no sky to forecast." Allen Banks now reads
  *Landscape*, which is true of it and is why it is in the list at all. BLUEBELL is absent from both
  sets anyway, for the reason `locationTypes.js` records. The join is by
  **name**, from the `locations` prop P9 already drilled into the shell — passed as a lookup rather
  than folded into each spot descriptor, which is the argument the card already makes for
  `scoreIndex`. **Deriving the options from the spots is what makes it safe**: a chip is offered only
  when two or more types are actually present, so no chip can match nothing, a one-type window draws
  no control at all, and a roster that never arrived draws none either.
- **A lens is not a gate when it has no data — §2.5 rule 1, extended to both new axes, and the two
  halves only work as a pair.** The rating control is offered only when something in the window is
  rated, **and the floor does not run when it is not offered**. Without the pairing the persisted
  floor is a trap: 4★ kept from a scored evening would silently empty an unevaluated window with no
  control on screen to explain it or take it back. The type control has the same pairing for the same
  reason.
- **⚠️ An unrated spot FAILS a rating floor, which is the opposite of what an unknown drive time
  does — and the two rules are deliberately not symmetrical.** The adversarial panel argued the
  reverse from §2.5 rule 1 and §6`:1312`, and the argument is a good one, so the reason is recorded
  rather than assumed. Three things settle it. **The precedent already exists, in one function.**
  `CloseToHome.keepCard` resolves both questions on *adjacent lines*: `:391` keeps a card with no
  drive time, `:392` drops a card with no rating. That is not an oversight to be copied — it is this
  product having made the identical pair of judgements once already. **The two absences mean
  different things.** An unknown drive time is a gap in *per-user* data about a spot that is
  otherwise fine; an unknown rating is a gap in the very axis being filtered, and "4★ and up" is a
  claim about what was measured. **And the failure §2.5 rule 1 guards is already closed by the
  pairing above, not by this**: rule 1's stated purpose is that "the lens becomes a visible no-op
  rather than silently emptying the page", and with nothing rated the control is not drawn and the
  gate does not run, which satisfies §6's "no control gates on data that does not exist" literally.
  The only case left is a *part*-rated window, where the control is on screen, the empty line names
  it, and one click undoes it. It is written as an explicit null test rather than
  `(rating ?? 0) < min` because that coercion is what §5e caught rendering `☆☆☆☆☆`.
- **Persistence: one new key, `photocast.planRating`, holding one field.** §5c`:866-876` settled the
  shape and the mock does the thing it rejected (`{reach, dRate, day}` in one key, read–modify–write).
  Whole-value write, never reads storage back. **No day stamp, and that is the difference from
  reach**: "how far will I drive tonight" is a judgement about one evening, where "I only care about
  4★ and up" is taste. It has no "today only" pill and no reset button because it needs neither —
  the `Any rating` chip *is* the reset and is on screen whenever the floor is, and the footer states
  the policy in words. Reach is inherited from the bar and never stored by the sheet; type starts
  loose every visit, which costs nothing because closing unmounts the component.
- **`Modal` in `bare` mode, and the sheet's state lives in the shell.** `BottomSheet` is the phone
  sheet and has no Escape handler (§5e). `WindowPickDialog` is this arm's precedent for `bare` and
  proves the mode can express arbitrary panel geometry, so the only thing lost against the mock is
  `top: 34px` — dropped rather than reproduced, because `Modal` centres its child and that is the
  shape all fifteen dialogs in the app take. Shell-level state, like `openPick`, makes "exactly one
  sheet" **structural** — no page-level token, which is the machinery `useSpotPeek` needs only
  because peek state is deliberately per-strip. It holds the window's **key**, not the card: cards
  are rebuilt on the ten-minute poll, on the reach fetch and on every lens change, so an object would
  leave the sheet filtering an array nothing else on screen still uses.
- **The trigger is the design's "See all", and it carries no number.** Charge c2 convicts four
  affordances for one intention — but its verdict names this one as the *keeper* ("Keep swipe plus
  'See all'"), and cuts the arrows, which are already pointer-only by media query. The number goes
  because the footer's count sits 8px to its left (§2.7) and because it could not be right anyway:
  the sheet opens on the bar's tier, so "See all 16" would open showing 13. It is also on the
  **fully lens-gated** card, on the end of "12 spots are further out" — a number with no route to
  what it counts is the exact defect CLAUDE.md records against Close-to-home's old four-card cap.
  And it is **absent** wherever the sheet could show nothing the strip does not (`sheetOffersMore`),
  which is the arrows' own rule applied to a different control: fewer than four spots, none rated,
  one type, nothing gated.
- **⚠️ The canopy debt is NOT paid here, against §5a`:610` and `windowFirstSpots.js:30-32`, which
  both name this drill-down as where a bluebell wood belongs.** Read them again and the hazard they
  state is that a woodland 4★ and a coast 4★ are opposite claims in one badge **colour** — and a
  10px grey type word beside a coloured chip does not disambiguate a colour. A three-column grid
  makes that collision *denser* than a horizontal strip did, not looser, and it would be at its worst
  in the default "Any type" state. What those notes actually ask for is "a second vocabulary", which
  is a second badge, not a control. Shipping it half-solved is worse than handing it on. The cost of
  not doing it is smaller than it looks, and it is worth being exact about why. `slot.canopy` is
  **not** "carries WOODLAND" — `BriefingSlotBuilder:131` forks on
  `LocationEntity.isWoodlandOnly()`, which is WOODLAND *and no* LANDSCAPE/SEASCAPE/WATERFALL, and
  that method's own Javadoc names the counter-example: "a wood that also has an open aspect (Allen
  Banks) has a horizon to forecast, so it keeps the sky treatment". So a mixed site emits an ordinary
  slot, is in this sheet already, and **its Woodland chip is real** — the derived options are not a
  guarantee that the chip never appears, only that no chip is ever offered which matches nothing.
  What is excluded is the narrow set whose *whole verdict* answers the opposite question, which is
  precisely the set the badge cannot describe. **The benefit is that every count stays consistent end to end** — the strip
  footer's `N of M`, the sheet's own total and the header's `within reach` all describe one
  population, which they could not if the sheet's list were wider than the card's.
- **No star in the sheet header.** The design writes `N spots · best B`; both go, and the counts move
  to the footer where the sort claim already lives. `compareSpots` sorts rating-first with nulls
  last, so whenever anything is rated the first card *is* the best and badges itself 40px below —
  and where nothing is rated there is no best to state, which is exactly when the window card omits
  its own star. It also removes the one element here that could contradict the card behind it.

**What was verified live, and what was fixtured.** The local DB still has no evaluation run and
`BriefingHonestyFilter.fullRewrite` empties every region's slots, so everything below was seen in a
real browser against an injected payload over the real 16-location roster: the sheet opening from the
strip footer; the reach control inheriting the bar's tier and `· widened for browsing` appearing only
on a **looser** one; 16 cards in a 3-column grid at 880×688 with the list scrolling and **no page
overflow**; two columns at 600px; the rating floor trimming 16 → 4 at 4★+ and **surviving a close and
re-open** while type and reach both reset; the derived empty line naming only the two controls that
were narrowing; a peek open on the strip being taken down in the same commit as the sheet mounts, and
no new peek opening while it is up; Escape closing it and handing focus back to the trigger that
opened it, with focus inside the dialog while it was up. **The gated-card path was reachable after
all** — the fixture's own reach map was rewritten to put every location 200 minutes out, which is
what a fully lens-gated window needs and what the roster never produces: the card then reads
"Nothing within 45 min in this window. 16 spots are further out. See all →", and pressing it opens
on **Any**, marked *widened for browsing*, with all sixteen drawn. Clicking a spot inside the sheet
leaves exactly **one** `aria-modal` element on the page (the map overlay, with the full paragraph
and both scores) — checked by counting them, not by looking. And at 380px the fourth type chip sat
at 382.6px against a 364px card edge before the narrow rule and is scroll-reachable after it. All twelve `.wf-sheet*` rules plus
`.wf-film-all` and the 620px media query were read back out of `document.styleSheets` — P9 lost a
rule to a comment edit and nothing but this catches it. **Contrast, composited against each element's
real backdrop chain rather than its token** (the mistake this project has now made seven times):
title **14.45:1**, widened clause 6.88, close button 6.88, control kicker 6.75, segment off 6.88,
segment on **9.18**, empty line 6.88, footer 7.06, "See all" **10.38**. No muted ink anywhere.

**The lens bar was re-measured at 53.5px on the running app**, which is what retires §5c's warning
rather than an argument that it should not fire.

**Two defects the browser found that the tests did not**, both fixed before the commit and both now
pinned: the header read **"Sunset · 22:41" and named no day**, because on a lead card `when` is the
bare event word and the day lives in the kicker — so a dialog opened from a six-window page said
nothing about which sunset it was; and the footer printed **"0 of 16 · Listed alphabetically."** over
an empty list, `spotOrderStatement` falling through to its fourth sentence when no spot carries any
key, which is a claim about an ordering that never happened.

**What the adversarial review changed, because these species recur.** Six lenses over the staged
diff, one refuter per charge defaulting to REFUTED, then synthesis. Everything below was fixed
before the commit, and the four worth not re-deriving are:

- **Clicking a spot inside the sheet stacked two `aria-modal` dialogs.** `MapOverlay` is itself a
  dialog; the sheet stayed mounted underneath it, Escape had two listeners to satisfy, and the
  reader's place was held in a list they had navigated away from. It now closes first — the same
  thing the strip already does to its peek before the identical handoff, which is where the rule
  should have been read from in the first place.
- **The fully lens-gated card's trigger opened a dialog whose entire content was "nothing
  matches".** Inheriting the bar's tier is right everywhere else and is exactly wrong there, because
  that tier is what emptied the window. It now opens **widened**, says so in the header, and the
  widening still dies with the sheet — which is what charge c6's clause was for.
- **Two tests could not fail, and both were the review's finding rather than the sweep's.**
  `sheetOffersMore`'s "reach withheld" case used a *rated* second spot, so it passed on the
  `hasRatings` branch instead; and the card's `peeksSuppressed` test asserted "no panel" with no
  fake timers and an empty score index, so no peek could have opened whatever the flag did. Both now
  have a control case that fails without the feature.
- **The empty line named controls that could not help.** "Widen the reach" was printed whenever a
  tier was set — including for a user with no drive times at all, where the tier gated nothing and
  is the one control that cannot help them. Each candidate is now tested by **re-running the filters
  with that one control loosened**, so the sentence names only what would work. A consequence worth
  knowing: "clear the type" can never appear alone, because a chip is only offered when a spot
  carries it, so with no other filter set the list is non-empty by construction.

- **The empty state took the card's only scroll container with it.** `.wf-sheet-list` is the sole
  `overflow-y: auto` inside a `max-height` card that clips, and it was being *swapped* for a sibling
  paragraph — so on a short viewport (a landscape phone, a 400% zoom) the header, three rows of
  controls and the footer had nowhere to go. The message now lives inside the list, which is what
  the mock does and what `WindowPickDialog` does with its own prose.

Smaller, all fixed: the trigger's accessible name dropped the day on a lead card — the *same*
defect as the header's, in the control that opens it; the filter bar clipped its fourth chip below
~400px inside a card that clips, with no scroll route (it now takes `.wf-lens`'s own phone answer —
measured: the last chip sat at 382.6px against a 364px card edge, and is reachable after scrolling);
the grid was inset 12px against every other band's 16, from a miscalculation that treated the ring's
room as additional to the padding rather than part of it; the result count is now `role="status"`,
on the **always-mounted** element rather than the conditional empty paragraph, because a live region
inserted in the same commit as its content is unreliably announced — without it a filter press
rewrote the list in silence; the name→type join was written twice in this arm from the same prop and
is now `locationTypes.buildLocationTypeMap`, shared with the regional planner, which is how the five
copies that module already replaced started; the 620px breakpoint became **639**, this arm's single
one (`useIsMobile`'s own boundary, stated in CSS by `.wf-spots` and `.wf-lens`); and `spotTypes`'
Javadoc claimed a join-missed spot was "hidden by none" when `browseSpots` hides it under every
specific chip.

**Sixty-nine mutations were run across five sweeps and all sixty-nine are killed.** One survivor was
an **equivalent mutant** and is now commented as such: `typeOptionsFor`'s second `SKY_SUBJECT_TYPES`
pass *orders*, it does not filter — `spotTypes` is the only guard — so swapping it for the full enum
changes nothing. Four more survivors were all the same thing: fixes made in response to the review
with no test yet. Each one now has one.

⚠️ **The last sweep found the most useful survivor of all: two tests that passed for a reason other
than the feature.** "No peek opens while the drill-down is up" was green with `modalOpen` hard-wired
to `false` — because `useDialogFocus` moves focus on a frame and the peek's own `focusin` listener
dismisses a panel whose anchor is not the focused element. The test was pinning the focus rule, not
the suppression. Letting the focus move **settle** before the hover is what makes it discriminate,
and it is worth carrying: on this surface a dialog has two independent reasons to take a peek down,
so any test of one of them has to neutralise the other first.

**One charge was investigated and DISPROVED by measurement, which is worth recording because P14
will meet it.** The review argued that at 400% zoom (a ~320x256 CSS-px viewport) the card's fixed
chrome — header, a filter bar wrapped to three rows, footer — exceeds `max-height: calc(100dvh -
32px)`, that `.wf-sheet-list` collapses to zero because a scroll container's automatic minimum is 0,
and that the footer is then clipped with no scroll route anywhere in the stack (`Modal`'s overlay
has no `overflow-y` either). The mechanism is real and the arithmetic was right **against the blob
the reviewer read**. It is wrong against the tree, for two reasons that only became true during this
same review: the ≤639px per-segment `overflow-x: auto` added for a *different* charge makes each
`.wf-seg` a scroll container, so the filter bar's rows can compress; and above that width the bar
does not wrap to three rows at all. Measured on the running app at **320x256** (head 88.8, bar
128.5, foot 78.3, card 224) and **660x256** (head 55.5, bar 132.5, foot 34.8), the footer sits
inside the card in both. A `.wf-sheet-body` scroll region was built for it and then **reverted** —
it added a `max-height` breakpoint and made the filter bar scroll away below 520px tall, for a
defect that no longer exists. Two fixes interacted; the second was measured rather than argued.

**Not verified, said plainly.** Anything against real ratings or real drive times — the fixture is
the whole data path, and the reach map had to be rewritten by hand to reach the gated case.
`sheetOffersMore` returning false (unit-tested only; the local roster always has ratings). The residual on the shell's `sheetKey`: a window that vanishes and returns would
re-show the dialog, left undefended because the effect that would release the key is a `setState`
in `useEffect` the lint rules reject and because `isEventPast` is monotonic, so the clock cannot
produce it. Touch. No screen reader, axe or Lighthouse pass. Chrome only.

---

### 5g. What P12 decided — read before P13

Eight decisions that changed behaviour rather than wording. P13 renders this feed, so all of them
are visible to it.

- **`AlmanacSource` is a new interface, not `HotTopicStrategy`.** The two have the same shape and
  that is the trap: §3 said "the range plumbing already exists", which is true of the signatures and
  false of ten of the thirteen implementations. `AlmanacSource`'s contract adds the one rule its
  sibling cannot state — *answer for the whole range or do not exist* — and each implementation
  derives dates from ephemeris, which has no horizon.
- **`HotTopicAggregator` is not reused, for three independent reasons.** Beyond the bounded
  strategies it applies a travel-day filter (a "should I go out tonight" concern that would delete a
  solstice) and honours a simulation override built for demoing the Plan tab. A feed quietly serving
  simulated tides three months out is precisely what the degrade rule exists to prevent.
- **The tide path is genuinely two-source, and the second source was never wired up.**
  `LunarPhaseService.classifyTide` gives the dates, unbounded; `TideRunBuilder` gives the metres and
  clock times, bounded by stored extremes. §3 implied `classifyTide` was already used by the tide
  strategies — it is not, they do not even inject `LunarPhaseService`, and its only callers are
  `ForecastDtoMapper` and `TideFactDeriver`.
- **The degrade rule is mechanical, not per-caller.** `AlmanacEvent.metaOf` drops any null or blank
  value, so a source assembles every fact it might have and the underivable ones fall out. No source
  writes the same null-check five times, and none can emit an empty string as though it were a
  measurement. `isDatesOnly()` is how a client tells a degraded entry from an enriched one without
  probing keys.
- **Meteor and supermoon needed new range logic, because the existing strategies single-emit.**
  Both return on their first match — indistinguishable from "all of them" over four days, and it
  loses three of four showers over ninety. The shower table itself is *not* duplicated: it is
  package-private in `MeteorHotTopicStrategy` and read directly, which is why the sources live in
  the same package.
- **A washed-out meteor peak is reported, where the Plan tab suppresses it.** The strategy is right
  to hide a moonlit shower from "is tonight worth it"; an almanac answers "when is it", and the peak
  date does not move because the moon is up. The illumination rides in `meta` as a caveat instead.
- **NLC is split in half.** Its season bounds are almanac and its firing condition is a clarity
  cache built during the briefing run, so `NlcHotTopicStrategy` returns nothing for ninety days by
  construction. `NlcSeasonAlmanacSource` reports only the span, probing
  `NlcClarityService.isNlcSeason` day by day rather than copying its private constant, and makes no
  claim about visibility on any night.
- **Solstices did not exist and equinoxes are sky-wide here.** There was no solstice code anywhere
  in the project. The equinox anchors are restated in `SolarAlignmentAlmanacSource` because the
  strategy keeps them private, and a **day-by-day agreement test across two full years** — one of
  them a leap year — is what makes that duplication safe. Regions are deliberately empty: at ninety
  days the question is *when*, not *from where*, and carrying a region list would mean a solar
  calculation per location per day to produce something unactionable.

**What the adversarial review changed, because three of these were real.** Nineteen agents — six
prosecutor lenses, two refuters per lens defaulting to REFUTED without citable evidence, then
synthesis. 64 charges laid, 12 put to a defence, 5 survived. All 5 fixed before the phase closed.

- ⚠️ **The threshold decoupling removed an accidental minimum-sample floor and put nothing in its
  place.** This is the finding worth remembering: the *fix* introduced the defect. Before, the
  forward fetch window guaranteed every location ~27 high waters; bounding the sample to past
  extremes meant a location fetched forward-only derived a spring threshold from two tides on its
  second day. Two neap samples put the bar under almost every later high water — a **standing**
  king-tide flag, not an occasional false positive — reaching `forecast_evaluation.surge_risk_level`
  and the Claude prompt. `MIN_HIGHS_FOR_THRESHOLDS = 28` (one spring–neap cycle) now gates the
  thresholds only; averages are reported at any sample size. **When you remove an implicit
  invariant, check what was relying on it.**
- **Spans were clipped at the window edge**, contradicting a contract stated in `AlmanacSource`, the
  controller Javadoc *and* the CHANGELOG. Both tide and supermoon sources now walk outwards past the
  requested range to the true first and last day.
- **A king run was relabelled a spring run** when its perigean day fell outside the window. Perigee
  is a half-day window so exactly one day of a run can be perigean, and clipping it flipped the
  type, the copy and the useful water — correcting itself a day later. Fixed by the same walk.
- `peakDate` on the fallback path named the first *derivable* day, not the biggest; it is now
  `figuresFrom` + `partialCoverage` so a renderer cannot confuse the two. `washedOut` compared an
  unrounded fraction against a rounded reported percentage, so two rows could read "50%" with
  opposite flags.
- The NLC clip flags asked the window rather than the season, so once a year a genuine season
  boundary was reported as a rendering artefact — and the test that should have caught it stubbed
  every day in season and could not distinguish the cases.

Worth keeping: the review also **refuted** several alarming-sounding charges. The 97-day delete
window does not destroy coverage on a short WorldTides response (pre-existing, and a static upstream
cap leaves nothing beyond it to delete); `meta` key-order randomisation is real but harmless and is
the existing house pattern; the feed's UTC `today` is the dominant backend idiom, not a violation of
a London convention; and the feed's spring/king predicate is a strict *subset* of the Plan tab's, so
this narrowed a pre-existing divergence rather than widening it. Do not re-raise these.

**Coverage, said plainly.** 112 new tests, all backend and all unit-level; eight mutants aimed at the
load-bearing claims — including three that revert exactly the defects above — each killed by their
named test. **Nothing has been rendered or seen** — there is no UI for this yet, that is P13. Nothing
has been run against production data; the local `tide_extreme` table is empty, so the enriched half
of the tide path is verified only against a stubbed builder. `V139` is unexercised against Postgres
because Docker was not running. The review itself was **static reading only** — no agent compiled,
ran or rendered anything — and 52 of its 64 charges fell below the verification cut untested.

---

### 5h. What P13 decided — read before P14 and P15

Nine decisions that changed behaviour rather than wording. P13 is the first time anything in §3 was
drawn, so several of them are the plan's own text meeting the payload for the first time.

- **The tab speaks `AlmanacKind`, verbatim off the wire, and never imports `topicCertainty`.** The
  two vocabularies looked like a contradiction and are not: they answer different questions about
  different objects. `AlmanacKind` is a claim about the **date** — its own Javadoc says so — while
  `topicCertainty`'s third value, `chance`, is a claim about the **display**. NLC is honestly both:
  its season dates are fixed and its display cannot be forecast. What could not stand is deriving
  one value two ways, so nothing maps the feed's kebab-case `type` into `TYPE_TO_KIND` — the key
  sets are disjoint (`spring-tide` vs `SPRING_TIDE`), so every row would have silently taken the
  `forecast` default. `topicCertainty.js`, `CertaintyChip.jsx` and their tests are untouched, which
  also keeps the frozen v1 arm frozen.
- **⚠️ No kind chip on an ALMANAC row, and that is not the mock's design.** Every entry the five
  sources emit is `ALMANAC` — `AlmanacKind.FORECAST` exists and nothing writes it — so a chip on
  every row is a word that never varies. It is marker-on-exception instead, which is the treatment
  the confidence channel already takes on the Plan screen: the reassuring value is the page's
  stated default and goes unmarked, only the provisional one is called out. The **footer** states
  the default once, and it asks the rendered set rather than asserting what the plan expects —
  `rows.some(kindLabel)` swaps the sentence rather than appending a clause, because a clause leaves
  the false half still printed above it.
- **Spans here, one card per day on Plan — justified, and the rule is: a list's atom is whatever
  the reader must choose between.** On a four-day Plan the choice is which night to go out, so the
  atom is the day, and CLAUDE.md's reverted tide-run card is right that collapsing hides the run on
  the Thursday someone is looking at. On a ninety-day almanac the choice is which event to plan
  around, and the list is indexed by **event** rather than by day: seventy-nine of the ninety days
  have no row at all, so there is no Thursday row for a span to be absent from, and expanding an
  eleven-week NLC season into seventy-seven rows would bury every other event. The ordering spine
  stays time in both, which is why a span occupies exactly one position and never expands in place.
- **Two columns, not the mock's three.** The mock's right-hand column carries "4 regions /
  11 nights left". `regions` is empty on every entry from all five sources and `@JsonInclude`
  omits the key, so half of it can never be drawn; the other half is the countdown that column one
  already gives, 112px to the left. A third column carrying one duplicate is the empty footer bar
  P5 refused. **"N days left" was considered and rejected** on the same ground: it would fire only
  on a span that started before today, and the end date in column one already answers it.
- **⚠️ `datesOnly` is NOT the degrade signal, and reading it as one puts a "something is missing"
  caveat on a healthy row.** Empty `meta` means different things by source: `TideAlmanacSource`
  drops the whole map when no day could be derived, which is a real absence, but
  `NlcSeasonAlmanacSource`'s `meta` holds nothing except two clip flags, so an *unclipped* season is
  empty **by construction** — it means the span shown is the whole season. Meteor, supermoon,
  equinox and solstice always populate `meta` and cannot reach the state at all. So the caveat is
  gated on `TYPES_WITH_FIGURES` as well as on the absence, and the absence is read off the
  normalised map (`Object.keys(meta).length === 0`), because the key absent, `null` and `{}` are
  three spellings of one state and `!event.meta` catches only the first.
- **A clipped span names only its real edge.** `NlcSeasonAlmanacSource` does not walk outwards the
  way the tide and supermoon sources do — it clips to the request window and sets
  `startsBeforeWindow`. So its `startDate` is *today* on most days of the season, and the first
  build rendered "On now / 9–10 Aug / Began before this list": a start date that did not happen,
  with a note underneath contradicting the line above it. It now reads `Until 10 Aug`, `From 9 Aug`,
  or `In progress` when neither edge is real. The lead word takes the same rule — a clipped entry is
  **"On now", never "Today"**, or an eleven-week season announces that it starts this morning.
- **The fact line is a strict allow-list in a fixed order, and it drops what the prose already
  says.** The order is hard-coded because `AlmanacEvent`'s canonical constructor runs `Map.copyOf`,
  which salt-randomises key order **per backend restart** — reading it off the payload would
  reshuffle the line on every deploy. `zhr`, `radiant` and `bestHours` are dropped because
  `MeteorAlmanacSource` composes exactly those three into its own `detail` sentence; `moonIllumination`
  is kept because it is the one number that sentence omits on precisely the nights worth going out.
  `rangeAnomaly` is composed **into** the range chip (`range 4.6 m · +0.4 vs average`) rather than
  standing alone, because it is signed, one decimal and deliberately carries no unit — beside a
  separate "4.6 m" it reads as a different quantity, and inventing a " m" for it would be the
  parse-forward this project bans. An unrecognised key renders nothing.
- **Tab selection is not persisted, and the feed is fetched lazily, once, latched on the date.** No
  third localStorage key: the two the arm already has are settled taste, and which tab you last had
  open is not — the 05:00 question is about tonight. The fetch lives in a hook called from the
  **shell**, not from the pane, which removes the in-flight-unmount case rather than defending
  against it, and not in `WindowFirstBriefingContext`, whose own class comment is an inventory of
  what the arm fetches for every reader. The latch key is `todayStr` rather than a boolean, so a
  session open past midnight refetches instead of showing a row still reading "Today". It earns its
  own contract not because the data is differently *owned* — it is shared and system-owned, like the
  briefing — but because it is a different **snapshot**: 90 days against four, rebuilt daily against
  ~8-hourly. `/api/almanac` was already on `HttpCachingConfig`'s revalidation whitelist, so a repeat
  load is a 304 and no SWR entry is warranted.
- **The tab bar became a real ARIA tab widget, which it had not been.** Through P12 it carried
  `role="tablist"` and one `role="tab"` with a hard-coded `aria-selected="true"`, no
  `aria-controls`, no id pairing, and there was no `role="tabpanel"` anywhere in the repo. With one
  tab that is inert; with two it is a promise the markup does not keep. P13 adds the pairing, a
  roving `tabindex` so the bar is one stop, and Left/Right/Home/End with wrap — **the first
  roving-tabindex implementation in this codebase**, since `ViewToggle` uses `aria-current` on plain
  buttons and `ManageView`'s tabs carry no roles at all. Selection follows focus. The Plan pane is
  **hidden, not unmounted** — `WindowFirstDoors` fires an astro request per visible date on mount,
  and unmounting would re-fire them on every change back, which is exactly what `ManageView` does.
  It takes both the `hidden` attribute and a display class, and that is defence in depth rather than
  necessity — ⚠️ **two earlier versions of this note got the mechanism wrong in opposite
  directions, and the review caught both.** Tailwind v4's preflight ships
  `[hidden]:where(:not([hidden='until-found'])) { display: none !important }`, which is
  **author-origin and important**: it beats every normal author declaration whatever the
  specificity, so the attribute alone hides the pane and `.flex` does not override it (verified on
  the running app — a `<div class="flex" hidden>` computes to `display: none`). Equally the class
  alone would suffice, since `display: none` is itself what removes an element from the
  accessibility tree. The attribute is kept as the semantic statement and because it is the half
  jsdom can see; the class is kept so a display utility added later cannot quietly re-expose the
  panel. The neither-half-is-necessary fact matters because P14 and P15 will copy whichever
  explanation they read.

**What the browser found that the tests did not.** Three defects, all fixed before the commit: the
equinox qualifier wrapped as "Exact day 22" / "Sept", splitting a date across two lines in the 112px
column (day and month are now joined by a non-breaking space, so a phrase that must wrap wraps
*before* the date); the clipped NLC span claimed a start date it did not have; and dropping the
mock's third column handed its width to the prose. On that third one the **first fix was wrong
twice and the review caught it**, which is the more useful half of the story: the body is 888px
uncapped, not the ~1120 the screenshot suggested, and there is no single width the mock's own `1fr`
resolves to because each of its rows is its own grid container — so a cap justified by that
arithmetic was a number with a made-up reason, and at 820px it closed about 6% of the gap while the
comment claimed the problem solved. Counted on the running app with a Range over the longest detail
the five sources emit: **163 characters to the first line uncapped, 116 at the 620px now shipped**,
which is the tightest cap that does not add a third line to that row.

**And one test that could not fail — twice, in opposite directions, and CI found the second one.**
⚠️ **Never spy on `localStorage.setItem` in this suite.** The no-persistence test first spied
`Storage.prototype.setItem`; `setup.js:27-37` substitutes a plain object for `localStorage` **only
when jsdom does not supply one**, which is what happens on this project's Macs, so the prototype
spy recorded nothing and the assertion passed whatever the shell did. Spying the instance instead
fixed it locally and **failed on CI**, where jsdom's real Proxy-backed `Storage` is present and an
own-property `setItem` is treated as a stored item rather than a method override — so the spy is
bypassed and records zero. Both spellings are green on one machine and blind on the other, and only
the control write (`setItem` then assert the spy saw it) exposed either. The test now observes
through `length`/`key`, which both implementations honour, and compares a key-set snapshot taken
before the interaction. Verified against a Proxy-backed stand-in: the public-API read sees the
write, the instance spy sees none.

**Verified live**, on the running app against the real feed (11 entries, all ALMANAC, 6 of them
dates-only because the local `tide_extreme` is empty): the tab pairing and roving tabindex read back
out of the DOM; Arrow/Home/End moving selection, focus and the panel together, with wrap; the lens
bar withdrawing on Coming up and returning on Plan; **one** `/api/almanac` request across three tab
switches; the error state and its retry, forced by failing the request at the XHR layer, with the
empty state correctly *not* firing; all 22 `.wf-cu*` rules read back out of `document.styleSheets`;
no page overflow at 375px; and **contrast composited against each element's real backdrop chain**,
lowest 6.88:1 (secondary ink on `--color-plex-panel`) against AA's 4.5 — no muted ink anywhere.

**Handed to P14.** The pane's `14px 18px 20px` inset is a literal duplicated across the shell and
the pane, with a comment saying the two must match and nothing enforcing it. It is not a new sin —
the arm carries sixteen inline padding literals and three are already duplicated across files — and
the frontend standard points at a CSS class rather than a JS constant, so folding the arm's 18px
gutter into a rule belongs in P14's pass rather than in a one-off constant here.

**Not verified, said plainly.** No enriched tide row has ever been rendered: the local
`tide_extreme` is empty, so `range`, `rangeAnomaly`, `highWater`, `verdict`, `location` and the
`figuresFrom` fallback are covered by unit tests against hand-written fixtures and have never been
seen. No king-tide, solstice or FORECAST row exists in any real payload either — the first two are
outside a 90-day window from August, the third is emitted by nothing. Touch, screen reader, axe and
Lighthouse: none. Chrome only. And the six identical spring-tide rows visible locally are an
artefact of the empty tide table rather than the production shape — P12 raised the fetch horizon to
97 days precisely so every row inside 90 carries figures.

---

### 5i. What P14 decided — read before P15

The row for this phase says "Responsive pass — real media queries, including the taller rail tile on
phone". Both halves of that sentence turned out to be wrong about the work, and establishing what
was actually left to build took longer than building it.

- **Nine README bullets, not thirteen, and five of the nine were already shipped.** The handover
  said thirteen and listed nine (`README.md:222-230`). Of those: the rail scroller, the attribute
  rows, the doors, the lens bar and the spot strip's `flex: 0 0 72%` **already ship** — the strip's
  72% at `index.css:1965` and its arrows at `:2166`, both of which the handover named as unbuilt.
  "Narrative: single column" is **vacuous**, because the mock's two-column `.wtop` was never built
  and this arm's narrative is a modal. "Promoted strip" has **no referent at all**: P7b never
  shipped and no such component exists in the tree. So the genuinely unbuilt work was the **tab
  bar**, the **window header**, and the **18px→14px chrome gutter** that no bullet names and every
  mock phone rule implies.

- **The migration rule, stated once so the line is not redrawn per element.** *An element whose
  geometry a phone rule changes gets a `.wf-` class carrying its **whole** style object; everything
  else stays inline, untouched; where the object holds a render-computed value, hook on an attribute
  the element already carries rather than inventing a state class or a prop.* Nine elements
  qualified. The alternative — moving only the properties the phone changes — would have left the
  tab's type in the stylesheet and its selected-state paint in JSX, and the card header's `gap` in
  one file and its `padding` in the other. Both hooks already existed: `aria-selected` on the tab
  (the tab pattern requires it) and, for the header, a new `data-open` that only publishes a prop
  the component already had. **Verified behaviour-preserving by measurement, not by inspection**:
  at 1280px every migrated element's computed padding, gap, height and width is identical to the
  pre-change baseline.

- **`--wf-gutter`, because the gutter is one fact that was written seven times.** Declared on
  `.wf-shell` and consumed by masthead, rail, rail-empty, rail footer, tab bar, both panes and the
  exit foot. One declaration moves all of them at the breakpoint, which is what makes the specific
  failure of a partial migration — half the chrome shifting and half not — structurally impossible
  rather than merely unlikely. **14px is this arm's own number, not the mock's**: `.wf-lens` has
  used `padding: 9px 14px` on phone since P8.

- **§5h's duplicated pane inset is closed by a shared class, not a shared comment.** `14px 18px 20px`
  was written in `WindowFirstShell` and `WindowFirstComingUp` with a comment in each asking the next
  reader to keep them in step. Both panes now wear `.wf-body`. Measured on the running app: at 375px
  both resolve to `12px 14px 18px`.

- **The masthead and the rail footer keep everything the mock hides, and this is a deliberate
  departure from the vendored spec.** `mock:239` hides `.sub` and `.ghost`; `mock:245` hides
  `.railfoot .b`. In this arm `.sub` has no referent (`BrandLockup variant="compact"` renders no
  tagline), and the other three are **⚙, Sign out and "Edit reach"** — the only route to settings,
  the only route out of the app, and, in `WindowFirstShell`'s own words, "the only route to fixing
  an empty lens". The mock can hide them because they do nothing in a static HTML page. Following it
  would have given a phone user an app with no settings, no sign-out and no way to widen a lens that
  had emptied. `usePlanLayout.test.jsx`'s "exactly two controls" rule therefore stays
  **unconditional** — and the reason is worth keeping: a CSS-only hide would have left that test
  green while pinning nothing, so if this is ever revisited it must be a JS branch and that test
  must fail. The brand's 20px is kept too: `BrandLockup` derives it from the compact spine's 5px
  pitch (20 = 4 × 5), and the mock's 17px is 3.4 repeats — the exact defect `compact` exists to fix.

- **No taller rail tile.** `git log -S` puts the phrase in `60448d6b` (2026-08-03); the component was
  created by `f91a1113` (2026-08-04). **The phrase predates the file it describes by a day**, and it
  is a prediction that P4c's new two-line pick chip would make the tile taller than the mock's — a
  prediction P4c discharged, breakpoint-independently, with `DATE_ROW_MIN_HEIGHT_PX` derived from
  the chip's own type and applied to every tile at every width. Neither spec supplies a height. The
  rail's only unbuilt item was one line of padding.

- **No tab scroller.** `TABS` has two entries; the mock's `overflow-x: auto` exists because it has
  four. Measured rather than estimated: at 320px the bar's `scrollWidth` equals its `clientWidth`,
  so it fits with room to spare, and the phone type scale (`8px 11px` at 12px) buys ~30px more. The
  cost of building it now is not zero — `overflow-x: auto` computes `overflow-y` to `auto`, and the
  bar's bottom padding is `0`, so it would clip the focus ring of the arm's only roving-tabindex
  widget. **The trigger is written into the component**: a fourth tab needs the scroller *and*
  `padding-bottom: 4px; margin-bottom: -4px`, the technique `.rail-scroller` already documents.

- **The sticky lens bar no longer wraps at any width, and that fixed a live defect nobody had
  looked for.** `index.css`'s reservation comment warned that `scroll-margin-top: 60px` "tracks the
  bar's HEIGHT, so … P14 must re-measure — a bar that wraps to two lines makes this number short."
  It had already come true. Measured: between **640px and ~781px** the bar wrapped to **77.3px**,
  overrunning both the 60px reservation and the 76px one on `.wf-exp`, so throughout the tablet band
  a card scrolled into view parked partly behind the bar. Fixed at the source rather than by raising
  the numbers: the phone block's own justification — "wrapping it would grow a sticky element to two
  or three lines and eat the viewport it is there to keep clear" — is not a phone fact, so
  `flex-wrap: nowrap` and the overflow moved to the base rule. Re-measured at **53.5px** from 640px
  up and 49.5px below, and both reservations are true again.

- **That fix had a second half, and the adversarial review found it.** Stopping the bar wrapping
  fixed its height and left its width: in the same 640–781px band the readout is wider than the room
  left after the controls, and a `nowrap` flex item does not shrink, because its automatic minimum
  size is its own content. Measured at 640px with a production-shaped readout (`45 min · weekday
  default · 12 spots across 6 windows`, 327.6px natural): **116.6px of it painted outside the bar**,
  mid-word, with no scrollbar and no ellipsis to say so — where before the change it had wrapped
  onto a legible second line. It was invisible locally because the empty briefing produces the
  degenerate `0 spots across 5 windows`, which fits. `.wf-lens-res` now carries `min-width: 0`,
  `overflow: hidden` and `text-overflow: ellipsis`. Re-measured across thirteen widths: **zero
  clipping at every one**, the readout ellipsising to 193/253/313/321px at 640/700/760/768, and a
  no-op at 800 and above where the string already fits. This is the phase's own instance of the rule
  the last three reviews keep proving — *a fix can introduce the defect* — and it is recorded rather
  than quietly patched because the trade was a real functional defect for a cosmetic one, which is
  worth doing but not worth leaving half-finished.

- **The window header's two meta clauses share one phone row, because in the spec they are one
  clause.** The mock prints `best 4★ · 6 within reach` from a single `.best` span (`mock:471`) and
  gives it `flex-basis: 100%`. This arm split it in two at P8 so "within reach" could be null
  independently — a nullability difference, not a second clause — so they are wrapped in
  `.wf-wh-meta` and one `flex-basis` governs both. Two orders would have spent two rows saying what
  the design says in one. The wrapper renders only when a child does. **Measured A/B on the running
  app**: with the group present at 1280px, both clauses sit at the same x, with the same 10px gap,
  the same rule width and the same header height as two bare siblings — the wrapper costs zero
  desktop pixels.

- **The tablet band was measured and deliberately left alone.** `index.css:1957-1959` deferred it to
  this phase in as many words. Measured: a spot card is **148.6px at 640px**, **222.6px at 899px**,
  284.6px at 1080px, and 399.6px at 639px — a **2.69× discontinuity across the 639/640 seam**. No
  rule was added. The band's floor of 148.6px is within a pixel and a half of the 150px rail tile,
  which this arm already treats as a workable minimum for a multi-line tile, and the comment's own
  instruction was that this is "not a number to invent here". ⚠️ The thing that would justify a
  number — real spot cards at that width — **has never been rendered in this environment at any
  width**, so the tightness claim remains unevaluated on content. P15's sweep should look at it with
  real data before anyone adds a tenth breakpoint block.

- **What the unit tests may and may not claim.** `vite.config.js` sets `css: false`, `setup.js`
  loads no stylesheet, and jsdom's `matchMedia` stub returns a fixed `{matches: false}` that ignores
  the query. **No media query in this project is evaluated by any test.** So `WindowFirstResponsive.
  test.jsx` asserts only the hooks — the classes and the `data-`/`aria-` attributes the components
  emit — exactly as `frontend-test-standards.md:120-122` requires, and says so in its own header.
  One existing assertion had to change: `toHaveStyle({ padding })` on the card header now reads the
  empty string and would pass against anything, so it became `toHaveAttribute('data-open', …)`. Four
  mutations were run to prove the new tests can fail (dropping `wf-body` from the Coming-up pane,
  dropping `data-open`, removing the meta render guard, dropping `wf-wh-rule`); all four were caught.

**Measured in the browser, at these widths.** 320, 320×256 (400% zoom), 375, 390, 639, 640, 700,
760, 768, 800, 899, 1080, 1280, 1440. Confirmed at every one: no horizontal page overflow, no
element painting outside the viewport except inside a scroll container, and the masthead, rail, rail
footer, tab bar, lens and pane sharing one left edge. The 639/640 boundary flips the gutter 14↔18px,
the masthead `13px 14px 12px`↔`16px 18px 14px` and the bar 49.5↔53.5px. The phone window header
renders as four lines — title run, meta, badges, then the expander right-aligned on its own row —
with the spacer rule `display: none`, which is `README:225` in full. At 320×256, reflow holds:
vertical scrolling only (WCAG 1.4.10).

**The review, and what it cost.** Six prosecutor lenses over the staged diff, one independent
refuter per charge defaulting to REFUTED, then synthesis — 32 charges, 21 refuted outright, one
must-fix (the readout above), two should-fixes, both taken: `.wf-rail-empty` is a class this phase
invented with its own phone rule and nothing was pinning it, and the new test file's header claimed
a completeness it did not have. `.rail-scroller` is now pinned in `WindowFirstDayRail.test.jsx`,
which matters more than it did before this phase: the class used to sit beside an inline style and
now owns the rail's whole inset as well as its overflow. Four mutations were run against the new
tests before the review and all four were caught. Two things the review could NOT do, stated because
the next phase should not read a clean report as full coverage: it ran no visual check of any kind,
and it never rendered the real Plan tab — every number in it came from injected markup against the
real stylesheet.

**Not verified, said plainly.** The **spot strip, attribute rows, promoted-strip variants and the
meta row were never seen on real data**: `BriefingHonestyFilter.fullRewrite` empties every region's
slots locally, so the pane renders "0 spots across 5 windows". The meta row and the spot cards were
exercised by injecting the exact markup the components emit, which tests the CSS rule and not the
component's decision to render it. The **rem/px seam is untouched and still live**: `useIsMobile.js:3`
is `(max-width: 639px)` in px while Tailwind's `sm:` is `40rem`, and `WindowFirstDoors` gates the
regional door on the first because `HeatmapGrid` is `hidden sm:grid`; at a non-default browser font
size they diverge and that band renders a bordered box around a `display: none` grid. The mechanism
is certain from the code and **was not reproduced** — media-query `rem` resolves against the initial
root font size, which JavaScript cannot change, so only Chrome's own font setting reaches it. The
owner deferred the fix to its own commit; it touches four `useIsMobile` callers, two in the frozen
v1 arm, and five hand-rolled `matchMedia` stubs that key on the literal string `639px`. Also
untouched and worth knowing: **`<main class="px-4 py-6">` adds 16px each side at every width**, so
the real phone inset from the screen edge is 16 + 14 = **30px**, not the mock's 14 — it is shared
App chrome the frozen v1 arm uses, so it is P15's to decide, not a responsive pass's to change. And
still, as of every phase since P4: no touch, no screen reader, no axe, no Lighthouse, Chrome only.

### 5j. What P7b decided — read before anyone revisits the strip

Nine decisions that changed behaviour rather than wording.

- **A coincidence is `badges.length >= 2`, and emphatically NOT "`topRarityRank` is present".**
  `PlanWindowProjector.rarestRank` is a `min()` over the window's badges and returns null only for
  an *empty* list, so that field is populated on every single-badge window too — a strip keyed on it
  would put the pane's largest element above a page whose only "coincidence" is one aurora.
  `topRarityRank` is the **tie-break**: which of several coincidences wins the one strip. That is
  what `BriefingWindow`'s own Javadoc has always said ("Advice for the client's promoted strip"), and
  P7b is its first consumer in five phases.
- **The badges counted are the window's, not the card's — hence `allBadges`.** `card.badges` has
  already had the attribute rows' promotions filtered out of it. Counting that list would make a
  winter dawn carrying `SNOW_TOPS` + `SNOW_FRESH` — a real coincidence, and the *only* reachable
  same-channel one, since `KING_TIDE` suppresses `SPRING_TIDE` and `STORM_SURGE` never gets an event
  anchor — look like a single-badge window because one of the two became a row forty pixels lower.
  `allBadges` is the same before/after pairing `allSpots`/`spots` already carries.
- **⚠️ The chart is NOT built, and both halves of the reason matter.** §5's P7b row and README §3
  specify a 320×44 tide curve with two amber markers and four labelled extremes. *The label band is
  not derivable*: `BriefingWindowTide` carries exactly **one** extreme and no per-extreme x position,
  and that one clock time is wrapped modulo the day (`TideWording.clock` uses `floorMod`), so a 23:45
  sunset's 00:05 high water would plot at the opposite edge of the axis. The four-label dataset
  exists only on `hotTopics[].tideRun`, which only the two tide strategies set and which selects its
  representative coastline *independently* of the window rollup — `TideRepresentativeSelector`'s own
  Javadoc says the two "may name different places" — so the join can silently plot one coast's labels
  over another coast's curve. *And the curve alone would not earn its place*: it would be the FOURTH
  tide chart in this arm on one pane (the same window's own `WindowTideSparkline`, plus `TideRunRow`
  and `SurgeRunRow` behind the hot-topics door), and it is meaningful for only 2 of the 12 topic
  kinds that can become a badge at all. To build it honestly: add an extremes list to
  `BriefingWindowTide`, or gate a `tideRun` join on `tideRun.locationName === tide.locationName`.
- **Nothing comes out of the card, and §6's "something should usually come out" is therefore NOT
  discharged.** Taking the promoted window's badges was the obvious trade and was rejected on the
  codebase's own doctrine. `windowFirstRows.js` promotes a topic to a richer surface *on the same
  card, forty pixels away*, and closes with "Nothing is ever lost: a topic the cap drops keeps its
  badge". The strip is not on that card — it may be four items up the pane — and badges live in the
  **always-visible card head** while rows live inside `{open && …}`. Removing them would make the
  page's most notable window its least-marked card, for a reader who has scrolled past the strip.
  What is offered instead: the strip is capped at one, renders only on a coincidence, and is the
  smallest it can be. **Measured across two real coincidence shapes: 115–131px desktop, 192–208px
  phone** — the range is the difference between a keyed headline fact (two lines) and a keyless one
  (one), and the phone figures are with the labels the strategies really emit
  (`Noctilucent cloud season`, `Kp 5 · glow reaches ~57°N and north`), which wrap. ⚠️ **An earlier
  draft of this row said "131px desktop / 154px phone"; the phone half was wrong by 54px** because
  the harness used an invented short label. The phone strip is about a quarter of an 844px viewport.
  Against that: the ~150px the height-budget analysis assumed, and the 58px of chart it does not
  spend.
- **It is a lede that points INTO the list, not an item in it.** The pane's only ordering spine is
  time and the strip may describe the fourth window down — the shape CLAUDE.md records as "tried,
  reverted" for tide runs. What makes it tolerable is that it names its window in the card's own
  words (`card.kicker` + `card.when`, so the two cannot disagree) and carries a control that opens
  and reveals that card. **When the promoted window is already the pane's first item the control is
  not rendered**: it would scroll to the element directly beneath it, and a control with no visible
  effect is what §6 bans. That is why the builder takes `paneItems` rather than `windowCards` — an
  away row above the first card makes the strip non-adjacent, and only the pane list knows.
- **`UNKNOWN_RANK`'s wire semantics, settled here.** It is `Integer.MAX_VALUE` — a real number on the
  wire, not null. Three rules, deliberately different from one another: an unranked kind **still
  counts** toward the coincidence (the topic landed; only its *ranking* is unknown, and excluding it
  would let a missing table row delete a real coincidence); it **loses every rarity contest** (the
  backend's own stated intent); and a missing or non-integer rank is **treated as unknown, never as
  rank 0** — 0 would be rarer than `SUPERMOON` and would hand the strip to whichever window had the
  most broken data.
- **No right-hand meta, and no "why" clause.** The mock's meta is "tonight · tomorrow · Monday — 61
  coastal locations, 2 regions"; the count is a fact about our database, which §6 bans and which
  `WindowAttributeRow`, `WindowFirstDoors` and the day rail have each already dropped for that exact
  reason. A rarity claim cannot replace it: §2.6 rules out the mock's "first coincidence since 2 Mar"
  as an unscheduled historical scan, and a fixed ordinal supports *choosing* a winner but not
  *asserting* a delta. The why clause explains the PAIR, and no field says that — a badge's `detail`
  explains its own topic, so rendering one of two as though it described both is a sentence about
  half the strip presented as the whole of it.
- **It makes no quality claim.** No verdict, no star. A coincidence is about *attributes*, not about
  whether the sky will deliver — and a second verdict render site would have to be reconciled with
  §6 clause 5's "verdict colours consistent in every location". The route into the list is the
  honesty guard: the window's own card carries the verdict, one click away.
- **Ungated, by the same rule and the same reasoning as the attribute rows.** No `role` reaches this
  component. The strip shows topic labels and one measured fact each — the same class of content as
  `WindowAttributeRow`, which ships ungated for every role, and not `HotTopicStrip`'s promotional
  surface. The LITE split remains the one open pricing decision (§6a), untouched here.

**What was measured in a browser, and what was not.** The local DB has never had an evaluation run,
so everything below was seen against an **injected payload** at 1280×900 and 390×844, Chrome only:
one strip on a two-coincidence page and none on a badge-free one; the rarest pair winning over an
earlier one; every token resolving rather than being pruned to the empty string; `color-mix(in
oklch, …)` computing to a real colour; the 3px `--color-tide` left edge; the gradient over
`--color-plex-panel`; the 15.5px title; kicker **9.06:1**, figure label **6.88:1**, value
**14.45:1**, footer action **10.47:1**, each against the composite it actually renders on; the route
opening a closed card, focusing its expander, and clearing the sticky lens bar (which needed a new
`scroll-margin-top` on `.window-card` — it is the first thing on this pane scrolled to
programmatically rather than by focus); the phone rule being dropped; and no overflow at 390px.
**Not verified:** any of it against real badge data, and no screen reader, axe, Lighthouse,
forced-colors, real device, or width above 1440px — the same gap every phase since P4 records.

**The adversarial review before this landed** (six prosecutor lenses over the diff, one independent
refuter per charge prompted to *refute* and defaulting to REFUTED, then synthesis — 19 agents):
**18 charges, 12 verified, 3 confirmed**, all fixed before the commit, plus two comment corrections
the synthesis raised as weak refutations. Six charges fell below the verification cut and are
recorded in the review as **unexamined, not refuted**. The three that survived are worth carrying,
because two of them are failures of the *fixture* rather than of the code:

- **A figure's lead-in fell back to the topic's own label, printing that label twice.** The two live
  producers of a keyless *headline* fact are `AuroraHotTopicStrategy`
  (`HotTopicFact.metric(null, "Kp 5 · glow reaches ~57°N and north")`) and `NlcHotTopicStrategy` —
  both NIGHT topics, which `PlanWindowProjector.keysFor` buckets onto the **same two windows**, so
  aurora × NLC is the ordinary coincidence, not an exotic one. The strip read
  `Aurora possible × Noctilucent cloud season` and then `Aurora possible / Kp 5 · …` beneath it:
  exactly the duplication `windowFirstRows.js` refuses to build a row for. ⚠️ **It survived the whole
  suite, eleven mutants and a browser pass because every fixture invented `{key: 'Kp', value: '5.7'}`
  — a shape the backend never emits.** The justification in the code cited `SnowTopsHotTopicStrategy`,
  which does emit a keyless fact but only as its *second*, un-emphasised one, so it can never reach
  the branch. **Shape a fixture like its producer, or the test proves nothing about production.**
- **The only whitespace between two topic names lived inside the `aria-hidden` separator.** JSX drops
  the newline-only whitespace between sibling elements, so pruning the hidden subtree — which is what
  assistive tech does — left `King tideAurora possible`. Every other decorative glyph in this arm is
  a prefix or suffix whose removal leaves its label whole; this was the first used as the boundary
  *between* two labels. `textContent` is identical either way, which is why the pinned header string
  never moved. The test that now catches it asserts **both** that the separator's own text carries no
  padding and that the kicker still reads with spaces — either alone passes with the defect restored.
- **The phone header wrapped by label length.** The mock carries three phone rules for this header
  and only two were ported; the missing one gives the title its own row. Measured at 390px before the
  fix: a short pair put all three items on one dense line, a medium pair stranded the clock alone on
  row 2, a long pair stacked — three layouts at one width, chosen by how long the topic names
  happened to be. The comment justifying the hidden rule asserted the layout that had not been built.

⚠️ **A third measurement trap, on top of the two below.** Two of the review's "failures" on the
re-run were the harness's own assertions, not the code: a last-card scroll check keyed to a fraction
of the viewport (the page genuinely cannot scroll past its end — the honest test is *fully visible
and clear of the sticky bar*), and a header-row grouping keyed on an exact `top`, which reported a
15.5px title and a 13.5px clock centred on one row as two rows. Both would have been written up as
defects by anyone reading the exit code.

⚠️ **Two measurement traps caught in the doing, both of which produce confident false results.**
`zsh does not word-split unquoted parameters`, so a `$SPECS` holding four test paths reached vitest
as ONE filter, "No test files found", exit 1 — and an entire mutation run reported eleven mutants
"killed" when the baseline had failed the same way. Always assert the baseline exits 0 *and* that the
run found files. And the first contrast pass compared a translucent foreground as if it were opaque,
reporting the 0.66-alpha secondary ink at the same 14.45:1 as the full-strength value ink; composite
the foreground over its backdrop too, or every muted tone flatters itself.

---

---

## 6. Pre-pilot sweep

- No demo buttons, no annotation cards anywhere in the shipped build.
- Signal copy is **stateless** — deltas computed from data alone ("clearest in 11 nights", "biggest
  tides of the month"). No per-user exposure counter: every pilot user must see the same page on the
  same night so bug reports reproduce, and silence must never mean "you have been told before".
- ~~**The promoted strip renders when a coincidence exists, and never more than one.** Stated this way
  deliberately: "at most one" passes vacuously on a page that never built the strip at all.~~
  **⚠️ RETIRED 2026-08-21 — the promoted strip no longer exists.** `plan-matrix-plan.md`'s D-1
  deleted it at M5: v3 names every topic on its own card and all six cards are above the fold, so
  the strip's job (surfacing a coincidence above the fold) went with the shape of the page. The
  clause is struck rather than removed because its *wording* is the useful part and still binds
  anything that replaces it — a rule about "at most one" of something is satisfied vacuously by a
  page that builds none, which is exactly how this clause spent a phase recorded as UNTESTED. The
  §6a row below is struck for the same reason.
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

### 6a. The sweep as actually run — 2026-08-11

Seven prosecutor lenses over the v2 arm, one per clause group, each charge then handed to an
independent refuter prompted to **refute** and defaulting to REFUTED without citable evidence.
19 raw charges → 16 after dedup → 12 verified → **6 survived**. Read-only throughout.

**Clause-by-clause result. One clause could not be tested at all:**

| clause | result |
|---|---|
| 1 — no demo buttons / annotation cards | pass |
| 2 — stateless copy, no exposure counter | pass |
| **3 — promoted strip: renders on a coincidence, never more than one** | ⚠️ **RETIRED 2026-08-21 — the strip was deleted (plan-matrix D-1, M5), so there is nothing left to test.** The result below is the record of the last run against a component that existed. ~~**UNTESTED — not pass**~~ → **PASS, re-run 2026-08-11 when P7b landed.** Both halves, in the browser against a payload carrying **two** coincidences: one strip rendered, and it named the rarer pair. A no-coincidence payload rendered none. See below |
| 4 — no invented vocabulary, no counts of our own data | **1 fixed** (rail's "4 regions") |
| 5 — verdict colours consistent; confidence decay only on the card badge | **first half: 2 fixed** (rail GO token, away hue). **Second half: superseded, not tested** — see below |
| 6 — every footer's claimed sort and count matches what is rendered | pass |
| 7 — coherent with no home postcode | pass |
| 8 — no control whose only visible effect is an `aria-hidden` panel | pass |
| 9 — no telemetry | pass |

**⚠️ Clause 3 was re-run on 2026-08-11, after P7b shipped, and now passes.** The original entry is
struck rather than deleted, because the reason it could not be tested is the more useful half of the
record. What was measured, on the running app rather than in jsdom: a fixture with a coincidence on
**two** windows (snow×snow at rank 8 on an earlier day, king tide×aurora at rank 3 on a later one)
rendered **exactly one** strip, and it named the rank-3 pair — so neither half passes vacuously. A
payload with the badges stripped rendered **none** while still drawing six cards. The row cap was
checked against it as §6a asked: `windowFirstRows.js` and `windowFirstPromoted.js` both order by
`rarityRank` with the same absent-sorts-last degrade, and the strip reads `allBadges` — the list
*before* the row promotion — so a window whose second badge became an attribute row is still counted
as a coincidence. Original text follows.

**Clause 3 is recorded UNTESTED, and that is the honest reading rather than a pass.** P7b never
shipped, so there is no strip to check and the clause passes vacuously — which is the failure its own
wording exists to forbid ("'at most one' passes vacuously on a page that never built the strip at
all"). It is **not** a deviation and P7b is **not** cancelled: the owner scheduled it as the next
phase on 2026-08-11. Nobody may later count clause 3 among the checks that cleared. Its backend half
already ships (`TopicRarity`, `BriefingWindow.topRarityRank`, `Badge.rarityRank`,
`PlanWindowProjector:464`), so P7b is frontend-only and nothing here has to be undone to pick it up.
When it lands, re-run this clause **and** check it against `windowFirstRows.js`'s row cap, which
sorts by the same `rarityRank` and must not disagree with the strip about which topic matters most.

**Clause 5's second half is superseded, and saying so is not the same as testing it.** The clause asks
that the confidence decay appear on the window card's verdict badge "and **nowhere else**" — check the
rail, the drill-down header and the spot strip. That instruction predates the confidence channel this
project actually shipped: CLAUDE.md now documents it as deliberately spanning grid cells, Best Bet,
the summary-strip pills and the shared `VerdictPill`, with only the **star** ring-fenced. So the
"nowhere else" wording describes a design that was superseded, and the sweep did not test it. The rail
independently honours the original intent (`WindowFirstDayRail`'s javadoc records refusing a
`ProvisionalMark` for exactly this reason); the drill-down header and the spot strip were **not**
checked either way. Whoever revisits this should reconcile the clause with CLAUDE.md rather than
re-run it as written.

**Two charges survived and were deliberately NOT fixed here**, because both are the same question and
it is a pricing decision, not a rendering one: the v2 spot peek shows the two scores ungated while
`MarkerPopupContent` withholds and upsells them, and `HotTopicStrip`'s LITE blur is defeated by the
window card's ungated attribute rows on the same screen. `freemium_ui_strategy.md:79`/`:98` say LITE
is **entitled** to the scores, so the gates are the deviation and the ungated surfaces are the policy
— but resolving it edits two components shared with the frozen v1 arm. It does not gate the pilot:
self-registration yields `PRO_USER` (`RegistrationProperties.java:24`), so no pilot user can reach
it. It **would** gate a LITE user, so decide it before any LITE account exists.

**What the sweep did not examine, stated plainly.** `vite.config.js` sets `css: false`, so jsdom
evaluates no stylesheet and **no unit test in this repo proves any colour, cascade, media-query or
`display` claim** — every contrast figure quoted in the sweep is arithmetic on declared hex, not
measurement. No screen reader, no axe, no Lighthouse, no forced-colors, no real device, nothing above
1440px — true of every phase since P4 and still true. Five of the seven shared v1 components were not
audited beyond targeted greps. The local DB has never had an evaluation run, so no rich state was
seen on real data. Four low-severity charges were dropped at the verification cap; three were checked
by hand afterwards and two of those were fixed here, one refuted (`App.jsx:274`'s `mapHandoff` write
is inert in v2 but load-bearing in v1, and P15b already recorded the split at `:309-321`).

---

## 7. Deviations from the spec, and why

- **No chart on the promoted strip** (P7b). §5's build-order row and README §3 both specify one
  (320×44 curve, two amber markers, a 16px band of four labelled extremes). The label band is not
  derivable — `BriefingWindowTide` carries one extreme, no per-extreme x position, and a clock time
  wrapped modulo the day — and the only source of four labelled extremes, `hotTopics[].tideRun`,
  exists on two topic kinds and picks its representative coastline independently of the window
  rollup. The curve alone would be the fourth tide chart in this arm on one pane, for a strip that
  is generic over 12 topic kinds. See §5j for what it would take to build honestly.
- **No right-hand meta on the promoted strip** (P7b). The mock's meta counts our own data (§6). Still
  true on both branches.
  ⚠️ **The "why" clause and the rarity line were revisited when ECLIPSE landed** — both refusals
  turned out to be arguments about a *pair*, not about the element, and both lapse on a strip
  promoting a single topic. The strip now carries a `reason: 'coincidence' | 'rarity'` discriminator:
  on `'rarity'` it renders the topic's editorial `note` (never its `detail`, which restates the
  figure beside it) and its backend-composed `rarityNote`; on `'coincidence'` both stay refused, for
  the original reason. The rarity line is a *forward* claim read from a seeded catalogue, which is a
  different thing from §2.6's rejected *backward* historical scan.
- **The promoted strip takes nothing out of the window card** (P7b), so §6's "something should
  usually come out when something goes in" is not discharged for it. Stated rather than quietly
  skipped, with the measured cost (131px desktop, 154px phone) and the reason the obvious trade —
  taking the promoted window's badges — was rejected. Measured 115–131px desktop, 192–208px phone.
  §5j.
- **No count on the Coming up tab** (P13). The README specifies "Coming up (with count)" and the
  mock draws a `3`. Two reasons compound. The count does not exist until the feed has been fetched
  and the feed is not fetched until the tab is opened, so it could only ever appear *after* the
  reader had already looked — unless the fetch were made eager, which would spend a request on
  every Plan-tab reader to decorate a tab neither of them may open. And the number it would show is
  the row count, which changes no decision: eleven dated events and eight are the same answer to
  "is there anything coming up". The mock's `3` was a count of *live* events, which this feed has
  no notion of.
- **Two columns on a Coming up row, not the mock's three** (P13). The mock's right-hand column
  carries "4 regions / 11 nights left". `regions` is empty on every entry from all five sources and
  `@JsonInclude(NON_EMPTY)` drops the key, so half of it can never be drawn; the other half is the
  countdown the left column already gives. A third column carrying one duplicate beside a permanent
  blank is the empty footer bar P5 refused.
- **No Almanac chip on an almanac row** (P13). §3 gives the tab a two-word tag vocabulary and the
  mock stamps a chip on every row. Every entry the five sources emit is `ALMANAC`, so the chip would
  be a word that never varies; it is stated once in the pane footer instead and only a row that
  departs from it is marked — the confidence channel's marker-on-low treatment, applied to a second
  channel. The footer says "fixed **in advance**" rather than the mock's "fixed by orbital
  mechanics", because two of the five sources compute nothing orbital: the NLC season is a
  hard-coded 25 May – 10 Aug window and the equinox/solstice anchors are fixed `MonthDay`s whose
  accuracy §5g records as unverified.
- **The Coming up row is inert** (P13). The mock gives `.ev` a pointer cursor and a hover tint and
  never says what a click does. The feed carries no location, no rating and no region, so a click
  has nowhere to land — §6 bans a control that cannot act, and a row that is not a control must not
  be dressed as one.

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
  LITE-included. **~~Open question for P7~~ SETTLED AT P7: the attribute rows are not gated, and
  `HotTopicStrip` is not changed.** The premise the question rested on — that the strip "blurs tide
  metres" — is true but mis-scoped: `TopicFacts` blurs *every* topic's fact chips regardless of kind,
  so it is a blanket tease over a promotional strip rather than a judgement about tides. The window
  card's rows are a different surface: the window's own context, the equivalent of the v1 Plan tab's
  tide chips and tide-aligned markers, which every role already reads unblurred. The freemium split
  blurs cloud-layer breakdown, aerosol metrics and the technical panel — tide is none of those, it is
  almanac, and this project has already argued itself into that answer once (`GET /api/tides` is
  Bearer, not ADMIN, because "tide extremes are astronomical data already surfaced in the forecast
  UI"). One rule for the whole row block, which also keeps `role` out of this arm entirely. The two
  surfaces are never on screen together today — `HotTopicStrip` arrives with P9's hot-topics door —
  and editing it would perturb the v1 arm §4 rests on. **P9 owns the reconvergence, and the blanket
  blur is the single place to make it.**
- **The day rail is no longer inert.** The first handoff specified a read-out with no hover and no
  click. The second makes it the page's densest interactive surface: region chips open a gloss, pick
  chips open the pick's prose, and the tile carries a show-on-map action. Anywhere this plan still
  calls the rail inert, the second handoff wins.
- **~~A LITE user sees a hole where the picks are.~~ SETTLED AT P4c: no gate, and neither option
  above.** The premise this bullet rested on — "the underlying prose is PRO-gated today" — is true
  of `BestBet.headline` (Claude best-bet-advisor output on the `bestBets` path) and **false** of
  what the rail and the window card actually read. `BriefingWindow.Pick` is region gloss, and the
  evidence is one-sided: no role check touches it anywhere on the `/api/briefing` path
  (`SecurityConfig` gates that path at `.authenticated()` only, and nothing downstream strips by
  role); LITE already reads the same gloss in two places on the v1 Plan tab
  (`HeatmapGrid.jsx:452`, `BriefingSummaryStrip.jsx:244`); `freemium_ui_strategy.md:79-80` lists
  the one-line Claude summary as LITE-included and `:99` forbids truncating it; §2.3 rejected the
  `bestBets` vehicle *partly because* routing through it "would make the pilot's headline feature
  PRO-only against §7"; and the role-gating bullet above already says Best Bet needs no new gating.
  So the two bullets contradicted each other and §2.3 breaks the tie.

  The one real counter-argument, recorded because it becomes live again if the arms ever merge:
  v1 shows a LITE user a blurred `BestBetPlaceholder` (`DailyBriefing.jsx:875-896`) and gates the
  strip chips' pick accents on `isPro` (`:1206-1215`), so an ungated chip would contradict them one
  row apart, and would also make `CloseToHome`'s deliberate withholding of the region name
  (`CloseToHome.jsx:585-588`, pinned by `CloseToHome.test.jsx:209-221`) pointless. **It does not
  reach the v2 arm**: the flag branches above `<main>` (`App.jsx:381`; `:380` is the comment's last line), so `DailyBriefing`, its
  placeholder and `CloseToHome` never render beside the rail. The two stories are never on screen
  together. Reconverging the arms after the flag default flips means making this one decision
  once, across both — not splitting it across two surfaces.
- **Styling.** CLAUDE.md says Tailwind only, no inline styles; the spec is written in exact px
  (12.5 / 10.5 / 9.5). Follow the existing precedent — `index.css` component classes plus tokens, with
  inline style for one-off exact values, which is what `CloseToHome`, `TideRunRow` and `ViewToggle`
  already do. Do not introduce a third way.
