# The Map tab's tide panel becomes Pro and Admin only — plan

> Owner ask, 2026-10-24: the design bundle *PhotoCast Tide PRO Gate* (`design_handoff_tide_pro_gate/`,
> Option A; Option B, the blurred full panel, is the README's alternative and is **not** built). Make
> "Tide at this light" a Pro/Admin feature. LITE gets a one-line teaser row and an upsell, and no tide
> dimming. The server must not send the tide curve or the match data to LITE.
>
> Status: **plan, reviewed, not built.** It had one adversarial round with three lenses: frontend Map,
> backend and caching, and fidelity and rules. Their findings are folded in; the ones that changed
> the shape are noted with *(rev)*.
>
> Five Sonnet-built PRs: **F0 → F1 → F2 → B1**, with **L1** at any time (§8). **The owner answered every §9
> question on 2026-10-24**. Two answers differ from the defaults: Q8 (B1 may ship in the same release
> as F0/F1) and Q13 (the worded upsell becomes the house pattern; Ask follows in a separate plan). Every ⚠️ marks a trap the code sets for the builder. Line references were
> correct at `1a9986f5`. **The builder must re-read every reference before relying on it.** The owner
> questions are in §9; each has a default, which is what gets built if it is not answered.

## 1. Where we are

The tide panel and tide matching are open to every role today. The decision recorded at
`frontend/src/utils/windowFirstRows.js:18–24` says so in so many words: "tide chips and tide-aligned
markers are ungated for every role … tide is almanac … So: no gate." **This plan reverses that decision
for the curve and the per-location match only** (§2).

| Piece | Today |
|---|---|
| Desktop/tablet panel | `MapTideStrip` (`components/map/MapTideStrip.jsx`) in `.wf-map-chrome-bl` (`MapView.jsx:7390–7409`, `!isMobile`). It has a header (kicker, `statePhrase`, `headerMeta` = height · range anomaly · measured at), `TideDayChart`, `TideStripFooter` ("N of M coastal spots are dimmed …", next-fit jump), and a collapsed row (`:551–581`). Its root `[data-testid="wf-tide-strip"]` is **load-bearing DOM**: `MapCallout.jsx:96` (`BAND_BAR_SELECTOR`), `MapView.jsx:4636–4664` (`footerLift` and its ResizeObserver), and `MapLabels.jsx:105` / `PinsLayer.jsx:60` (obstacle lists). |
| Phone | The peek sheet's Tide button (`tideSummary`, e.g. "Mid ↓ · 3 dim", `utils/mapPeek.js:101–109`) and `MapPeekTideSection` (`:57–92`: kicker, state, `heightTimeLine`, the chart `tall`, the footer). Visibility is `tideVisible={tideCuesOn}` (`MapView.jsx:7042`). ⚠️ The same value feeds `tideCloseGateRef` (`:5985–5988`), whose effect (`:1728–1737`) **force-closes the open Tide section** when it turns false. |
| The Map's tide index | `tideAlignmentIndex` (`buildTideAlignmentIndex`, `utils/locationSheet.js:370`) has four Map readers: `getTideOnLightForLocation` (`MapView.jsx:3353`, which feeds `spotOf`'s `tideTier`/`tideShortfall`/`tideFitPhrase` at `:4206–4220` and the star-floor exemption at `:3388–3391` / `:3404–3431`), `stripModel` (`:4999`), the region panel rows (`:5083`, `utils/mapDrilldown.js:382–383`, not via `spotOf`), and the callout (`:6722–6723` → `MapCallout.jsx:1189` `TideFitBlock`). |
| `tideCuesOn` | `!isMobile \|\| tideVisible({mode: mapTideMode, …})` (`MapView.jsx:4091–4096`). No role term. |
| Dimming | CSS on `data-tide`: chips (`MapLabels.jsx:920,939`, glyph `:962–972`, tooltip `:1017–1029`), pins (`PinsLayer.jsx:568,582,645–653`), region panel (`MapRegionPanel.jsx:201–211`), and the label tiebreak (`utils/mapLabels.js:314–315`). **The heat layer has no tide input** (`MapHeatLayer.jsx`). |
| Stand-down footer | **No tide term today.** `weatherStoodOf` removes `TIDE_MISALIGNED` (`utils/mapStandDown.js:22–24,133–142`), and `filterActiveCount` (`MapView.jsx:4926–4929`) has none. The README's "39 stood down filtered must not include tide" already holds. |
| Spring / King doors | The chip `MapTopicChips` (`.wf-tch`, `aria-pressed`, no `aria-haspopup`) → `toggleTopic` (`MapView.jsx:4607–4615`). The four-day `MapTopicStrip` rows: desktop `onToggleType={toggleTopic}` (`:7385`); phone `toggleTopicFromSheet`/`pickTopicFromSheet` (`:4736–4750`, both end in `collapseSheetAfterTopicPress()`). The Plan/Coming up handoff: `WindowComingUpFourDays.jsx:176` → `mapDoor.js:416` `planHandoff.topicType` → `MapView.jsx:4425–4446` `pickTopicFromStrip`. Escape clears the topic last (`:6239–6247`). |
| Server | `GET /api/briefing` (`BriefingController.java:63`) → `days[].eventSummaries[].window`. `window.tide` is `BriefingWindowTide`: `curve`, `windowPosition`/`windowLevel` (**primitive `double`**), `sunrisePosition`, `sunsetPosition`, `extremes[]`, `heightAtWindow`, `rangeAnomaly`, `state`, `direction`, `nearest*`, `range`, `seas`, `locationName`. `window.tideFacts[]` is `LocationTideFact`: `tideAligned` (**primitive `boolean`**), `tideAlignmentQuality`, `tideShortfall`, `tideFitPhrase`, `tideState`, `tideLevel`, `tideDirection`, `tideHeight`, `tideOnTheLight`, `nearestSolarOffsetPhrase`. `curve` is normalised to `List.of()` and pinned "always present" (`BriefingEventSummaryWindowSerializationTest.java:151`). The body is identical for every role. |

`tideAligned` is computed on the server at build time from the location's admin-set `tideType`
(`TideFactDeriver.java:125`, `TideService.java:451–461`). It does not depend on the user.

## 2. The target

**The line between free and Pro.**

- **Free, for every role:**
  - the tide *state* at the light ("MID TIDE, FALLING");
  - the hot topics themselves: spring, king, big seas and surge, including their text, their aligned
    counts and `tideRun` (the two-question rule);
  - Claude's region and best-bet prose;
  - plain tide facts: the nearest extreme, the range anomaly, the Plan row's tide chips, and the
    location sheet's rising-tide and surge badges;
  - `GET /api/tides`.
- **Pro:**
  - **the curve**: the Map chart, the height at the light (window and per-spot), the extremes drawn on
    the chart, and, under Q12, the Plan popup's tide sparkline;
  - **the per-location match**: Map dimming, match glyphs, the footer's counts, next-fit jumps,
    tide-fit clauses on the callout, the sheets and the cards, the matrix's "N tides aligned" and its
    WORTH_IT cell tiering (`tierUtils.js:62–68`), and the slot flags "Tide aligned" / "Tide not
    aligned".

  Q2 asks whether the match is Pro on the Plan tab too or only on the Map *(rev)*.

**For PRO and ADMIN, nothing changes.** Every existing suite stays green with no assertion edits. Their
API body is byte-identical after B1.

**For LITE:**
1. **Panel (desktop and tablet).** A collapsed teaser row only (Option A): the kicker, the live state
   phrase, a spacer, a static decorative spark, and the Pro pill. There is no chart, no footer, no
   height or trend meta, and no chevron. F2 makes it a button that opens the upsell.
2. **No match cue anywhere on the Map.** This covers spots, chips, pins, the region panel, the
   callout, the label tiebreak and next-fit jumps.
3. **Phone.** The peek's Tide button shows the state only (no "· N dim"). Its section draws the teaser
   content, never the chart, the footer or the height line. The README does not cover the phone; this
   is the planner's design (Q10) *(rev)*.
4. **Tide topic doors** (Spring and King chips, their four-day strip rows, and the Coming up handoff)
   open the upsell instead of selecting the topic and drawing rings. This deliberately overrides the
   "hot topics are free" line for the **ring view only**. The README gates the Spring chip; the King
   chip, the strip rows and the handoff are extensions for consistency (Q4) *(rev)*.
5. **Server (B1).** LITE's briefing omits the curve and the per-location match fields of the window tide
   block, the window tide facts and the slot flags. The state, direction and plain facts stay. Hot
   topics and prose are untouched (§2, Free).
6. **No upgrade link.** `roles-and-gating.md:54` rules it out. `/upgrade` is not a route (there is no
   router; `App.jsx:89–97` knows only `/reset`), and the App Store forbids pointing at a web purchase.
   `UserSettingsModal.test.jsx:357` pins that there is **no `/upgrade/i` text at all** in Settings. The
   design's "Upgrade to PRO" button has nowhere to go, so it is dropped (Q1).
7. **The worded upsell becomes the house pattern (Q13, owner).** The Ask lock (the same day,
   `roles-and-gating.md:58`) is "nothing opens, no upsell words". The tide teaser **opens an upsell with
   words**, because the owner's design asks for it, and the Map's "Aurora viewline available — upgrade
   to Pro" chip (`MapView.jsx:1852–1853`) is the precedent. The owner chose to make this upsell the
   **house pattern going forward**.
   - F1 records it in `roles-and-gating.md`: a Pro feature's locked surface opens a worded upsell (no
     link, no "upgrade" text, Q1).
   - The greyed lock stays for controls too small to carry one.
   - Moving Ask's lock to an upsell is a **separate follow-up plan**, not part of this series.

## 3. Where the lock comes from (every phase)

- **Copy the drive filter.** The role is read in a context, and only a derived boolean crosses into
  components. `WindowFirstBriefingContext.jsx:681` already exposes `isPro`. Add `tideLocked: !isPro`
  beside it. The context's default value (`:97`, `isPro: false`) gets **`tideLocked: false`**, so a bare
  test render is unlocked. Pass it through `WindowFirstMapPane` to `MapView` (the `askLocked` route),
  with a **default of `false`** on `MapView` and on every child prop. The PRO suites then stay unedited
  (no existing tide test runs as LITE; the reviewer grepped). ⚠️ `MapView` also reads `role` directly
  (`:1283`). Do **not** add another `role ===` check there. One boolean, one source.
- **The stale role.** The client's role is localStorage `goldenhour_role`, written at sign-in only
  (`AuthContext.jsx:40, 67, 126`). The server reads the database row on every request.
  - A **demoted** PRO→LITE session draws the full panel against B1's stripped body. F0 makes that safe.
  - A **promoted** LITE→PRO session stays locked until the next sign-in, as Ask does. Record it in the
    reference doc.

## 4. F0 — the client tolerates a missing curve and a missing match (one PR, Sonnet) *(rev)*

**Why it exists:** it is B1's precondition. It changes **no output for any role today**, because every
payload still carries a boolean and a curve. It is split out so F1 is only the Map lock.

1. **The index.** At `locationSheet.js:392`, `aligned: fact.tideAligned ?? null`. In `mapTideFit.tierOf`
   (`:99–101`): `if (!fact || fact.aligned == null) return null;`. ⚠️ `mapPeek.js:66` has a
   *different* `tierOf` (the verdict tier). Do not touch it.
2. **The flat readers.** `windowFirstSpots.js:282` (`Boolean(fact.tideAligned)`) becomes
   `fact?.tideAligned ?? null`. In `windowFirstCards.js:305–307`, `tideFit`'s `coastal` count must only
   count spots with `tideAligned != null`. Otherwise `WindowSheetDialog.jsx:286–296` prints
   "0 of 7 coastal locations … on tide" for a stripped payload.
3. **Grep both `tideAligned` and `\.aligned\b`** across `frontend/src` (not tests) and classify every
   hit in the PR. The reviewer's table at `1a9986f5`:

   | Reader | Handles a missing value? |
   |---|---|
   | `locationSheet.js:392` | coerces it; fixed by #1 |
   | `mapTideFit.js:99–101` | fixed by #1 |
   | `mapTideFit.js:259` (`!fact?.aligned`) | yes, it skips |
   | `mapDrilldown.js:382` | yes, after #1 |
   | `askModel.js:328` | yes, after #1 |
   | `TideFitBlock.jsx:102` | yes, after #1 (returns null) |
   | `MapCallout.jsx:758`, `LocationFourDaySheet.jsx:481` (`!x.aligned`) | runs a wasted next-fit scan, then the block returns null. Make it `x.aligned === false` |
   | `HeatmapGrid.jsx:764`, `WindowFirstHeatStrip.jsx:268`, `windowFirstCards.js:306` (`=== true`) | yes |
   | `windowFirstSpots.js:282` | fixed by #2 |
   | `tierUtils.computeCellTier` (`hasTideAligned`, `:62–68`) | check it; a missing value must rank as "no tide claim" |

   Exclude `bigSeasChips.js:66` / `BigSeasDay` `structures[].aligned`. That is big seas, a separate
   record that stays free.
4. **No curve.**
   - `MapTideStrip`'s PRO branch with `tide.curve` empty or absent draws the header without the missing
     meta parts and **no chart**. `TideDayChart` already survives it (`:256–261`), but it draws an empty
     frame. When neither tiers nor a curve exist, the footer uses the "No per-spot tide fit for this
     window" wording, **never** "No coastal spot here has its water on this light" (`:122`), which is a
     negative match claim.
   - The same rule applies to `MapPeekTideSection`.
   - `windowFirstRows.js:193–212` (the Plan popup's sparkline and mark) must draw no mark when
     `windowPosition` or `windowLevel` is absent. Its own comment says a mark at `0.0` "would say the
     window sits at midnight at dead low water".
5. **Tests:**
   - one per surface in #3, using a fact with `tideState` and no `tideAligned`: no `miss`, no "dimmed",
     no "0 of N" copy, and no next-fit jump;
   - one for the strip and the popup row with an empty curve and no positions.

   These are the frontend test standards (`docs/engineering/frontend-test-standards.md`).
6. **Docs:**
   - `architecture-decisions.md:11` (the Backend-heavy seventh class): add a line saying this is a
     null-guard on the class's input, not a new derivation.
   - `changelog.d/20261024-tide-fit-null-tolerant.md`, `### Fixed — Tide fit reads a missing match as unknown, not a miss`.

## 5. F1 — the lock on the Map (one PR, Sonnet; after F0 merges)

### 5.1 One choke point for every Map match cue *(rev)*

1. In `MapView`, define `const mapTideIndex = tideLocked ? null : tideAlignmentIndex`, and use it for
   all four Map readers (§1): `getTideOnLightForLocation` (`:3353`), `stripModel` (`:4999`), the region
   rows (`:5083`), and the callout's `tideOnLight` / `tideAlignmentIndex` (`:6722–6723`). That single
   line clears every Map consumer at once: `tideTier`, `tideShortfall` and `tideFitPhrase` (so every
   `data-tide` consumer and the label tiebreak), the region panel's glyph and spoken clause, the
   callout's `TideFitBlock` and its next-fit jump, the `LocationFourDaySheet` opened from the callout,
   and the strip's counts. Do not touch the consumers. ⚠️ The teaser reads `activeMapEvent.tide`, not
   the index, so it is unaffected.
2. **The star floor (Q11).** The `visibleLocations` / `hasUnrated` exemption reads
   `getTideOnLightForLocation`, so it also ends for LITE. A 1–2★ coastal spot is then withheld like any
   other 1–2★ spot, and LITE's null-rated coastal spots (T+3/T+4, travel days) move behind the
   "unknown" toggle (`MapView.jsx:3440`). That is consistent: the exemption existed to draw a spot *as a
   miss*. List every count surface it moves (the footer, "N places", the region counts) and test a 2★
   coastal spot both ways.
3. **Teaser visibility.** For LITE it follows LITE's own visible coastal spots (`stripModel` needs
   coastal spots in `labelSpots`, `mapTideFit.js:362–365`). So it can be absent on windows where PRO
   sees the panel (no rated coastal spot in view). That is accepted and documented, not hidden.
4. **`tideCuesOn`.** Leave it as it is. With the index null for LITE it has nothing to show. Do **not**
   fold `tideLocked` into it, because it also drives phone visibility (§5.3).
5. **Pin the stand-down footer.** For a LITE reader, a window holding a `TIDE_MISALIGNED` stand-down
   gives the same "N stood down" count and " filtered" suffix as the fixture without it.

### 5.2 The teaser (desktop and tablet)

1. **A gated variant inside `MapTideStrip`**, behind a new `locked` prop. Placement: **after**
   `if (!model?.visible || !tide) return null` (`:549`) and **before** `if (collapsed)` (`:551`), so
   `collapsed` has no effect while locked.
2. ⚠️ **Keep the root** *(rev)*. The locked return keeps
   `<div className="wf-map-tide-strip" ref={setNode} tabIndex={-1} role="group" aria-label="Tide" data-testid="wf-tide-strip">`.
   The band bar, the footer lift and the obstacle lists all find the strip by that test id, and
   `onHeightChange` fires only through `ref={setNode}` (`:535–547`). The root is `flex-direction: column`
   (`index.css:3946–3961`), so the row needs an **inner** wrapper,
   `<div className="wf-tide-strip-locked" data-testid="wf-tide-strip-locked">` (`display:flex;
   align-items:center; gap:10px; white-space:nowrap`). It holds:
   - the kicker `TIDE_KICKER_TEXT`;
   - the state `statePhrase(tide)` in the **existing** `.wf-tide-strip-state` class (`index.css:3975–3982`,
     `--color-badge-tide`, already checked for contrast);
   - a `flex:1` spacer;
   - the spark: a fixed inline SVG, 84×22, `aria-hidden="true"`, `focusable="false"`, stroke
     `--color-badge-tide` (one teal with the state, opacity .55, blur 1.2px). It is a hard-coded sine
     and is **never** derived from `tide`;
   - the pill.

   In F1 it is static. Nothing opens yet.
3. **The pill.** Use the house `<span className="wf-lens-pro">Pro</span>`, which matches the drive row
   and the Ask lock (Q3). No lock icon component exists.
4. **The height.** Test that the callout's band reserve and the footer lift follow the teaser's height
   for LITE.
5. **Topic yield.** `MapTopicStrip` is `held={tideStripModel.visible && !tideYielded}` (`:7378`). Keep it
   as it is (Q14). Check that the four-day header still opens over the one-line teaser, and say what was
   measured.

### 5.3 Phone

1. **One derived visibility** *(rev)*:
   `const tidePeekShown = tideLocked ? (tideAvailableForCues && hasCoastalInViewForCues) : tideCuesOn`.
   Feed it to **both** `tideVisible={…}` (`:7042`) and `tideCloseGateRef.current.tideVisible`
   (`:5987`). Otherwise the force-close effect shuts the locked section on the render after it opens.
   Test that a LITE section stays open.
2. **The Tide button.** For LITE, `tideSummary` has no dim count. Add the `wf-lens-pro` pill only if it
   fits at 320px; measure, and drop the pill if it doesn't.
3. **The section when locked** draws the kicker, the state, and in F1 the one line `The tide curve and
   spot matching are part of Pro.` There is **no** `heightTimeLine` (it carries `heightAtWindow`), no
   chart, no footer, and no "No coastal spot in view" line.
4. **The Layers "Tide" segment** (Auto/Always/Off, `MapView.jsx:7155–7194`) is drawn LITE-locked in the
   house pattern: `.wf-lens-locked`, a real `disabled`, and the `wf-lens-pro` pill outside the grey
   (Q10b). The "Auto: shown when the light is Maybe or better…" hint (`:7191–7193`) is hidden while
   locked. Never write `mapTideMode` for LITE. ⚠️ Avoid a double dim if the segment's buttons already
   dim when disabled (ask-pro-gating-plan §4.2, M1).
5. This is a role gate, so "phone-only work stays phone-only" does not apply. **PRO must be provably
   unchanged in every band.**

### 5.4 Tests

New `MapTideLocked.test.jsx`:
- a `WindowFirstMapPane`-level render, proving that a LITE context reaches `MapView` locked;
- the teaser has the state, the pill and the spark (`aria-hidden`). It has no `wf-tide-strip-toggle`,
  no chart and no footer. The fixture's height string is absent. The root keeps
  `data-testid="wf-tide-strip"`;
- no `data-tide` on any chip or pin;
- no `wf-reg-panel-tide`;
- the callout has no `TideFitBlock`;
- the peek button has no "dim", and the peek section has no chart and no height line and stays open;
- the 2★ coastal floor case;
- the Layers segment is locked and its save is never called;
- the stand-down pin.

PRO regression: `MapViewFillerTideStrip`, `MapViewMobileTideRule` and `MapViewBlankedWindowTideFacts`
stay green unedited.

### 5.5 Docs

- `windowFirstRows.js:18–24`: rewrite the "row is not role-gated" note. The Plan row's tide chips stay
  ungated, and the curve and the per-location match are Pro (link this plan). It is a code comment, so
  Sonnet writes it.
- `reference/roles-and-gating.md`:
  - a new bullet, "**Tide at this light: the curve and the per-location match are Pro (2026-10-24)**",
    with §2's line;
  - the house-pattern change (§2 #7, Q13);
  - the stale-role note;
  - the known open paths (§7.4).
- `reference/map-tab.md`: the teaser, the choke point, the star-floor change and the phone gate.
- `changelog.d/20261024-tide-panel-pro-client.md`, `### Changed — The Map's tide panel and tide matching are Pro`.

## 6. F2 — the upsell (one PR, Sonnet; after F1 merges)

### 6.1 Trigger and popover

1. **The trigger.** The teaser's inner row becomes a `<button type="button">` with
   `aria-haspopup="dialog"`, `aria-expanded` and `aria-controls`. It gets the README's hover treatment
   (the rule-2 border, also kept while open) and focus-visible treatment (a 2px ink outline, offset
   2px), using tokens, not hex. The root `wf-tide-strip` div stays as it is.
2. **The popover joins `openMapMenu`** (`MapView.jsx:1587`) as `'tideUpsell'`. Opening it closes
   whatever `openMapMenu` held: the window, filters, legend and jump menus, but also `'window-panel'`
   (the drilldown), `'ask'` and `'peek:*'`. State that this is intended.
   - **Non-modal and inline.** `role="dialog"`, `aria-labelledby` pointing to the heading, **no
     `aria-modal`**, rendered inside the pane and **never portalled**. A portalled dialog would make
     `foreignModalOver` (`mapForeignModal.js:48–51`, a containment test) treat it as foreign and stand
     every Map Escape rule down. Inline, it stays out of the two-dialog cap.
   - **Focus.** On open, focus moves to the heading (`tabIndex={-1}`). The opener is recorded in a ref:
     the row, a chip, or the strip root for a strip press (`MapTopicStrip.jsx:229, 241` call
     `parkFocus()` before `onPick`). Every close restores focus to that ref.
   - **Escape.** The popover's own `onKeyDown`, guarded by `foreignModalOverPaneOf` (the
     `FiltersPopover.jsx:88–98` pattern), closes it and restores focus. The pane rung (`:6233–6236`)
     only nulls `openMapMenu`, so when that rung fires for `'tideUpsell'` it must also restore focus
     through the same ref.
   - **Close on:**
     - "Not now";
     - Escape;
     - an outside press via `useOutsideDismiss`. ⚠️ That hook ignores presses inside the map frame,
       and all the chrome is inside it (`MapView.jsx:6360`), so it fires only from the masthead or the
       tab bar;
     - **a plain click on the map**: add a pane-level click or mousedown on the map surface that
       closes it, matching the README's "click outside";
     - **a hand pan or zoom**: a `useMapEvents` child inside `MapContainer` in the
       `SheetDismissOnMapTouch` pattern (`:685–725`), on `dragstart` plus a hand-only `zoomstart`
       (honour `ownMoveUntil`), gated on `openMapMenu === 'tideUpsell'`. Programmatic moves (a window
       change, Ask's camera) must not close it;
     - **unmount**: if the teaser unmounts (window change, no coast in view, a breakpoint flip),
       close it and move focus to the pane.
   - **Placement.** Anchored above the teaser in `.wf-map-chrome-bl`, as wide as the column, and
     overlapping Hot topics (intended). Check it against the callout band and at 640px.
3. **Content.**
   - The pill.
   - Heading: `Match spots to the tide at your light`.
   - Body: `See the tide curve for each sunrise and sunset, and dim coastal spots that need different water.`
   - A static preview: a committed SVG, 70px, with two light markers in `--color-verdict-marginal` at
     60% (the chart's own marker token, `MapTideStrip.jsx:322,327`). ⚠️ It is built from a constant and
     never from `tide`. A test asserts the markup is identical for two different fixtures.
   - **Actions (Q1 default):** one ghost button, `Not now`. There is no upgrade button, no link and no
     "upgrade" text. Add the "no `a[href]`, no `/upgrade/i`" assertion here, as the Settings and popup
     tests do.

   The copy is a placeholder until the owner signs it off (Q5). The fonts are the app's display, body and
   mono tokens.

### 6.2 The tide topic doors (Q4)

Add a `lockedTopicTypes` prop (`['SPRING_TIDE','KING_TIDE']` when locked, otherwise empty) to
`MapTopicChips` and `MapTopicStrip`. Guard the **choke points** in `MapView`, so a LITE tide type
**never reaches `setSelectedTopic`**:
1. **`toggleTopic`** (chips, desktop strip): a locked type calls `openTideUpsell(trigger)`. The chip
   drops `aria-pressed` and **adds** `aria-haspopup="dialog"` and `aria-expanded`.
2. **`toggleTopicFromSheet` / `pickTopicFromSheet`** (phone, `:4736–4750`): the lock branch **returns
   before** `collapseSheetAfterTopicPress()`. Otherwise the sheet collapses the section it just opened.
   On the phone, a locked door opens the peek's Tide section **explicitly** (never through
   `handlePeekPress`, which toggles, `:5863–5866`), and only when `tidePeekShown` is true. When it is
   false, it does nothing, and the strip row's label says so (below).
3. **The handoff effect** (`:4425–4446`): a locked `planHandoff.topicType` lands the Map with **no topic
   selected** and does not open the upsell. Nothing pops up unasked after a tab change.
4. **A locked strip row press does not move the window.** The strip's accessible names ("show on the
   map", "opens Friday sunrise", `MapTopicStrip.jsx:367–369, 411–413`) are false for a locked row. Give
   it "Spring tide, a Pro map view" plus `aria-haspopup="dialog"`.
5. Test that a LITE reader cannot reach `selectedTopic === 'SPRING_TIDE'` or `'KING_TIDE'` through each
   door: the chip, the desktop strip, the phone strip and the handoff. Escape's topic rung can therefore
   never meet one.

### 6.3 Phone, analytics, tests, docs

- **Phone.** There is no floating popover. The locked `MapPeekTideSection` draws the popover's content
  inline (pill, heading, body, preview) and replaces F1's line. The sheet's own close does "Not now".
- **Analytics:** none. There is no analytics client (Q6).
- **Tests** (extending `MapTideLocked.test.jsx`):
  - open by click, Enter and Space, from the row, a chip and a strip row;
  - each close path with focus returned to the right trigger, including the pane-rung Escape;
  - Escape under a foreign modal does nothing;
  - a hand drag closes it, a programmatic `flyTo` does not;
  - a map click closes it;
  - unmount closes it;
  - exclusion with the filters popover;
  - the static preview is the same for every input;
  - no link and no "upgrade";
  - the phone section's inline content;
  - the §6.2 #5 table;
  - PRO's chip still toggles rings.
- **Docs:** `reference/map-tab.md` (the popover and its close rules), and
  `changelog.d/20261024-tide-panel-pro-upsell.md`.
- **UI review cadence and browser check** (§8).

## 7. B1 — the server strips the curve and the match for LITE (one PR, Sonnet)

**Dependencies:** F0 and F1 **merged** (Q8, owner: B1 may ship in the same release; the PWA residual in
§8 is accepted). Q2 and Q12 are answered: **Pro everywhere**, and the Plan popup's sparkline is Pro.

### 7.1 Where

**Only at the HTTP edge, `BriefingController.getBriefing`.** Not in `getCachedBriefingForApi`, whose
internal callers keep the full data: `BriefingDigestService:97` and `AskSnapshotBuilder:213`. Ask is
Pro-only, and the user-less Ready precompute must keep tide. The reviewer verified that the controller
is the only edge serving `window.tide`, `tideFacts` and slot flags. The digest's `Window` has no tide
fields (`BriefingDigestResponse.java:60–72`). `ComingUpAssembler` uses `AlmanacEvent`. No email or
notification path reads the window tide.

1. `getBriefing(Authentication auth)`. LITE is `!(hasRole PRO_USER || hasRole ADMIN)`, a **fail-closed**
   check. Anonymous callers are already 401 (`SecurityConfig.java:91`; it is pinned in
   `BriefingControllerTest`). ⚠️ `BriefingControllerTest:44,70,97` and
   `WindowTideFactsWireContractTest:45,100` use `@WithMockUser`'s default `ROLE_USER`. Set their harness
   role to `PRO_USER`. That is a harness change, not an assertion edit. Update `getDigest`'s javadoc
   `{@link #getBriefing()}`.
2. **A new `BriefingRoleShaper` bean** (no dependencies), with `DailyBriefingResponse forLite(...)`.
   Rebuild **only** through the existing withers:
   `DailyBriefingResponse.withDays` → `BriefingDay.withEventSummaries` → `BriefingEventSummary.withWindow`.
   Add `BriefingWindow.withTide(..)` / `withTideFacts(..)`, `BriefingWindowTide.forLite()` and
   `LocationTideFact.forLite()`, and have each call its **canonical** constructor. ⚠️ **Forbidden:**
   `BriefingWindow`'s 8-arg convenience constructor (it drops `tideFacts`, so LITE would lose every
   Plan-tab tide fact), `BriefingWindowTide`'s 12-arg one, and any Jackson round-trip (that would go
   through `AppConfig`'s Jackson 2 mapper, not the wire mapper). Every type is a record with
   `List.copyOf`, so mutation is impossible. Keep one "the original still has its curve" pin anyway.
3. ⚠️ **Type changes are needed** *(rev)*. A primitive cannot be omitted:
   - `LocationTideFact.tideAligned`: `boolean` → `@JsonInclude(NON_NULL) Boolean`. `from` autoboxes.
     Audit every internal `.tideAligned()` caller (e.g. `AskSnapshotBuilder.java:482`) and use
     `Boolean.TRUE.equals(...)`.
   - `BriefingWindowTide.windowPosition` / `windowLevel`: `double` → `@JsonInclude(NON_NULL) Double`.
     Audit `TideCurveCalculator`, `WindowTideRollupBuilder` and Ask for unboxing.
   - `curve` stays `List.of()` for LITE, which keeps the existing "empty = no shape" contract and
     `BriefingEventSummaryWindowSerializationTest.java:151`. Assert it with `isEmpty()`, **never**
     `doesNotExist()`.
   - Pin that **PRO's JSON is byte-identical** before and after the type changes: serialise a full
     fixture through the wire mapper and compare it to a golden.
4. **What is stripped for LITE** (decided here, not left to the builder) *(rev)*:

   | Record | Stripped (null, or empty for `curve`) | Kept |
   |---|---|---|
   | `BriefingWindowTide` | `curve`, `windowPosition`, `windowLevel`, `sunrisePosition`, `sunsetPosition`, `extremes`, `heightAtWindow` | `state`, `direction`, `locationName`, `nearestType`, `nearestTime`, `nearestOffset`, `range`, **`rangeAnomaly`** (almanac, shown free on hot topics and Coming up), `seas` |
   | `LocationTideFact` | `tideAligned`, `tideAlignmentQuality`, `tideShortfall`, `tideFitPhrase`, **`tideHeight`, `tideLevel`** (the per-spot height and curve position at the light, the same datum as `heightAtWindow`) | `locationId`, `locationName`, **`tideState`** (⚠️ `buildTideAlignmentIndex` drops facts without it, `locationSheet.js:381`), `tideDirection`, `tideOnTheLight`, `nearestSolarOffsetPhrase` |
   | slot `flags` | the strings "Tide aligned" and "Tide not aligned" (`BriefingVerdictEvaluator.java:349–353`; rendered as `TideChip` by `HeatmapGrid.jsx:211, 278`) | every other flag. ⚠️ The STANDDOWN verdict and its "Tide mismatch" reason (`BriefingSlotBuilder.java:254–258`) are a **rating** and stay (§7.4). |

   Grep every client reader of each kept field and list it in the PR, so nothing free is lost. A
   **reflection completeness test** classifies every record component of `BriefingWindowTide` and
   `LocationTideFact` as kept or stripped, so a field added later fails until someone classifies it.
5. **Under Q2's alternative** (the match stays on the Plan tab), B1 strips only the curve rows of the
   table, and F1's client gate is the Map's only enforcement.

### 7.1a 2026-10-26 addendum: the slot `TideInfo` was missed; now stripped

The table above covered the window block, the window's per-location facts and the two slot flags, but not
each slot's own tide block, `BriefingSlot.TideInfo` (`@JsonUnwrapped`), which `BriefingSlot.forLite()` passed
through untouched. `TideInfo.forLite()` now mirrors `LocationTideFact`'s split and nulls `tideAligned`,
`tideAlignmentQuality`, `tideShortfall`, `tideFitPhrase`, `tideHeight`, `tideLevel` and
`nearestHighTideHeight` (a height; the window block keeps the nearest extreme's time and type, never its
height). Kept: `tideState`, `nearestHighTideTime`, `heightAboveP95`, `heightAboveSpringThreshold` (they feed
the free spring and king hot topics and the rollups' king/spring counts; they are size flags, not the match),
`lunarTideType`, `lunarPhase`, `moonAtPerigee`, `nearestSolarOffsetMinutes`, `nearestExtremeKind`,
`tideOnTheLight`, `nearestSolarOffsetPhrase`, `tideDirection`. `TideInfo.tideAligned` became a
`@JsonInclude(NON_NULL) Boolean`; the four internal readers on a `TideInfo` (`BriefingVerdictEvaluator`,
`ForceEvalHeadlineSelector`, `BriefingRollupBuilder`, `BriefingGlossService`) use
`Boolean.TRUE.equals(...)`. A reflection completeness test classifies every `TideInfo` component.
Note: on the live `GET /api/briefing` path `ServedBriefingAssembler.stripSlotTide` already sets every
slot's `tide` to null (window-tide-facts P5), so the leak was latent; this makes the shaper correct on its
own for any body that still carries a slot tide.

### 7.2 What B1 changes on LITE's Plan tab (F0 makes it render cleanly)

B1 changes the Plan tab for LITE, so it **gets the UI review cadence and a browser check** *(rev)*:
- the Plan card `tideFit` and `WindowSheetDialog`'s "N of M coastal locations on tide": omitted;
- the heat strip's `TideWave`: gone;
- the matrix's "N tides aligned" chip and the slot "Tide aligned" chips: gone;
- the matrix WORTH_IT tier 1/2 split (`tierUtils.js:62–68`): no longer uses tide for LITE;
- the location sheet's and the `LocationFourDaySheet`'s fit clauses: gone;
- the Plan popup's tide sparkline, mark and "X m at the light" (`WindowSheetDialog.jsx:10` →
  `WindowAttributeRow` → `WindowTideSparkline`, `windowFirstRows.js:145–146,170,193–212`): gone (Q12).
  The state and nearest-extreme facts stay.

Each one disappears without a Pro pill. They are inline clauses, too small to grey
(`roles-and-gating.md:55`'s exception), and the upsell lives on the Map's teaser. Q2 confirms this.
Test it with a LITE-shaped fixture through each surface: the free facts are drawn, and no match, no
miss, no "0 of N" and no sparkline.

### 7.3 Caching

- `/api/briefing` is ETag-listed (`HttpCachingConfig.java:77`). The ETag is hashed from the body, with
  `Cache-Control: private, no-cache` (`:179`). Pin it, using `HttpCachingIntegrationTest` as the
  template, with a fixture that **really carries** a non-empty curve and `tideAligned`. Otherwise the two
  bodies hash the same and a 304 is correct. A LITE GET with a PRO 200's `If-None-Match` gets **200
  with the LITE body**, and the reverse holds too.
- The client SWR key is already `briefing:${role || 'anon'}` (`WindowFirstBriefingContext.jsx:219`). No
  change.
- A role variant is not personal data. Record it in `architecture-decisions.md` beside the Forecast DTO
  precedent (`:8`). Amend the seventh-class note (`:11`): "every fact is on the shared `/api/briefing`"
  is now true only for PRO and ADMIN.

### 7.4 Known open paths (stay free; recorded in `roles-and-gating.md`) — Q7

- `GET /api/tides` (raw extremes).
- `GET /api/forecast/{id}`'s tide block and `aligned`.
- Hot topics on the briefing: `tideRun` (`rangeAnomaly`, extremes, `aligned`/`bestAligned`/`alignmentPhrase`,
  the `roster` counts); `ExpandedHotTopicDetail.tideMetrics()` aligned counts; and the king-tide headline
  "tide aligned with …" (`KingTideHotTopicStrategy.java:293`, drawn at `WindowTopicRows.jsx:117`). These
  are under the two-question rule.
- Claude's best-bet prose (fed `tideAlignedCount`: `BriefingRollupBuilder.java:461–515`,
  `BestBetPromptText.java:155`). **Status: the region gloss is fixed** (`audit-followups-plan.md` P): it is
  no longer fed `tideAlignedCount` (was `BriefingGlossService.java:295`) and its prompt forbids a tide match.
- The `TIDE_MISALIGNED` stand-down: the verdict, the slot's "Tide mismatch" reason, the
  `/api/briefing/evaluate/scores` `triageReason`, and its client label "tide not aligned with location
  preference" (`utils/forecastDetailFacts.js:41`, `LocationSheetFacts`). It is a **rating**: the
  verdict is honest for every role, and only the reason names the match.

### 7.5 Tests and docs

- `BriefingRoleShaperTest`: the table in §7.1 #4, the PRO golden, nulls, empty days, and the
  completeness test.
- `BriefingControllerTest`: LITE, PRO and ADMIN wire shapes. The shaper is a **real** bean (no
  `@MockitoBean`, which would fork `AbstractControllerTest`'s shared context). Assert that the PRO and
  ADMIN bodies keep `curve` and `tideAligned` instead of `verifyNoInteractions`.
- The ETag pin.
- Minimise `any()` in `when`/`verify`; Codex flags it.
- The four frontend commands too (the only backend `readFileSync` reader is
  `windowFirstTopics.test.js:437`, which reads `PlanWindowProjector.java`; B1 must not touch that file).
- **Docs:**
  - `reference/api-endpoints.md` §Briefing (the role-shaped body);
  - `reference/hot-topics-tides-eclipse.md` (the free/Pro line);
  - `reference/roles-and-gating.md`: add a **current-state** note to the tide bullet, and do not edit
    the history paragraph at `:58`;
  - `changelog.d/20261024-tide-pro-api.md`, `### Changed — Lite's briefing omits the window tide curve and per-location tide matching (Plan tab included)`.

## 8. Order, release and the Sonnet briefs

| PR | Depends on | Why |
|---|---|---|
| **F0** | — | Changes no output today. It is B1's precondition. |
| **F1** | F0 merged | The Map lock. Until B1, LITE can still read the match on the **Plan tab** (cards, the heat strip, the matrix, the location sheet and the Plan popup's sparkline), for at least one release. This is accepted, and B1 closes it. |
| **F2** | F1 merged | Builds on the teaser. Independent of B1. |
| **B1** | F0 + F1 merged | Without F0 in the running bundle, a stripped body draws every LITE coastal spot as a miss. **PWA residual, accepted by the owner (Q8):** the precached bundle runs for one session after a deploy (ask-pro-gating-plan §6). If B1 ships in the same release as F0, an installed LITE PWA may show every coastal spot dimmed for one session. |
| **L1** | — (deploy after F1's release) | A landing-only PR (CI skips the suites, #1361). The owner deploys it with `./release-landing.sh`, after the app release carrying F1, so the page never promises as Pro something LITE still has free. |

**Briefs.** Each phase is one Agent call with `model: "sonnet"` and `isolation: "worktree"`, branched
from the updated `main`. Each brief carries:
- this plan's matching section verbatim, plus §2, §3 and the answered §9 rows;
- the files named in it;
- the tests listed;
- the gates: the backend ladder ending in `clean verify` excluding integration, gated on Maven's exit
  code, and the frontend's four commands;
- the changelog file.

The planner reviews each diff read-only and sends fixes back to the same agent. Then CLAUDE.md steps 3–6:
a ready PR, CI and Codex 👍 on the exact head, and a squash merge.

**UI review cadence (F1, F2, B1's Plan-tab effect):** about six read-only lenses:
- focus and keyboard;
- accessibility: the hidden spark, dialog naming, the pill's accname, forced colours;
- phone vs desktop: 320px peek, 640px tablet;
- **role leakage**: no curve or match cue for LITE on any surface, no link;
- visual: no double dim on the Layers lock;
- **PRO regression**.

Then a browser check on 8083 as `admin` and as a LITE account. Memory
`cannot-sign-in-for-browser-checks` applies, so say what was seen and what was only tested.

### L1 — landing (one PR, Sonnet) *(rev)*

⚠️ **The bundle's `landing/index.html` is stale.** It predates #1362–#1367: the viewer, the Ask
screenshot, the "next few days" wording and the asset fingerprints. Copying it would revert all of
that. Make **two surgical edits** to `landing/index.html` on `main`:
1. After `<p>It's another useful consideration when planning a morning or evening on the coast.</p>`
   (line 222 at `1a9986f5`) and before the `Coastal safety information →` link, insert the **Q9-resolved**
   paragraph, wrapped as `<p>… <span class="pro">Pro</span></p>`.
2. Replace `<li>Advanced coastal and tidal information</li>` with
   `<li>Tide at the light: water level, tide curve and coastal locations matched to the tide</li>`.

There is no CSS change. `git diff main -- landing/` must show exactly two hunks. Add
`changelog.d/20261024-landing-tide-pro.md`.

## 9. Owner questions — ANSWERED 2026-10-24

The owner took every default except **Q8** (same release is fine) and **Q13** (the upsell becomes the house
pattern; Ask follows in a separate plan). The *Default* column below is now the decision.

| # | Question | Default |
|---|---|---|
| Q1 | The design's "Upgrade to PRO" button has no route, and the house rule forbids a link and any "upgrade" text (App Store; `UserSettingsModal.test.jsx:357`). | **Drop it.** The popover has its words and "Not now" only. |
| Q2 | Is the per-location match Pro **on the Plan tab too**? B1 would remove the card and popup "N of M on tide", the heat-strip tide wave, the matrix's "N tides aligned", the slot "Tide aligned" chips, the tide term in the matrix's WORTH_IT tiering, and the fit clauses in the sheets, all silently (§7.2). | **Yes, everywhere**: the server enforces it. The alternative strips only the curve, and the match stays readable on the wire and on the Plan tab. |
| Q3 | Pill: the house `wf-lens-pro` (it matches the drive filter and the Ask lock), or the design's amber mono "padlock PRO" pill? | **House pill.** |
| Q4 | Tide topic doors for LITE: the Spring chip (README), plus the King chip, the four-day strip rows and the Coming up handoff (extensions). Rings carry no match data, so gating them overrides the "hot topics are free" line for the ring view. | **Follow the design and extend it consistently**: the doors open the upsell; the handoff lands with no topic selected. The other option is to keep rings free and have the chip toggle as today. |
| Q5 | The popover's heading and body copy (placeholders). | The README's placeholders. |
| Q6 | Analytics events. | None (no analytics client). |
| Q7 | The open paths in §7.4 stay free. | **Yes**, recorded. |
| Q8 | Hold B1 until F0 and F1 are released (§8). | **Owner: no.** Same release is fine, and the one-session PWA residual is accepted. B1 follows F0 + F1 merged. |
| Q9 | The landing paragraph sells "high, mid or low and rising or falling" as Pro, but the teaser shows it free. | **Keep the state free, and reword**: "With Pro, you can see the full tide curve for each sunrise and sunset, the height of the water at the light, and which coastal locations suit it." The owner signs it off. |
| Q10 | The phone treatment (§5.3, §6.3) is the planner's own design; the README draws the desktop only. (b) The Layers Tide segment is locked rather than hidden. | **As planned; locked.** |
| Q11 | LITE loses the coastal star-floor exemption (§5.1 #2), so low-star and unrated coastal spots are withheld like any others. The teaser can then be absent where PRO sees the panel. | **Accept.** |
| Q12 | The Plan popup's tide sparkline, its mark and "X m at the light" become Pro (the curve). | **Yes.** The state and nearest-extreme facts stay. |
| Q13 | A second gating pattern: tide opens a worded upsell, while Ask's lock opens nothing (§2 #7). | **Owner: the upsell becomes the house pattern.** Ask moves to it in a separate follow-up plan. |
| Q14 | A one-line LITE teaser still holds the four-day topic strip closed (an extra press). | **Keep**, consistent with PRO. |
