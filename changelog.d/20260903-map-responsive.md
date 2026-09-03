### Changed — the Map tab gets its own phone layout, and a full a11y sweep (map-v2 P12)

**Phone chrome re-arrangement**, scoped entirely to a new `.wf-map-tab` class on the tab's own root
wrapper (`MapView.jsx`) — never present on the Plan-tab overlay's mount, so every rule below is a
descendant selector under it and cannot reach the overlay at any viewport. Under the app's own
`useIsMobile` breakpoint (≤639px — carried rather than the design bundle's literal 390px iPhone
figure, per the plan's own reconciliation note):

- The window control (`WindowControl.jsx`) goes full-width across the top — `.wf-map-chrome-tl`
  spans edge-to-edge, the pill grows to fill the space its fixed-width steppers don't
  (`flex: 1; justify-content: center`), and its dropdown widens to match rather than staying pinned
  to its desktop 334px (`right: 0; width: auto` — plus `max-width: none`, below).
- The Regions / Heat-Pins / Filters cluster (`.wf-map-chrome-tr`) becomes a thumb-reachable
  **bottom bar** — row direction, `justify-content: space-between`, the three top-level children
  (`RegionsJump`, the Heat/Pins toggle, `FiltersPopover`) sharing it equally via `flex: 1`. The
  Heat/Pins cluster keeps its desktop COLUMN stacking (segmented toggle above the ramp key), just
  re-centred for its new position mid-bar rather than a top-right corner.
- **`FiltersPopover.jsx` and `RegionsJump.jsx` now render their panel as a `BottomSheet` on the
  phone** instead of the desktop's positioned popover — the exact same row markup in both shapes
  (extracted into a shared `panelBody`/fragment so a row can never drift between viewports), wrapped
  differently. Both pass the shared `BottomSheet` a new `modal={false}` prop (below) since these are
  disclosure widgets standing in for a dialog, not dialogs themselves, and both drop their
  desktop-only outside-click listener on the phone — `BottomSheet` already dismisses on its own
  backdrop, and its content is portalled outside the trigger's own DOM subtree, so leaving that
  listener live would close the sheet on the very first tap inside it. `Escape` still closes either
  sheet: the desktop `onKeyDown` handler is unmoved, and a key event fired inside a portalled sheet
  still bubbles through the REACT component tree to it regardless of where the DOM put the node.
- `BottomSheet.jsx` gains a `modal` prop, defaulting to `true` (byte-identical for every existing
  caller) — `false` omits `aria-modal` only. **This is an ARIA-semantics change, not a behavioural
  one**: the full-viewport backdrop still catches every pointer event and still dismisses on tap,
  and body scroll still locks while the sheet is open, regardless of `modal` — deliberate, since a
  disclosure-widget sheet standing over a pannable map letting the map pan through its own backdrop
  would be new behaviour, not a straight `aria-modal` swap, and stayed out of this prop's scope.
  Full-bleed (`left-0 right-0`), not the design bundle's own `left/right: 10px` inset — one shared
  layout every `BottomSheet` caller already agrees on, over a second inset-only variant.
- The Legend chip/panel (`MapLegendPanel.jsx`) is withheld entirely on the phone — `MapView.jsx`
  simply does not mount it, rather than hiding it with CSS. **The ramp key row survives** next to it
  being gone, though (below) — hiding both left no colour key anywhere on the phone.
- Leaflet's native zoom control **and** `CentreOnHomeControl`'s `⌂` (`.map-home-control`) are both
  hidden, pinch taking over for zoom (see the adversarial-review section below for why `⌂` joined).
- The counts footer lifts above the bottom bar and drops its second line (the "Beyond 3h…" area
  note) — the first line's "N named · M rated of K" stays.
- `MapCallout.jsx` renders at 266px on the phone instead of 286px (README §7) — the every-window
  strip's default-collapsed state (`stripOpen`'s own `useState(false)`) was already universal, not a
  new phone-specific gate; the generic anchor/band maths (unchanged) already keep the card clear of
  the new bottom bar, since a bar spanning ≥50% of the frame's width already counted as a floor/
  ceiling before this phase (`utils/mapCallout.calloutBand`) — verified rather than reworked, and
  confirmed live (below).
- Region-name truncation below 430px frame width and the touch-target minima (30px chips / 32px
  segments / 36px desktop / 40px mobile pills) were already shipped in earlier phases; this phase
  verifies both, adding the touch-target cascade pins and an explicit region-truncation regression
  check that were missing rather than rebuilding either.

**A11y sweep.** Every popover trigger (`WindowControl`, `FiltersPopover`, `RegionsJump`,
`MapLegendPanel`) now carries `aria-controls` naming its own panel/menu's `id`, alongside the
`aria-expanded` each already had — a genuine, consistent gap across all four, closed the same way
on all four. None carry `aria-modal` and none call `useDialogFocus`, so all four were already
disclosure widgets rather than dialogs; this phase only adds the missing half of that contract. The
Map tab's z-ladder cascade test (`mapChromeZLadderCascade.test.jsx`) gains the TOOLTIP tier
(`.wf-maplab-tip`, shipped at P8 but never asserted) and the full "menus > tooltip > callout >
chrome" chain the plan's own restatement asks for. The canvas layers' `aria-hidden` treatment and
their location chips'/pins' real `aria-label` text equivalents were already correct (P8/P10); this
phase adds the missing chip-level `aria-label` regression tests (`MapLabels.test.jsx`) alongside the
existing canvas/pin ones.

**The double-BottomSheet hazard, re-checked now the tab opens sheets itself.** The tab's new sheets
are driven purely by `MapView`'s own `openMapMenu` state — the SAME exclusivity mechanism that
already governed the four popovers (opening one closes the others) — so a swap is structural, not a
new rule: `FiltersPopover`/`RegionsJump` each unmount their own sheet the instant their `open` prop
goes false, and since both flip off the one `openMapMenu` state variable, the two portals can never
both be mounted in the same commit. Unlike the pre-existing overlay-vs-tab hazard (a PROP-driven
bug: the tab's never-unmounted `MapView` instance answering an App-level handoff meant for the
overlay), nothing here is reachable from outside this tab's own chrome — `FiltersPopover`/
`RegionsJump`/`WindowControl`/`MapLegendPanel` are all gated `!overlayMode`, so the overlay's own
mount cannot raise a sheet on any viewport, structurally. Proven live: opening Filters while Regions
was open swapped the sheet's content in place with no second portal ever mounted, in both
directions.

**Adversarial review round — 7 confirmed findings fixed, 1 refuted.** The live 390px pass verified
no-scroll (`780/780` — the frame's own height matched the viewport with nothing to scroll), the
bottom bar's layout, the callout's clearance of it (≥37px at three locations, collapsed and
expanded), and the sheet swap — but the pass itself initially probed only `.leaflet-control-zoom`
and missed a second control sharing that corner:

1. **BLOCKING** — `CentreOnHomeControl`'s `⌂` (`.map-home-control`) sits in the SAME Leaflet
   bottom-right corner as the zoom control (`ZoomControlPositioner`'s own `bottomright`), which the
   new bottom bar's footprint now covers entirely: at Leaflet's built-in corner z-index (1000),
   UNDER the bar's z-1100, its rect sat inside the Filters chip's own clickable rect — invisible
   AND untappable, not merely crowded. Fixed by hiding it alongside the zoom control, the bundle's
   own `.zoomg` treatment (its `⌂` lived in that same hidden group). Its two affordances survive on
   the phone elsewhere: resetting scope to My area is the Filters sheet's own scope segment
   (README §4), and the postcode nudge is the masthead's own empty state.
2. Both ramp legends vanished on phone (the mini "Poor … Worth it" key newly hidden alongside the
   Legend panel already being gone) — no colour key anywhere. Fixed by keeping the mini key row: it
   shrinks (8px type, a 26px swatch, tighter padding, `flex-wrap: wrap` for the rare long "not
   scored" text) rather than disappearing — the bundle's own real-estate concern was the SEGMENTED
   row's width, not this narrow mono line.
3. The bottom-left chrome (`.wf-map-chrome-bl`, the LITE viewline-upsell host) overlapped the new
   bottom bar. Fixed by lifting its `bottom` offset to clear the bar's own documented height
   estimate plus a gap, rather than hiding a monetisation surface.
4. `BottomSheet`'s `modal={false}` doc comment overclaimed "never any behaviour" — the backdrop and
   scroll-lock were never conditional on it and were never meant to be. Fixed the CLAIM, not the
   behaviour: the prop's jsdoc (and this entry, above) now states plainly that it is ARIA-semantics
   only.
5. Recorded, not changed: full-bleed sheets vs. the bundle's `left/right: 10px` inset is a
   deliberate shared-component-consistency trade-off, now a one-line note in `BottomSheet`'s class
   doc.
6. The phone `.wf-win-menu` rule set `right: 0; width: auto` but the base rule's own
   `max-width: calc(100vw - 32px)` — a different property `width: auto` never touches — still
   capped it short of the full-width pill above. Fixed with an explicit `max-width: none`.
7. Added the `Escape`-closes-the-sheet case to both phone describes (`FiltersPopover.test.jsx`,
   `RegionsJump.test.jsx`).

Tests: a new `mapPhoneChromeCascade.test.jsx` (the phone chrome rules against the real `index.css`,
extending the jsdom-cascade technique to reach one level inside `@media` blocks — its own class doc
records a real bug the file's first draft caught in itself, where concatenating two overlapping
single-needle extractions silently reordered a shared base rule ahead of its own override — plus
the review round's own new cases: `.map-home-control` hidden/visible, the ramp key's shrink-not-hide,
the bottom-left chrome's clearance arithmetic, and the dropdown's `max-width: none`); extended
`mapChromeZLadderCascade.test.jsx` (the tooltip tier); a new `MapViewResponsivePhone.test.jsx` (the
`.wf-map-tab` scoping class present on the tab and absent on the overlay, Legend withheld on the
phone, the counts-footer CSS hook, and — with the real `BottomSheet`, not mocked — the
swap-not-stack exclusivity in both directions plus backdrop dismissal); new phone/BottomSheet
describes in `FiltersPopover.test.jsx` and `RegionsJump.test.jsx` (sheet vs. popover, `modal={false}`,
the disabled outside-click listener, `Escape`); a new `modal={false}` case in `BottomSheet.test.jsx`;
new `aria-controls` cases in `WindowControl.test.jsx`, `MapLegendPanel.test.jsx`,
`FiltersPopover.test.jsx` and `RegionsJump.test.jsx`; new `aria-label` regression cases in
`MapLabels.test.jsx`; and a new phone-width describe in `MapCallout.test.jsx`. No existing assertion
changed except where a testid gained an `id` sibling attribute (additive) — every desktop/tablet
suite still mocks `useIsMobile` to `false` (or never mocks it at all, relying on the same default),
and stays green unmodified.

**PR #741 review round — two more instances of the SAME collision class, plus a sweep to end it.**
CI was green, but Codex flagged two elements this phase's own review had not yet reached, both
things sitting at the bottom of the map frame that the new bar's full width now covers:

1. The scored-locations chip (`photocast-scored-legend`, desktop `bottom: 8px; right: 54px` —
   clearing the OLD corner column, not the new full-width bar) painted over and intercepted taps on
   the Heat/Pins and Filters segments beneath it. Lifted to the same `76px` clearance
   `.wf-map-chrome-bl` already uses (`.wf-map-scored-legend`, a new pure-CSS hook class), not hidden
   or folded into the counts footer — its text is not duplicated anywhere else on the phone.
2. Leaflet's own attribution control had its upper half buried under the bar. It shares
   `ZoomControlPositioner`'s `bottomright` corner with the now-hidden zoom control and `⌂`; Leaflet
   stacks controls within a corner via `float` + `margin-bottom` on each CONTROL rather than the
   corner container, so with its two neighbours gone the attribution collapsed to sit flush at the
   corner's own `bottom: 0`. Fixed with `padding-bottom: 76px` on `.leaflet-bottom.leaflet-right`
   (the same selector `MapCallout.jsx`'s own `LEAFLET_CORNER_SELECTOR` already reads) — a licensing
   requirement, so it lifts rather than hides, and the P3 quieting styles on
   `.leaflet-control-attribution` itself (a different selector) are untouched.
3. **The sweep.** Every `bottom-*`/`bottom:` rule reachable from the map tab was enumerated
   (`.wf-map-chrome-bl`, `.wf-map-counts-footer`, the two fixes above, plus everything ruled OUT:
   the now-hidden zoom/`⌂`, the phone-absent Legend, the mouse-only hover tooltip, the callout's own
   dynamic band already proven against a ≥50%-width bar, and the WindowControl dropdown/BottomSheets
   which are top-anchored or cover the whole frame rather than being passive bottom-band residents —
   recorded in full in `mapPhoneChromeCascade.test.jsx`'s own class doc). The counts footer's
   existing `60px` lift was 4px short of the same clearance formula every OTHER lifted element uses
   once the bar's assumed height grew to account for the restored ramp key — revised to `64px` for
   consistency. One new geometry test renders every candidate "active at once" (a scored solar
   window, a LITE reader mid aurora alert, an active filter) and asserts each clears the bar's own
   rect, arithmetically (jsdom computes no real layout, so this pins the declared CSS values plus
   documented height estimates against a fixed, live-measured 780px frame height) — the test meant
   to make a third Codex round have to find something genuinely new.

Tests (this round): 6 new cases in `mapPhoneChromeCascade.test.jsx` (the scored-legend and
attribution lift/no-lift pairs, the attribution's P3 styling staying untouched, and the sweep's own
consolidated geometry check) plus the counts-footer assertion's revised `64px`. Full suite:
205 files / 4894 tests, green.
