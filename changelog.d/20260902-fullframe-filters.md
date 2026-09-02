### Changed — the Map tab owns the whole frame, filters move into a popover (map-v2 P7)

The Map tab's filter drawer — the ~380px block that used to sit above a 500px map — is gone. A new
`components/map/FiltersPopover.jsx` puts every one of its rows (minimum rating, subject chips, a
drive-from-origin segment now offering the design's named tiers, dark-sky-only, and — new — a
"My area" / "Whole catalogue" scope segment moved out of the old toolbar) behind a single chip
(`Filters (N) ▾`) that opens a 318px panel over the map. `N` counts active filters only; switching
scope never counts, because it reframes the camera rather than hiding anything. The panel's own
footer states "N of M shown" and offers "Clear all" (everything but scope). Opening the popover
closes the window control's own dropdown and vice versa — the two now share one exclusivity
switch — and clicking empty map closes whichever is open. Inbound handoffs that used to open the
old drawer (a Hot Topic pill tap, a Coming-up chronology card) now open this popover instead.

The map itself now fills the whole frame below the masthead and tab bar, with no page scroll. Four
things had to move together: `App.jsx` learns which Plan tab is active (a new `onTabChange` from
the shell) and recasts its whole page as a flex column on the Map tab (root: `height:100dvh;
overflow:hidden`; `<main>`: `flex:1; min-height:0`), suppressing its own outer padding on that tab;
`WindowFirstShell`'s 1080px width constraint now wraps only the masthead and tab bar — the panel
region below it releases to full width on the Map tab alone, so the masthead and tab bar never
shift on a tab switch, and both the shell root and that panel region become `flex:1; min-height:0`
links in the same flex column; the Map tab's own panel (`.wf-body.wf-body--map`) is the column's
last growing item, computing no height of its own; and `MapView`'s old fixed `MAP_HEIGHT_PX` is
gone in favour of `flex:1; min-height:0` the rest of the way down. The Plan-tab overlay keeps its
exact old drawer, fixed heights and toolbar, and every other Plan tab keeps today's ordinary
document flow and page scroll — none of this reaches either.

With the frame now full-bleed, the window control moves onto the map itself as top-left chrome; the
Heat/Medallions segment and the new filters chip form a top-right cluster (a Regions jump list has
a ready-made third slot there, for a later phase); the colour-scale notice, the LITE
aurora-viewline upsell chip and the scored-locations chip are re-homed to the corners the new
chrome leaves free; and a new counts footer (bottom-centre) reads "N named · M rated of K", a
`filtered` flag, and either the regions beyond a 3-hour drive (My area) or a whole-catalogue
caveat. Every new chip adopts the design's z-ladder: chrome at 1100, the two dropdown/panel menus
at 1500 so neither is ever hidden under a sibling chip. The zoom control and "centre on home" both
move to bottom-right, per the design's own layout — moved imperatively (`map.zoomControl.
setPosition`), not via `react-leaflet`'s `<ZoomControl>` swap, because every one of the eighteen
test files that mocks `react-leaflet` mocks it down to a handful of exports and none carries
`ZoomControl`.

Three adversarial review rounds surfaced nine confirmed findings (four real after
de-duplication; one refuted), one upgraded by the orchestrator's own live measurement, and one
more from a second live re-measurement after the first fix:

- **The mobile cascade catch.** `.wf-body--map { padding: 0 }` and the phone media query's own
  `.wf-body { padding: 12px … }` are both plain class selectors — equal specificity, later source
  wins — so every phone carried a padded band around the "full-bleed" map, invisible on desktop
  where no media query competes. Fixed with the specificity bump `.wf-body.wf-body--map`, robust
  against either rule being reordered later, and pinned by a sliced-stylesheet test that reproduces
  the selector contest directly (jsdom resolves specificity but not the `@media` condition itself).
- **The live footer proof, and the 16px it didn't fix.** Measured at 1280×800 with zero banners
  showing, the app-wide footer alone overflowed the full-frame page by 99px and clipped the map's
  bottom edge. The first fix suppressed the footer and added a THIRD measured term
  (`--wf-banner-h`, alongside `--wf-mast-h`/`--wf-tabbar-h`) to a `calc(100dvh - …)` height chain —
  and a second live re-measurement found 16px of scroll still surviving with every banner gone: an
  inter-element margin between the tab bar and the panel that a `ResizeObserver` on element BOXES
  structurally cannot see. Two leaks from the same class of gap retired the whole mechanism rather
  than adding a fourth term: the height chain (and the `useTabBarHeight`/`useElementHeightVar`
  hooks that measured it — both deleted, dead code with no remaining consumer) is replaced by the
  flex-column recast described above, which asks the browser to lay the column out rather than
  reconstructing its answer by hand.
- The admin stand-down/unknown toggles get scope's exact treatment on adjudication — present,
  sticky, uncounted — since they are debug lenses that widen the pool back out, not reader filters:
  excluded from the filters chip's count, the counts footer's `filtered` flag, and Clear all alike.
