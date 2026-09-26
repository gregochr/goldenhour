### Added — one collapsed peek sheet replaces the phone's floating map controls

Phase M2 of `docs/engineering/map-mobile-sheet-plan.md`. On a phone (`hooks/useIsMobile.js`) the
Map tab's window pill dropdown, the Regions/Heat-Pins/Filters bar and the standing tide strip are
replaced by one new `components/map/MapPeekSheet.jsx` — an in-frame, backdrop-free section (never
`components/BottomSheet.jsx`, which has no collapsed state, portals behind a map-blocking backdrop
and reads as a foreign modal to every Escape rule on the tab) that starts collapsed at 74px with
two summary buttons (Other windows · Layers — the Tide button and section arrive at M3) and opens
to 356px on one section at a time, clamped to the frame (`max-height: calc(100% - 64px)`).

The pill body opens the sheet's Windows section on the phone instead of the desktop listbox
(`WindowControl`'s new `pillOverride` prop swaps its popup semantics along with it — no
`aria-haspopup="listbox"`, `aria-controls` pointed at the sheet's own body, `aria-expanded` read
from whether the section is open). The Windows section heading is the served-derived
`landingCardModel().header`, never a fixed string; its rows are the pill's own roster, with night
rows showing the licensed `{bestRating}★ best` figure in place of a verdict word; its last row is
the only phone entry to the region drilldown, which collapses the sheet by `openMapMenu`
exclusivity alone. The Layers section carries Show (Heat/Pins), Regions and Filters trigger rows
and the ramp legend; `RegionsJump`/`FiltersPopover` gain a `chipHidden` prop and stay mounted
outside the sheet's body so their `BottomSheet` stays reachable once the row that opened it
unmounts, with a new `restoreFallback` prop (threaded through to `useDialogFocus`) returning focus
to the Layers button on close.

The sheet collapses on any Leaflet `mousedown`/`touchstart`/`dragstart`/`zoomstart`
(`SheetDismissOnMapTouch`, deliberately not `useOutsideDismiss`, whose whole rule is the opposite —
panels persist through a map touch) and whenever a selection installs by any route (a new effect
keyed on `selectedLocationName`, since chips/pins/handoffs all write it directly and stop click
propagation before the map listener ever sees them); a peek press clears the selection first, so
the callout and an open section never coexist in either order. The phone's lifted stack (attribution,
the LITE viewline-upsell chip) is rebuilt against the sheet's own collapsed height instead of the
retired bar, and the counts footer — with no phone home left at all — is unconditionally
`sr-only`-clipped rather than lifted; `MapCallout`'s phone band now reads a fixed `--psh: 74px`
custom property instead of measuring a live rect, since the callout and an open sheet never
coexist. `mapPhoneChromeCascade.test.jsx` is rewritten (not deleted) for the new two-row stack.

**Adversarial review (four read-only lenses): all real findings fixed before the commit landed** —
a stale doc comment claiming the phone tide strip sat at its own `z-index: 1120` rung (it is
chrome-tier, 1100, unchanged by the phone media query) corrected; a `MapPeekWindowsSection` test
asserted only that the active row carried the `.on` class, never that its sibling did not; no test
proved the Tide button is absent in M2 (task 2's explicit "never render an empty panel" rule); no
test proved `aria-expanded` tracks the same pill through a real open→close cycle rather than two
separate mounts; and no test exercised D-7's reverse-order clause (a peek press clearing an
existing selection before growing the sheet) — all five closed. The accessibility lens measured the
peek row's 8.5px key labels at `~7.1:1` contrast against the sheet's own background — comfortably
clears WCAG AA for small text.

**Browser (390 × 844 and 1280 wide, SEEN AND MEASURED)**: phone — sheet exactly 74px collapsed,
356px open; the pill's `aria-haspopup` absent and `aria-controls="wf-map-peek-body"`; the Windows
section shows the served header and per-tier verdict colours; the Layers section's Regions row
opens the real `BottomSheet` with the peek sheet gone, and both a real Escape press and the ✕
button return focus to the Layers peek button; a real mouse drag on the map collapses an open
section; the verdict span in the "Other windows" button computed `flex: none`. Desktop — the
landing card, the Regions/Heat-Pins/Filters bar, the Legend chip and the counts footer render
exactly as before, no `.wf-map-peek` anywhere.
