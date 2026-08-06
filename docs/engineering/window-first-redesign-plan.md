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

⚠️ **Sea state is independently nullable.** Tides reach T+13; waves reach T+4, exactly the briefing
window and no further. A window beyond T+4 has a full tide rollup and no sea state, so the field must
degrade on its own rather than being assumed present whenever the rollup exists. Do not let a missing
sea state suppress the rollup.

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
- **`--blue #7C8DD6`: NOT a new token.** It ships as `--color-pick-also` (`index.css:58`), and the
  whole chip treatment ships with it — `data-pick` accents at `index.css:655-669`, the `◎` `rn-mark`,
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
| **P7b** | Promoted strip — both variants; chart 42px curve + 16px label band | Single-strip cap enforced in code. Also owns `UNKNOWN_RANK`'s wire semantics |
| **P8** | Lens bar — **reach only** + persistence policy | Shrunk: rating floor and type move to P11. Day-derived default; reach expires at the day roll |
| **P9** | Collapse/expand, six-window case, away-day row + its rail variant, the two doors | Re-measure the height budget: the rail grew, the window card shrank |
| **P10′** | Peek content kind 1 (spot) + click-to-map | Split — the host is P4b, the pick-chip kind is P5. New `WindowSpotPeek`; **on touch the card activates the map**; phone peek via `BottomSheet`; 140/160/120ms |
| **P11** | Drilldown sheet — **plus the rating floor and type controls** and their persistence | Grown by what P8 shed |
| **P12** | Backend: almanac feed (§3) + the tide fetch-horizon decision | Unchanged |
| **P13** | Coming up tab | Unchanged |
| **P14** | Responsive pass — real media queries, including the taller rail tile on phone | Keep control labels at 9px |
| **P15** | Pre-pilot sweep (§6), then flip the flag default | |
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
  reach the v2 arm**: the flag branches above `<main>` (`App.jsx:380`), so `DailyBriefing`, its
  placeholder and `CloseToHome` never render beside the rail. The two stories are never on screen
  together. Reconverging the arms after the flag default flips means making this one decision
  once, across both — not splitting it across two surfaces.
- **Styling.** CLAUDE.md says Tailwind only, no inline styles; the spec is written in exact px
  (12.5 / 10.5 / 9.5). Follow the existing precedent — `index.css` component classes plus tokens, with
  inline style for one-off exact values, which is what `CloseToHome`, `TideRunRow` and `ViewToggle`
  already do. Do not introduce a third way.
