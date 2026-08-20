# Heat field — Plan strip + Map tab, v2 only — implementation plan

**Status:** drafted 2026-08-18 from the `PhotoCast.zip` design handoff (2026-08-18, high
fidelity); adversarially reviewed the same day (4 lenses, ~25 findings upheld and applied — the
notable ones: the day-rail replacement is a recorded decision **reversal** with a pinned test to
retire (D1), the strip header carries no database count (§2.6), per-region `bestRating` is served
rather than client-derived (P1), marker treatment in heat view is specified (D8), and the dev-seed
recipe needs a backend restart (§7.3)). **P0 is MERGED** (#552, `e73985de`, 2026-08-19) —
kernel, ramp, vendored coastline and §7.1 tests, adversarially reviewed (six lenses) with the
surviving findings applied; see the P0 row for what it decided and what it deliberately left to
later phases. The P0 row's roster question is **answered**: the owner confirmed (2026-08-19)
there are no Isle of Man, Channel Islands or Scilly locations, so the ISO-826 coastline clip is
safe. **P1 is MERGED** (#554, `a6341e71`, 2026-08-19) — the join, the planning area, the
provider plumbing and the served `BriefingRegion.bestRating`, adversarially reviewed (seven
lenses) with the surviving findings applied; the P1 row records what it decided, including one
real defect the review caught (the field was blending woodland ratings). **P2 is MERGED** (#559,
`caba0b17`, 2026-08-19) — the strip, the Order control, the day rail retired per **D1** (confirmed
by the owner 2026-08-18), and the `manualChunks` rule; adversarially reviewed (six lenses) with
the surviving findings applied and browser-verified at 1280 and 390. Its merge reconciled a moved
main (#556–#558): the lit band and the strip coexist, gate-proven on the merged tree, and #556's
App tests re-anchored from the rail's retired `window-first-rail-empty` sentinel to
`window-first-pane-empty` (same fetch-settled gate). **§9.6 is RESOLVED — ungated for the pilot**
(owner, 2026-08-19).
The P2 row records what it decided, including three defects the review caught that a green suite,
a clean lint and a successful build had all passed over, and one more the browser caught after
them. **P3 is MERGED** (#561, `5419f503`, 2026-08-19) — the open row's field map, region rail
and region band, the shared `useHeatCanvas` extraction, and the region filter composed onto the
card's spot pool; adversarially reviewed (six lenses) with the surviving findings applied and
browser-verified at 1280 and 390 (via the documented Playwright fallback — the pane wedged). Its
row records six defects that landed only because the review and the browser ran before the
commit, two of which no green suite could have caught.
**P4 is MERGED** (#564, `f9e1be4f`, 2026-08-19; released base beneath it is v2.18.12, the
deliberate pre-P4 rollback point) — the Map tab's heat layer, the `MapView`
opt-in, the widened `useHeatCanvas` and the `scoreRamp` marker swap; adversarially reviewed (six
lenses) with the surviving findings applied, and browser-verified at 1440 and 390 against a
210-location dense fixture. Its row records what it decided, including the two defects the browser
caught after a green suite, a clean lint and a successful build had all passed over — one of them a
CSS specificity tie against Leaflet's own stylesheet, the other a cached cluster icon — and the one
place the plan overrules its own design prototype. **P5 is MERGED** (#566, `6b513d58`,
2026-08-19) — **D2 is fully discharged**: all five ramp consumers have shipped in their named
phases, so one colour language now exists everywhere in v2. P5 delivered the leave-by line on the spot card, the sheet and the peek, and the spot badge's
swap onto `scoreRamp`; adversarially reviewed (six lenses plus two refutation passes) with the
surviving findings applied, and verified in the running app at 1200 and 375. Its row records the
one defect only the browser could catch (the peek's chip row overflowing its own panel), the two
design-level charges that were **refuted** on evidence, and what it leaves for P7.
**P6 is MERGED** (#570, `6fa78e26`, 2026-08-19; CI ran V144 against real Postgres, which the
Docker-less dev machine could not; its merge reconciled the saturday-sunrise hotfix pair #568/#569
as a union — both new strip props, both accessible-name clauses, both test suites; the two §4.7
deviations are **settled**: one-previous-build basis, and "Moved at the last forecast run" over
"Since", which would have attributed a previous-build→now delta to the last build's age) — the `briefing_region_snapshot`
sink, the serve-time `meanRatingDelta` + `previousGeneratedAt`, and the strip chips, change line and
band figure; adversarially reviewed (six lenses) with the surviving findings applied, and verified
in the running app at 1280 and 375 against two real briefing builds. Its row records what it
decided, including **one defect that would have disabled the whole feature in production and
nowhere else** (a nanosecond/microsecond mismatch making every build compare against itself on
Linux), the change line's wording deviation from §4.7's own sample copy, and the two indexes and
one column the review corrected. **P7 is BUILT 2026-08-20** (`feature/heat-p7-origin`, unpushed;
released base beneath it is **v2.18.13**, the deliberate pre-P7 rollback point) — region bases, the
shared ORS matrix and its endpoint, the origin chip, search, `setOrigin`, the away lens relabel, the
clash states and P2's deferred beyond-line link; adversarially reviewed (six lenses) with the
surviving findings applied, and verified in the running app at 1280, 390 and 320 against a seeded
four-region catalogue. Its row records what it decided, including **four defects that a green suite,
a clean lint and a successful build had all passed over** — a card whose header described a region
the reader had just scoped away, a region selection that survived the origin move with every control
that could clear it unmounted in the same commit, a reach tier persisted to `localStorage` by a
gesture made on a different control, and an all-null ORS response that would have wiped a working
matrix while the job logged that it had been preserved. **P8 (the four-day location sheet, owner-optional) is what remains.**
The remaining §9 questions are
open but none blocks a phase before its own row says so. Per-phase kickoff prompts for the
implementing sessions: `docs/engineering/heat-field-prompts.md`. Every phase is bound by
CLAUDE.md § *UI Work — Review Cadence* (build → tests → adversarial review of the diff → fix →
re-verify → commit) and updates `CHANGELOG.md`.

**Scope guard:** v2 / window-first Plan UI **only**, behind the existing `usePlanLayout` flag
(default still `v1`). v1 is the pilot's frozen comparison control and will be deleted after the
flag flip — nothing here ports into v1, and every shared component change uses the established
caller opt-in shape (`scrollable`, `serverCellRating`, `resizeNonce` precedents) so v1's behaviour
is byte-identical without the opt-in.

**Spec:** `docs/design/heat-map/` (vendored verbatim from the bundle — P0). Read the bundle's
`README.md` and both HTML files before writing code, **then read §2 of this plan**: several of the
README's claims about this repo are stale, and §2 wins where they disagree. `heat-field.js` is the
one file ported close to as-is; everything else in the bundle is reference, not code to copy.

The design in one paragraph: every rated location paints its own score for one solar window into a
blended **heat field**. On the Plan tab, six small static-canvas thumbnails (d3-geo projection,
real UK coastline) sit under the lens bar as a visual index of the six window rows; opening a row
adds a full-width field map, a ranked region rail and a region drill-down band above the existing
tide row and spot strip. On the Map tab, the same field paints over the Leaflet tiles as the
default view, with the existing medallion clustering one tap away. Plan is time-first, Map is
space-first; same kernel, same catalogue, same colour ramp.

---

## 1. Decisions taken up front (and the ones the owner must take)

| # | Question | Decision |
|---|---|---|
| D1 | Does the strip replace `WindowFirstDayRail`? | **CONFIRMED — replace (owner, 2026-08-18).** Full comparison, job-by-job relocation, what is genuinely lost, and the two rejected alternatives: **§1.1**. |
| D2 | Colour ramp conflict | **One ramp inside v2.** New `frontend/src/utils/scoreRamp.js` owns the design's five stops (1 `#B03A2A`, 2 `#C8452F` = `--color-verdict-standdown`, 3 `#E0A542` = `--color-verdict-marginal`, 4 `#B0BE74`, 5 `#8AAE72` = `--color-verdict-go`; linear interpolation, clamp 1–5). The kernel, the strip, the row maps and the six-dot swatches read it from P0; the v2 map markers/clusters swap onto it **in P4, riding the same `heat` opt-in** (see D8 — geometry, clustering and popups unchanged, only the colour source moves); the v2 spot-card badges swap **in P5** (`windowFirstSpots.spotBadgeStyle` is v2-only, safe). Each swap is named in its phase's contents and exit criteria — a ramp consumer listed here but absent from a phase is a plan bug. v1 keeps `markerUtils.RATING_COLOURS` untouched everywhere. **Do not port the design's `vCls`/`vWord` thresholds** — see D3. |
| D3 | Verdict words and confidence display | **Server-owned, existing vocabulary — never recompute.** The strip/rail/band verdict words come from `displayVerdict`/`BriefingWindow.verdict` through `VERDICT_LABEL` (`windowFirstCards.js`: Worth it / Maybe / Poor / Awaiting). The design's client thresholds (≥3.7 / ≥2.8) and its `◐ 88%` percentage are **not ported**: this project's confidence channel is deliberately three-tier (`Confidence` high/medium/low + `CONFIDENCE_TREATMENT` + `ProvisionalMark`), and a percentage would invent precision the backend never claimed. The kernel's `conf` scalar is fed from the tier via `fillScale` (high 1.0, medium 0.72, low 0.5) so the haze and the badge decay speak one language. The strip footer keeps the design's honest caption ("later days render hazier — lower confidence"). |
| D4 | Movement chips / change line data | **New append-only `briefing_region_snapshot` sink** (P6) — CLAUDE.md's own doctrine: no store retains per-run history the live pipeline writes; `cached_evaluation` and `forecast_score` are latest-wins, `evaluation_delta_log` records only the intersection population with absolute deltas, and `forecast_evaluation` belongs to the retiring sync engine. Until P6 lands the strip renders **no movement chips and no change line** — never a fabricated `—`. |
| D5 | Origin move + search | **Built, but last (P7), and severable.** Regions today have no base town, no coordinates, no aliases, and no region-local drive times — `user_drive_time` is per-user-from-home only. P7 adds shared (user-independent) region-base data + an ORS region matrix. Everything in P0–P6 is written origin-agnostic (scope = "home planning area") so P7 is additive. Owner may defer P7 without stranding anything. |
| D6 | Planning area (GLANCE) | **Client-side module**, `frontend/src/utils/planningArea.js`, `GLANCE_MINUTES = 180`. A region is in the planning area iff the minimum `driveMinutes` over its locations (from `GET /api/user/settings/reach`) ≤ 180. Per-user data, so it must stay client-side (§3, privacy split). No home / no drive times → planning area = whole roster (degrade honestly; never synthesise a smaller area). Both the strip framing and the Map tab's opening bounds read this one module. `GLANCE` as a user setting: deferred, listed in §9. |
| D7 | Dark-sky filter scale | **The mock's darkness scale is inverted — use the app's real Bortle.** Real data: `bortleClass` 1–9, lower = darker; the existing Map filter is `bortleClass <= 4` (`DARK_SKY_THRESHOLD`, `MapView.jsx`). The bundle's `dark = bortle >= 3.8` is mock-scale and must not be ported. |
| D8 | Map-tab zoom handover | **Simplified first cut, but the marker half is specified — it is not "markers stay as today".** In **heat view**, the existing marker/cluster layer keeps its geometry, clustering and popups but (a) its colours come from `scoreRamp` (D2) and (b) it **fades with zoom**: the marker panes' opacity is 0 below z10.6, ramps to 1 across z10.6→z12.2, with `pointer-events: none` while opacity < 0.2 so there are no invisible click targets — one style write on `map.getPane('markerPane')` (plus the cluster container) in `MapHeatLayer`'s zoom handler. Without this, today's fully-opaque cluster medallions paint over the field at every zoom and recreate exactly the overload the feature removes. Heat itself fades the opposite way, full → 17% wash across the same band. **Medallion view is byte-identical to today.** The prototype's own pin-drawing and name labels are **not** ported (our markers are richer and carry the popups). The bottom-left Field/Handing over/Locations status panel is P4-optional polish — note the corner is already occupied by the LITE viewline upsell chip and the legend chip (`MapView.jsx` ~:1739-1770), so if built it must not collide with them. |
| D9 | Thumbnail host | **d3-geo static canvas, not six Leaflet maps** (the bundle's own recommendation). UK land geometry is **vendored, never fetched from a CDN** — prod CSP (`nginx.conf`) allows no jsdelivr on any directive (§2.7). A committed script generates a UK-only topojson once; the output is committed and imported lazily. |
| D10 | Four-day location sheet | **P8, last, owner-optional.** Until then a spot-card click keeps today's behaviour (open on map). The sheet's per-window "why" prose already exists per (location, date, event) — the scores payload's field is **`summary`** (`LocationEvaluationView`); `claudeSummary` is the *briefing* payload's name for the same prose on `BriefingSlot`. |

---

### 1.1 D1 in detail — the strip vs the day rail

**What each one is.** `WindowFirstDayRail` (489 lines) is four **day** tiles rendered as screen
chrome **above the tab bar** — visible on every tab (Plan, Coming up, Map, Operations). Each tile:
DOW + day-of-month + label, the pick flag chip (a button opening `WindowPickDialog`), sunrise and
sunset times (`↑ ↓`), a day-verdict line rendered from `BriefingDay.peak`, peak-region chips with
gloss popovers (`PopoverHost`), a "◍ Show on map →" button, and an away-day variant. On the phone
it is a horizontal scroller (`flex: 1 0 150px` tiles). The heat strip is six **solar-window**
thumbnail buttons rendered **inside the Plan tabpanel**, directly under the sticky lens bar: DOW
kicker, sunrise/sunset arrow, the heat canvas (the actual forecast, drawn over real geography),
event time, verdict word, a passive `BEST BET` flag, and (P6) a movement chip. On the phone it is
a 3×2 grid — all six windows visible, no scrolling.

**Why they cannot both stay.** Stacked, they are two summaries of the same forecast at two grains
— four day tiles saying "Wednesday: Worth it (sunrise), regions A, B" above six thumbnails saying
the same thing split by window — costing roughly two screens of chrome before the first window
row, and re-creating the exact "quality stated four times" overload the bundle's own open
question 2 warns about. The design frames the strip as *"a visual index of the window list"*: it
is the rail's job done at the list's own grain, with space shown inside each window instead of
named beside it.

**Job-by-job relocation:**

| Rail job today | Where it lives after P2 |
|---|---|
| Day verdict line (`BriefingDay.peak`) | Per-window verdict words on the thumbnails — finer grain; a day's shape is its two adjacent thumbnails |
| Pick flag chip (button → pick dialog) | `BEST BET` flag on the thumbnail (**passive** — a nested interactive control is invalid HTML); the dialog stays reachable via the window card's existing pick badge |
| Sunrise + sunset times per day | Each thumbnail carries its own window's time + `↑`/`↓` arrow — same information, one window per surface |
| Peak-region chips + gloss popovers | The open row's region rail (every region, ranked, not just peaks) + region band (narrative). **Trade:** region *names* were one glance on the rail, now one click into a row — but the heat canvas answers the underlying question ("where is it good?") with zero clicks, spatially, which names never could |
| "◍ Show on map →" per day | The Map tab (which has its own `DateStrip`) — one extra tap |
| Away-day tile variant | `✈` marker in the thumbnail top row (§9.7); away rows keep their place in the window list |
| Whole-screen date context above the tabs | **Genuinely removed — this is the recorded reversal.** See below |

**The reversal, named.** The rail's above-tabs position was a deliberate decision, pinned by
`WindowFirstShellTabs.test.jsx` (~:514 — *"keeps the day rail, which is the whole screen's date
context and not one pane's… It sits ABOVE the tab bar for exactly this reason: Coming up asks
about the same days"*). Replacing it makes the summary a Plan-pane element, so the other tabs
lose the rail from their chrome. That is acceptable **now** because each tab has since grown its
own date context — the Map pane renders its own `DateStrip` over the full forecast horizon, and
every Coming-up row carries its dates — but it is a reversal of a recorded decision, and the
window-first plan's own preamble requires reversals to be recorded with their reason, not
silently overwritten. P2 must retire that pinned test *with this rationale in the commit*, and
retire the rail's own test files with the component (`WindowFirstDayRail.test.jsx` ~581 lines,
`windowFirstRail.test.js` ~655 lines) rather than leaving ~1,200 lines guarding unmounted code.

**What else falls out.** `BriefingDay.peak` and `buildRailTiles` lose their only v2 consumer —
they become cleanup-after-flip items (recorded, not acted on; the backend field stays because
cached payloads and the frozen v1 arm's era are not this plan's business). The rail *footer*
(`window-first-railfoot`: Home · place / Edit reach / forecast age) is a separate element and
**stays** — only the tile row goes.

**Alternatives rejected:**
1. *Keep both* — the double-summary cost above, for one unique surviving job (cross-tab chrome)
   that the other tabs no longer need.
2. *Move the strip above the tab bar into the rail's slot* — keeps cross-tab context but breaks
   the design's anatomy (the strip belongs under the lens, whose label and counts it sits
   against), forces Plan-row-opening click semantics onto tabs that have no window rows, and
   detaches the thumbnails from the lens bar that explains what they do and don't filter.
   Complexity without design backing.

If the owner rejects D1, the fallback is alternative 1 with the rail collapsed to a single-line
variant — but that is a new design exercise, not this plan, and P2 blocks on the call either way.

---

## 2. Where the design README is stale against this repo — corrections that win

The bundle was written against an older snapshot. Chasing these costs real time; each was verified
against the worktree on 2026-08-18.

1. **`RATING_COLOURS` is not "a monochrome grey→gold ramp".** `markerUtils.js:23-29` is already a
   five-stop red→green ramp (`#A32D2D`…`#3B6D11`). The real conflict is *two different* red→green
   ramps. D2 resolves it: v2 unifies on the verdict-anchored stops; v1 keeps `RATING_COLOURS`.
2. **`--mastH` does not exist.** The sticky-lens mechanism is `--wf-lens-reserve`, measured live by
   `hooks/useLensReserve.js` and consumed as `scroll-margin-top`. The strip must integrate with
   that, not introduce a second reserve variable.
3. **The reach tiers already exist and already match.** `utils/reachLens.js` tiers are 45/90/150/Any
   — the design's 45 min / 1h 30 / 2h 30 / Any. Reuse `useReachLens`; do not build a second lens.
4. **The tiles are already CARTO `dark_all`** (`MapView.jsx:1568-1572`). No tile work.
5. **The tokens already exist** — `--color-verdict-*`, `--color-plex-panel` `#1E1712` (in
   `@theme static`), `--color-close-to-home` `#C9A24B` (the design's "Home/selected"), `--color-tide`.
   IBM Plex Mono 600 is bundled (`fonts.js`). Any genuinely new token goes in **`@theme static`**
   (Tailwind v4 prunes unreferenced plain-`@theme` tokens — the `--color-plex-panel` empty-string
   incident, `index.css:85-93`).
6. **"204 rated locations · 51 named" is mock copy — and its replacement is NOT "N rated
   locations".** Real locations are all named (the mock's unnamed scatter only makes the field
   legible at mock scale) — but the window-first plan's pre-pilot sweep **bans counts of our own
   data** ("11 aligned is a fact about the database, not about tonight", §6 clause 4;
   `WindowAttributeRow`, `WindowFirstDoors` and the day rail were each stripped of exactly this
   copy). The strip header is therefore the kicker + rule **with no count** — a recorded
   deviation from the bundle. Per-window in-reach counts (`withinReachCount`) are lens output,
   not database counts, and stay.
7. **`cdn.jsdelivr.net` is blocked in prod.** `nginx.conf:78` CSP has no jsdelivr in `script-src`,
   `connect-src` or `img-src`. Hence D9's vendored geometry. No nginx change needed if nothing
   external is fetched.
8. **Regions have no geography.** `RegionEntity` = `{id, name, enabled, createdAt}` — no base, no
   lat/lng, no aliases (`plan-data.js`'s `al`/`base`/`lead` fields have no backing). P7 creates the
   backing; earlier phases must not assume it.
9. **There is no search anywhere in the v2 arm** (`WindowPickDialog` is a prose modal, not a
   picker) and no `Order` control. Both are new (P7 / P2 respectively).
10. **`GET /api/briefing/evaluate/scores` is already fetched eagerly** — a mount-time
    `useEffect(…, [])` in `WindowFirstBriefingContext.jsx` (~:274-294); only its *consumer* is the
    map-handoff path. The real P1 gap is that the provider's index **discards `locationId`**
    (keyed `regionName|date|targetType|locationName`, storing a name-keyed subset) while
    `LocationEvaluationView` carries the id — P1 retains the raw rows (or an id-keyed index) so
    the heat join can be locationId-first, name-fallback. Payload is ETag'd and shared; measure
    it once at P1.
11. **Local dev has zero locations.** `application-local.yml`'s `forecast.locations` block is dead
    config (nothing reads `ForecastProperties`); Flyway is off locally. Browser verification needs
    seeding — §7.3.
12. **The design's per-window verdict/`bestWin` math must not be ported** (D3). `BEST BET` =
    the window whose `BriefingWindow.pick.kind === 'BEST'` — already served, exactly two picks per
    forecast. `topAvg` for Order·Best ranking = max `BriefingRegion.meanRating` over the event's
    regions (server-computed field), AWAITING/never-scored windows rank last.
13. **Mock `localStorage` keys are demo furniture.** `photocast.heat.viewport` is not ported;
    the order preference becomes `photocast.planOrder` via `useLocalStorageState`, following the
    documented whole-value-write rule (`reachLens.js:63-86`).

---

## 3. Data contract — what exists, what is joined, what is new

**The privacy seam (binding, from `docs/engineering/plan-panel-data-contracts.md`):** shared
forecast data rides the ETag'd shared payloads; per-user data (home, drive times, reach, planning
area, leave-by) stays client-side or on never-cached `/api/user/settings*` endpoints, and must
never ride `GET /api/briefing`. The design already agrees: *"The lens does not filter the field"*
— the field is shared, the reach lens is personal.

Per-location heat spot — every field's source:

| Design field | Source | Status |
|---|---|---|
| `id, name, lat, lng, regionId` | `GET /api/locations` (already in App as `visibleLocations`; the shell already receives a `locations` prop) — join key `regionName` via `location.region?.name` | exists |
| `scores[6]` | `GET /api/briefing/evaluate/scores` → flat `LocationEvaluationView` rows (`locationId, locationName, regionName, date, targetType, rating, fierySkyPotential, goldenHourPotential, summary, displayVerdict, evaluatedAt`) joined by **locationId first, name fallback** (the `BriefingSlot` doctrine) against the six `renderedEvents` (date, targetType) pairs | exists (join is new) |
| `darkness` | `bortleClass` on `/api/locations`, dark = `<= 4` (D7) | exists |
| `driveMinutesFromBase, miles` | `GET /api/user/settings/reach` → `ReachEntry(locationId, driveMinutes, distanceMiles)`, whole roster, already fetched by the provider | exists, per-user, client-only |
| `localDriveMinutes` (away origin) | **new** — P7 `region_drive_time` (shared, user-independent) | new |
| Window `id/dow/time/eventType` | `renderedEvents` + `eventSummaries[].solarEventTime` / `window.eventTime` (already formatted for display by existing card utils) | exists |
| Window `confidence` | `BriefingWindow.confidence` tier → `fillScale` scalar (D3) | exists |
| Window `tide`, `lead` | `BriefingWindow.tide` (unchanged row), `window.pick` | exists |
| Region narrative per window | `BriefingRegion.summary` (+ `glossHeadline`/`glossDetail`) | exists |
| Region rail figures | verdict word ← `region.displayVerdict`; star ← `region.meanRating`; `best N★` ← **new served field `BriefingRegion.bestRating`** (P1 backend — the non-canopy max, the same rule as `BriefingWindow.bestRating`; a client-side max over `region.slots` would re-create exactly the aggregation class Phase 3 of the verdict consolidation moved server-side, minus its canopy rule); `N in reach` / `Xh away` ← client reach join (per-user, correctly client-side) | mostly exists; one small backend field new |
| Delta vs previous run | **new** — P6 snapshot sink | new |
| `SETUP = 20` min | client constant in the leave-by util (user setting later, §9) | new |
| Region `aliases`, `base`, `lead` prose | **new/absent** — P7; lead prose has no content source yet (§9) | new |

**Payloads carrying none of this:** the briefing and scores payloads contain **no lat/lng**
(verified) — coordinates only ever come from `/api/locations`. Keep it that way.

---

## 4. Architecture

### 4.1 The kernel port (P0)

`docs/design/heat-map/heat-field.js` → `frontend/src/utils/heatField.js`, ported close to as-is
(the algorithm is load-bearing; both optimisation rounds — cull and 3×3 spatial bucketing — are
required). Permitted deviations, each small and stated:

- ES module exports instead of `window.HeatField`; no IIFE.
- Inline tiny `mean/min/max` helpers instead of importing `d3-array` (recharts already carries it
  transitively, but the kernel should not depend on it for three one-liners).
- `d3-geo` (`geoMercator`, `geoPath`) and `topojson-client` become real dependencies, imported only
  by the geo host so the kernel stays framework-free. Chunking: **this paragraph was corrected
  after P0 measured it** (the original claimed no `manualChunks` rule could work — half-wrong). A
  rule *appended after* the existing `id.includes('d3-')` catch never fires, but one placed
  **before** it does — measured at P0: a 22 KB `geo` chunk, with `recharts` staying lazy. That
  rule is **required at P2**, because without it `d3-geo` rides the recharts chunk, which today
  is reached only behind `ManageView`'s `lazy()` boundary (ADMIN-only) — so the first Plan-tab
  `drawGeo` caller would make **375.75 KB / 107.46 KB gzip** a render-blocking first-paint fetch
  for every user, for ~20 KB of d3-geo. The **topojson asset** additionally needs the
  dynamic-import treatment to stay out of the entry chunk (done in P0's `load()`). Full
  measurements in the P0 row.
- `load()` imports the **vendored** UK topojson (D9) instead of `d3.json(cdn…)`:
  `scripts/generate-uk-land.mjs` (committed) filters `world-atlas@2.0.2` `countries-50m` to id
  `826` at dev time; output committed as `frontend/src/assets/uk-land-50m.json`; loaded via
  dynamic `import()` so it code-splits. Record provenance + licence (world-atlas ISC / Natural
  Earth public domain) in the script header. **Never hand-draw a coastline.**
- `field()` additionally returns the `ImageData` it built (`img`) so tests can assert cell values
  through a stub context (§7.1). No math changes.
- The ramp moves to `utils/scoreRamp.js` (D2); `heatField.js` imports it so kernel and UI cannot
  drift.

Everything else verbatim: cull at `2.45R + grid`, bucket size = cutoff, `d2 > 6R²` cutoff,
coverage clamp `1 − exp(−Σw/1.15)` with `Σw < 0.02` fully transparent, focus fade `×1e-4`,
confidence desaturation (60% at conf 0) + alpha −34% + up to 2.6px extra blur, DPR cap 2,
`bbox`/`latLngBounds`/`aspect`/`centroid`/`radiusFor` helpers, corner-MultiPoint-never-ring
projection fitting.

### 4.2 Heat spots + planning area (P1)

- `utils/heatSpots.js` — pure join: `(locations, scoreRows, renderedEvents) → [{id, name, lat,
  lng, regionName, scores: (Integer|null)[6], bortleClass}]`. Null score for a window = that spot
  contributes nothing to that window's field (filter before handing points to the kernel) — this
  is the coverage-clamp honesty, not a bug.
- `utils/planningArea.js` — D6. Exports `areaRegions(spots, reachById)`, `beyondRegions(...)`,
  `areaSpots(...)`; one module, both surfaces.
- Provider change: the scores fetch is already eager (§2.10) — P1's provider work is to **retain
  the raw rows (with `locationId`)** alongside the existing name-keyed map, receive the
  `locations` roster (App currently hands it only to the shell — `App.jsx` joins P1's touched
  files to pass it into `WindowFirstBriefingProvider` too), and memoise `heatSpots` (+ per-window
  point arrays) — `useMemo` on `[locations, scores, renderedEvents]`, mirroring the bundle's
  "memoise derived scope" lesson. Nothing is recomputed inside a render loop.
- Backend (small): `BriefingRegion.bestRating` — nullable, `@JsonInclude(NON_NULL)`, the
  non-canopy max over the region's slots, attached in `enrichWithCachedScores` beside
  `meanRating` with the same voting/canopy discipline as `BriefingWindow.bestRating`. No
  migration (JSON-column field, the `confidence` precedent). The rail and band read it; the
  client never re-derives a per-region max.

### 4.3 The strip (P2) — `components/WindowFirstHeatStrip.jsx`

Replaces the day rail (D1 — including its above-the-tabs position: the strip is a **Plan-pane**
element, rendered directly under the lens bar and **above `WindowFirstPromotedStrip`**; the two
are different things and both exist — "the strip" unqualified means the heat strip in this doc,
and the promoted strip keeps its name and its position at the head of the pane items). Six
`<button>` thumbnails in the payload's chronological `renderedEvents` order — **the strip is
never reordered**, whatever the Order control says. Per card: DOW kicker (mono 600, gold when its
row is open), sunrise/sunset arrow, canvas (`drawGeo`, `grid: 4`, radius `max(10, cell*0.155)`,
blur 2.4, aspect from `clamp(aspect(fitOf()), 0.85, 1.22)`), time, verdict word (D3 vocabulary,
verdict colour), `BEST BET` flag as a **passive** `<span>` (below the top row so it cannot cover
the movement chip; the pick dialog stays reachable via the window card's pick badge — D1).
Header line (kicker + rule, **no count** — §2.6), footer (ramp bar from `scoreRamp`,
`poor → worth it`, haze caption, "The field shows the forecast, not your reach — the cards below
apply it"), beyond line (from `planningArea`, 34% opacity, no search link until P7 — render the
region names only). Click → `revealWindow(key)` (the shell's existing open+scroll+focus path).
Phone (≤639px, the arm's one breakpoint): `grid-template-columns: repeat(3, 1fr)` — 3×2, never a
horizontal scroll. Canvases are `aria-hidden`; each button's accessible name states window +
time + verdict (+ "best bet").

Order control: `When | Best` joins the lens bar as a third segmented group (reusing `LensSegment`),
persisted as `photocast.planOrder`. `Best` re-sorts `paneItems`' window cards by max
`region.meanRating` for the event (AWAITING last, date as tiebreak) and numbers the cards
(`1`…`6`); away rows stay in date order beside them (chronological interleave only applies under
`When` — under `Best` away rows sink below the ranked cards; state this in the row footer). The
strip never re-orders.

### 4.4 The open row (P3) — inside `WindowFirstWindowCard`

New body order: **field map → region rail → region band → existing attribute rows (tide first) →
existing spot strip → footer**. All new pieces render only when the card is open (canvas drawing
is lazy by construction).

- **Field map**: `drawGeo` at `grid: 6`, radius `max(20, bw*0.072)`, blur 3.6, height
  `bw × clamp(aspect, 0.36, 0.62)` desktop / `(0.5, 0.95)` phone. Region labels are DOM (not
  canvas) at `centroid()` positions; the `.mapbox` `line-height: 0` trap and the label plate's
  explicit `line-height: 1.35` come straight from the bundle README. Click within 26% of frame
  width of a centroid selects that region; same-region click or empty space clears. Canvas
  `aria-hidden`; the rail is the accessible equivalent.
- **Region rail**: `All N regions` as a peer cell first, then regions ranked by `meanRating` desc.
  Cell: name, verdict word (`displayVerdict`), `best N★ · M in reach` or `best N★ · 2h 38min away`
  when nothing is in reach (min `driveMinutes` over the region's locations). Grid
  `repeat(auto-fit, minmax(118px, 1fr))`; phone `1fr 1fr` with the All cell spanning. The rail
  disappears when the scope is a single region (P7 origin case; unreachable before then).
- **Region band** (when a region is selected): name, active-filter list (reach tier + rating floor
  + region — the lens states it already; the band names it), `Show all regions ×`,
  `region.summary` narrative (serif) — **with the design's explicit null path**: when `summary` is
  absent (AWAITING regions can lack one), render "No narrative generated for this window." plus,
  when a different window is that region's best, "This region's own best is *<window> <time>*" —
  never silent blankness. Three figures (`best in field` ← the served `bestRating` / `at N★+` /
  `within <tier>` — the latter two are lens output, client-side), six-dot window strip coloured
  by that region's `meanRating` per event through `scoreRamp`, `◎` on its best window, dots jump
  windows via `revealWindow`. Delta chip arrives with P6.
- **Focus**: selected region feeds the kernel's `focus` option (repaint with `alpha: 238`); card
  spot pool gains the region filter (compose with the existing `gateSpotsByReach`/`ByRating`);
  the footer names all three filters in force.
- Per-card `reg` state lives with the card's other open-state in the shell (`cardOverrides`
  pattern), cleared on collapse and on window change.

### 4.5 Map tab heat (P4) — inside the shared `MapView`, opt-in

`MapView` gains `heat` (default `undefined` → v1 untouched): `{spots, windows, enabled}` handed
only by `WindowFirstMapPane`. New internal `MapHeatLayer` component (react-leaflet child using
`useMap`):

- On mount: `map.createPane('wf-heat')` with `zIndex: 350` — between the tile pane (200) and the
  overlay/marker panes (400/600) so heat sits under azimuth lines, viewline and markers; a single
  `<canvas>` in that pane, `pointer-events: none`, `aria-hidden`.
- On every `move`/`zoom`/`viewreset`/`resize`: reposition the canvas to
  `map.containerPointToLayerPoint([0, 0])` (`L.DomUtil.setPosition`) and repaint via
  `drawTiles(canvas, map, points, …)` — **rAF-throttled `render()`**, plus an un-throttled
  `renderNow()` on `moveend`/`zoomend`. Throttle, never debounce (the bundle's stale-overlay
  lesson is codified in `map-tab.js:96-100`).
- Radius in real distance: `radiusFor(map, 8500, 34, 240)`; `grid: 6`, blur 4, `conf` from the
  selected window's tier, opacity `0.9 × heatAlpha(zoom)` with the z10.6→12.2 fade to a 0.17
  floor; the marker panes fade the opposite way with the pointer-events gate (D8 — both fades
  live in the same zoom handler).
- Toolbar (top-left, `left: 60px`, `z-index: 1100` — Leaflet's zoom control paints over anything
  lower, and `CentreOnHomeControl` stacks under it in the same corner): `◎ My area | Whole
  catalogue` (fitBounds from `planningArea`, **`{animate: false}`** — the bundle's
  fitBounds-mid-paint trap; **absent entirely when no home is set**, since the two states are then
  identical and a control with no effect is banned by the §6 coherence rule), `Heat | ◍
  Medallions` (heat default in v2; medallions = today's cluster view untouched), window selector
  over the six `renderedEvents`. The existing dark-sky toggle stays and repaints the field (D7).
  Opening bounds: `latLngBounds(areaSpots, 0.12)` with `padding: [28,28]` — **not** all locations.
- The window selector is Map-tab state (`WindowFirstMapPane`), independent of the Plan tab's open
  row — the design keeps the two tabs' questions separate (time vs space).
- v1's Map tab and the overlay `MapView` instances pass no `heat` prop and render byte-identically.
  Pin this with the opt-in's own test, the `serverCellRating` shape.

### 4.6 Leave-by (P5)

`utils/leaveBy.js`: `leaveBy(eventTimeUtc, driveMinutes, SETUP_MINUTES = 20)` → London-formatted
`HH:mm`, null when `driveMinutes` unknown (never guess). Rendered on `WindowSpotCard` as
`↰ leave 03:50` (and in the sheet + peek). Uses the existing instant-formatting utils — the
codebase's instants-vs-London-dates rules apply; boundary tests around midnight wrap (a fixture
that actually wraps: an 04:40 BST sunrise = 03:40 UTC with a 3h45 drive leaves at 23:35 the
previous day — a 2h30 drive off an 05:42 sunrise does *not* wrap and pins nothing). P5 also
swaps the v2 spot-card rating badge onto `scoreRamp` (D2).

### 4.7 Movement (P6) — the one backend phase before origin

- Migration (⚠️ read the next number from `ls backend/src/main/resources/db/migration/ | sort -V`
  **on main**, never from a doc): `briefing_region_snapshot(id, region_name, evaluation_date,
  target_type, mean_rating NUMERIC(3,1) NULL, voting_count INT, display_verdict VARCHAR(20),
  generated_at TIMESTAMPTZ, briefing_generated_at TIMESTAMPTZ)`, index
  `(region_name, evaluation_date, target_type, briefing_generated_at DESC)`, pruned by age (90
  days) in the writer.
- Writer: end of `BriefingService.refreshBriefing`, after enrichment — one row per
  region × date × event with the same `votingSlots`-based mean the display uses (the
  `evaluation_delta_log` alternative was rejected: intersection-only population, absolute deltas,
  canopy merges invisible — its mean cannot reconcile with the displayed `meanRating`).
- Serve: `enrichWithCachedScores` (or a sibling step beside it) attaches nullable
  `BriefingRegion.meanRatingDelta` = current serve-time mean − latest snapshot with
  `briefing_generated_at <` the current build's stamp, and the response gains nullable
  `previousGeneratedAt`. Nullable + `@JsonInclude(NON_NULL)` — the `confidence` precedent: no
  migration for the payload, legacy caches deserialize null, **degrade is silence, never `—` for
  "unknown"** (the design's `—` chip means a real measured zero). One semantic to state in the
  field's javadoc: the current side is the serve-time re-enriched mean, so the delta includes
  post-build batch drift, not only run-to-run change — that is the honest quantity (it is what
  the reader's screen moved by), just not literally "since the last build".
- Frontend: strip movement chips (`▲0.6` `--color-badge-go` / `▼0.3` `--color-badge-poor`, `—`
  muted only when a delta of 0.0 was measured), chip per window = the delta of the window's top
  region; change line under the strip ("Since the last forecast run 52m ago · <window> ▲0.6 in
  <region> · …", top two movers by |delta|) — the run age is `generatedAt`, already rendered by
  the shell; "your last look" per-user tracking is deliberately not built.

### 4.8 Origin + search (P7)

Backend (all shared, user-independent — this is what lets it ride shared payloads):
- Region base: `regions` gains `base_name VARCHAR`, `base_lat`, `base_lon DOUBLE PRECISION`
  (nullable; admin UI edit in `RegionManagementView`). A region without a base cannot be an
  origin (search still finds it; "Plan from here" disabled with a title saying why).
- `region_drive_time(region_id, location_id, drive_duration_seconds)` — ORS matrix from each base
  to the whole roster (`OpenRouteServiceClient.fetchDurations` already takes an arbitrary origin),
  refreshed beside `DriveTimeRefreshJob`. The rate-limit `Semaphore(2)` is currently a
  `private static` inside `DriveDurationService` — hoist it somewhere both jobs share it
  deliberately; do not duplicate it and double the effective concurrency. Re-run when a base
  changes or locations are added. Served by a small bearer endpoint (shape:
  `GET /api/regions/drive-times` → `{regionId: {locationId: minutes}}`), ETag-whitelisted — it is
  shared data.
- Optional: `aliases` column (comma-separated) for search; start name-matching only.

Frontend:
- Origin chip in the shell masthead (home glyph + place; blue away variant + `⌂` return), replacing
  the rail-footer `Home · <place>` line. `origin` joins the shell state: `HOME` or one region.
- Search (`/` and chip click): one box over three groups — Windows (day/event token matching),
  Regions ("PLAN FROM HERE"), Locations (jump to spot on map / four-day sheet at P8). Keyboard
  ↑↓/enter/esc. Regions absent from the resting list (the row maps are the region picker), still
  matched when typed. The prototype's resting-state "Recent locations" group is **not built** —
  no recency store exists (§9.11); the resting list is windows-only. P7 also wires the beyond
  line's "search to plan from one →" link (search pre-filled with the first beyond region),
  deferred from P2. Reuse the dialog/focus machinery (`useDialogFocus`, `BottomSheet` on phone).
- `setOrigin(region)`: scope becomes that region (pool, `fitOf`, all six thumbnails re-frame — the
  design's headline state), lens relabels `Drive from <base>` and drive figures switch to the
  shared `region_drive_time` map, reach default drops to the 90 tier, leave-by recomputes from the
  same figures. The region rail drops away (single-region scope). The reach *lens* stays personal;
  the *field* never filters by it, home or away.
- The two clash states (nothing within reach of the base / rating floor set at home empties an
  away region) get the design's explicit explain-and-offer treatment — these are lens-empty
  states, and v2 already has the lens-empty card pattern to extend.

---

## 5. Performance invariants (port these, do not rediscover them)

1. **Cull + bucketing stay exactly as written.** 204 locations stalled a pan without bucketing
   (~4.5M inner-loop iterations/frame); coverage is growing. Any "simplification" that touches the
   3×3 bucket walk needs the brute-force equivalence test (§7.1) green and a measured pan.
2. **Throttle, never debounce, map moves** — one rAF-guarded `render()` for `move`/`zoom`,
   un-throttled `renderNow()` on `moveend`/`zoomend`.
3. **No heavy synchronous paint in the same tick as an animated `fitBounds`** — area/catalogue
   switches use `{animate: false}`.
4. **Memoise derived scope** — `heatSpots`, per-window point arrays, `areaRegions`, `fitOf` aspect:
   `useMemo` on their real inputs; nothing derived inside a render loop. (The prototype's
   unmemoised `areaRids` cost seconds per repaint.)
5. **Grid dials**: 4 thumbnails, 6 big map + tiles; blur hides the step; never below 3 without
   measuring. DPR capped at 2.
6. **Zero-measure guard**: `drawGeo` declines when the box is < 20px (a zero canvas throws); the
   strip retries via rAF exactly as the prototype's `drawThumbs(tries)` does — the shell's panes
   mount hidden (`display: none`), so first-draw-on-reveal must be handled (the `resizeNonce` /
   ResizeObserver precedent in `WindowFirstMapPane`).

---

## 6. Phases

Each phase: own branch, conventional commits, CHANGELOG entry, full frontend gate (`npm run lint
&& npm test && npm audit --audit-level=high && npm run build`), adversarial review of the diff
**before** commit, browser verification where the phase is previewable. Backend phases add the
Maven ladder (`compile → single-class test → checkstyle:check → full verify`), exit-code-gated.

| Phase | Contents | New/changed files (indicative) | Exit criteria |
|---|---|---|---|
| **P0 — foundations** | ~~Vendor bundle; add `d3-geo` + `topojson-client`; generator + committed asset; port kernel + `scoreRamp.js`; kernel unit tests~~ **DONE 2026-08-18** (`feature/heat-p0-kernel`). **Build size delta: ZERO** — nothing imports the kernel yet, so it is tree-shaken entirely (`dist` 2,652 KB, 38 assets, 22 precache entries / 918.92 KiB, all unchanged). Measured cost once P1+ imports it: entry chunk +7.2 KB raw / +3.4 KB gzip (kernel, ramp **and `topojson-client`** — no `manualChunks` rule matches it, so it lands in the **precached** entry chunk), `d3-geo` folded into the existing `recharts` chunk, and the topology code-splits to its own 9.1 KB / 3.4 KB gzip lazy chunk that is **not** precached. **Four decisions worth not re-deriving:** (1) a **non-finite score resolves to the ramp's bottom, never its top** — the one deliberate departure from the prototype, because a bare clamp leaves NaN intact, NaN fails every `<=`, and the segment search then returns the LAST stop; an out-of-range window index alone would have painted 5★ GO green at full opacity. (2) `rgb()` lives in `scoreRamp.js`, re-exported from the kernel, so P5's spot badges get it without dragging a static `d3-geo` import into the spot-card module graph. (3) `load()` shares an in-flight promise — six thumbnails mount at once and without it five of six hold a FeatureCollection that is not the one `drawGeo` reads — and **rejects rather than latching failure**, so callers must handle it or show a permanent loading state. (4) The asset keeps the **upstream `transform`**, which is what makes it decode bit-identically to the CDN original; a test compares it against the `world-atlas` package on every run and `.prettierignore` holds it out of `npm run format`. **Left to later phases, deliberately** — each is verbatim-from-the-prototype behaviour the port was not authorised to change: the kernel treats a **null/undefined score as zero** (drags the blend to 1★ and still counts the point's weight — §4.2's filter in P1 is load-bearing, not belt-and-braces, and it must drop non-finite `lat`/`lng` too, which is the only way `centroid` can be handed a NaN); `opts.focus` is tested for **truthiness**, so a region id of `0` or `''` silently shows the unfocused field (P1 should key `rid` on something never falsy); `field`/`fit` **dereference a null 2d context** rather than declining, which real browsers can return under memory pressure (P2/P4 own mounting, so the guard belongs there); and `drawGeo` returns null for **three** different reasons, of which "geometry not loaded yet" is the one P2's rAF retry must not confuse with a zero measure — use `land()`. ⚠️ **§4.1's chunking note is measured and half-wrong in a way that will bite P2:** a rule *appended after* the `d3-` catch indeed never fires, but one placed *before* it does — measured, it yields a 22 KB `geo` chunk and leaves `recharts` lazy. That matters because `recharts` is reached today only behind `ManageView`'s `lazy()` boundary (ADMIN-only), so the first Plan-tab component to call `drawGeo` makes **375.75 KB / 107.46 KB gzip** a render-blocking first-paint fetch for every user, to obtain ~20 KB of `d3-geo`. Deliberately NOT fixed here — `vite.config.js` is outside P0's file list and the cost is zero until something imports the kernel — but P2 should take the one-line `manualChunks` rule and measure it. Also recorded: the coastline is ISO **826 only**, so the Isle of Man, Channel Islands and Scilly are sea, and `drawGeo` **clips heat to the land path** — a roster location on any of them paints nothing on the Plan surfaces (the Leaflet host has no clip and is unaffected); **roster checked — owner confirmed 2026-08-19 there are none**, so no mitigation is needed unless the catalogue later grows there | `docs/design/heat-map/*`, `frontend/src/utils/heatField.js`, `utils/scoreRamp.js`, `frontend/scripts/generate-uk-land.mjs`, `src/assets/uk-land-50m.json`, `src/test/heatField.test.js`, `scoreRamp.test.js` | Kernel tests green incl. bucket≡brute-force; bundle diffable against the zip; no CDN reference anywhere; build size delta noted |
| **P1 — data plumbing** | ~~Provider retains raw score rows (incl. `locationId`) + receives `locations`; `heatSpots` join; `planningArea`; conf tier→scalar helper; **backend `BriefingRegion.bestRating`**~~ **DONE 2026-08-19** (`feature/heat-p1-data`). **Scores payload, measured** (the exit criterion): the window is today−2..today+5 × 2 events × the enabled roster (`source != NONE` rows only), at **~560 bytes/row raw** — 799 KB raw / 17.2 KB gzip at 61 locations, 1.31 MB / 27.0 KB at 100, 2.68 MB / 53.6 KB at 204. nginx gzips `application/json` (`nginx.conf:28`), so the small column is the wire cost and the raw column is the browser's parse and heap cost; the gzip figures bracket identical-prose and per-row-unique-prose regimes, since real summaries compress worse than a repeated fixture. Modelled from the row shape through Boot's web-mapper settings, **not** captured from production, and an upper bound (it assumes every location scored every day and event). **Build**: `dist` 2,652 KB, 38 assets, 22 precache entries / **920.82 KiB** (P0 recorded 918.92) — +1.90 KiB, no new chunk, and `d3-geo`/`topojson`/the kernel are all still tree-shaken because nothing imports them yet. ⚠️ `planningArea.js` has no production importer either, so P2 adds its weight on top of this baseline. **Decisions worth not re-deriving:** (1) **A rating that does not mean sky colour never reaches the field** — the review's one real defect. Woodland and bluebell sites are scored by their own prompts on inverted polarity, and `mergeWoodlandFromBatch` writes those ratings into the very cache `evaluate/scores` serves, so a wood rated 5 on a misty dawn would have bloomed gold over sky locations rated 1. The predicate is `isSkyPromptCandidate`, the existing frontend mirror of the backend's sky-prompt gate — chosen over `MapView.excludeFromSkyCluster` because it **includes WATERFALL**, which is the same population `BriefingRegion.bestRating` votes over, so the field agrees with the `best N★` printed beside it rather than being a third answer. The spot is kept and `skySubject` records why its scores are null. (2) **The point sets are KEYED `date:targetType`, never positional.** The card list is not the event list — `buildWindowCards` drops travel days, `buildPaneItems` folds away runs, and `selectUpcomingEvents` re-indexes when a window passes mid-session — and a positional array also hands every caller an integer that looks exactly like the kernel's `win`. Passing it reads past a one-element `r`, and P0's non-finite→bottom rule then paints a **full-strength dark red field** with the coverage clamp untouched. (3) **Region names are joined byte-identically, never normalised.** Nothing normalises `BriefingRegion.regionName`, which is where the focus key and the rail's labels come from; a trim here made `" North East"` miss `"North East"` on every point, and the kernel answers a focus matching nothing by fading the **whole** canvas to transparent. The stable key would be an id, but `BriefingRegion` carries none — putting `regionId` on it belongs with P7's region bases. (4) `rid` mirrors `regionName` on the spot so `centroid()` (which filters on `rid`) can run over the whole catalogue; from the scored subset alone, P3's region labels would move between thumbnails as coverage changed. (5) Rows are **pre-filtered to the rendered windows before indexing** — 2.6 ms → 1.3 ms per rebuild on a 204-location model, and the join re-runs on every poll and every window focus. Splitting the index onto its own `[scoreRows]` memo is faster still (0.39 ms) but costs ~800 KB resident to hold both indexes; rejected for a phone. (6) `planningArea`: an **unmeasured** region is IN the area and is **never NAMED** as beyond — the no-home degrade falls out of that one rule rather than being a special case. The two lists are **exact complements** (an earlier comment claiming otherwise was wrong): what is asymmetric is where the doubt goes, not the arithmetic. `GLANCE = 180` is the bundle's coverage-scale reason (`plan-data.js:190`), not a reach tier plus margin; its second clause — "or a configured home region" — cannot be implemented until P7. (7) `bestRating` is `votingStats.maxRating()`, the same `Stats` object that already yields `meanRating` and `displayVerdict`, so best ≥ mean by construction. ⚠️ Its canopy fallback is **per region** while `BriefingWindow.bestRating`'s is **per window**, so an all-wood region inside a mixed window reports its wood (5) under a header reporting the sky's best (4) — and `PlanWindowProjector.rank` drops that region from the window's list entirely. Deliberate and now pinned: making it window-aware would leave one record printing two populations. **A surface showing both levels must not present them as one number.** (8) ⚠️ **The "no migration" precedent is one-directional.** Rolling back past an additive field makes `AppConfig`'s Jackson 2 mapper (default `FAIL_ON_UNKNOWN_PROPERTIES`) throw on every `daily_briefing_cache` row written since deploy, and `loadPersistedBriefing` swallows it — the Plan tab is empty until the next scheduled refresh. True of `confidence` and `meanRating` too; the fix is payload-wide and was not made here. (9) The raw rows are retained but **not published** on the context — P5/P8 want `LocationEvaluationView.summary` and should export *these*, not `scoreIndex`, which is name-keyed and drops every row missing a region or location name (a different population from the one the join saw). **Known gap, recorded not fixed:** there is no `App` test file anywhere in the repo, so `locations={visibleLocations}` — like every other prop App wires — is unpinned; deleting it empties the field with 3,697 tests green | `context/WindowFirstBriefingContext.jsx`, `App.jsx`, `utils/heatSpots.js`, `utils/planningArea.js`, `utils/confidenceUtils.js`, backend `BriefingRegion.java` + `BriefingService.enrichWithCachedScores` + tests | Join tests (id-first/name-fallback, null-score windows, unregioned drop); planning-area boundaries 179/180/181 + no-home degrade; `bestRating` non-canopy rule pinned both sides; scores payload size measured and recorded |
| **P2 — the strip** | ~~`WindowFirstHeatStrip` replacing the day rail (D1, including retiring the above-tabs pinned test with the recorded rationale); Order control on the lens bar; beyond line (names only)~~ **DONE 2026-08-19** (`feature/heat-p2-strip`). **Build: the first paint got SMALLER.** Entry chunk 359.80 KB → **351.00 KB** (103.13 → 100.67 gzip), precache 22 entries / 920.82 KiB → **21 / 913.66 KiB**, `dist` 2,652 → 2,692 KB / 41 assets. The strip is behind a `lazy()` boundary and that is not an optimisation — `App` imports the v2 shell **statically** while `usePlanLayout` still defaults to v1, so a static import puts `d3-geo` + the kernel in the entry chunk for **every** reader of an arm they never see, and the plan's own scope guard says v2 must not change v1. Behind the boundary: strip 11.12 KB / 4.65 gzip, `geo` 24.14 / 9.19, topology 9.07 / 3.42 — none precached, all on the runtime `CacheFirst` `/assets/` route. ⚠️ **The `manualChunks` rule is still required, for a different reason than P0 predicted.** With the strip lazy the rule no longer protects first paint; it protects the strip's own chunk — measured both ways, with the rule that chunk statically imports `geo` (24.14 KB), without it it imports `recharts` at **396.61 KB / 115.40 KB gzip**, so opening Plan would fetch the whole charting library. `d3-array` must ride with `d3-geo` (which depends on it) or `geo` lands downstream of `recharts` again; measured cost of carrying recharts' own d3-array usage: 3.43 KB raw / 1.33 KB gzip. **Decisions worth not re-deriving:** (1) **The header prints no count at all** — not the mock's "204 rated locations", and not the "The next N days" the first cut derived from the days DRAWN either. §2.6 and §4.3 both say kicker + rule, no count, unqualified; the design's static "Next four days" is not portable either, since six windows span three dates or four depending on whether the day's sunrise has passed. It reads **"The days ahead"**. (2) **The beyond line names a drive, never the field** — it reads `Beyond 3h from home:`, not the design's "and not in the field", because the field is **not** area-filtered: only the framing is (§3 — the lens does not filter the field), and `bbox` pads 1.7× in longitude, so a beyond region is often painted. (3) **An away window is a `<div>`, not a button** — a deviation from §4.3's "six `<button>` thumbnails", because a travel day has no card to open and §6 bans a control with no visible effect. It keeps its slot (removing it renumbers the shape of the week) and its sun time (almanac), and says "Not forecast" — `windowFirstAway.js`'s own word, not a fourth vocabulary. (4) **The accessible name is one `aria-labelledby` sentence, and name-from-contents does not work here.** `.wf-hc` and `.wf-hc-bot` are flex containers, and CSS Flexbox §4 makes a whitespace-only child text run unrendered, so the `{' '}` separators an earlier cut relied on contribute nothing in a browser — while jsdom, with `css: false`, treats every span as inline and makes those literals the ONLY spaces. The test passed and the browser would have announced "Tonight Sunset21:11Worth it". The label is a real hidden span built from the same fields the visible words are, so it cannot drift and 2.5.3 holds. (5) The thumbnail carries `aria-expanded` + `aria-controls`: which row is open was a CSS-only signal (a gold tint), invisible to AT and to anyone who cannot resolve it. (6) **Verdict words take the lifted `--color-badge-*` variants, never the `--color-verdict-*` fills** — at 9px the fills measure go 7.06:1, marginal 8.12:1 and **standdown 3.66:1**, so "Poor" would be the only verdict below AA. Five other new strings moved muted → secondary for the same reason (3.55:1 → 6.88:1), the seventh time this file has made that correction. **Four defects the adversarial review caught that the gate did not:** (a) the **ResizeObserver never attached** — `useEffect(…, [])` read a ref that is null on a cold load (the component returns null until `cards` and `spots` arrive) and with an empty dep list never ran again, so there was no repaint on a rotate or on the reveal of a `display: none` pane, which is the case it exists for; it is a ref callback now. (b) **`Order · Best` inverted the promoted strip's `adjacent` gate**, which is computed in date order and withholds the "Go to" control when true — under `Best` that either stranded a promoted window with no route to it or offered a button scrolling one element down; the shell re-answers it against the rendered order. (c) **`topMeanRating` ranked on the unfiltered region list**, so in bluebell season an all-canopy region's inverted-polarity mean (a canopy GO means heavy cloud and mist) could take rank 1 under a header reading "Poor" and beside a thumbnail painting no heat there — `PlanWindowProjector.rank` drops those regions and the client now mirrors it. Same class as the defect P1's review caught in the field itself. (d) `usePlanOrder` returned an unmemoised object, changing the context identity on every provider render — both sibling lens hooks memoise for exactly that reason. ⚠️ **And one the review could not catch, because jsdom has no layout: the repaint-on-resize did not fire.** The observer gated on `getBoundingClientRect()` while the paint measures `clientWidth`; in the verifying browser the first answered 0 and the second 82, so every observation took the zero-box early return and the canvases stayed at their previous width, clipped by `.wf-hc`'s `overflow: hidden`. Both ends read `clientWidth` now, and a `window.resize` listener sits beside the observer — belt and braces, because the observer alone did not fire on a viewport change in that host. **Seams recorded for later phases:** P3 needs this component's whole canvas host (the `load()`/`land()` split, the rAF measure-retry, the observer) at different dials — extract a `useHeatCanvas` hook there rather than copying it; the framing (`areaSpots` → `bbox` → `thumbAspect`) is derived inside the component and P7's `setOrigin` will need it lifted; `.wf-hc-flag`'s `top: 22px` is a measured constant against today's top row, so P6's movement chip must re-measure it or become a second flex row; and `topMeanRating` returns a number, so P6 (which wants the top region's delta) will want it to return the region too. §9.6's LITE question is still open — the strip ships ungated, per that section's own proposal, with no owner confirmation recorded | `components/WindowFirstHeatStrip.jsx`, `WindowFirstShell.jsx` (lazy boundary, ordering, adjacency), `WindowFirstLensBar.jsx`, `WindowFirstWindowCard.jsx` (optional `rank`), `utils/windowFirstOrder.js`, `utils/windowFirstStrip.js`, `hooks/usePlanOrder.js`, `context/WindowFirstBriefingContext.jsx`, `utils/windowFirstCards.js` (`topMeanRating`), `index.css`, `vite.config.js`, `scripts/dev-seed-locations.sh`; deleted `WindowFirstDayRail.jsx` + `windowFirstRail.js` + both test files | Six buttons with correct accessible names ✔; `BEST BET` flag passive ✔; click opens+scrolls its row (browser-verified: `aria-expanded` flips, the row opens, focus lands on its expander) ✔; strip order immune to Order·Best ✔ (browser-verified on one render: rows ranked 1–6, strip still chronological); verdict words from payload only ✔; no database-count copy ✔ (no count of any kind); 3×2 at 639px asserted as a class ✔ and seen at 390 ✔; browser screenshots desktop + 390px ✔ |
| **P3 — the open row** | ~~Row field map + centroid labels + click-to-region; region rail; region band; focus + card-pool region filter; footer filter naming~~ **DONE 2026-08-19** (#561, `5419f503`) — see the Status block above for what its review found | `WindowFirstWindowCard.jsx`, `components/WindowRowFieldMap.jsx`, `WindowRegionRail.jsx`, `WindowRegionBand.jsx`, `hooks/useHeatCanvas.js`, shell state, tests | Rail ranked by `meanRating`, star figures from served `bestRating` only ✔; nothing-in-reach cell shows distance ✔; band narrative = `region.summary` verbatim **and the null-summary fallback renders the design's line** ✔; same-region click clears ✔; six-dot jumps windows ✔; tide row + spot strip unmoved (their tests pass unedited) ✔ |
| **P4 — map heat** | ~~`MapHeatLayer` + pane; `heat` opt-in prop (canvas + marker-pane zoom fade + `scoreRamp` marker colours, per D8/D2); toolbar (view/area/window); legend~~ **DONE 2026-08-19** (`feature/heat-p4-map`). **Build:** entry chunk **unchanged in shape**, 360.65 → 361.52 KB raw (103.57 → 104.01 gzip) — the +0.87 KB is `scoreRamp.js`, which reaches the entry for the first time because `markerUtils` now imports it; precache 21 entries / 930.82 → **932.83 KiB**. `MapView` 36.39 → **40.79 KB** (11.74 → 13.03 gzip), and — the number that matters — its chunk statically imports **no `heatField`, no `geo`**: `MapHeatLayer` is behind `lazy()` and v1 fetches nothing new. New lazy chunks: `MapHeatLayer` 2.47 / 1.28 gzip, `heatGeometry` 0.84 / 0.40; `useHeatCanvas` 2.28 → 7.40 (it absorbed the kernel). **Measured pan, 210 locations, 1044×500 canvas at z8: median 9.0 ms per step, p90 10.2, p99 26.7** — inside a frame budget, no stall. **Decisions worth not re-deriving:** (1) ⚠️ **The area segment FRAMES and never filters, against the prototype.** `map-tab.js`'s `visible()` filters; the plan forbids it in five places (§3's "the lens does not filter the field", §4.5's *fitBounds*, §9 Q4 still OPEN, and the same warning in both `planningArea.js` and `WindowFirstHeatStrip`), and P2 chose the strip footer's wording *because* the field is not area-filtered — so filtering here would have made a caption false on the tab next door. The dark-sky toggle DOES narrow the field (§4.5, D7) and the difference is not arbitrary: darkness is a property of the place, distance is a property of the reader. (2) **The window selector sets the map's own date and event** rather than holding its own — §4.5 puts the state in the pane, but the tab already has a date strip and event chips, and a third control that could disagree would put the field on one evening and the markers on another under the same star. (3) **The quality threshold does NOT touch the field.** The map defaults to 3★+; a field filtered by that paints only the good news and flattens the gradient the feature exists to show. (4) **The star-filter swatches move to the ramp with everything else** — D2 never listed them, and left alone they were a second colour language for one rating on the same screen. (5) `requiresLand: false` saves the topology asset and the wait, **not** the `geo` chunk: `drawTiles` shares a module with `drawGeo`. Left alone because on this arm the Plan strip has fetched `geo` long before the Map tab is opened. (6) **The markers stay in the accessibility tree at every zoom.** The first cut used `visibility: hidden` to close the pointer hole, which also removed the only route to a location's popup — at the zoom this tab OPENS at — and dropped focus to `<body>` whenever a focused marker faded. Opacity hides the picture; a class removes the target. **Three defects the adversarial review caught that the gate did not:** the toolbar's pressed state named `bg-plex-accent`, a token this project has never had (the third recurrence — `TravelDaysView.jsx:163` and `ModelTestView.jsx:639` record the other two), so the selected segment painted nothing and had no focus ring and stood 21px tall; the widened change gate watched the frame's HEIGHT for the two static hosts, whose frame height is a *consequence* of the paint, giving both a doubled first paint that jsdom (every `clientHeight` is 0) could not see; and `MapContainer` reads `bounds` once at construction, so the opening framing raced the reach fetch with no way back. **And two the browser caught after them:** ⚠️ `pointer-events: none` on the marker pane is necessary and *not sufficient* — Leaflet's `.leaflet-marker-icon.leaflet-interactive { pointer-events: auto }` is two classes, exactly the specificity of the first fix, and later in source order, so an invisible medallion still ate the tap (`markerInertCascade.test.jsx` now pins the cascade); and toggling to Medallions left every cluster bubble on the ramp it was built with, because `L.MarkerClusterGroup` caches each bubble's `_iconObj` and `react-leaflet-cluster` writes the new factory into `options` without refreshing — fixed by keying the group on the view. A third, found while measuring: a same-tick latch on the settle (added to stop Leaflet's `zoomend`+`moveend` painting twice) froze the marker fade when the map was driven through several zooms in one task; the duplicate is deduped on the **view** instead. **Known and recorded, not fixed:** at 390px the toolbar wraps to four rows over **27% of the map's height** (133px of 500) — its container is `pointer-events: none` so the gaps still pan, and the alternatives all cost the design's own words; and with `zoomSnap: 1` two framings that differ by less than a zoom step read as a no-op press (the prototype uses `zoomSnap: 0`, which is a shared `MapContainer` option and so v1's too). D8's bottom-left status panel is **not built** — it is P4-optional | `MapView.jsx` (opt-in), `components/MapHeatLayer.jsx`, `WindowFirstMapPane.jsx`, `hooks/useHeatCanvas.js`, `components/markerUtils.js`, `utils/heatGeometry.js` (new), `utils/heatField.js`, `utils/planningArea.js`, `index.css`; tests `MapHeatLayer`, `MapViewHeat`, `WindowFirstMapPaneHeat`, `markerRampOptIn`, `markerInertCascade`, `useHeatCanvas` | v1 map + overlay byte-identical without the prop ✔ (pinned per surface, incl. v1's `[60,60]` padding and `ramp === false` on every marker call); marker fade + pointer-events gate asserted ✔ (and the threshold pinned ON its boundary via an exported predicate); area toggle **absent when no home** ✔ (both derivations); throttle/renderNow wiring asserted ✔ (by counting FRAMES, not paints — a debounce passes a paint count); `{animate:false}` pinned ✔ with two genuinely different boxes; browser: 210-location dense fixture panned without stall ✔, heat/medallion, area toggle, dark-sky repaint and the z8→z13 handover all verified at 1440 and 390 ✔ |
| **P5 — leave-by** | ~~`leaveBy` util + spot card/sheet/peek line; spot-badge ramp swap (D2)~~ **DONE 2026-08-19** (`feature/heat-p5-leaveby`). **Build:** entry chunk 361.52 → **362.36 KB** raw (104.01 → 104.21 gzip), precache 21 entries / 932.83 → **933.65 KiB**, no new chunk — the delta is `leaveBy.js` reaching the entry through the statically-imported v2 shell. `WindowSpotSheet.jsx` is in the file list above and was **not edited**: it renders `WindowSpotCard`, so the line arrives there by extraction rather than by a second copy, and a test in its own file pins that. **Decisions worth not re-deriving:** (1) **The event time is the SLOT's, not the window's.** `BriefingSlot.solarEventTime` is per location; the window header's time is `BriefingEventSummary.earliestEventTime`, the earliest across a region set, chosen for determinism. Sunrise spans tens of minutes across this roster, and leave-by is advice to one person driving to one place. The visible cost is that a card can print `21:11` in its header over a leave time derived from 21:25 — the right trade, and the fixtures on both sides now carry a *different* summary/header time from the slot's, so a fallback to the window's fails rather than passing unnoticed. (2) **Raw on the descriptor, formatted per RENDERED card.** `formatInstantUk` builds an `Intl` formatter per call (~0.09 ms against 0.002 ms cached) and the join runs over the whole roster × six windows on every poll and every window focus — 204 × 6 = 1,224 formats to serve the handful of cards actually drawn. Both consumers call one pure function on the same two fields, so they cannot disagree. (3) **The badge's ink pair had to move with the ramp.** Against `RATING_COLOURS` the app's cream cleared AA on every step it was picked for; against the ramp's 2★ stop (#C8452F) cream is **3.94:1** and the dark ink **3.70:1** — neither reaches 4.5 at 10px, so the swap would have shipped one step below AA. The light ink is now `#FFFFFF` (6.03 / 4.83 / 2.18 / 2.01 / 2.51 across the five stops); the flip still lands between 2★ and 3★. Darkening the stop instead would have broken D2's whole point. (4) **The badge's domain gate is the RATING's bounds, not the ramp's.** `RATING_COLOURS` was a five-key table answering `undefined` for everything else, so the old `fill ?` test was the domain check as well; `rampHex` clamps, so 0, 6 and 2.5 all resolve to a colour. The explicit gate uses `MIN_RATING`/`MAX_RATING` deliberately rather than `RAMP_MIN`/`RAMP_MAX` — they coincide today, but `scoreRamp` is shared with the kernel and a ramp re-based to 0–100 would silently widen this badge to paint a "0★". One behaviour difference from the table, recorded: object indexing coerced, so `RATING_COLOURS['4']` answered a colour and this does not (Jackson serialises the rating as a number, so no live payload reaches it). **One defect no green suite could see, caught in the browser:** the peek's first row is `flex` with no wrap and `.wf-peek-chip` is `white-space: nowrap`, so adding the leave chip put **303px of content in a 252px box** on any rated spot with a drive time — and `.wf-peek` declares no `overflow`, so it painted ~50px outside the panel's own border, over the page. Measured, fixed with `flex-wrap` and a row gap, re-measured at 252/252 with the panel at 211px, inside `PEEK_ESTIMATED_HEIGHT`'s 220. jsdom reports 0×0 for everything, so no component test could have failed. **Two design-level charges were REFUTED on evidence and should not be re-opened without new evidence:** (a) *"the line has no pastness rule, so the lead card states an unachievable plan all evening"* — the headline scenario never reaches the arm's pastness machinery (the event is still in the future), that machinery **removes and disables, it never annotates**, the masthead prints today's sunrise unqualified for nine hours at larger type on the same screen, a past-marker would need a live clock the arm does not have (the only scheduled re-render is a 10-minute poll, so the mark would be stale exactly in the ten minutes that matter), and the line is *how* a reader learns the window is out of reach without doing the subtraction themselves. The residual — that the whole card outlives its window by up to 40 minutes — indicts `WindowFirstWindowCard`, not a derived field on every spot card. (b) *"a bare `HH:mm` is ambiguous when the departure wraps past midnight"* — upheld only as a stale comment, since a wrap needs a drive longer than the event's hour: **over 4h03** for the roster's northernmost sunrise, against a loosest bounded reach tier of 150 minutes and a longest realistic catalogue drive of about 2h20, and every wrapping card is already a `far` card. ⚠️ **P7 moves the origin, which is exactly the change that turns a four-hour drive from impossible into ordinary — revisit the day marker there.** **Left for later phases, recorded not fixed:** P4's map marker hard-codes `#0f172a` at every SVG site, so on the ramp its label is 2.96:1 at 1★ and 3.70:1 at 2★ — the badge fixed itself and the marker did not come with it; §9.5's "SETUP as a user setting" would want the term threaded through two call sites, and nothing on the card names the twenty minutes today, so a reader checking event-time-minus-drive lands twenty minutes early; P8's timeline needs an event time the scores payload (`LocationEvaluationView`) does not carry, and this descriptor is canopy- and region-filtered, so P8 should take `BriefingSlot.solarEventTime` directly rather than through `buildWindowSpots`; and `spot.driveMinutes` has exactly one producer, so P7 must **overwrite** it with the region-base figure rather than adding `localDriveMinutes` beside it, or the reach line and the leave line will describe two different journeys with no test failing | `utils/leaveBy.js`, `utils/windowFirstSpots.js`, `utils/windowSpotPeek.js`, `WindowSpotCard.jsx`, `WindowSpotPeek.jsx`, `WindowSpotStrip.jsx`, tests `leaveBy`, `leaveByAbroad`, `windowFirstSpots`, `WindowSpotStrip`, `WindowSpotSheet`, `WindowSpotPeek`, `windowSpotPeek`, `test/setup.js` | Null drive → no line ✔ (and pinned against the sharper case: a distance with no drive still prints the reach line and no leave-by); a genuinely wrapping midnight fixture ✔ (both the UTC-day wrap §4.6 names and a UK-day wrap it does not); London formatting under the TZ-pinned suite ✔ + an abroad-zone test file ✔ (`America/New_York`, with the `resolvedOptions` guard — forced here, because the code names `Europe/London` itself so its answer is pin-invariant and a date guard would prove nothing); badge on the ramp with the ink flip pinned per step and the 2★ AA arithmetic pinned as arithmetic ✔; the card's whole accessible name pinned once ✔ (with the jsdom-vs-browser spacing divergence recorded on it) |
| **P6 — movement** | ~~`briefing_region_snapshot` migration + writer + pruning; `meanRatingDelta` + `previousGeneratedAt` serve fields; strip chips + change line + band chip~~ **BUILT 2026-08-19** (`feature/heat-p6-movement`). **Build:** entry chunk 362.36 → **362.77 KB** raw (104.21 → 104.34 gzip), precache 21 entries / 933.65 → **934.85 KiB**, `dist` 2,748 KB / 47 assets, no new chunk — `movement.js` is reached only through two `lazy()` boundaries (`WindowFirstHeatStrip`, and `WindowRegionBand` via `WindowRowRegionLayer`), so the strip's own chunk grows 5.08 → **5.30 KB** and the entry's delta is the vocabulary the shell pulls in transitively. **Decisions worth not re-deriving:** (1) ⚠️ **The build stamp is truncated to MICROseconds on both write and read, and without that the feature silently disables itself in production and nowhere else.** `briefing_generated_at` is a Postgres `TIMESTAMP` (microseconds) while `LocalDateTime.now()` on **Linux** carries nanoseconds (macOS's `gettimeofday` gives microseconds — which is why no local run and no reviewer's laptop can reproduce it); pgjdbc rounds half-up, so for every build whose sub-microsecond remainder is ≤ 499 ns — about half of them — the stored value is strictly *less* than the value held in memory, `MAX(stamp) < :current` answers with the **current build's own row**, and every delta becomes `serveMean − sameBuildMean`. The strip then fills with the muted `—` that this feature documents as "a real measured zero": a wrong number asserted confidently, on the production platform only. Truncating both sides makes them identical by construction, so the strict `<` means what it says. (2) **The basis is ONE previous BUILD, not each region's own latest earlier row** — a deviation from §4.7, so that a single "at the last forecast run" line can describe every chip truthfully; a region absent from that build gets no chip rather than one reaching further back. (3) **The verb is "moved at", never "since"** — a second deviation, from §4.7's own sample copy. The delta spans *previous build → now* while the printed age is the *last* build's, so "since the last forecast run 52m ago" attributes an eleven-hour change to fifty-two minutes; three independent review lenses raised it. `previousGeneratedAt` is published for the honest basis and deliberately not printed, because two ages for one forecast on one screen is its own defect. (4) **`briefing_generated_at` is TIMESTAMP, not §4.7's TIMESTAMPTZ** — it is a *comparand* copied verbatim from a `LocalDateTime` that `daily_briefing_cache` already stores as TIMESTAMP, and a zone round-trip on one side could move which build a delta measures against. `generated_at` stays TIMESTAMPTZ because it is an absolute audit instant read only by the pruner. (5) **§4.7's index became a UNIQUE constraint** — neither query the repository issues constrains `region_name`, so as a plain index it was dead weight whose own comment described a read the code deliberately does not perform; as `uq_brs_key` it enforces one row per region/date/event per build, which nothing else did. (6) **`voting_count` records the RATED voting slots, not the voting roster** — the mean is over the slots `BriefingRatingStats` actually counted, and recording the roster size beside it answers the opposite of the question the column exists for, unrecoverably. (7) **`attachMovement` lives on `getCachedBriefingForApi`, never on the shared `getServedBriefing`** — `CloseToHomeService` shares that accessor and reads slots and bestBets only, so a step inside it would cost `/api/user/settings/reach` two queries and a full rebuild of the day hierarchy per request for a field it discards; that is the same "paid twice per page" pattern that moved the window projection out there. (8) **The reader THROWS and the caller catches** — a `try/catch` inside a `@Transactional` method cannot work, because a JPA failure marks the transaction rollback-only and the proxy's commit rethrows past the catch; as written it would have 500'd `GET /api/briefing`. **Three defects only the browser could confirm or refute:** the change line's `white-space: nowrap` made the whole clause one unbreakable run (**measured: 648px inside a 315px column at 375px, and a 678px page against a 375px viewport** — a horizontal page scroller, in the block whose own comment says the strip exists not to be one); the predicted `.wf-hc-flag` collision **refuted by measurement** (the top row is 13.5px with and without the chip, and the flag clears it by 2.5px); and the two paragraphs under the footer sat 4px apart with ~5px of leading inside each. | backend migration/entity/repository/service + `BriefingRegion`/`DailyBriefingResponse`/`BriefingDay`/`BriefingService`; `utils/movement.js`, `WindowFirstHeatStrip.jsx`, `WindowRegionBand.jsx`, `windowFirstCards/Regions/Strip.js`, `index.css`, tests both sides | Delta uses votingSlots mean both sides ✔; null delta renders nothing ✔ (browser-verified with the table emptied: zero chips, no change line); measured-zero renders `—` ✔; JaCoCo 80% per class ✔; snapshot pruning tested ✔ (against the literal 90 days, not against the constant) |
| **P7 — origin + search** | ~~Region bases + ORS matrix + endpoint (backend); origin chip, search, `setOrigin`, away lens relabel, clash states, beyond-line search link~~ **BUILT 2026-08-20** (`feature/heat-p7-origin`). **Build:** entry chunk 362.77 → **367.99 KB** raw (104.34 → 105.93 gzip), precache 21 entries / 934.85 → **943.18 KiB**, plus a new lazy **`PlanSearch` chunk at 5.11 KB / 2.08 gzip** — the dialog is on no first-paint path. **Migration V145.** **Decisions worth not re-deriving:** (1) ⚠️ **The origin supplies the reach lens's DEFAULT; it never selects a tier.** The obvious shape — `setOrigin` calling `selectTier('90')` — is wrong twice, and both were review findings. It **persists**: `selectTier` ends in a `localStorage` write stamped with today's date, and the origin is deliberately in memory, so a reload restored the away lens without the away frame and the reader landed at home behind a 1h 30 gate they never chose, with a "today only" pill marking a choice they never made. And it **splits the default from the far mark**: `isFarSpot` measures against `defaultTier`, so an away page marked spots against 90 while the bar's own reset button still read "Back to 45 min" — exactly the drift `reachLens.js` says its derived labels exist to make impossible. As a default override, nothing is written, the reset button and the readout name the away tier, and a reload returns the lens with the origin. One consequence, deliberate: a tier the reader chose explicitly *survives* the move — an explicit choice outranks a default, which is what makes the move a change of frame rather than a control reaching over to move the bar. The readout says `default here` rather than `weekday default`, because the day word is not true while the origin owns the default. (2) ⚠️ **Away, the card's HEADER figures are re-pointed to the origin region's own served record** — `bestRating`, the verdict, `topMeanRating`, `confidence`, the movement chip, and the pick (withheld when it names another region, which takes the strip's `BEST BET` flag with it). `BriefingWindow`'s roll-ups are roster-wide by construction (the backend knows nothing about the origin, which is client-only by design), so left in place they put two populations one gap apart in one meta row: `Worth it · best spot 5★ · 3 within reach` over a strip whose best card is 2★. **Nothing is recomputed** — every field taken is one the backend already serves per region, the same records the rail and band render; a client-side max over `card.spots` would re-create the aggregation class Phase 3 of the verdict consolidation moved server-side. Measured in the browser: `best spot 5★ · 2 within reach` at home became `best spot 3★ · 5 within reach` from Keswick, with the verdict moving `Worth it` → `Poor` on the same card. (3) **`spot.driveMinutes` is OVERWRITTEN, never extended** (P5's requirement): the provider swaps the whole reach map, so the reach line, the far mark, the reach gate and `leaveBy` switch together and cannot describe two journeys. The away map is built from the shared matrix **alone** and carries `distanceMiles: null` — it does not even borrow the miles, which are measured from home. Browser-measured: Derwentwater `🚗 190 min` at home → `🚗 8 min · ↰ leave 20:07` from Keswick, with no miles; and back to `🚗 30 min · 18 mi` on `⌂`. (4) **`⌂` restores TODAY'S default, not the exit criterion's literal 150** — this app has derived that number from the date since P8 (weekday 45 / weekend 2h 30), so re-imposing a constant would override the reader's own day on the way back. Recorded as a deviation rather than settled silently. (5) **The rename clause P6 left open is closed** — `RegionService.update` now clears `briefing_region_snapshot` for the previous name. ⚠️ **Re-keying that table on `region_id` is still NOT done**: it needs `BriefingRegion` to carry an id, which is a payload change this phase did not make, and leaving a careful `clearForRegion` in `setBase` beside a rename that discarded nothing would have read as deliberate asymmetry. The degrade is one build cycle (~11–13h) with no chip for the renamed region, which is the chip's own "no basis renders nothing" rule. (6) **The ORS semaphore is hoisted into the CLIENT, not just into a bean** — `OrsRateLimiter` is applied inside `OpenRouteServiceClient`, so a future third caller cannot bypass it by forgetting to acquire, and the permit now covers the request alone rather than a whole sweep including its DB write. **Four defects only the review or the browser could catch:** a region selection made at home **survived** the origin move while the rail that would clear it was withheld in the same commit — filtering an already-scoped strip to a region the reader had scoped out, and leaving the band's `Show all regions ×` as the only way back, whose handler focuses the rail cell that scope had just unmounted (focus to `<body>`); the `/` shortcut fired **over the settings modal**, which is a sibling of the shell in `App` and therefore invisible to its own `modalOpen` flag, stacking two `aria-modal` overlays; an ORS row of all-nulls (an unroutable base — the exact case the nullable base columns guard against) cleared the `durations.isEmpty()` guard and **wiped a working matrix** while the job logged that it had been left in place; and the search panel's `margin-top: -12vh` was **half-applied and could clip its own input off the top of the viewport** — a flex item is centred including its margins, so it lifted 6vh, and at a full three-group list the input was unreachable on any window under ~940px tall (`align-self: flex-start` at every width, measured: panel top 64px at 1280×800, 16px at 390). **Recorded, not fixed:** `GET /api/regions/drive-times` is a full-table scan and full serialisation per Plan mount (~2,100 rows at ten based regions), unserviced by any cache — cheap today, O(regions × locations) as both grow; setting a base leaves that region with no matrix until 03:10, and the admin form does not say so; and the Map tab's popup drive **chip** was not browser-verified, because it lives in the lazy detail payload and the local DB has no `forecast_evaluation` rows — the drive **filter**, which shares the same accessor, was (≤30 min: 1 marker from Durham, 0 from Keswick) | backend `V145`, `RegionEntity`/`RegionDriveTime*`/`RegionService`/`RegionController`/`OrsRateLimiter`/`HttpCachingConfig`; `utils/planOrigin.js`, `utils/planSearch.js`, `components/PlanSearch.jsx`, `components/PlanOriginChip.jsx`, shell/lens bar/strip/map pane/row map/region layer/regional panel, `windowFirstCards.js`, `windowLensEmpty.js`, `useReachLens.js`, `RegionManagementView.jsx`, `index.css`, tests both sides | Origin move re-frames all six thumbnails ✔ (browser-verified at 1280 and 390; all six re-drew and their verdicts moved with the origin region's own served records); shared matrix never mixes with per-user reach ✔ (`distanceMiles` always null away, nothing borrowed for an unmeasured location, pinned from both directions in `planOrigin.test.js` and at the provider seam in `WindowFirstBriefingContext.test.jsx`); baseless region cannot be an origin ✔ (shown in search, disabled, reason on the row, cursor never rests on it — browser-verified); `⌂` restores home + the day's default tier ✔ (deviation 4), leaving **no** stored override ✔ |
| **P8 — four-day location sheet** (optional) | Per-location six-window timeline (ratings + `summary` why from the scores rows, leave-by), location-card entry (+ search entry if P7 landed) | `components/LocationFourDaySheet.jsx`, tests | Owner accepts click-behaviour change; peek/map-open behaviours preserved where kept |

Sequencing notes: P0→P3 are strictly ordered; P4 needs only P0+P1; P5 is independent after P1;
P6 backend can start any time, its frontend needs P2; P7 needs P2+P3; **P8 needs P1 + P5 (its
timeline rows render leave-by), plus P7 only if the search entry point is chosen**. Suggested
landing order: P0, P1, P2, P3, P4, P5, P6, P7, P8 — with P4 swappable earlier if the Map tab is
wanted sooner.

---

## 7. Test strategy

### 7.1 Kernel (jsdom has no canvas)

jsdom's `getContext('2d')` returns null. Do **not** add a global canvas polyfill to
`src/test/setup.js` — stub per test file: a minimal fake context (`createImageData` returning
`{data: Uint8ClampedArray, width, height}`, `putImageData`, `clearRect`, `fillRect`,
`save/restore`, `drawImage`, `beginPath/fill/stroke/clip`, **the path methods d3's
`geoPath(proj, ctx)` drives — `moveTo`, `lineTo`, `closePath`, `arc`** (without these the drawGeo
smoke tests throw on the coastline), settable `filter`/`globalAlpha`, `setTransform`) patched
onto `HTMLCanvasElement.prototype` in `beforeEach`. The kernel's returned `img` (§4.1) makes cell
assertions direct. Required tests, each mutation-resistant per `frontend-test-standards.md`:

- **Bucket ≡ brute force**: seeded random point sets (~40 points, mixed in/out of frame) — the
  bucketed field's RGBA equals a test-local brute-force accumulator's. The term *sets* are
  identical (`d2 > 6R²` matches both ways) but FP summation order differs, so a value on a
  rounding boundary can differ by 1 — assert equality **within ±1 per channel** (or pin an exact
  seed known to pass and say so in a comment), so a boundary failure is not misread as a port
  bug. This is the test that guards the performance structure against "simplification".
- Cull: a point beyond `2.45R + grid` of the frame influences nothing; one just inside does.
- Coverage clamp: `Σw < 0.02` → alpha 0; empty input → `field()` null.
- Focus: non-focused region's contribution ×1e-4 (assert a cell flips colour).
- Confidence: conf 0.5 desaturates toward grey and thins alpha by the stated factors; conf 1
  identity.
- Ramp: exact at the five stops, interpolated at 1.5/2.5/3.5/4.5, clamped at 0/6.
- Geometry: `bbox` padding (×1.7 lng), `latLngBounds` orientation, `aspect` cos-lat correction,
  `radiusFor` clamps at both ends. (The aspect clamps 0.85/1.22 and 0.36/0.62|0.5/0.95 are **not**
  kernel behaviour — `aspect()` has no clamps; they are strip/row-map constants and their boundary
  tests belong to P2/P3.)

`drawGeo`/`drawTiles` get smoke coverage through the stub (paints without throwing, declines
under 20px, sea/plate/clip call order) — their visual truth is browser-verified (§7.3).

### 7.2 Components

House rules apply in full (`docs/engineering/frontend-test-standards.md`): mock at the API-module
boundary, `fireEvent`, `findBy*`, roles + accessible names for every interactive element,
test-ids for containers (`wf-heat-strip`, `wf-heat-card`, `wf-row-map`, `wf-region-rail`,
`wf-region-cell`, `wf-region-band`, `wf-map-toolbar`… following the arm's naming), boundary
fixtures (zero/one/six windows; a window with no scored locations; AWAITING windows under
Order·Best), degrade paths as named tests (scores fetch fails → strip absent, rows intact; no
reach → beyond line absent and counts absent, field unaffected; legacy payload without
`renderedEvents` → strip falls back with the same walk the cards use). The strip's chronology
invariant gets its own test: with Order·Best active, thumbnail order still equals
`renderedEvents` order while cards are ranked.

### 7.3 Browser verification (the cadence's second half)

Local path: backend `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local` (port
**8083**), `npm run dev`, sign in `admin`/`golden2026`. ⚠️ **A fresh worktree needs
`frontend/.env.local` with `VITE_API_TARGET=http://localhost:8083`** — the file is gitignored so
it is absent by default, and the dev proxy then falls back to 8082 and every request 502s at the
login screen (this has now cost two sessions; `vite.config.js`'s own comment records the trap). The local H2 starts empty (§2.11), so each
verifying session seeds once:

1. **Regions first** — `POST /api/regions` and capture the ids (`POST /api/locations` takes
   `regionId`, and the heatSpots join **drops unregioned locations**, so skipping this yields an
   empty field). Then ~20 locations across 3+ regions via `POST /api/locations` (curl script
   committed as `scripts/dev-seed-locations.sh`; include coastal + inland + a couple with
   `bortleClass ≤ 4`), plus a `--dense` mode that scatters ~200 around the anchors — the P4 pan
   test needs a 200-spot fixture and 20 will not exercise the bucketing.
2. Ratings without an API spend: insert `cached_evaluation` rows in the H2 console
   (`results_json` is a JSON list of `BriefingEvaluationResult`; use **today/future dates** —
   rehydration ignores the past), then ⚠️ **restart the backend** — the briefing enrichment reads
   the in-memory `ConcurrentHashMap`, which is populated from the DB only by
   `rehydrateCacheOnStartup` (`@EventListener(ApplicationReadyEvent)`); rows inserted after
   startup are invisible to `POST /api/briefing/run` until a restart (confusingly,
   `GET /api/briefing/evaluate/scores` *does* see them via its own DB fallback, so a half-lit
   screen here means you skipped the restart, not that the join is broken). Then
   `POST /api/briefing/run`. Document the exact SQL beside the seed script.

Verify with screenshots at desktop and 390px: strip default, a thumbnail click, an open row with
a region selected, Map tab heat vs medallions, area toggle, dark-sky repaint, and (P4) a pan on a
dense fixture. State plainly which claims were seen versus asserted. Review agents are read-only;
anything mutating gets its own worktree; commit or stash before any review that runs mutations.

---

## 8. Risks and traps

- **Tailwind v4 prunes plain-`@theme` tokens** — new heat tokens go in `@theme static`
  (`index.css:61-93` records the incident). JS-side hexes live in `scoreRamp.js`, not CSS vars, so
  canvas and CSS cannot desynchronise silently — the ramp module is the single source and the
  tokens reference the same literals with a comment pointing both ways.
- **Shared-component blast radius**: `MapView` is mounted three times (v1 tab, v2 pane, overlay).
  Only the v2 pane passes `heat`. The opt-in default-off test is not optional. Same for any
  touch on `WindowSpotCard` (shared strip/sheet) — v2-only, but peek/sheet snapshots of behaviour
  should pass unedited where unaffected.
- **Hidden-pane first paint**: shell panes mount `display: none`; canvases measure 0 there. The
  rAF-retry + ResizeObserver patterns already exist (`drawThumbs(tries)` in the prototype,
  `resizeNonce` in `WindowFirstMapPane`) — use them, don't invent a third.
- **Inline-style shorthand trap**: dynamic canvas sizing uses the sanctioned inline pattern
  (runtime-computed values only); never inline `border`/`padding`/`display` shorthands — they have
  repeatedly zeroed phone media queries (`index.css:796-801` etc.).
- **PWA/nginx**: no new external origins (D9), so no CSP edit; keep new chunks out of the precache
  shell globs; the topojson asset rides the runtime `CacheFirst` `/assets/` route with the
  existing 80-entry cap — check the cap still holds with the new chunks.
- **Payload growth**: eager `evaluate/scores` for every v2 session (measure at P1) and the
  briefing payload is already ~1.3MB raw/133KB gzipped — P6 adds one nullable field per region,
  fine; nothing else rides it.
- **Migration numbering rots** — read `ls .../db/migration | sort -V | tail -1` on **main** at P6
  and P7 time (two V136s collided once).
- **CHANGELOG conflicts are guaranteed** across eight PRs — check `git rev-list --count
  HEAD..origin/main` before each merge, not which files changed.
- **Additive payload fields are one-directional across deploys** (established at P1, and true of
  the whole briefing payload — `confidence` and `meanRating` rode in the same way before this
  feature): rolling production back past any deploy that added a `BriefingRegion`/payload field
  makes `AppConfig`'s Jackson 2 mapper throw on every `daily_briefing_cache` row written since;
  `loadPersistedBriefing` swallows the error and the Plan tab stays empty until the next
  scheduled refresh. Not a P-phase defect and deliberately not half-fixed on one record — but a
  rollback that crosses P1 (or later P6) should expect one empty-Plan window and may want a
  manual `POST /api/briefing/run` behind it.
- **The two arms' SWR briefing cache is shared** (`briefing:${role}`) — the strip must tolerate a
  cached payload from before any new field existed (null-safe reads everywhere; the
  `renderedEvents` null-vs-empty contract is already in the provider).
- **evaluate/scores date-keying**: rows are keyed by (locationId, date, targetType); renderedEvents
  supplies exactly those pairs — do not re-derive dates from times (instants-vs-London-dates
  rules; the suite's TZ pin will catch it, an abroad-zone test file must too).

---

## 9. Open questions for the owner

Carried from the bundle, plus new ones this plan surfaced. None block P0/P1.

1. ~~**D1 confirm**~~ **RESOLVED — confirmed by the owner 2026-08-18**: the strip replaces the
   day rail. (Comparison, job relocation table and rejected alternatives: §1.1.)
2. **Plan/Map division of labour** (bundle Q1): time-first/space-first as built, or Map as the
   forecast-agnostic catalogue? Decide before building more onto either tab; does not change P0–P6.
3. **Density on a Plan row** (bundle Q2): quality is stated four times before the rail (thumbnail
   verdict, `best N★`, confidence mark, verdict badge). Bundle recommends cutting two.
   Recommendation here: drop the open row's duplicate `best N★` (the rail cells carry it) and let
   the confidence mark live only on the verdict badge as today — decide at P3 review with the real
   screen in front of you.
4. **Should the field respect drive time?** (bundle Q3) Currently no, deliberately, stated in the
   strip footer. Revisit only with evidence.
5. **`GLANCE` as a user setting** (bundle Q5), defaulting to the widest configured drive-time
   habit; hard-coded 180 for the first cut. Same for `SETUP = 20`.
6. ~~**LITE gating**~~ **RESOLVED — ungated for the pilot (owner, 2026-08-19)**: the strip and
   heat render from the same scores payload LITE already receives; parity with the rest of v2.
   Revisit with the LITE pricing decision, which remains open project-wide.
7. **Away-day windows on the strip**: the design doesn't address travel days. Proposal: thumbnails
   render normally with a small `✈` in the top row; away rows keep their place in the list.
8. **"Somewhere is good, just not near you"** (bundle Q4): real feature, deliberately not built.
9. **P8 scope**: does the four-day location sheet replace card-click→map, or hang off the search
   only?
10. **Region lead narrative** (the away-origin editorial paragraph): no content source exists.
    Options: reuse `glossHeadline`/`glossDetail`, generate at briefing build for regions with
    bases, or omit at P7. Recommendation: omit first, decide with real away usage.
11. **Search "Recent locations" resting group**: the prototype shows recently-opened locations at
    rest; no recency store exists and none is planned. Shipped as windows-only resting list
    (§4.8) — build a recency store only if the owner wants the group.

---

## 10. For the implementing session — first hour checklist

1. `git pull && git branch --show-current` (never build on main; a review agent has switched
   branches mid-session before). ⚠️ **NEVER push to remote, and never create or delete tags —
   always wait to be explicitly asked** (CLAUDE.md Git Conventions; this applies to every phase).
2. Read: CLAUDE.md (§ UI Work — Review Cadence, § Speeding Up the Dev Build Cycle),
   `docs/engineering/frontend-test-standards.md`, `docs/engineering/plan-panel-data-contracts.md`,
   `docs/design/window-first/Adversarial Review.html` (the §6 ongoing rule: new Plan-tab elements
   earn their place against that list — this is the largest Plan-tab addition since the redesign),
   this plan, then the vendored bundle (`docs/design/heat-map/README.md` + both HTML files in a
   browser).
3. Verify §2's corrections still hold (`markerUtils.js`, `nginx.conf:78`, `RegionEntity`,
   `reachLens.js`) — this project has had citations rot within a single commit.
4. Start at P0. The bundle's `heat-field.js` is the spec for the port; §4.1 lists the only
   permitted deviations.
5. Update this doc's **Status** line and the per-phase table in the same commit as each phase —
   a status block updated separately lies.
