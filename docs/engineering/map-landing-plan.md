# Map tab — verdict, picks and the landing card: implementation plan

**Source**: the design bundle vendored at `docs/design/map-landing/` — `README.md` (the spec),
`CLAUDE_CODE_PROMPT.md` (the designer's working order), `Map Landing.html` + `map-tab-v4.js` (the
prototype, option A), `Map Verdict Options.html` + `map-tab-v3.js` (the rejected alternatives).
`docs/design/map-landing/VENDORING.md` records what was copied and what was not. Read the spec
before any phase; **this document is the *port* plan**: what the codebase already has, what is
genuinely new, where the spec and the code disagree on purpose, and how the work cuts into
single-session phases for **Sonnet** sessions.

**The one-sentence version.** The Map tab draws one window at a time and colours it by per-location
score, so it can state neither the verdict the Plan tab states on every card nor that a *different*
window is better. This increment puts the served verdict — and the region it is true of — on the
window pill, wears the served Best bet / Also good as an outline medallion, opens on a two-row card
answering *tonight or the morning*, and adds one drilldown (window → regions → region → the location
sheet that already exists).

**The one-paragraph correction.** Far more of this is already on the wire than the spec assumes.
`BriefingWindow` already carries `verdict`, `bestRating`, `confidence` and a **served** `pick`
(`BEST`/`ALSO`); `BriefingRegion` already carries `displayVerdict`, `meanRating`, `bestRating`,
`confidence` and `scoredLocationCount`; and `utils/windowFirstCards.js#buildWindowCards` already
derives, per rendered window, the verdict, the verdict word, the **leading region's name**
(`hotRegionName`) and the pick — origin-scoped, with the pick *withheld* when it names a region the
origin has scoped away. So the increment's part 1 is largely **forwarding**, not deriving. What is
genuinely new is: the tier **tally** (`+N` / "everywhere"), the map's own `heatArea` scope axis
(different from the Plan's `origin` axis), the pill's verdict cell and medallion, the stepper ticks,
the landing card, the two drilldown panels, and the rule that a map click no longer closes a panel.

**Cadence per phase** (CLAUDE.md "UI Work — Review Cadence"): build → tests → adversarial review of
the diff (~6 prosecutor lenses + one refuter per charge, all read-only) → fix survivors → browser
verification (§9 recipe) → commit. Frontend gate before any push-request: `npm run lint && npm test
&& npm audit --audit-level=high && npm run build`, gated on **exit codes, never on a grep of the
output**. Never push; never tag. Every phase adds a `changelog.d/YYYYMMDD-<slug>.md` entry (never a
direct `CHANGELOG.md` edit). Paste the relevant section of THIS plan and the spec into every review
agent's prompt — review agents cannot see untracked context, and a compliance lens with no spec
returns zero findings and looks clean.

**Check open PRs first.** `gh pr list --state open` and grep the titles for *verdict*, *pill*,
*landing*, *card*, *window control*, *region panel*. The owner writes specs and sometimes builds
against them in parallel; the overlap only surfaces at merge, where it is most expensive. Checked
2026-09-05 at plan time: **no overlap** (only #779, a frontend dialog-focus test change).

---

## §0 Status

**Status: PLANNED — nothing built.** Three owner decisions are open and two of them block a phase
(§6 Q1 blocks L2's copy, Q2 blocks L4's open rule). Phase log (L1 creates the first row; every phase
appends its own in the same commit as its code):

| phase | branch | commit | date | notes |
|---|---|---|---|---|
| — | — | — | — | — |

---

## §1 Corrections to the spec — where the codebase has already moved

The spec was written against the prototype, in which the map derives its verdict from the field
because it has no verdict endpoint, computes its own picks with a confidence-nudged rank, and hangs
a shrink-to-fit pill off a bounded absolutely-positioned group. **None of those three is true of the
app.** Re-verify every path and symbol below against the tree before editing — this plan was written
2026-09-05 and code moves.

### 1. The verdict is already served, at both levels, and the spec says to use it

`BriefingWindow.verdict` (a `DisplayVerdict`: `WORTH_IT` / `MAYBE` / `STAND_DOWN` / `AWAITING`, never
null) is the **top region's own** verdict — an average, per its own record javadoc — and
`BriefingRegion.displayVerdict` is the same thing one level down. Both ride `GET /api/briefing`,
which the Map tab already consumes through `WindowFirstBriefingContext`. The spec's `OPEN 1` says to
use the API's verdict, and that is what this plan does.

⚠️ **But the spec's fallback thresholds are wrong for this app, and the spec's own stated reason is
why.** It asks for `>= 3.7 Worth it / >= 2.8 Maybe` "so the fallback lands in the same place" as the
served verdict. It does not: `BriefingRatingStats` maps a region mean at **`>= 3.5` → WORTH_IT,
`>= 2.5` → MAYBE**. A region averaging 3.6 is `WORTH_IT` served and "Maybe" under the spec's client
rule — the exact Plan/Map disagreement the spec exists to prevent. See §4 #1 and §5 D-1.

⚠️ **`utils/mapLabels.js#verdictWord` is NOT the function to reuse here.** Its 3.7/2.8 constants are
correct for the quantity it serves — a **per-location whole star**, where the fractional thresholds
collapse to `>=4` / `>=3` and match `DisplayVerdict.resolve`'s per-location bands exactly. A **region
mean** is a different quantity with different bands. Its own doc block already warns the callout not
to re-derive its copy; that warning is about the per-location star, and does not license using it for
a mean.

### 2. `buildWindowCards` already derives the verdict, the word, the region and the pick

`utils/windowFirstCards.js#buildWindowCards` (the Plan tab's own window cards, which
`WindowFirstMapPane` already builds as `heatStripCards` and feeds the map's field) returns, per
rendered window:

| field | what it is | the spec's name for it |
|---|---|---|
| `verdict` | the served `DisplayVerdict`, origin-scoped | the tier |
| `verdictLabel` | `VERDICT_LABEL[verdict]` — `Worth it` / `Maybe` / `Poor` / `Awaiting` | the verdict word |
| `hotRegionName` | the window's **leading region by mean** (`topRegion(es).regionName`), origin-scoped, null when nothing in scope carries a mean | the region the verdict is true of |
| `topMeanRating` | the best of the window's region means | the number the verdict came from |
| `confidence` | the top region's served tier | the pick rank's confidence term |
| `pick` | the **served** `BriefingWindow.Pick`, `{kind:'best'\|'also', regionName, headline, detail, locationName, locationId}` — **withheld** when it names a region the origin has scoped away | Best bet / Also good |
| `bestRating` | one location's score, deliberately not the verdict | the ramp swatch's `N★ best` |

**So the increment's step 1 is not "wire the verdict"; it is "forward six fields the pane already
has, and add the tally".** `WindowFirstMapPane.jsx`'s `heat.windows` mapper currently forwards only
`key`, `date`, `targetType`, `label`, `time`, `bestRating`, `conf`, `confidenceTier`, `badges` — it
drops `verdict`, `verdictLabel`, `hotRegionName` and `pick` on the floor. That mapper is L1's main
edit.

### 3. Picks are server-owned, and that rule is already enforced in code

`PlanWindowProjector.selectPicks` chooses exactly two picks across the **rendered** window set
(`PlanRenderLimits.MAX_VISIBLE_EVENTS` = 6), ranked by the top region's `averageRating` with
chronology as the tie-break, the runner-up gated by `AlsoGoodFloor` (`>= 3.0` absolute **and** within
`0.5` of the top). `windowFirstCards.js` copies that through and states the rule in a comment:
*"picks are server-owned (plan §2.12)"*.

The spec's client-side formula — `candidates = mean >= 2.8`, `rank = mean × (0.78 + 0.22 ×
confidence)`, `Also good` must differ in **both** window and region — is a **second computation** of
a quantity the app already serves. Building it would put a different Best bet on the Map tab from the
one on the Plan tab, which is the precise failure the spec's own `OPEN 1` forbids one paragraph
earlier for verdicts. §4 #2 and §5 D-2 record what this costs and what is kept.

Two of the spec's three pick rules are **already true** of the served picks and need no work: solar
only (a `BriefingWindow` exists only per solar event summary), and the two picks always differ in
**window** (`selectPicks` builds a `Map` keyed by `WindowKey` from a list holding one draft per
window). Only "differ in region" is not guaranteed — see §4 #2.

### 4. The pill is a **fixed 262px**, and that was won deliberately

`index.css`'s `.wf-win-pill` carries `width: 262px; flex-shrink: 0` with a long comment (#773)
recording why: the steppers either side must not move as the reader steps, and the content varies
115.41px → 227.52px, so `›` was travelling up to 112px between clicks. **262 is derived from the
menu**: `.wf-win-menu` is 334px (the bundle's own figure), less two 32px steppers and two 4px gaps,
so the pill and the menu share both edges.

The spec's three layout constraints were written for a prototype pill that **hugs its content**
(`#wnow{gap:9px;padding:6px 10px;min-height:36px}`, no width, no cap, no overflow) inside a
shrink-to-fit absolutely positioned group. Ported literally they would *undo* #773. Specifically:

- `max-width: calc(100% - 344px)` on the control group — **unnecessary here**. A fixed-width group is
  bounded by construction: `.wf-map-chrome-tl` is `left: 60px` shrink-to-fit and its content is
  `32 + 4 + W + 4 + 32`. The constraint the spec is protecting (the group must not grow rightward
  under the right-anchored `.wf-map-chrome-tr` cluster at the same `z-index: 1100`) is real and must
  still be **measured** (check 4) — it is just already satisfied.
- `flex: none` on the day label so it never truncates — the app's `.wf-win-label` deliberately does
  the opposite (`overflow: hidden; text-overflow: ellipsis`, taking the fixed pill's slack so the
  time and caret stay right-anchored). The *invariant* the spec wants (the day is the fact the
  control exists to state, so it yields last) is honoured by **width**, not by `flex`: the pill is
  sized so the widest reachable content fits.
- `min-width: 0` on the phone pill — ⚠️ **a no-op beside `overflow: hidden`.** This was measured on
  this codebase during #773 and a test was written that pinned the no-op. Do not add it, and do not
  write a test that asserts it does something.

**What the pill genuinely needs is to get wider**, because a verdict cell and a medallion do not fit
in 262px. The prototype does not answer the width question — it lets the pill grow and leaves
`#wmenu` at 334px, so in the prototype the two **no longer share edges**. §5 D-3 decides.

### 5. Four components close themselves on an outside `mousedown` — not one map handler

The spec's part 5 ("Panels do not close when you click the map") reads like one change to one
handler. It is five:

| component | mechanism |
|---|---|
| `components/map/WindowControl.jsx` | `document` `mousedown` listener, closes when the target is outside `rootRef` |
| `components/map/FiltersPopover.jsx` | same idiom |
| `components/map/RegionsJump.jsx` | same idiom |
| `components/map/MapLegendPanel.jsx` | same idiom |
| `MapView.jsx`'s `MapBackgroundClickController` | Leaflet `click` on empty ground → `setOpenMapMenu(null)`, else `setSelectedLocationName(null)` |

⚠️ **Removing only the `MapBackgroundClickController` branch does nothing** — the four document
listeners fire first (and commit first: that ordering is already documented at length in
`MapBackgroundClickController`'s own class doc, where it caused a live regression). Both halves move
together or the rule does not hold. The bare-ground **deselect** (`setSelectedLocationName(null)`)
stays: the spec keeps it explicitly ("that is a selection, not a panel").

### 6. There are TWO scope axes on this tab, and the spec only names one

- **`origin`** (shared app state, `WindowFirstBriefingContext`) — *which region you are planning
  from*. `buildWindowCards` already re-points verdict, region, best, confidence and pick onto it.
- **`heatArea`** (`MapView` local state, the Filters popover's segment) — *My area* (the ≤3h
  planning area, `planningArea.areaRegions`) vs *Everywhere*. This is the axis the spec means by
  "scope", and **nothing today re-points the verdict onto it**.

The scope-only pool already exists as `scopeBasePool` in `MapView.jsx` (`heatArea ? heat.areaSpots :
heat.spots`), before every other filter — which is exactly the population the spec's check 1 needs.
`utils/planOrigin.js#scopeRegions` and `planningArea.js#areaRegions` give the region-name set.

### 7. The scope segment is labelled "My area" / "Everywhere", not "Whole catalogue"

`FiltersPopover.jsx` renders `{areaLabel || 'My area'}` and `Everywhere`, and `areaLabel` becomes
`Around <base>` under an away origin. The spec's single string `everywhere in your area` is therefore
correct in exactly one of three states. §4 #7 gives the three-branch copy.

### 8. Region short names do not exist

The spec's short-name table (`ntw -> Northumberland`, `lakes -> the Lakes`, …) has **no producer**.
Curated region short names are `map-tab-v2-plan.md` **O-4**, still open — the app has only CSS
truncation (`REGION_TINY_FRAME_WIDTH`, and the pill's own ellipsis). Real names are
`Northumberland & Tyneside`, `The Lake District`. §4 #8 and §6 Q3.

### 9. Everything else the increment leans on already exists

`BottomSheet.jsx` + `useIsMobile.js` (phone treatment), `RegionsJump`'s `wf-jump-reset` row (the
"a way back lives in the list that caused the jump" precedent for the pill menu's reopen row),
`LocationFourDaySheet.jsx` (the drilldown's terminus — **extend it, do not build a second one**),
`utils/regionsJump.js#buildRegionBestIndex` (the exact `date|targetType|regionName` index shape the
tally needs), `briefing.generatedAt` (the run age `OPEN 3` asks for), and
`colourScaleNoticeDismissed` in `MapView.jsx` (the localStorage-backed one-time-notice pattern).

---

## §2 Strategy

**Forward first, derive last, and derive in one pure module.** Every figure this increment renders
either already rides `GET /api/briefing` or is a fold over figures that do. The one genuinely new
computation is the **tier tally** — how many in-scope regions share the top region's tier — and it
exists only because the scope it is taken over (`heatArea`) is per-user and therefore cannot ride the
shared, ETag-revalidated briefing payload. That is the same argument that keeps reach off
`/api/briefing` and puts it on `/api/user/settings/reach`. It goes in `utils/mapVerdict.js`, pure,
with the licence and its exit recorded (§5 D-4, §6 Q4).

**Nothing is re-derived that the server owns.** No client verdict from ratings, no client pick rank,
no client region mean. The client filters and selects among served figures; it never recomputes one.

**One surface at a time, correctness order.** The persistence rule (L3) lands before the two panels
(L5, L6) so they are built into a world where it already holds, rather than retrofitted.

**The overlay stays frozen.** Every new class is scoped to `.wf-map-tab` and every new mount is
gated on `!overlayMode`, exactly as P12's phone chrome was. The Plan-tab overlay keeps
`MarkerPopupContent`, the medallions and `ForecastTypeSelector`; convergence remains `map-tab-v2-plan.md`
**O-6**, an owner decision this increment does not take.

---

## §3 Phases

Sizes are single-session estimates for a **Sonnet** session: S ≈ half a session, M ≈ one, L ≈ one
long one.

### L1 — The verdict, the region and the pick, as data — M

**Goal.** Every EV row carries its served verdict, the region that verdict names, the tier tally over
the *scope-limited* region set, and its served pick. **No visual change at all.**

1. **New pure module `frontend/src/utils/mapVerdict.js`.** No imports from React or Leaflet.
   - `buildRegionVerdictIndex(days)` → `Map<'date|targetType|regionName', {displayVerdict, meanRating}>`.
     Mirror `regionsJump.js#buildRegionBestIndex` exactly — same composite key, same
     `region.regionName` join key (**not** `region.name`, which the served record does not carry;
     `mapCallout.buildRegionGlossIndex` once made that mistake), same "first wins" policy.
   - `windowVerdict({index, date, targetType, regionsInScope})` →
     `{tier, regionName, sharingCount, allInScope, scopedRegionCount}` or `null`.
     `tier` is the **served `displayVerdict`** of the region with the highest served `meanRating`
     among `regionsInScope`; `regionName` is that region's name; `sharingCount` is how many *other*
     in-scope regions carry the same tier; `allInScope` is true when every in-scope region does and
     `scopedRegionCount > 1`. Regions with no `meanRating` are not candidates for the name but **do**
     count in `scopedRegionCount` — an unscored region is not evidence that everywhere agrees.
   - ⚠️ **No threshold function.** There is no `>= 3.7` path (§4 #1, §5 D-1). When no in-scope region
     carries a served `displayVerdict`, return `null` and let the caller render nothing.
2. **Forward the four dropped fields.** `WindowFirstMapPane.jsx`'s `heat.windows` mapper gains
   `verdict`, `verdictLabel`, `hotRegionName` and `pick` from the `card` it already has in hand.
   Copy-through only — no new derivation in the mapper.
3. **Hand the pane's scope through.** `heat` gains `regionsInScope` (a `string[]`) — but ⚠️ the
   **`heatArea` toggle lives in `MapView`, not the pane**, so the pane cannot compute the scoped set.
   Publish the two candidate sets instead (`areaRegionNames`, `catalogueRegionNames`, from
   `planningArea.areaRegions` / the full spot list) and let `MapView` pick with its own `heatArea`.
   This keeps the ownership boundary the pane's own doc block already draws.
4. **Enrich the EV rows.** `utils/mapEvents.js#solarRow` gains `verdict`, `verdictLabel`,
   `regionName`, `pick` from the served window it is already handed. **Night rows get none of them**
   and must not synthesise one (spec: night events have no verdict, no tint and are not pick
   candidates). Keep `bestOfNight`'s licence untouched.
5. **Tally at the call site.** `MapView` computes `windowVerdict` per EV row from the index and its
   own `heatArea`-chosen region set, memoised on `(events, heatArea, index)` — the spec's
   `(windowIndex, scopeKey)` cache, in this app's idiom.

**Tests (pure, `test/mapVerdict.test.js` + additions to `mapEvents.test.js`).**
- The tally's three label cases: one region in tier → name only; several → name + `sharingCount`;
  every in-scope region → `allInScope` true. **The third is the one that regressed in the design.**
- `scopedRegionCount > 1` guard: a single-region scope never sets `allInScope`.
- An unscored region counts in the denominator but never wins the name.
- Filters do not reach this module at all — it takes a region-name list, and the list is built from
  `scopeBasePool`. Pin this at the `MapView` level in L2 (check 1 is a UI check).
- Night rows carry no verdict and no pick.
- A window whose regions carry no `displayVerdict` returns `null`, not `AWAITING` dressed as Poor.

**Acceptance.** `heat.windows[i].verdict` is byte-equal to the Plan tab's own card verdict for the
same window at the same origin; no new network call; no visual diff (assert by snapshotting the
rendered pill text before and after).

---

### L2 — The pill: verdict cell, medallion, stepper ticks — L

**Goal.** The window control states the verdict for the window it names, names the region that
verdict is true of, wears the medallion when the window on screen is a pick, and shows the
neighbouring windows' tiers on the steppers.

1. **Verdict cell** on `.wf-win-pill`: word in tier text colour, mono, 10px, `letter-spacing: .1em`,
   uppercase, 1px left rule at the app's own border token; region stacked under it at 9px in
   `--color-plex-text-secondary`, sentence case. **Stacked, not inline** — the spec measured inline
   at ~120px, which pushed the stepper under the nav cluster.
2. **Tier tint**: `box-shadow: inset 3px 0 0 <tier fill>` plus a matching border tint. Fills
   `#8AAE72` / `#E0A542` / `#C8452F`, text `#A8C795` / `#EFC377` / `#E5806C`, night `#6E7C8C` /
   `#9FB0C0`. ⚠️ Add these as **tokens** in `index.css`, never as literals in JSX (Tailwind-only
   rule, and the theme-token pruning defect this project has hit before). ⚠️ **Never draw ramp or
   verdict colour as text on a coloured fill** — the v2 rule still holds.
3. **Night events get no tint and no word** — they state their own model's word in the slate colour
   (`Clear` / `Cloudy` / `Kp N`). If the app cannot source that word for a night row today, render
   the cell **empty** rather than inventing one; the spec's own rule is that night runs on a
   different model, and `mapEvents.js` already knows a night row's `bestRating` is a different
   population. State which you did in the phase log.
4. **Medallion**: outline chip only — `BEST BET` `#A8C795`, `ALSO GOOD` `#EFC377`, 1px inset ring,
   **no fill** (a filled chip became the loudest thing on a control whose job is the verdict — built
   and cut). On the pill **only when the current window is a pick**; on **every** menu row that is
   one. ⚠️ **Glyph and words are separate elements** — `font-size: 0` + `::first-letter` does not
   work, because the glyphs are symbols, not letters, and the chip renders as an empty bordered box.
   ⚠️ **No chip pointing at other windows** (built, then cut).
5. **Stepper ticks**: 11×3px, 4px from the bottom, centred, filled with the *neighbouring* window's
   tier colour; hidden when that stepper is disabled. Free once L1 landed — read `events[i±1]`.
6. **Width.** Re-derive both the pill and the menu, per §5 D-3: **measure** the widest reachable
   content in Chromium against this stylesheet's own loaded fonts (the #773 comment records the
   method and the numbers it produced), set the menu to the measured pill width + 2×32 + 2×4, and
   record both figures and the measurement in the CSS comment the way #773 did. Keep
   `flex-shrink: 0`. **Do not** add `max-width: calc(100% - 344px)` or `min-width: 0` (§4 #3).
7. **Responsive** (`@media (max-width: 639px)`, scoped to `.wf-map-tab`): the region line is hidden,
   the medallion drops its **words** and keeps its **glyph** at 11px, the control stays one row.

**Tests.** Component-level (`WindowControl.test.jsx`): all three region-label cases render (check 2);
a night row renders no word and no tint class; the medallion appears on the pill only when the
current row is a pick and on every pick row of the menu; ticks read the neighbour's tier and vanish
when the stepper is disabled. `MapView`-level: **check 1** — assert the pill's word and region are
identical before and after changing minimum rating, reach, subject and dark-sky, then assert they
*do* change when `heatArea` flips.

**Browser (check 4, measured — not a screenshot).** At 1280×800, 834×1112 and 390×844: the next
stepper's right edge is left of the Regions chip's left edge; `.wf-win-label`'s
`scrollWidth === clientWidth`; the medallion glyph's computed `font-size` is non-zero on phone; the
control is one row (one distinct `getBoundingClientRect().top` across its children).

---

### L3 — Panel persistence — S

**Goal.** A click on the map no longer closes the week menu, the region panels, Filters or Legend.
Tapping bare ground still deselects a location.

1. Remove the `setOpenMapMenu(null)` branch (and its `openMapMenuAtMouseDownRef` snapshot, if it has
   no other reader) from `MapView.jsx`'s `MapBackgroundClickController` wiring — leaving
   `setSelectedLocationName(null)` as the ground click's only effect.
2. **And** stand down the four `document` `mousedown` listeners for clicks that land inside the map
   frame. Prefer one shared hook (`hooks/useOutsideDismiss.js`) taking an "ignore" predicate, so the
   four cannot drift; a per-component `if (mapFrameRef.current?.contains(e.target)) return;` is
   acceptable if the shared hook turns out to need four different roots. Clicks **outside** the map
   frame (the masthead, another tab) still dismiss — nothing in the spec asks otherwise.
3. Audit the close affordances the rule now depends on: each panel must close on **its own chip**,
   **its ✕ where it has one**, and **Escape**. `RegionsJump` already has `wf-jump-reset`;
   `FiltersPopover` and `RegionsJump` use `BottomSheet` on phone (which brings its own close);
   `MapLegendPanel` and the window menu need checking. Add what is missing — a panel that can only be
   closed by re-pressing a chip the reader may have forgotten is worse than the behaviour being
   replaced.

**Tests.** For each of the four: open it, fire a Leaflet ground `click` (and a real `mousedown` →
`click` pair, per `MapBackgroundClickController`'s own recorded ordering trap), assert it is still
open; then assert its chip, its ✕ and `Escape` each close it. Assert a ground click with **no** panel
open still clears the selection. ⚠️ Write the mousedown+click pair, not a captured `click` handler
invoked by hand — that is exactly the shape of test that missed the original regression.

**Browser.** With each panel open: pan, zoom, wheel and click bare ground; it stays. Report as four
pass/fail cells, not prose.

---

### L4 — The landing card — L

**Goal.** On a cold open the map answers *should I go tonight or in the morning*.

1. **New `components/map/MapLandingCard.jsx`**, mounted in `MapView` behind `!overlayMode`, 376px,
   `top: 60px; left: 12px`. **z-index 1300 in this app's ladder** (§4 #6): above the callout (1200)
   and the selection ring, below the map tooltip (1400) and below the menus/panels (1500) — a menu
   must win over a card behind it.
2. **Rows**: the next two **solar** windows from now — the first two solar EV rows, which the served
   payload has already withdrawn elapsed events from (`PlanWindowProjector.hasPassed`; do not write a
   second pastness rule). Each row: kind chip, day, time, medallion when that window is a pick,
   verdict word with region stacked. Selecting a row sets that window **and** closes the card.
3. **Header derived from the rows it is showing** — same day → `Tomorrow — sunrise or sunset?`;
   different days → `Tonight, or tomorrow?`. **Never hard-coded**; a header naming windows not on
   screen was a real defect. One function, exported, unit-tested against both branches and the
   one-row and zero-row degenerate cases.
4. **Picks ride their rows** when they are one of the two. A pick later in the week becomes one quiet
   line (`Also good` in `--color-plex-text-secondary`, **no colour**) naming its window and region and
   taking you there. **Picks earlier than the first row are suppressed** — a window that has passed
   is not an answer.
5. **Both rows Poor** → the card stops comparing: *Neither is worth the drive. Next up: <window> ·
   <region> · Worth it ›*, naming a window **strictly later than both rows** and **only** one that is
   Worth it. If none exists, the sentence ends after "drive" — offering a window the sentence has
   just called not worth the drive is the failure to avoid.
6. **Dismissal**: the close button, `Escape`, or selecting a row. ⚠️ **Not** map click, drag, zoom,
   wheel or outside tap. Panning to the region it just named is *reading* the card. It is **not** a
   `Modal` and must not become one — the Plan screen's two-deep dialog invariant (CLAUDE.md) is not
   this card's to spend, and `useDialogFocus` is not involved.
7. **Recoverable**: the same header text is the **first row of the pill's menu**, above the windows —
   `RegionsJump`'s `wf-jump-reset` is the precedent for "the way back lives in the control that
   caused it".
8. **When it opens** — §6 Q2. Until the owner decides, implement the recommendation behind one
   named constant so the alternative is a one-line change: **once per forecast run**, keyed on
   `briefing.generatedAt` in `localStorage`, using `MapView`'s existing `colourScaleNoticeDismissed`
   read/write helpers (`readMapFilter`/`writeMapFilter`) — they already carry the try/catch and the
   during-render-read caveat.
9. **Phone**: `BottomSheet` with `modal={false}` (the idiom `FiltersPopover` and `RegionsJump`
   already use), or the same card inset to the frame — the spec does not say; pick one, state it, and
   keep the dismissal rules identical.

**Tests.** Header derivation (both branches, both degenerate cases); rows are solar only; a pick on a
row renders as a medallion and never also as the quiet line; a pick earlier than row 1 renders
nowhere; the all-Poor branch's "next up" index is strictly greater than both rows **and** is
`WORTH_IT`, and is absent when no such window exists; close / `Escape` / row-select each dismiss, and
a simulated pan / zoom / wheel / ground click each do not; the reopen row exists in the pill menu and
reopens with the **same** header text.

**Browser (checks 5 and 6).** Force the all-Poor state with a seeded fixture (§9) and read the two
row verdicts and the "next up" window index out of the DOM as numbers.

---

### L5 — The window panel: window → regions — M/L

**Goal.** The first of the drilldown's two levels, entered three ways (the pill menu's footer row,
the landing card, a pick chip).

1. **Header**: window label, time, confidence percentage, medallion if a pick, verdict chip in tier
   colour.
2. **One note, branched four ways** — the copy is in the spec's §5 table verbatim. The
   several-regions branch prints the count from L1's `sharingCount`; the all-in-scope branch **drops
   the drive-time sentence** (there is no choice to make between N write-offs) and takes §4 #7's
   three-way scope wording, not the spec's single string.
3. **Region rows**: name, `<drive> · N of M at 4 stars+`, verdict word (em dash for night events),
   ramp swatch plus `N star best`. **Ranked by mean, ceiling shown beside it** — a single 5★ in a
   flat region is a lucky location, not a good night. Both numbers are served
   (`BriefingRegion.meanRating`, `BriefingRegion.bestRating`); ⚠️ do not take a client max over drawn
   spots — `windowFirstRegions.js`'s own doc block records why (the canopy fallback lives on the
   backend and cannot be reconstructed here).
4. ⚠️ `N of M at 4 stars+` is a **reach-scoped count of places you could drive to**, so it falls under
   CLAUDE.md's named licensed class and **may not say "within reach" unless `reachMeasured` is
   true** — an unknown drive passes every tier. Reuse the existing `card.reachMeasured` producer;
   do not mint a second.
5. Opens as a panel governed by L3's rule; closes on its own chip, its ✕, or `Escape`.

**Tests.** Each of the four note variants renders for its state and only for its state; rows are
ordered by mean and not by ceiling (a fixture where the two orders differ); a night window's rows
show an em dash; a region with no `meanRating` sorts last rather than as zero; the reach clause is
absent when `reachMeasured` is false.

---

### L6 — The region panel, into the existing sheet — M/L

**Goal.** The second level, and the handoff into the sheet that already exists.

1. Back arrow to the window panel; region name, window, medallion, verdict chip; stats line
   (`N of M at 4 stars+ · nearest <drive> · average N stars`, same reach caveat as L5); top four
   locations (name, tide glyph where the tide lands on the light — `BriefingSlot.TideInfo
   .tideOnTheLight`, served since #749, so **do not** re-derive one representative coastline's
   geometry; `<drive> · leave <time>` via `utils/leaveBy.js`; ramp swatch plus stars); the region's
   narrative for that window (`BriefingRegion.glossHeadline` / `glossDetail`).
2. **Actions**: `Zoom to region` (reuse `MapView`'s existing `jumpToRegion` — which already flips
   `heatArea` when the region is outside My area) and `Four days at <full location name>`.
   ⚠️ **The full name.** Splitting on whitespace produced "Four days at Infinity" and "Four days at
   Scott's", which are not places.
3. **Terminus is `LocationFourDaySheet`** — the sheet the callout's `Four days here ›` already opens
   **over the map** since 2026-09-05 (`map-tab-v2-plan.md` **O-18**). **Extend it; do not build a
   second one.** ⚠️ Route through the same `onOpenLocationSheet` handoff with `inPlan: false`, so the
   sheet's own footer map door is stamped `inPlace` from the tab in force and does not import the
   Plan's lens onto the map the reader is already looking at.
4. ⚠️ **Stack depth.** The shell refuses a third dialog layer. The sheet over the map is layer one
   and already carries two per-route guards (`MapView`'s Escape rule stands down while a foreign
   `aria-modal` dialog is open; the pane warms the sheet's lazy chunk on mount). These panels are
   **not** `Modal`s and must not become them — if a panel is open when the sheet opens, close the
   panel first, the same close-then-move ordering `utils/mapDoors.js#openMapDoor` already encodes.
   Re-read `map-tab-v2-plan.md` **O-20** before touching this.

**Tests.** The action label prints the full location name for a two-word and an apostrophe'd name;
`Zoom to region` flips scope for an out-of-area region and not for an in-area one; the sheet opens
with `inPlan: false` and the panel is closed first; back returns to the window panel with the same
window.

---

### L7 — Sweep, docs and the OPEN answers — S

1. Answer the three `OPEN`s in the spec against what shipped, in §6, with the owner's decisions.
2. Update `CLAUDE.md`'s **Map tab (v2)** bullet and its **Backend-heavy** bullet — the latter gains
   `mapVerdict`'s tally as a **named** member of the licensed per-user-join class with its exit
   (§5 D-4, §6 Q4), in the same voice as the existing five classes.
3. Update `docs/engineering/map-tab-v2-plan.md` §6 with anything this increment closed or opened
   (O-4 is now load-bearing for the pill's region line; O-6's overlay convergence is untouched;
   O-16's night rollup is untouched and is why night rows have no verdict).
4. Grep for orphans: any constant, class or test-id introduced by a phase and left with no reader.
5. `docs/engineering/plan-panel-data-contracts.md` — add the tally if and only if it survived as a
   client computation.

---

## §4 Disagreements with the spec, on purpose

Every entry here is a place the spec and the code are made to disagree deliberately. A reviewer
should challenge these **in review**, not silently "fix" them in code.

1. **Fallback thresholds: none, and certainly not 3.7/2.8.** The spec asks for the client thresholds
   to be kept "so the fallback lands in the same place" as the API. They do not:
   `BriefingRatingStats` bands a region mean at `>= 3.5` / `>= 2.5`. Since
   `BriefingRegion.displayVerdict` is **never null** on a served region, the fallback path is
   unreachable in practice — so this plan builds none, and renders nothing where no region record
   exists. The spec's *intent* is served exactly; its literal numbers are not.
2. **Picks: served, not client-ranked.** No `0.78 + 0.22 × confidence` nudge, no `>= 2.8` candidate
   floor, no client both-differ rule. The app's picks come from `PlanWindowProjector.selectPicks`
   (rank by top-region average, chronology tie-break, `AlsoGoodFloor` on the runner-up). **What this
   costs**, stated rather than hidden: the two picks are guaranteed to differ in *window* but not in
   *region*, so the Map tab can show Best bet and Also good naming the same region on two nights —
   which the spec's both-differ rule exists to prevent. That is a **backend** change to
   `selectPicks` if the owner wants it, and it would move the Plan tab too. §6 Q5.
3. **Pill layout: fixed width, not shrink-to-fit.** `max-width: calc(100% - 344px)` is unnecessary
   (a fixed group is bounded by construction) and `min-width: 0` is a measured no-op beside
   `overflow: hidden`. The spec's *invariants* — bounded group, day label never truncates, glyph
   survives on phone — are all still enforced and all still measured by check 4. Only the mechanism
   differs. §1 #4.
4. **`mapLabels.verdictWord` is not reused.** Its 3.7/2.8 are right for a per-location whole star and
   wrong for a region mean. Nothing in this increment imports it.
5. **Scope means `heatArea`, and `origin` is a second axis the spec does not model.** Under an away
   origin `buildWindowCards` has *already* re-pointed the verdict onto that one region, so the tally
   must be taken over the origin-scoped set, not the home planning area. Getting this wrong prints a
   roster-wide "+4" beside a verdict about one region.
6. **z-index 1300 is this app's number, not the spec's ladder.** The spec's `z 1300` happens to land
   correctly between this app's callout (1200) and tooltip (1400); it is adopted because it is right
   here, not because the spec said it. The prototype's `410/415/420` ladder assumed no real Leaflet
   markers underneath — this app keeps them at 600.
7. **"everywhere in your area" is three strings, not one.** The scope segment reads `My area` /
   `Everywhere` / `Around <base>`. Use: *everywhere in your area* (My area, home), *everywhere*
   (Everywhere), *everywhere around <base>* (away origin). One string across all three states is
   wrong in two of them.
8. **Region names are the full served names, truncated.** The spec's short-name table has no
   producer; O-4 is open. `Northumberland & Tyneside` will ellipsis in a 9px line. Acceptable and
   honest; the exit is O-4, not a client-side name map (which would be a second source of truth for
   a region's name).
9. **Night rows.** The spec wants them to state their own model's word (`Clear` / `Cloudy` / `Kp 5`).
   No served per-window night *verdict* exists (`map-tab-v2-plan.md` **O-16**), so L2 either sources
   one honestly or renders the cell empty. It must not borrow a solar word, and it must not
   synthesise from `bestRating` — that is the rated/unrated conflation O-16 exists to name.

---

## §5 Decisions taken in this plan (challenge in review, not in code)

- **D-1 — No client verdict derivation at all.** Read `displayVerdict`; render nothing where there is
  none. Rationale in §4 #1. A reviewer wanting the fallback must first show a served region record
  with a null `displayVerdict`, which the record's own contract says cannot happen.
- **D-2 — Picks are server-owned.** Rationale in §1 #3 and §4 #2. The precedent is in code already
  (`windowFirstCards.js`: *"picks are server-owned (plan §2.12)"*).
- **D-3 — Keep the fixed pill; re-derive both widths from a measurement.** The pill and the menu keep
  sharing both edges, and the new numbers are recorded in the CSS comment with the method, exactly as
  #773 did. Rejected: letting the pill hug its content (undoes #773's travelling stepper fix);
  putting the verdict cell outside the pill (a second control on a surface whose whole argument is
  that one control replaced three).
- **D-4 — The tier tally is a client computation, licensed and named.** `heatArea` is per-user, so
  "how many regions *in your area* are Worth it" has no servable answer on the shared, ETag'd
  `GET /api/briefing` — the same reasoning that put reach on its own never-cached contract. It joins
  CLAUDE.md's Backend-heavy licensed class as a **named** member with a recorded exit (§6 Q4), not as
  a precedent for client aggregation. It aggregates *served verdicts*, never ratings.
- **D-5 — The landing card is not a `Modal`.** It is a dismissible card with its own `Escape`
  listener. It does not claim `aria-modal`, does not enter the shell's stack, and does not consume
  either of the two permitted dialog layers.
- **D-6 — The persistence rule moves five call sites together.** Rationale in §1 #5. A phase that
  changes only `MapBackgroundClickController` has not implemented the rule and its tests will still
  pass, because they invoke the captured `click` handler by hand.

---

## §6 Owner decisions / OPEN items

Nothing below blocks **L1**. Q1 blocks L2's night-row copy; Q2 blocks L4's open rule.

- **Q1 — Night events' own word.** The spec wants `Clear` / `Cloudy` / `Kp 5` on the pill for astro
  and aurora. No served per-window night verdict exists (**O-16**). Options: (a) render the cell
  empty for night rows — *the recommendation*, honest and costs nothing; (b) derive a word on the
  client from the night's served rows, which is the rated/unrated conflation O-16 names; (c) serve
  one, which is a backend phase this plan does not contain.
- **Q2 — When the card opens** (the spec's `OPEN 3`). **Recommendation: once per forecast run**,
  keyed on `briefing.generatedAt` — the app already has both the value and the localStorage pattern.
  Alternatives: once per day; suppress when the window you left is still current.
- **Q3 — Highest or nearest region** (the spec's `OPEN 2`). **Recommendation: highest**, and not
  merely as the status quo — `buildWindowCards.hotRegionName` already names the highest-mean region,
  and the Plan tab's heat strip brightens *that* region's thumbnail. Naming a different region on the
  map would make the two tabs point at two places for one window. If the owner wants nearest, it
  should change `hotRegionName` for both tabs, not just the map.
- **Q4 — The tally's exit.** Whether a served, scope-aware region tally is ever wanted. It cannot ride
  `/api/briefing` (per-user scope, ETag-shared); the shape would be `map-tab-v2-plan.md` **O-4**'s
  never-cached per-user endpoint. Until then D-4 stands.
- **Q5 — Should the two picks be forced to differ in region?** A backend change to
  `PlanWindowProjector.selectPicks` that would move the Plan tab too. §4 #2.
- **Q6 — Curated region short names** (`map-tab-v2-plan.md` **O-4**). The pill's 9px region line is
  the first surface where the full names visibly truncate. Closing O-4 would improve this increment
  and three surfaces beside it.
- **Q7 — Does the map become the landing tab?** The spec raises it and its own working order says
  *flag it, do not start it*. If the map lands, the Plan tab's job narrows to the week and the *why*.
  **Out of scope for every phase here**, recorded so it is decided deliberately rather than by
  accident.

---

## §7 Verify by measurement — the spec's seven checks, and how each is measured

Report **numbers, not screenshots**. Each row names the phase that owns it.

| # | check | phase | how |
|---|---|---|---|
| 1 | Filters do not move the verdict; scope does | L2 | Read the pill's word and region from the DOM; change min rating, reach, subject, dark-sky in turn; assert string equality each time. Then flip `heatArea` and assert inequality on a fixture where the area and the catalogue have different top regions. |
| 2 | Three region-label cases render | L2 | Force one-region, several-region and all-region states; assert `the Lakes`-shaped, `<name> +2` and the §4 #7 three-way "everywhere" string. **The third regressed in the design.** |
| 3 | Picks | L1/L2 | Solar rows only carry `pick`; the two picks differ in window; no pick below the served floor. ⚠️ The both-differ-in-**region** assertion is **not** made — §4 #2 says why. |
| 4 | The control is one row at three widths | L2 | `getBoundingClientRect()`: next stepper's `right` < Regions chip's `left` at 1280/834/390; `.wf-win-label`'s `scrollWidth === clientWidth`; `getComputedStyle(glyph).fontSize !== '0px'` at 390. |
| 5 | The card survives the map | L4 | With it open: pan, zoom, wheel, ground click — assert still in the DOM. Then close ✕, `Escape`, row-select — each removes it. Assert the reopen row exists and reopens with the identical header string. |
| 6 | The card cannot contradict itself | L4 | All-Poor fixture: both rows' verdict text is `Poor`; the "next up" window's index is strictly greater than both rows' indices and its tier is `WORTH_IT`; no pick line names an index below row 1. |
| 7 | Night events | L1/L2/L5 | No verdict word, no tint class, never a pick candidate, the panel's night note variant present. |

Two checks this plan adds, because they guard decisions the spec does not know about:

| # | check | phase | how |
|---|---|---|---|
| 8 | Map/Plan verdict agreement | L1 | For every rendered window at one origin, `heat.windows[i].verdict` equals the Plan tab's own card verdict for the same key. Drive both from one fixture so neither can pre-satisfy the other. |
| 9 | Panels survive the map | L3 | Each of the four: open, fire a real `mousedown` → `click` pair on bare ground, assert still open. **Not** a hand-invoked `click` handler. |

---

## §8 Phase → session map

| session | phase | size | depends on |
|---|---|---|---|
| 1 | L1 — verdict/region/pick as data, `utils/mapVerdict.js` | M | — |
| 2 | L2 — the pill | L | L1 |
| 3 | L3 — panel persistence | S | — (independent; may run before L2) |
| 4 | L4 — the landing card | L | L1, L3 |
| 5 | L5 — the window panel | M/L | L1, L3 |
| 6 | L6 — the region panel, into the sheet | M/L | L5 |
| 7 | L7 — sweep and docs | S | all |

L3 has no data dependency and can be pulled forward if a session is short.

---

## §9 Local verification recipe

Backend on **8083** (not the 8082 CLAUDE.md records elsewhere):

```bash
cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local
```

⚠️ Another session may already hold 8083 and serve **its own empty H2** — check before assuming a
wall of 401s is your bug; run yours on a free port with
`-Dspring-boot.run.jvmArguments=-Dserver.port=8093` (a bare `-Dserver.port` on the Maven command line
does not reach the forked app JVM) and clear `localStorage`.

Frontend: `cd frontend && npm run dev`. Sign in as `admin` / `golden2026`.

**A local DB with no evaluation run has no ratings, so every state this increment renders needs a
fixture.** ⚠️ Do **not** trigger `POST /api/forecast/run` to get one — it bills the real Anthropic key
(~$0.016/location, measured). Seed instead: `scripts/dev-seed-locations.sh` for the catalogue, then
stop the backend (H2 file lock), insert `cached_evaluation` rows via `org.h2.tools.RunScript` spanning
today + three days × SUNRISE/SUNSET × several regions, restart, `POST /api/briefing/run`. Vary the
means so the three region-label cases and the all-Poor card state are all reachable — checks 2 and 6
cannot be seen otherwise.

⚠️ **The Browser pane can wedge permanently.** The fallback that has shipped every phase of this
series is headless Chromium driven through `playwright-core` directly, not `npm run test:e2e`. It is
also the only instrument that can answer check 4 — jsdom resolves specificity but not `var()`, and
the Browser pane composites nothing while hidden (`requestAnimationFrame` never runs, so a
focus-on-open reads as broken when it is not).

State plainly which claims were **seen** and which were **tested**.
