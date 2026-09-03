### Added — the Regions jump list, the masthead statement, and ⌂'s new meaning (map-v2 P11)

**Regions jump list.** A new `components/map/RegionsJump.jsx` — `◎ Regions ▾` joins the top-right
chrome cluster above Heat/Pins and Filters, matching the design's own layout order, and its open
dropdown joins the popover-exclusivity group and the label layer's obstacle list (`wf-jump-menu`,
seeded in `MapLabels.jsx`/`PinsLayer.jsx` alongside the window control's and the Filters popover's
own menus). One row per region in the whole catalogue — never the current scope, so the list can
always answer "where else could I go" — sorted by the nearest measured drive from whichever origin
is active: the per-user home reach at home, the shared region-base matrix while planning away,
never mixed (the new `utils/regionsJump.js` reuses `planningArea.regionDriveMinutes` unchanged for
both, since the two maps share one shape and that function only ever reads `driveMinutes`).
Unmeasured regions sort last and carry no duration text, and a drive past the shared 3h "glance"
threshold (`planningArea.GLANCE_MINUTES`) gets the `· beyond your area` suffix.

Each row's best score is the served `BriefingRegion.bestRating` for a SOLAR active window,
name-keyed exactly like `heatSpots.js`'s own join (`utils/regionsJump.buildRegionBestIndex`, built
in `WindowFirstMapPane.jsx` from the same `briefing.days` `regionGlossIndex` already reads, and
handed down as a new `regionBestIndex` prop). **A night (astro/aurora) window carries a score too —
an adjudicated ruling during review.** The window dropdown's own "N★ best" column already takes a
licensed client max over that night's served per-location stars (`mapEvents.bestOfNight` — exported
by this phase — licensed because no server-owned per-region figure exists for a night at all). The
jump list's `utils/regionsJump.buildNightRegionBest` groups those SAME served rows by region, via
`heat.spots`' own location-name→region-name pairing, and calls `bestOfNight` once per group: a
finer key on an already-licensed operation, not a second re-derivation, so the dropdown and the
jump list can never disagree about a night's best per region. Only a region with no served night
rows at all still renders the honest em dash.

Selecting a row fits the map to that region's own bounds (`heatGeometry.latLngBounds`, pad 0.06,
Leaflet `fitBounds` padding `[40, 40]`, `animate: false`) — a THIRD box neither "My area" nor "Whole
catalogue" already holds — and **closes the jump panel**, stated as a deliberate choice in code
since the design bundle is silent on it: a jump is a COMPLETED navigation (the camera has already
moved), unlike a `FiltersPopover` row, which rightly stays open because a filter is a standing
choice still being composed one control at a time. Jumping to a region outside "My area" flips
scope to Whole catalogue automatically ("a jump is honest; a no-op is not"), and the two effects
land in the same commit without racing each other: `MapView`'s `HeatBoundsController` now takes an
override (`jumpFitOverride`) rather than gaining a second sibling instance, because a second
controller would have raced the ordinary "My area ⇄ Whole catalogue" one over a single frame the
moment scope also flipped. The override wins outright while set and is cleared by the one thing
that should supersede it — a later, deliberate press of either scope segment button
(`FiltersPopover`'s own "My area"/"Whole catalogue" pair) or of `⌂` itself.

**`⌂` now resets scope, not the camera to a fixed point.** `CentreOnHomeControl`'s pre-P11 behaviour
— fly to the home coordinate at a zoom derived from the reader's Close-to-home radius — predates the
Filters popover's "My area/Whole catalogue" segment (P7) and the design bundle never drew it that
way either: its own `zhome` resets scope and refits to the scoped spot set's bounds. The two are not
additive (refitting to a fixed-radius disc AND to the area bounds in one click would leave the
second fit as the only one ever seen), so the radius-framed fly is retired rather than layered under
the new behaviour — `⌂` and the Filters popover's "My area" button now share one function
(`resetToMyArea`), so the two can never disagree about what "reset scope" means, and both inherit
`animate: false` from the same Leaflet-strand reasoning as every other scope change on this tab.
Filters are untouched by either route. With no home postcode saved, `⌂` keeps its pre-P11 fallback
exactly as it was — opening Settings on the postcode field — since "My area" and "Whole catalogue"
read the same box there anyway and a reset would be a genuine no-op. Its `aria-label` is
state-truthful (`Reset to My area` / `Set your home postcode in Settings`, mirroring `title`
exactly) rather than the stale "Centre on home" the button's old click behaviour left behind — the
identical accname-drift bug class this phase already fixed once on `MastheadTickLine`'s own origin
control, caught here by review before it could ship pinned by a green test. `zoomForHomeRadius` and
the `homeRadiusMiles` prop it alone fed are removed end to end — `MapView.jsx`, `WindowFirstMapPane`
(declare/destructure/forward/PropTypes/jsdoc) and `App.jsx` (state, settings-fetch write, and the
prop pass to the pane).

**The masthead's origin control is a non-interactive STATEMENT on the Map tab.** `MastheadTickLine`
gains a per-tab `isMapTab` prop (threaded from `WindowFirstShell`'s own `effectiveTab === 'map'`) —
a state of the existing component, not a fork: on the map tab the origin button is replaced by a
plain, non-interactive statement (pin glyph, `Home · <place>` or `<Region> · from <base>` while
planning away, plus a caption reading "drive times from here"), and the `⌕` search button and its
hairline separator are withheld outright, because on a map panning IS the search. The empty-state
"Set a postcode" nudge is unaffected on every tab — the map tab's statement never becomes a dead end
in place of it. Every other tab is unchanged: `isMapTab` defaults to `false`, and the existing
interactive origin button, the search button, and the away "plan from home again" pin are all
byte-identical to before.

**Pre-existing P9 bug found and fixed: `mapCallout.buildRegionGlossIndex` read the wrong field.**
Adversarial review against this phase's own diff turned up a defect that predates it —
`utils/mapCallout.js`'s region-gloss join (the Map tab callout's reason-prose fallback, shipped at
P9) has read `region?.name`/`region.name` since it was written, but the served `BriefingRegion`
record carries no `name` field at all; its own field is `regionName` (every sibling join on this
arm — `heatSpots.js`, `windowFirstRegions.js`, and now `utils/regionsJump.js` — has always used it
correctly). So the index has been silently EMPTY against real data since P9: the callout's "fallback:
region gloss" line never actually supplied one. The bug was invisible because `mapCallout.test.js`'s
own fixture used the identical wrong field, so the suite stayed green while the feature was dead —
a fixture pre-satisfying its own wrong predicate, the exact pattern this project's `docs/engineering`
history already has a name for. Fixed both call sites in `buildRegionGlossIndex`, the fixtures in
`mapCallout.test.js` and `MapCallout.test.jsx`, and added a dedicated test in each asserting the
index is (and is not) built from the two field names specifically, so a fixture can no longer
pre-satisfy its own predicate a second time.

Tests: `utils/regionsJump.test.js` (the sort/join logic in isolation, incl. `buildNightRegionBest`'s
grouping and the never-mixed drive-map rule), `RegionsJump.test.jsx` (the component, fully
controlled), new describes in `MapViewHeat.test.jsx` (the sort/threshold/best-score wiring incl. the
night-window grouped-max case, the fitBounds-on-select behaviour incl. the override-vs-race guard
and the panel closing on select, the no-flip-when-already-in-scope case, and popover exclusivity), a
new describe in `MapViewCentreOnHome.test.jsx` (`⌂`'s reset-scope-and-refit behaviour, the cleared
jump override, the untouched filter, the no-home fallback, and the corrected state-truthful
accessible name in both states), extended cases in `MapViewBackgroundClick.test.jsx` (closing the
jump menu), `MapLabels.test.jsx` and `PinsLayer.test.jsx` (seeding `wf-jump-menu` as an obstacle in
both, matching the two files' existing duplicated `OBSTACLE_SELECTOR` lists), a new describe in
`MastheadTickLine.test.jsx` for the `isMapTab` statement variant (non-interactive rendering, the
withheld search button and separator, the away origin still named correctly, and the empty-state
nudge surviving untouched), and new/extended cases in `mapCallout.test.js` and `MapCallout.test.jsx`
pinning the `regionName` fix.
