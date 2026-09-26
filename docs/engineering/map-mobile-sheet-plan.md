# Map tab on phone — the peek sheet: implementation plan

**Source**: the design bundle vendored at `docs/design/map-mobile-sheet/` — `README.md` (the spec),
`CLAUDE_CODE_PROMPT.md` (the designer's working order and Verify list), `Map Mobile Minimised.html`
(the prototype; **Option A only**), `screenshots/01–06` (the six states).
`docs/design/map-mobile-sheet/VENDORING.md` records what was copied. Read the spec before any
phase; **this document is the *port* plan**: what the codebase already has, what is genuinely new,
where the spec and the code disagree on purpose, and how the work cuts into single-session phases
for **Sonnet** sessions, each landing as its own PR.

**The one-sentence version.** On a phone the Map tab opens with the landing card, the tide strip
and the scored-locations toast stacked over almost the whole map; this increment moves every map
panel into one bottom sheet that starts collapsed (74 px, three summary buttons: *Other windows ·
Tide · Layers*), opens to 356 px on one section at a time, collapses on any map touch, and adds a
persisted *Tide mode* (Auto / Always / Off) whose Auto rule hides every tide cue on a Poor window or
an inland view. Tablet and desktop are unchanged.

**The one-paragraph correction.** Almost all of the *content* already exists and is already
computed the right way: the in-view coastal gate (`bounds.pad(0.12)`), the dimmed count and the
dominant want, the next-fit scan, the tide state/direction words, the served day curve, the
window roster with its verdicts, the landing card's rows and header, and the Heat/Pins, Regions and
Filters controls. What is genuinely new is small and mostly *chrome*: a new in-frame sheet
component (`BottomSheet.jsx` does **not** fit, §1 #2), one verdict clause and one mode on the
existing tide gate, one null at the single site every tide cue already keys off, a 3-second fade on
a toast that has no timer today, a `V155` column and endpoint copied from `V147`/`map-colours`, and a
phone entry to the drilldown that the pill menu used to be the only door to. The risk is not the
new code; it is the **phone lifted stack** — five hand-tuned `bottom:` literals and a `--tsh`
re-anchor in `index.css`'s phone block, all of which assume a bottom bar that will no longer exist
on the phone, and a cascade test that re-checks that arithmetic against the real stylesheet.

---

## §0 Status

**Status: 📝 PLANNED — nothing built. Six code phases (M1–M6) plus this docs PR (M0).** Phase log
(every phase appends its own row in the same commit as its code):

⚠️ **The `commit` column names the PR, not a SHA** — the repo squash-merges, so a per-phase hash is
orphaned at merge; the PR number is the durable pointer (the map-landing plan's own rule).

| phase | branch | commit | date | notes |
|---|---|---|---|---|
| M0 | `docs/map-mobile-sheet-plan` | — | 2026-09-25 | Vendored the bundle; this plan; `map-mobile-sheet-prompts.md`. No code. |
| M1 | `feature/map-mobile-sheet-m1-quiet-start` | — | 2026-09-26 | Landing card never opens on phone (`landingOpen` gated `!isMobile`, `mapLandingSeenRun` never written, `↺ Back to …` row withheld); the tab's scored-locations toast moves to a fixed `top: 62px` and fades (`wf-map-scored-legend-gone`) 3 s after its `\|date\|eventType\|` key last changes, reduced-motion-safe; retired the conflicting `.wf-tide-strip-on` `bottom` override for the toast that M1's fixed-`top` rule would otherwise have fought for the cascade. **Adversarial review (five read-only lenses, run by the orchestrator after the session's own review stalled at one): three confirmed findings, all fixed before the commit landed** — (1) `openDrilldown` calls `dismissLanding` unconditionally and the pill menu's drilldown row renders on every viewport, so the phone COULD write `mapLandingSeenRun` (§5 D-3 broken by a route the session's tests never pressed); `dismissLanding` now returns at once on the phone and a test presses `wf-win-more`; (2) the fade timer was keyed on the window alone, so a toast whose gate opened > 3 s after mount (a slow briefing load) was born with the class applied — the effect now keys on a single `toastShown` gate shared with the render, and a rerender-after-5-s test pins it; (3) an unmount-before-3-s cleanup test was missing. **Browser (390 × 844 and 1280 × 900, §9 fixture with a Worth-it tonight and a Poor tomorrow morning, SEEN AND MEASURED)**: phone — no `.wf-land`, `mapLandingSeenRun` null after the tab mount, toast at `top` 62 px in-frame, centred to the pixel, one line 26.5 px tall (it had wrapped to three lines, 43 px — fixed with the design's own `white-space: nowrap`, pinned in the cascade test), opacity 0 with `aria-hidden` and `pointer-events: none` after 3 s; desktop — landing card open (`Tonight, or tomorrow?`), toast standing bottom-right (8 px / 54 px gaps), no class, no fade. |
| M2 | `feature/map-mobile-sheet-m2-sheet` | — | 2026-09-26 | New `components/map/MapPeekSheet.jsx` (in-frame, backdrop-free, `data-testid="wf-map-peek"`, z-index 1120) replaces the phone's floating pill dropdown, Regions/Heat-Pins/Filters bar and tide strip with one collapsed (74px) / open (356px, clamped `calc(100% - 64px)`) sheet. `WindowControl` gains `pillOverride` (drops `aria-haspopup="listbox"`, points `aria-controls` at `wf-map-peek-body`) so the phone pill opens the Windows section instead of the listbox; `KindChip` exported for reuse. Windows section: heading `landingCardModel().header` (never fixed copy), the pill's own roster with night rows showing `{bestRating}★ best`, the drilldown row last (collapses the sheet by `openMapMenu` exclusivity). Layers section: Show/Regions/Filters/Legend; `RegionsJump`/`FiltersPopover` gain `chipHidden` + `restoreFallback` (threaded to `BottomSheet`/`useDialogFocus`) so their sheet stays reachable and closing one returns focus to the Layers button. `SheetDismissOnMapTouch` (a `useMapEvents` child, not `useOutsideDismiss`) collapses on any map touch; a second effect keyed on `selectedLocationName` collapses on any installed selection (chip/pin/handoff all write it directly); a peek press clears the selection FIRST (D-7). Lifted stack rebuilt off the sheet's 74px floor (attribution `padding-bottom: 74px`, chrome-bl `calc(74px + 27px + 8px)`); counts footer unconditionally `sr-only`-clipped (no phone home, §4 #10); `MapCallout`'s phone band reads a fixed `--psh: 74px` custom property rather than measuring a live rect. `mapPhoneChromeCascade.test.jsx` rewritten for the two-row stack; `mapChromeZLadderCascade.test.jsx` gains the 1120 rung. **Adversarial review (four read-only lenses): five real findings, all fixed before the commit landed** — a stale doc comment claiming the phone tide strip sat at z-index 1120 (it is 1100, chrome-tier, unchanged by the phone media query) corrected; a `MapPeekWindowsSection` test missing the sibling-row negative assertion; no test proving the Tide button is absent in M2 (task 2's "never an empty panel" rule); no test proving `aria-expanded` tracks the same pill through a real open→close cycle; no test exercising D-7's reverse-order clause — all closed. Accessibility lens measured the peek row's 8.5px key labels at **≈7.1:1** contrast against the sheet's background (`--color-plex-text-secondary` over the `.97`-opaque sheet bg over the app bg) — clears WCAG AA for small text (4.5:1) comfortably; §6 Q5 closed. **Browser (390 × 844 and desktop-wide, SEEN AND MEASURED)**: phone — sheet 74px collapsed / 356px open exactly; pill `aria-haspopup` absent, `aria-controls="wf-map-peek-body"`; Windows section shows the served header and per-tier verdict colours; Layers → Regions opens the real `BottomSheet` with the peek sheet gone, both a real Escape and the ✕ button return focus to the Layers peek button (verified with real clicks/keypresses, not synthetic `.click()` calls, which behaved differently); a real mouse drag on the map collapses an open section; the "Other windows" verdict span computed `flex: none`. Desktop — landing card, the Regions/Heat-Pins/Filters bar, Legend chip and counts footer render exactly as before, no `.wf-map-peek` anywhere. |
| M4 | `feature/map-mobile-sheet-m4-tide-mode-setting` | — | 2026-09-26 | Tide mode persisted setting: `V155__user_map_tide_mode.sql`, `MapTideModeRequest`, `UserSettingsService.saveMapTideMode`, `PUT /api/user/settings/map-tide-mode`, `useReaderSettings`' `mapTideMode`/`saveTideMode` (its own save line, reusing `colourSaveQueue.js`'s mechanics unchanged), threaded `App` → `WindowFirstMapPane` → `MapView` as unread props. No UI. Migration proven only in CI (no local Docker). **Adversarial review (three read-only lenses run by the orchestrator plus the session's own late reviewer): backend clean; two findings fixed before landing** — (1) `saveTideMode` claimed the hook's answer-order slot at PRESS time, the one participant that did (every other save is numbered when it lands), so a press during a slow `getSettings()` made that whole mount read fail its own `claim` and be dropped — home, colour and last-seen date with it; the claim now happens in `onSaved`, the landed mode is shown again in case a read landed in between, and a held-read test pins it; (2) `App.test.jsx`'s null → `'auto'` case waited on a call already made and asserted the pre-load default, so it passed with the mapping deleted — it now gates on the same read changing `mapColourScale` off its bootstrap. Migration pending CI. |

---

## §1 Corrections to the spec — where the codebase has already moved

Every path is under `frontend/src/` unless stated. Line numbers are as of 2026-09-25 and **must be
re-verified against the tree before editing** — `MapView.jsx` is 6,100 lines and moves weekly.

### 1. The "phone bottom bar" is CSS, not a component

The spec says the Regions / Heat-Pins / Filters controls "used to sit at the bottom of the map on
phone". They do, but there is no bar component: `.wf-map-chrome-tr` (`MapView.jsx` ~5491–5598,
holding `RegionsJump`, the `.wf-map-toolbar-cluster` with the `.wf-seg` Heat | Pins toggle and the
`.wf-map-key` ramp, and `FiltersPopover`) is moved to the bottom by the phone media block in
`index.css` (`@media (max-width: 639px)`, ~3834–4056: `bottom: 8px; left/right: 8px;
flex-direction: row`). So "move the controls into the sheet" means: **on phone, do not render
`.wf-map-chrome-tr` at all**, and mount its three children inside the sheet's Layers section. The
whole phone **lifted stack** keys off that bar — attribution `padding-bottom: 76px`, counts footer
`112`, scored toast `152`, `.wf-map-chrome-bl` `192`, the phone tide strip at `112`, and the
`wf-tide-strip-on` re-anchors on `var(--tsh)` — and every one of those literals goes with it.
`test/mapPhoneChromeCascade.test.jsx` re-checks that arithmetic against the real `index.css`; it
will fail on purpose and is **rewritten, never deleted** (§3 M2).

### 2. `BottomSheet.jsx` does not fit, and the spec half-knew it

`components/BottomSheet.jsx` (`{ open, onClose, label, modal = true, reserveCloseStrip, children }`)
has **no detents and no drag** (the handle is decoration), **returns `null` when closed** (so it
cannot show a collapsed 74 px state), **portals to `document.body`** with a `.app-scrim` backdrop
at `z-index: 9999` that blocks the map (`useOutsideDismiss.js` ~27–34 records that the map cannot
pan while one is open), locks body scroll, and is `role="dialog"` with `aria-modal` by default —
which every map surface's Escape/outside-press rule (`utils/mapForeignModal.js#foreignModalOver`)
would read as a *foreign modal* and stand down for. The peek sheet is the opposite of all five: it is
always mounted, in-frame, backdrop-free (rule 4 *depends* on the map staying touchable), not a
dialog, and part of the pane. So the sheet is a **new** component, `components/map/MapPeekSheet.jsx`,
mounted inside `.wf-map-tab` (so `foreignModalOver` never sees it). `BottomSheet` keeps its two
phone callers (`RegionsJump`, `FiltersPopover`), which the Layers section reuses (§4 #5).

### 3. The landing card has no phone branch, and its doc block says the opposite of this spec

`landingOpen` (`MapView.jsx` ~1807) is
`Boolean(runStamp) && (landingSeenRun !== runStamp || landingReopenedRun === runStamp)` and never
reads `isMobile`; `MapLandingCard.jsx`'s header comment (~36–42) says the phone keeps the card
inset. This spec supersedes that: on phone the card never opens, the pill menu's `↺ Back to …`
reopen row is withheld (`landingLabel = ''`), and **the phone never writes `mapLandingSeenRun`**
— a reader who later opens the same run on a desktop has not seen the card and should (§5 D-3).
The card's *model* (`utils/mapLanding.js#landingCardModel` → `{rows, header, allPoor, lead, nextUp,
picks}`) is what the Windows section reads its heading from (§4 #2).

### 4. The toast has no timer, no dismiss, and its phone position is a lifted-stack literal

`★ PhotoCast-scored locations shown` (`MapView.jsx` ~5700–5727 on the tab; a separate overlay copy
at ~5349 which is **untouched**) is gated on `!aurora && !astro && briefingScores.size > 0 &&
solarWindowOnScreen()` and keyed on `|date|eventType|`. It is always on while the gate holds. The
phone block anchors it at `bottom: 152px` right-aligned; the spec wants `top: 62px`, centred,
fading after 3,000 ms. Phone only — desktop keeps the standing legend (§5 D-4).

### 5. The tide gate already exists, in-view, on `moveend` — the rule gains one clause and one mode

`utils/mapTideFit.js#stripModel({ row, spots, bounds, evRows, evIndex, idx })` already returns
`visible = row.kind === 'solar' && row.tide != null && coastalInView.length > 0` with
`coastalInView = spots.filter(s => s.coastal && bounds.pad(0.12).contains(...))`, plus `dimmed`,
`matched`, `dominantWant`, `dominantWantCount` and `nextFitRow`. `BoundsTracker` (`MapView.jsx`
~701) calls `setTideViewBounds` on `moveend`/`zoomend`, and `stripModel` is rebuilt on every render
(~3663). The spec's Auto rule is **that gate AND `verdict.tier !== 'STAND_DOWN'`**, and the mode
sits in front of both. Nothing about the population changes — the spec's §8 "counts are in-view
counts" is already true, and the "N dim" on the button is the same `dimmed.length` the strip's
footer prints today, so the two can never disagree (§5 D-5).

### 6. Every tide cue already has exactly one gate

`MapView.jsx` ~3208 sets `tideTier: tierOf(tide)` (`'match' | 'miss' | null`, from the served
`tideAligned`) on each spot, alongside `tideShortfall`, `tideFitPhrase`, `coastal`. The chip's wave
glyph (`MapLabels.jsx` ~615 `data-tide`, ~628–638 `<TideWave shortfall>`), the pin (`PinsLayer.jsx`
~396), both tooltips (`tideTierHeading — tideFitPhrase`) and the dimming CSS
(`index.css` ~5574 `.wf-maplab-chip[data-tide='miss'] { opacity: .72 }`, ~5882 `.wf-pin[...]`) all
key off `spot.tideTier`. So "Off hides every tide cue" and "Auto hides them on a Poor window" is
**one null at that one site**, never a flag threaded to four consumers (§3 M5). There is no
existing on/off switch anywhere — `MapTideStrip.jsx:14` says so in as many words, and this spec
replaces that ruling *on the phone only*.

### 7. The verdict is one channel, and "Poor" is `STAND_DOWN`

The pill's verdict comes from `utils/mapVerdict.js#windowVerdict` over the **scope** pool
(`buildEvVerdicts` → `verdicts[row.id] = {tier, regionName, sharingCount, allInScope,
scopedRegionCount}`), with `tier` one of `DisplayVerdict`'s `WORTH_IT | MAYBE | STAND_DOWN |
AWAITING` and the words from `utils/windowFirstCards.js#VERDICT_LABEL` (*Worth it / Maybe / Poor /
Not scored*). The map-landing increment's first rule — **the verdict moves with scope and never with
a reader filter** — applies to the tide rule too: the clause reads `verdicts[activeRow.id]?.tier`,
the value already on the pill, and nothing else. A row with no verdict (`null` or `AWAITING`) is
**not** "Maybe or better", so in Auto it hides the tide (§5 D-6).

### 8. Rows carry no AM/PM, and the roster has night rows

`utils/mapEvents.js` rows are `{id, kind: 'solar'|'astro'|'aur', eventType, date, dayLabel, time,
bestRating, …}`; morning/evening is `eventType` (`SUNRISE` → `AM`, `SUNSET` → `PM`). Night rows
carry no verdict and no pick, and the pill lists them; the spec's prototype has four solar rows and
no night rows. §4 #3 says how the Windows section treats them.

### 9. "Only one thing open" already has a home: `openMapMenu`

`MapView` keeps every menu mutually exclusive through the single `openMapMenu` value
(`'window' | 'jump' | 'filters' | 'legend' | 'window-panel'`), and `handleMapPaneKeyDown` (~4392)
runs a fixed Escape ladder over it: foreign-modal stand-down → region panel back one level → close
`openMapMenu` → clear the selection. The sheet's section **is a value of that state**
(`'peek:win' | 'peek:tide' | 'peek:lay'`, §5 D-1) rather than a second piece of state beside it,
which gives the spec's rule 3 (one section), exclusivity with the drilldown panels and the phone
Filters/Regions sheets, and the Escape rung, for free.

### 10. A persisted per-user map setting has an exact precedent

`V147__user_map_colour_preference.sql` (`ALTER TABLE app_user ADD COLUMN map_colour_scale
VARCHAR(10)`, nullable, no default, NULL = never chosen), `MapColourPreferencesRequest`,
`UserSettingsController` `@PutMapping("/map-colours")`, `UserSettingsService
.saveMapColourPreferences` (validates against a `Set.of(...)`, writes through the column-scoped
`AppUserRepository.updateMapColourScaleByUsername`, reads the row back), `AppUserEntity
.mapColourScale` (`updatable = false` — the class Javadoc explains why every settings column is),
`UserSettingsResponse.mapColourScale`, `api/settingsApi.js#saveMapColourPreferences`,
`hooks/useReaderSettings.js` exposing it and `colourSaved`. Tide mode copies this shape exactly
(§3 M4). Latest migration is **V154**, so this is **V155** — re-check with
`ls backend/src/main/resources/db/migration | sort -V | tail -1` before writing it.

### 11. `useOutsideDismiss` ignores presses inside the map frame ON PURPOSE

Map-landing L3's rule is *panels persist*: `hooks/useOutsideDismiss.js` skips any `mousedown`
inside `[data-testid="map-container"]`. The peek sheet's rule 4 (any map touch collapses it) is the
**opposite** rule, deliberately — the sheet is chrome, not a panel — and it must not be routed
through that hook or its exception list. It is a `useMapEvents` child inside `MapContainer`
listening to Leaflet's own `mousedown` / `touchstart` / `dragstart` / `zoomstart` (§3 M2). The
drilldown panels keep persisting.

### 12. Two of the spec's colours are not tokens, and one of them was refused before

`#8FC0C7` has no token; `index.css` ~3438 and ~4771 record that the tide bundle's `#8FC0C7` was
deliberately not adopted and `--color-badge-tide` (`#9CCBD1`) used instead. This plan keeps that
ruling (§4 #8). `#6FA8B0` is `--color-tide`; `#1E1712` `--color-plex-panel`; `#3A2C23`
`--color-plex-border`; `#4A3A2E` `--color-plex-border-light`; `#C9A24B` `--color-home`. The spec's
own token note — "inside the map frame, use ≥ .66 on opaque panels for AA" — applies to the sheet,
which is `.97` opaque: the 50 %-ink key labels go to the app's `ink-2`-class alpha (§4 #9).

### 13. The drilldown's only door is the pill menu, and the phone pill will no longer open it

Rule 5 makes the pill body toggle the Windows section on phone. Today the pill body toggles the
`listbox` dropdown, whose footer row `▤ This window, region by region` (`onOpenWindowPanel` →
`openDrilldown`) is the **only** entry to `MapWindowPanel` → `MapRegionPanel`. The Windows section
therefore carries that row as its last row (§4 #4), or the drilldown loses its phone entry.

### 14. Filters has no row in the spec's Layers section

The prose says "the Regions, Heat/Pins and Filters controls … move into this section"; the layout
list and the prototype's `layPane()` have Show / Regions / Tide / hint / legend and no Filters.
Filters carries the **scope** segment ("My area" / "Everywhere"), which is the one control that
moves the verdict, so it cannot be dropped. §4 #5 adds the row.

### 15. `MapCallout` reads the strip's height as an obstacle

`MapTideStrip` publishes `--tsh` and `wf-tide-strip-on` on the pane through a `ResizeObserver`, and
`onHeightChange` feeds `MapCallout`'s placement band. On phone the strip goes; the sheet is the new
obstacle. Because **every** route that opens the callout is a map tap, and every map tap collapses
the sheet (rule 4), the callout only ever has to clear the collapsed 74 px — so the sheet publishes
one fixed `--psh` (peek sheet height) and never a live-measured one (§5 D-7).

### 16. The map is not the phone's first tab

`utils/initialTab.js` opens a phone on **Plan**; the Map tab mounts when tapped. "On load" in the
spec means *when the tab mounts*, and re-mounts count — the 3 s toast fade and the collapsed sheet
are per-mount, and the landing card's per-run key is irrelevant on phone (§1 #3).

---

## §2 Strategy

- **One increment, six code PRs, each shippable.** Nothing on the phone is left without a home
  between PRs: the strip stays until the Tide section replaces it (M3), the bar stays until Layers
  replaces it (M2). M1 is the only phase that removes without replacing (the landing auto-open),
  and that is the spec's own first step.
- **Reuse content, rebuild chrome.** The Windows, Tide and Layers sections are *compositions* of
  what exists (`landingCardModel`, `stripModel` + the strip's header/chart/footer pieces,
  `RegionsJump`, `FiltersPopover`, the `.wf-seg` toggle, the `.wf-map-key` ramp). The one genuinely
  new rendering is the sheet itself and the Tide section's **vertical** layout of the strip's
  content, whose chart needs a taller projection of the same served curve (§4 #6).
- **One gate, one channel, one state.** Tide cues: null `tideTier` at its one site. Verdict: the
  pill's own. Open section: `openMapMenu`. Every phase that is tempted to add a parallel flag has
  a §1 entry saying where the existing one is.
- **Phone-only by construction.** Every change is behind `isMobile` (`hooks/useIsMobile.js`,
  `(max-width: 639px)`) or inside the `@media (max-width: 639px)` block, and the desktop/tablet
  invariance is **pinned by test in every phase** (the existing desktop mounts render byte-for-byte
  as before — `MapViewResponsivePhone.test.jsx`'s "mounts the Legend chip on desktop/tablet" is the
  shape).
- **Backend-heavy stays true.** The only new client derivations are three filter/map/select reads
  over served facts (§4 #11): the "Other windows" button's pick, the tide button's summary string,
  and the AM/PM word from `eventType`. None computes a verdict, a level or a fit.
- **Measure, don't assert.** Every phase's browser step is at a **390 × 844** viewport (the
  built-in browser's `resize_window` with `width: 390, height: 844`), against the §9 fixture, with
  the README's Verify item(s) for that phase stated as *seen* or *tested*.

---

## §3 Phases

Each phase is one Sonnet session in its own worktree, one branch, one PR, squash-merged on green
CI **and** a clean Codex review. Sizes: S ≤ ½ session, M ≈ 1, L ≈ 1½. The branch and changelog slug
are given per phase; the PR title is the changelog heading.

### M1 — Quiet start: no landing card, a fading toast — S

Branch `feature/map-mobile-sheet-m1-quiet-start`; changelog
`changelog.d/YYYYMMDD-map-mobile-sheet-m1-quiet-start.md`, heading `### Map tab — on a phone the
map opens quiet: no landing card, toast fades after 3 s`.

**Tasks**
1. `MapView.jsx`: `landingOpen` becomes `!isMobile && …`; `dismissLanding` is never called on
   phone (nothing to dismiss) and the phone **never writes** `mapLandingSeenRun` (§5 D-3). The
   `landingLabel`/`onReopenLanding` props handed to `WindowControl` are `''`/`null` on phone, so the
   `↺ Back to …` row does not render. The document-level Escape listener for the card (~1840) is
   already a no-op when the card is closed; confirm and leave it.
2. Toast: on phone, add a `wf-map-scored-legend-gone` class 3,000 ms after the toast's key
   (`|date|eventType|`) last changed, via one `useEffect` with cleanup (a window change re-shows
   it for 3 s; a re-mount re-shows it). CSS in the phone block: `top: 62px; left: 50%;
   transform: translateX(-50%); bottom: auto; right: auto; transition: opacity .5s`;
   `.wf-map-scored-legend-gone { opacity: 0; pointer-events: none }`, and `aria-hidden="true"`
   once gone. Under `prefers-reduced-motion: reduce` the transition is dropped (the file's existing
   convention at ~1694/~2876). Desktop: no class, no timer, no CSS change.
3. Update the lifted-stack literal for the toast in `mapPhoneChromeCascade.test.jsx` (it moves
   from `bottom: 152px` to the top).

**Tests** — `MapLandingCard`/`MapView` phone: card absent on phone with an unseen run; present on
desktop with the same fixture; `localStorage.mapLandingSeenRun` untouched after a phone mount;
`WindowControl` receives `landingLabel: ''` on phone. Toast: with fake timers, class absent at
2,999 ms, present at 3,000 ms; cleared on key change; desktop never gains the class. Cascade: the
phone toast rule reads `top: 62px` in the real stylesheet.

**Verify (browser, 390 × 844)** — README Verify 1's first half: on tab mount, no landing card; the
toast visibly fades. Desktop at 1280 wide: landing card still opens once per run; toast stands.

### M2 — The sheet: shell, peek row, Windows and Layers sections, map-touch collapse — L

Branch `feature/map-mobile-sheet-m2-sheet`; changelog slug `map-mobile-sheet-m2-sheet`, heading
`### Map tab — one collapsed peek sheet replaces the phone's floating map controls`.

**Tasks**
1. **`components/map/MapPeekSheet.jsx`** (new, PropTypes, `data-testid="wf-map-peek"` — ⚠️ NOT
   `wf-peek`, which `WindowSpotPeek.jsx` already owns with global `index.css` rules (~7236–7278:
   `position: fixed`, a 280 px width, an entry animation and an `::after` arrow) that a same-named
   sheet would inherit; a Codex finding on M0 — every hook below is `wf-map-peek-*`): an
   in-frame `section` (`role="region"`, `aria-label="Map panels"`) at `position: absolute; left: 0;
   right: 0; bottom: 0; z-index: 1120` inside the map frame — an explicit rung on the map's own
   z-ladder (markers 600, `MapLabels` 650, Leaflet popups 700, the landing card 1050, chrome 1100,
   the tide strip 1120, the drilldown panels 1150), **never the prototype's `520`**, which would let
   chips and popups paint and take input over the open sheet (a Codex finding on M0); the strip's
   rung is free on the phone because the strip is gone there, and the drilldown's 1150 stays above
   for the one render in which both exist. `test/mapChromeZLadderCascade` gains the rung — `.wf-map-peek` collapsed (`height: 74px`) and
   `.wf-map-peek.wf-map-peek-open` (`height: 356px`, **clamped** `max-height: calc(100% - 64px)` so it never
   covers the pill, §4 #7), `transition: height .26s cubic-bezier(.3,.7,.2,1)` (none under reduced
   motion), the handle row (14 px, 36 × 4 bar, `--color-plex-border-light`), the peek row
   (`.wf-map-peek-row`, `gap: 6px; padding: 0 10px 10px`) and the body (`.wf-map-peek-body`, `border-top:
   1px solid --color-plex-border; padding: 4px 14px 14px; overflow: auto`, rendered only when open).
   **No safe-area term of its own**: the app root already carries `.app-safe`, and `index.css`
   ~307–310 records that absolute chrome inside the map frame already sits above that root padding
   — a second inset would double it on the devices that have one and squeeze 104 px of handle,
   buttons and padding into the 74 px box (a Codex finding on M0 against this plan's first cut,
   which had added one). Props:
   `{ section: null | 'win' | 'tide' | 'lay', onSectionChange, windows, tide, layers }` where the
   three are render-props/slots — the sheet owns no data.
2. **Peek buttons** (`.wf-map-peek-btn`, 46 px tall, `border: 1px solid --color-plex-border`, radius
   10, `--color-plex-panel`; key line mono 8.5 px `.1em` uppercase; value line 12.5 px / 600, single
   line, ellipsis): `Other windows` (flex 1; key reads `CLOSE` while its section is open), `Tide`
   (flex 1, tide-tinted — **not rendered at all in M2**: its section body arrives in M3 and its
   rule in M5, and a released control must never open an empty panel (a Codex finding on M0); the
   phone tide strip stays mounted through M2 so the phone keeps its tide summary, and M3 mounts
   the button gated on `stripModel.visible` until M5 replaces that with `tideVisible`), `Layers` (`flex: 0 0 58px`, `☰`). Each carries `aria-expanded` and
   `aria-controls="wf-map-peek-body"`; the active one gets `.wf-map-peek-btn-on` (border
   `rgba(201,162,75,.6)`, bg `rgba(201,162,75,.1)`; the tide one `rgba(111,168,176,.8)` /
   `.16`). Tapping the active button collapses; a different one switches without collapsing
   (rule 2).
3. **"Other windows" value** — `utils/mapPeek.js#otherWindow(events, activeIndex, verdicts)`: the
   first row **after** the active one whose tier is not `STAND_DOWN`, wrapping to the rows before
   it, else the next row of any tier (the prototype's `find(...) ?? o[0]`, made forward-first so it
   names what the `›` step reaches); night rows are eligible but carry `N★ best` in place of a
   verdict word (§4 #3). Rendered as `{dayLabel} {AM|PM}` (truncating, `min-width: 0`) + the verdict
   word in mono 10 px coloured by tier with `flex: none` — the README's Verify 5 (the verdict never
   cuts) is a computed-style assertion, not a visual one.
4. **State**: `openMapMenu` gains `'peek:win' | 'peek:tide' | 'peek:lay'` (§5 D-1). On phone,
   `WindowControl` receives an `onPillPress` override so the pill body toggles `'peek:win'` instead
   of the listbox (rule 5) — the `‹ ›` steps are untouched. ⚠️ The override carries the pill's
   **popup semantics with it** (a Codex finding on M0): today the pill advertises
   `aria-haspopup="listbox"`, `aria-controls="wf-win-listbox"` and derives `aria-expanded` from
   its listbox `open` prop, all of which would be false claims on the phone. The override is a
   small contract — `{ onPress, expanded, controlsId }` — under which the pill drops the listbox
   popup type, points `aria-controls` at `wf-map-peek-body`, and reads `aria-expanded` from whether
   `'peek:win'` is open; tested on the real pill on both viewports. `handleMapPaneKeyDown`'s third rung
   (close `openMapMenu`) already collapses the sheet; `foreignModalOver` already stands it down.
5. **Windows section**: heading = `landingCardModel(...).header` (§4 #2) in 16 px / 700; rows =
   the pill's roster (`events`), each ≥ 48 px, 1 px divider, the pill's own `KindChip` (a
   module-private function in `WindowControl.jsx` today — export it rather than copying its
   markup), `dayLabel` 13.5 px / 600, `time` mono 11 px at ink-3, verdict word right-aligned
   mono 10.5 px / 600 `.1em` (night rows: `{bestRating}★ best`); the selected row `.wf-map-peek-row-on`
   (`rgba(201,162,75,.09)`, `inset 3px 0 0 --color-home`). Tapping selects (`onSelect(row)`) and the
   sheet **stays open** (rule for the Windows rows). Last row: `▤ This window, region by region`
   → `openDrilldown` (§4 #4), which sets `openMapMenu = 'window-panel'` and so collapses the sheet
   by exclusivity; the panel's own focus rule (map-landing's "a panel that REPLACES another must
   move focus") applies.
6. **Layers section** (rows `justify-content: space-between`, label mono 10 px `.1em` uppercase
   at ink-2): **Show** — the existing `.wf-seg` Heat | Pins toggle, 170 × 36; **Regions** — a
   trigger row that sets `openMapMenu = 'jump'` (by exclusivity the peek collapses and
   `RegionsJump`'s phone `BottomSheet` opens — the swap-not-stack rule the phone already has,
   §4 #5); **Filters** — a trigger row that sets `openMapMenu = 'filters'`, same swap (§4 #5);
   **Legend** — `Poor [ramp] Worth it` using the `.wf-map-key` ramp. (The **Tide** segment and its
   hint land in M5; leave a documented slot.) ⚠️ **The two sheet hosts stay mounted outside the
   peek body** (a Codex finding on M0, gregochr/goldenhour#923): `RegionsJump` and `FiltersPopover`
   each render their chip AND their `BottomSheet` portal, and the peek body unmounts the moment
   `openMapMenu` leaves `'peek:lay'` — so a host mounted inside the Layers section would be torn
   down by the very press that opens its sheet. On the phone both components are mounted **once,
   in the pane, outside the sheet**, with their chips hidden (`chipHidden` prop, or an equivalent
   split of trigger from sheet — the session decides, and the test is that the sheet is in the DOM
   after the Layers row is pressed); the Layers rows are plain buttons. ⚠️ **Focus after the
   swap** (a Codex finding on M0): the Layers row that opened Regions/Filters is unmounted in the
   same commit the `BottomSheet` opens, so `useDialogFocus`'s passive capture of
   `document.activeElement` finds `<body>` and a close would return the reader to the top of the
   document. The two hosts are therefore given an explicit **restore target — the Layers peek
   button**, which stays mounted while the sheet is collapsed (`BottomSheet`/`useDialogFocus` gain
   a `restoreFocusTo` ref prop, or the host's `onClose` focuses it — the session picks the smaller
   change and pins open-by-keyboard → close → focus on the Layers button). `.wf-map-chrome-tr` is
   **not rendered on phone**; its desktop mount is unchanged.
7. **Map-touch collapse**: `SheetDismissOnMapTouch` — a `useMapEvents` child inside `MapContainer`
   (only when `isMobile`) on `mousedown`, `touchstart`, `dragstart`, `zoomstart` → if
   `openMapMenu` starts with `'peek:'`, set `null` (rule 4; §1 #11 says why not
   `useOutsideDismiss`). Presses inside the sheet never reach Leaflet, so they do not close it.
   ⚠️ **That listener alone is not enough** (a Codex finding on M0): `MapLabels.jsx` (~263) and
   `PinsLayer.jsx` (~198) both call `L.DomEvent.disableClickPropagation` and their buttons call
   `selectMapLocation` directly, so a chip or pin press never reaches the map listener. The
   collapse is therefore ALSO wired to the **selection itself, not to any one caller**: selection
   is not centralised — the Leaflet marker handler (`MapView.jsx` ~5144), the location and
   structured-handoff effects (~2031, ~2103, the Plan tab's `Show on map` door landing while a peek
   section is open, since the pane stays mounted across tab switches) and the fallback-marker path
   all call `setSelectedLocationName` directly (a second Codex finding on M0). So on the phone one
   effect keyed on `selectedLocationName` collapses any `'peek:'` value whenever a selection is
   **installed**, whatever installed it; the test covers a Heat chip press, a Pins button press, a
   Leaflet `dragstart`, and a `mapTabHandoff` arriving with `'peek:win'` open.
8. **Lifted stack**: with the bar gone, rewrite the phone block's literals — attribution
   `padding-bottom` clears 74 px, the counts footer is **hidden on phone** (§4 #10), the upsell chip
   in `.wf-map-chrome-bl` sits at `bottom: calc(74px + 8px)`, the tide strip's phone rule stays for
   M3 to remove. Publish `--psh: 74px` on `.wf-map-tab` in the phone block and point
   `MapCallout`'s phone band at it (§5 D-7); on the phone a peek press clears the selection first,
   so the band only ever has to clear the collapsed sheet (D-7's reverse-order clause). Rewrite `mapPhoneChromeCascade.test.jsx`'s pairwise
   arithmetic for the new stack.

**Tests** — `MapPeekSheet.test.jsx` (collapsed height class, open class, one body, switch vs
collapse, `aria-expanded`, the `CLOSE` key swap); `mapPeek.test.js` (`otherWindow` all four cases:
forward non-Poor, wrap, all-Poor fallback, night row); `MapView` phone: `.wf-map-chrome-tr` absent
on phone and present on desktop with the same fixture; pill press on phone opens `'peek:win'` and
not the listbox; pill press on desktop still opens the listbox; a Windows row press selects and the
sheet stays open; the drilldown row opens `MapWindowPanel` and the sheet is collapsed; Leaflet
`dragstart` collapses, a press inside the sheet does not (fire on the sheet node — and fire the
key tests at `document.activeElement`, never at a node, per map-landing's four-times lesson);
`MapViewResponsivePhone.test.jsx`'s swap-not-stack cases re-pointed at the new Regions/Filters
routes; the cascade test rewritten. **Desktop invariance**: a snapshot-free assertion that the
desktop tab still mounts chrome-tr, the legend chip and no `.wf-map-peek`.

**Verify (browser, 390 × 844)** — README Verify 1 (74 px, nothing else open — measure
`getBoundingClientRect().height`), 4 (drag collapses), 5 (computed `flex-basis`/`overflow` on the
verdict span), 6 (desktop at 1280: no sheet, bar in place). Windows and Layers open on their
buttons; Regions and Filters swap in their own sheets.

### M3 — The Tide section — M/L

Branch `feature/map-mobile-sheet-m3-tide-section`; slug `map-mobile-sheet-m3-tide-section`,
heading `### Map tab — the phone tide strip becomes the sheet's Tide section`.

**Tasks**
1. Split `MapTideStrip.jsx`'s three subtrees into exported pieces the strip itself keeps
   composing **unchanged** — `TideStripHeader` (state, direction, height, event time),
   `TideDayChart` (the served curve, night shading, dashed high/mid/low rules, the light marker and
   its height), `TideStripFooter` (`footerModel`, `nextFitCopy` jump/beyond). The desktop strip's
   rendered DOM must be identical before and after: pin it with a test that renders the strip on
   the §9 fixture and compares `outerHTML` to a stored string captured **before** the refactor in
   the same PR (a one-off, deleted at M6 — record the deletion).
2. `TideDayChart` takes a `tall` projection (§4 #6): `viewBox` height for ~92 px at full width,
   the spec's HW/LW labels at the served extremes, x-axis `00 06 12 18 24`, dashed rules,
   `--color-tide` curve 2 px `vector-effect="non-scaling-stroke"`, the `#E0A542` event rule, the
   light dot r 4.5 stroked `#161310` 1.5. **The strip's geometry constants stay where they are**;
   the tall variant reads the same served `tide.curve`/extremes and derives nothing (§4 #11).
3. **`components/map/MapPeekTideSection.jsx`**: key `TIDE AT THIS LIGHT` (mono 9 px `.12em`), phase
   line `{STATE} TIDE, {FALLING|RISING}` (mono 13 px / 600 `--color-badge-tide`, from the served
   state/direction via `windowFirstRows.js#STATE_WORD`/`DIRECTION_WORD`) + `{height} m · {time}`
   (mono 10.5 px at ink-2 — the served, pre-formatted strings the strip already prints), the tall
   chart, the dimmed line (`**N coastal spots** are dimmed; they want {want}.` / `No coastal spots
   in view are held back by the tide.` — `footerModel`'s own copy), the next-fit link (mono 11 px
   `--color-tide`, ≥ 32 px, `Next {want} on the light · {window} ›` → `onSelectEv(nextFitRow)`; the
   sheet stays open; if the new window hides the tide, M5's rule closes it). ⚠️ **The link can
   remove itself** (a Codex finding on M0): when the jumped-to window resolves every dimmed spot,
   `nextFitCopy` becomes null and the focused link unmounts with the sheet still open, dropping
   focus to `<body>` where the pane's Escape handler is unreachable. The strip already guards this
   by focusing its stable root before the update (`MapTideStrip.jsx` ~396–401); the extracted
   footer therefore takes a **stable focus target** prop — in the sheet, the Tide peek button —
   and focuses it before calling `onSelectEv`, with a keyboard test for the disappearing-link path
   (activate by Enter, link gone, `document.activeElement` is the Tide button, Escape still
   collapses).
4. **Tide button value**: `utils/mapPeek.js#tideSummary(tide, dimmedCount)` → `{High|Mid|Low}`
   + `↑`/`↓` **only when Mid** (from the served direction) + ` · N dim` when N > 0; rendered after
   the existing `TideWave` glyph in mono 11.5 px `--color-badge-tide`. **This phase mounts the
   Tide peek button** (withheld in M2), gated on `stripModel.visible` until M5 — **and this phase
   also owns the close**: the `‹ ›` steps can land on a night row with the Tide section open, which
   would unmount the trigger and leave `openMapMenu === 'peek:tide'` with nothing behind it (a
   Codex finding on M0). So M3 ships the rule M5 later reuses unchanged: when the button's gate
   turns false while `'peek:tide'` is open, set `null` in the same render and rescue focus to the
   Other windows button if the removed button held it (`useRowFocusRescue`'s pattern). M5 changes
   only what the gate IS, not what happens when it closes.
5. Remove the phone `MapTideStrip` mount (`MapView.jsx` ~5774–5787) and the phone block's
   `.wf-map-tide-strip`/`wf-tide-strip-on` rules; `--tsh` is desktop-only from here (the pane class
   is still set by the desktop strip — leave its effect alone). Desktop mount untouched.

**Tests** — the `outerHTML` pin for the desktop strip; `MapPeekTideSection.test.jsx` (every line
from one `stripModel` fixture, the zero-dimmed sentence, the jump calls `onSelectEv` with the
scanned row, the beyond sentence when `nextFitRow == null`); `tideSummary` (High no arrow, Mid ↑,
Mid ↓, `· 6 dim`, no chip at 0); `MapView` phone: no `.wf-map-tide-strip` on phone, still on
desktop; `MapViewTideStripCalloutWiring.test.jsx` re-pointed (phone band clears `--psh`, not
`--tsh`).

**Verify (browser, 390 × 844)** — README screen 03: Tide section matches the screenshot's
structure (key, phase line, chart with HW/LW labels and the light dot, dimmed line, next-fit link);
the next-fit link moves the pill. Desktop strip pixel-unchanged (compare a screenshot before/after
on the same fixture).

### M4 — Tide mode: the persisted setting — S/M (backend + api + hook; no UI)

Branch `feature/map-mobile-sheet-m4-tide-mode-setting`; slug `map-mobile-sheet-m4-tide-mode`,
heading `### User settings — persisted map tide mode (auto / always / off)`. **Can run in parallel
with M2/M3** (disjoint files); merge `origin/main` in before push, never rebase.

**Tasks**
1. `V155__user_map_tide_mode.sql`: `ALTER TABLE app_user ADD COLUMN map_tide_mode VARCHAR(10);`
   with V147's reasoning in the header — nullable, no default, no backfill; NULL = never chosen =
   Auto on the client.
2. `AppUserEntity.mapTideMode` (`@Column(name = "map_tide_mode", length = 10, updatable = false)`,
   Javadoc pointing at the class rule and the repository writer); `AppUserRepository
   .updateMapTideModeByUsername` (the `map_colour_scale` writer's shape, `@Modifying(clear/flush)`,
   `@Transactional`); `MapTideModeRequest(String mapTideMode)`; `UserSettingsService
   .saveMapTideMode` validating against `Set.of("auto", "always", "off")` (400 otherwise, null
   included — the colour save's null-before-`Set.of` note applies); `UserSettingsController
   @PutMapping("/map-tide-mode")`; `UserSettingsResponse.mapTideMode` (null when never chosen).
3. `HttpCachingConfigTest.personalDataPathsAreNeverFiltered` gains `/api/user/settings/map-tide-mode`
   (the `/api/user/settings*` exclusion already covers it; the test pins it per path).
4. `api/settingsApi.js#saveMapTideMode(mode)`; `useReaderSettings` exposes `mapTideMode`
   (`'auto'` when null) and a `saveTideMode(mode)` action that **serialises** saves the way
   `colourSaveQueue` does for colour (a Codex finding on M0: two quick presses can commit out of
   order, and a stale response can then write the earlier choice back into the hook). The shape:
   a page-lived line — one save in flight at a time, a newer choice queued behind it supersedes
   any older queued one, and a response is applied **only if it answers the newest request**
   (a request counter in the hook), so the UI and the stored value always end on the last press.
   Reuse `createColourSaveQueue`'s mechanics by generalising it over the save function if that is
   a small change; otherwise a sibling `settingSaveLine` with the same rules and the same
   "ends with its owner" clause. Either way the hook's doc names the rule, and the test is the **held-first-request**
   shape (a Codex finding on M0 against this plan's earlier "older request resolves last" wording,
   which a strict one-at-a-time line makes impossible): hold the first save unresolved, assert it
   alone is in flight, queue several newer choices, resolve it, and assert that only the newest
   queued choice is sent and becomes the stored and shown value. **And the line records every
   successful persisted mode as the rollback baseline** (the colour line's `saved`; a Codex finding
   on M0): with `always` in flight and `off` then `auto` queued, only `always` and `auto` are sent —
   if `always` lands and `auto` fails, the server holds `always`, and a revert to `off` (never sent)
   or to the pre-session value would leave the UI and the server divergent. Second test: first save
   succeeds, newest fails, and the shown value AND the hook's record both read the first save's
   mode.
   ⚠️ **The hook has ONE instance, owned by `App.jsx`** (a Codex finding on M0): `mapColourScale`
   reaches the map as props, `App` → `WindowFirstMapPane` (~584) → `MapView`, and `mapTideMode` +
   `saveTideMode` take the **same route in this phase** (threaded, unread by any UI until M5), so
   M5 never calls the hook a second time and creates a second settings read and an independent
   record. Test: `App` passes the value and the action to the pane, and a saved mode is the one
   `MapView` receives on the next render (live-update).
5. CLAUDE.md API section: add the endpoint beside `map-colours`; Migrations table: nothing (the
   table says "latest is deliberately not written down").

**Tests** — `UserSettingsServiceTest`: valid three, invalid/null → 400, writes through the
column-scoped update (verify the repository method, never `save`); `UserSettingsControllerTest`
round-trip; `IntegrationTestBase` subclass or an addition to the existing settings integration
test asserting the column exists (**proven only in CI**, §2 of CLAUDE.md's Docker note — say so in
the PR); `settingsApi.test.js`; `useReaderSettings.test.jsx` (`mapTideMode` default and after a
save).

**Verify** — none in the browser (no UI). Local gate: `./mvnw clean verify -Dtest='!**/integration/**'
-DfailIfNoSpecifiedTests=false` exit 0, plus the frontend four-step gate. The PR's *Backend* job is
the migration's proof.

### M5 — Tide visibility: the Auto rule, the Tide mode control, one gate for every cue — M

Branch `feature/map-mobile-sheet-m5-tide-rule`; slug `map-mobile-sheet-m5-tide-rule`, heading
`### Map tab — on a phone the tide shows only when the light is Maybe or better and the coast is in
view; Tide mode auto / always / off`. Depends on M2, M3, M4.

**Tasks**
1. `utils/mapPeek.js#tideVisible({ mode, tideAvailable, hasCoastalInView, tier })` — the three
   prerequisites are **separate BOOLEAN inputs** (`hasCoastalInView = coastalInView(spots,
   bounds).length > 0` — the helper returns the filtered array, and an empty array is truthy, so
   passing it straight in would keep the tide on after a pan inland; a Codex finding on M0, and
   the truth table includes the empty-array case), never folded into `stripModel.visible` (a Codex finding on
   M0: `visible` is false both for "no coast in view" and for "night row / no served tide", and
   Always must ignore the first while still respecting the second). `tideAvailable = row.kind ===
   'solar' && row.tide != null`; `coastalInView` comes from a **new exported helper**
   `mapTideFit.js#coastalInView(spots, bounds)` — the `s.coastal && bounds.pad(0.12).contains(...)`
   filter that `stripModel` runs internally today, lifted out so there is one definition and
   `stripModel` calls it too (⚠️ `stripModel`'s return exposes `namedCoastal`, not
   `coastalInView` — a second Codex finding on M0; do not reach for a field that is not there).
   `mode === 'off' → false`; `'always' → tideAvailable` (ignores the coast and the verdict, §4
   #12); `'auto' → tideAvailable && hasCoastalInView && (tier === 'WORTH_IT' || tier === 'MAYBE')`.
   Pure, exhaustively tested.
   ⚠️ **Order of evaluation in `MapView`, because there is a cycle to avoid** (the same Codex
   finding): today `stripModel` is built from the *decorated* `labelSpots` — the list that
   carries `tideTier` — and this rule decides `tideTier`. So the gate is computed from the
   **undecorated** spot list (`coastal`, `lat`, `lng` are on the base heat spots before the tide
   decoration; `coastalInView(baseSpots, tideViewBounds).length > 0`), `tideCuesOn` follows, the decoration
   reads it, and `stripModel` runs after, exactly as it does now. A test on the real `MapView`
   wiring (not only the pure function) pins that a coastal spot in view on a Poor window yields
   no `data-tide` — the case a circular or undefined read would silently pass.
2. `MapView.jsx`: `tideCuesOn = !isMobile || tideVisible(...)`; at the spot-build site (~3208)
   `tideTier: tideCuesOn ? tierOf(tide) : null` (§1 #6 — one null, no consumer changes;
   `tideShortfall`/`tideFitPhrase` follow it so the tooltip clause goes too). The Tide peek button
   renders iff `tideVisible`; the close-and-rescue when it turns false while `'peek:tide'` is open
   **already exists from M3** (task 4) and is reused unchanged with the new gate (rule 6's "close
   the sheet").
3. **Pulse**: `prevTideVisible` ref, **armed only after the first bounds-backed evaluation** —
   `tideViewBounds` is `null` at mount and `BoundsTracker` supplies the real bounds from a mount
   effect, so a coastal Worth-it window's first resolved visibility is itself a post-mount
   false → true (a Codex finding on M0); the ref is seeded from the first evaluation made with
   `tideViewBounds != null`, and only transitions after that count. Test: mount with null bounds,
   deliver the first real bounds, assert no pulse; then hide and re-show, assert one. On a false →
   true transition after that, add
   `.wf-map-peek-btn-pulse` (the `@keyframes` box-shadow `0 0 0 0 rgba(111,168,176,.7)` → `0 0 0 10px
   transparent`, 1.2 s, once; removed on `animationend`; none under reduced motion). Not on first
   load.
4. **Layers → Tide row**: a `.wf-seg` Auto | Always | Off (220 × 36), `aria-label="Tide"`,
   selected `rgba(201,162,75,.16)` / `--color-segment-active` (`#EBD9A8`, already the theme's
   active-segment token — the Heat|Pins segment's own rule is the authority) / 600; hint below (mono 9.5 px, ink-3 → ink-2 per §4 #9): `Auto: shown when the light is
   Maybe or better and the coast is in view.` A press goes through the hook's serialised
   `saveTideMode` (M4 task 4 — one save in flight, newest press wins, stale responses ignored),
   which `MapView` receives as a prop from `App` through `WindowFirstMapPane` (M4 wired it; M5 reads
   it — never a second `useReaderSettings` call); the segment moves at once, and a failed save
   reverts to the line's **last successfully persisted mode** (M4's baseline — never to a queued
   choice that was never sent, never to the pre-session value) and announces in the pane's existing
   `role="status"` line.
5. The callout's and the location sheet's `TideFitBlock` are **untouched** by Off (§4 #13, and
   §6 Q2 for the owner).

**Tests** — `tideVisible` truth table (3 modes × {solar/night} × {coastal in view or not} × {4
tiers + null}); `MapView` phone: Poor window → no Tide button, no `data-tide` on any chip or pin,
no dimming; Maybe → all present; Off on a Worth-it coastal view → none; Always on a Poor window →
present; the section closes when the tide hides while open, and focus lands on the Other windows
button; the pulse class appears only on a transition and never on mount; desktop: `data-tide`
present on a Poor window regardless of mode (invariance). Cascade: the pulse keyframes and the
reduced-motion override read from the real stylesheet.

**Verify (browser, 390 × 844)** — README Verify 2 (step to Poor → button gone; pan inland → gone;
coming back → one pulse) and 3 (Off removes glyphs and dimming; Always keeps them on Poor). Persist:
reload keeps the chosen mode (the `GET /api/user/settings` answer carries it). Desktop unchanged.

### M6 — Sweep, docs, the measured Verify list, and the OPEN answers — S

Branch `feature/map-mobile-sheet-m6-sweep`; slug `map-mobile-sheet-m6-sweep`, heading
`### Docs — map peek sheet series complete`.

**Tasks**
1. Run the README's six Verify checks end to end on one fixture at 390 × 844 **and** at 1280 wide,
   and record each as seen/tested in §0's M6 row with the measured numbers (sheet height, map
   visible height with the sheet collapsed — the spec claims ~440 px against ~0 today).
2. Delete M3's one-off `outerHTML` pin (record it). Re-read every §1 line number and correct.
3. CLAUDE.md: a **Map tab on a phone — the peek sheet** bullet under *What's Built* (the sheet, the
   rule, the mode, the one-gate fact, the `openMapMenu` fact, the `BottomSheet`-does-not-fit fact,
   the lifted-stack rewrite); the Backend-heavy bullet gains the **three filter/map/select reads**
   of §4 #11 as members of the *already-licensed* class (not a new numbered class — say so); the
   tide-window bullet's phone paragraph (the strip as a sibling of the chrome on phone, T7) is
   rewritten to say the phone strip is gone and why; the Map tab (v2) bullet's "Phone layout moves
   Regions/Heat-Pins/Filters into a bottom bar" is corrected.
4. `map-tab-v2-plan.md` §6 O-20: note that the peek sheet is **not** a `BottomSheet` and paints
   inside the frame, so arm B's z-10000 case is unchanged by this series (neither better nor
   worse). `tide-window-plan.md`: a §0 note that T7's phone mount was retired here.
   `map-landing-plan.md`: a §0 note that the landing card no longer opens on phone.
5. Answer §6's questions against what shipped; restate the open ones.

---

## §4 Disagreements with the spec, on purpose

1. **`BottomSheet.jsx` is not used for the sheet** (§1 #2). The spec offers it "if it fits". It
   does not, on five counts, and the two that matter most — a body portal that every map Escape
   rule reads as foreign, and a backdrop that blocks the map the sheet is meant to sit over — are
   structural. New `MapPeekSheet`, in-frame.
2. **The Windows heading is the landing card's served-derived header, not a fixed string.** The
   spec fixes "Tonight, or tomorrow?" as copy. `landingCardModel().header` already answers the same
   question and is *right* on a morning when tonight has passed or the roster starts on Saturday; a
   fixed string would be a false claim two mornings a week. The spec's string is what the model
   prints on the day the screenshot was taken.
3. **Night rows are listed, with `N★ best` in the verdict cell.** The prototype's roster is four
   solar windows; the app's pill roster carries astro/aurora night rows. Dropping them would leave
   the `‹ ›` able to reach a window the list cannot name. The cell shows the pill's own licensed
   `bestOfNight` figure (CLAUDE.md's fifth class), never a synthesised verdict word.
4. **The Windows section carries the drilldown row.** (§1 #13.) Without it the phone loses the
   only door to `MapWindowPanel`/`MapRegionPanel`. Opening it collapses the sheet by exclusivity.
5. **Layers carries a Filters row, and Regions/Filters open their existing phone sheets.** The
   spec's prose moves Filters into Layers and its layout omits it (§1 #14); scope lives there, so it
   stays. Pressing either chip opens the component's existing `BottomSheet` and the peek collapses
   — the phone's standing swap-not-stack rule, not a new one. The peek does not reopen when that
   sheet closes (the reader is looking at the map they just filtered).
6. **The Tide chart is the strip's chart in a taller projection, not the prototype's.** The
   prototype interpolates a cosine over mock extremes; the app serves the curve. `TideDayChart`
   gains a `tall` viewBox and the HW/LW/x-axis labels; the curve, bands, night shading and marker
   are the strip's. The strip's own rendering is pinned unchanged (M3 task 1).
7. **The open height is clamped to the frame.** `356px` is the reference frame's figure; on an
   iPhone SE (667 tall) or with Safari's bars expanded the map area is under 420 px and 356 would
   cover the pill. `max-height: calc(100% - 64px)` (pill top 10 + 44 + 10) keeps the pill clear.
8. **`#8FC0C7` → `--color-badge-tide` (`#9CCBD1`).** The same substitution the tide-window series
   made and recorded in `index.css` (~3438, ~4771); two tide-cyan texts on one screen would be worse
   than one shade off the spec.
9. **50 %-ink labels on the opaque sheet use the app's ≥ .66 alpha.** The spec's own token note
   requires it for AA inside the map frame; the sheet is `.97` opaque. Key lines (8.5 px, 9 px,
   9.5 px) are still small — an accessibility lens should measure contrast at M2 and M5, and the
   result goes in §0.
10. **The counts footer is hidden on the phone.** The spec says nothing else floats on the map;
    the footer is not one of the three things it names. The tide-window handoff had already hidden
    it under the strip on phone. Its "N in reach / beyond" statement has no phone home after this —
    §6 Q3 asks whether it needs one.
11. **Three new client reads, all filter/map/select over served facts, none a derivation**:
    `otherWindow` (a scan over served tiers), `tideSummary` (served state/direction words plus the
    strip's own dimmed count), and `AM`/`PM` from `eventType`. They join the *already-licensed*
    class in CLAUDE.md's Backend-heavy bullet (the `comingUpFeed.js`/`dawnRace.js` precedent), not
    a new numbered one; M6 says so in CLAUDE.md.
12. **Always still needs a tide.** "Always ignores both conditions" cannot draw a chart for a
    night row with no served tide; Always ignores the verdict and the coast-in-view test and
    nothing else.
13. **Off does not touch the callout's or the location sheet's `TideFitBlock`.** Those answer a
    question about one place the reader asked about, and the location sheet is also a Plan-tab
    surface. Off hides the *map-surface* cues: button, section, chip glyph, pin dimming, tooltip
    clause. §6 Q2.
14. **The seen-stamp is not written on phone** (§5 D-3) — the spec does not say, and the honest
    reading of "the landing card does not auto-open on phone" is that it was never shown.

---

## §5 Decisions taken in this plan (challenge in review, not in code)

- **D-1** The open section is a value of `openMapMenu` (`'peek:win' | 'peek:tide' | 'peek:lay'`),
  not new state. Gives rule 3, exclusivity with every other map surface and the Escape rung for
  free; the cost is three string cases in the existing switch.
- **D-2** Tide mode is **server-persisted** (V155, `PUT /api/user/settings/map-tide-mode`), copying
  V147/`map-colours` exactly. The spec allows localStorage; the app's every other per-user map
  setting is on `app_user`, localStorage is per-device, and the pattern costs one small phase. The
  fallback, if the owner prefers no migration: `useLocalStorageState('mapTideMode', 'auto')` and
  M4 collapses into M5 — say so before M4 starts.
- **D-3** The phone never writes `mapLandingSeenRun`.
- **D-4** The 3 s fade is phone-only; desktop keeps the standing legend.
- **D-5** The Tide button's `N dim` and the section's `N coastal spots` are both `stripModel
  .dimmed.length` — one population. The spec's "inside the current bounds" reads as the padded
  view the gate already uses; two paddings would give two counts for one word.
- **D-6** In Auto, a null or `AWAITING` verdict hides the tide. "Maybe or better" is a positive
  test; unscored is not better.
- **D-7** The sheet publishes a fixed `--psh: 74px` obstacle, never a measured height, because
  **the callout and an open sheet never coexist on the phone**, in either order: every
  callout-opening route is a map tap and every map tap collapses the sheet; and the reverse —
  select a spot, then press a peek button — **clears the selection** (`selectedLocationName` is
  independent of `openMapMenu`, so without this the callout would sit placed for 74 px under a
  356 px sheet — a Codex finding on M0). A peek press on the phone therefore closes the callout
  before the sheet grows, and any INSTALLED selection collapses an open section (an effect on
  `selectedLocationName`, because selection has several writers — M2 task 7); the test pins both
  orders. Desktop keeps its live `--tsh` band.
- **D-8** The pill's dropdown listbox is unreachable on the phone after M2 (the pill toggles the
  Windows section). Everything it held is in the section (rows, drilldown) or withheld on phone
  anyway (the landing reopen row).

---

## §6 Owner decisions / OPEN items

- **Q1 — Persistence.** D-2 recommends the server column. Confirm, or take the localStorage
  fallback (no backend, no migration, per-device).
- **Q2 — Does Off reach the callout/sheet `TideFitBlock`?** Plan says no (§4 #13). If the owner
  wants Off to mean *no tide anywhere on the phone*, that is one more null at the block's two mounts
  and a decision that the location sheet on the Plan tab also reads the mode.
- **Q3 — The counts footer has no phone home** (§4 #10). Options: leave it hidden; add a last row
  to Layers; put the reach line under the Windows heading. Plan: hidden until asked.
- **Q4 — Does the mode belong in the desktop settings dialog too?** Not in this spec (desktop is
  "not a mode"). A saved Off on the phone has no desktop effect. Record or extend.
- **Q5 — The 8.5 px key labels.** Below the app's smallest existing mono size; the accessibility
  lens at M2 measures them. If they fail AA at ink-2 the fix is 9 px, not a different alpha.
- ~~Q6 — Safe-area inset.~~ **Settled at planning**: the app root's `.app-safe` already carries the
  bottom inset and `index.css` ~307–310 says in-frame map chrome needs no term of its own; the
  sheet adds none (§3 M2 task 1).

---

## §7 Verify by measurement — the README's six checks, and how each is measured

| README check | phase | how |
|---|---|---|
| 1. First load at 390 × 844: sheet 74 px, nothing else open | M1 (card, toast), M2 (sheet) | `document.querySelector('.wf-map-peek').getBoundingClientRect().height === 74`; no `.wf-land`, no `[data-testid=wf-map-peek-body]`, no `.wf-map-chrome-tr`, no `.wf-map-tide-strip`; toast opacity `0` after 3.5 s |
| 2. Coastal + Worth it → Tide shows; Poor → gone; inland → gone | M5 | step the pill with `›` to a Poor window and assert the button is absent; `map.panTo` inland and assert; back and assert the pulse class appeared once |
| 3. Off removes glyphs and dimming; Always keeps them on Poor | M5 | count `[data-tide]` chips/pins under each mode; computed `opacity` of a coastal chip |
| 4. Dragging with the sheet open collapses it | M2 | dispatch a Leaflet `dragstart` (or a real drag via the browser tool) and measure height back to 74 |
| 5. "Other windows" never cuts the verdict | M2 | computed `flex-basis: auto; flex-grow: 0` (i.e. `flex: none`) on the verdict span and `scrollWidth === clientWidth`; the day span carries `text-overflow: ellipsis` |
| 6. Desktop and tablet unchanged | every phase | at 1280 and 768 wide: no `.wf-map-peek`; chrome-tr, legend chip, tide strip and landing card exactly as before (screenshot diff on the same fixture) |

---

## §8 Phase → session map, and how the chain is run

| phase | size | depends on | parallel with |
|---|---|---|---|
| M1 quiet start | S | — | M4 |
| M2 sheet + Windows + Layers | L | M1 (toast/lifted stack) | M4 |
| M3 Tide section | M/L | M2 | M4 |
| M4 Tide mode setting (backend) | S/M | — | M1, M2, M3 |
| M5 Tide rule + mode control | M | M2, M3, M4 | — |
| M6 sweep | S | M5 | — |

**Per phase (the orchestrator — this session — does everything outside the worktree):**
1. Launch a Sonnet session with the phase's prompt from `map-mobile-sheet-prompts.md`, in a fresh
   worktree off up-to-date `main`. The session builds, gates, reviews, fixes, commits. It never
   pushes.
2. Push the branch, open the PR (title = the changelog heading; body = the phase's tasks, what was
   seen vs tested, and the CLAUDE.md attribution footer). Turn **off** `auto_archive_on_close` on
   the PR monitor, or the session is archived at merge and cannot take follow-ups.
3. Wait for CI (`Backend — Build, Test & Coverage`, `Frontend — Lint, Test & Build`) and the Codex
   review (`chatgpt-codex-connector[bot]`: 👀 while reviewing, 👍 when clean, inline P1 comments
   otherwise). Route every finding back to the **same** session with a decision attached; reply on
   and resolve each thread; push the fix; request a re-review.
4. Squash-merge on green + clean Codex. Next phase off the new `main`.
5. Sessions that hang waiting for a background notification are the known failure mode: every
   prompt says gates run in the foreground with a `until grep -q '^exit:'` loop and the browser
   step has a wall-clock budget.

---

## §9 Local verification recipe

The memory recipe (backend on **8083** with `./mvnw -Plocal-dev spring-boot:run
-Dspring-boot.run.profiles=local`; `frontend/.env.local` with `VITE_API_TARGET=http://localhost:8083`;
`scripts/dev-seed-locations.sh`; JWT seeded into `localStorage` — never type the password into the
page) applies. This series needs, in addition:

1. **A coastal spot in view with a tide want**: `INSERT INTO location_tide_type (location_id,
   tide_type) SELECT id, 'HIGH' FROM locations WHERE name = 'Bamburgh Beach'` (or another named
   coastal seed), plus a 12h25 cycle of `tide_extreme` rows (UTC `event_time`) covering T through
   T+3.
2. **Two verdicts**: `cached_evaluation` rows giving the region ≥ 3.5 mean on one window (Worth it)
   and ≤ 2.4 on another (Poor), for the non-coastal spots too; restart (rehydrated at startup);
   `POST /api/briefing/run`; confirm `GET /api/briefing` shows `displayVerdict` WORTH_IT and
   STAND_DOWN on the two windows before opening the browser.
3. **Viewport**: the built-in browser at `width: 390, height: 844` (the spec's frame), then
   `preset: desktop` for check 6. The Map tab is reached by tapping the tab (the phone opens on
   Plan).
4. ⚠️ **Never trigger `POST /api/forecast/run`** — it bills the real Anthropic key. Everything
   above is deterministic and needs no key.
