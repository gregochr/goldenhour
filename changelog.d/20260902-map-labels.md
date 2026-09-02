### Added — location chips, region names, the home marker and reach rings on the Map tab (map-v2 P8)

The Map tab's heat field gets names. A new `components/map/MapLabels.jsx` runs one greedy
placement pass, in priority order, every time the field repaints: the home marker (below zoom 13),
reach-ring labels ("45 min"/"1h 30min" once a real drive time gates this screen's reach lens,
otherwise a plain "25 mi"/"50 mi"), region names (below zoom 11.2, centred on each region's own
projected centroid, the highest-rated region lifted to a brighter ink), then location chips — a
5px ramp-coloured square, the name, and its star (`--ink`, never ramp ink), sorted
best-first-then-nearest with a density-ramped budget (`clamp(6 + (zoom-8.6)*11, 6, 60)`) so the
middle zoom range never collapses to the two-or-three-names "hole" an earlier build hit — measured
live at 8 chips + 4 region names at county zoom, 11 names at the regional glance. The best-rated
location in every region is always offered, and the selected location always gets its chip. A
label with nowhere left to go on the ladder is dropped rather than stacked or shrunk.

Clicking a chip selects that location through the same path a marker click takes — including a
marker currently folded into a cluster bubble, via the cluster group's own `zoomToShowLayer` rather
than a bare `openPopup()` (which is a silent no-op on a clustered marker). Hovering a chip
(desktop only) shows a tooltip with the name, the active window's label, the star and its verdict
word, and the region, drive time and sky Bortle class — clamped to the frame's own edges, and
portalled to the same chrome wrapper the rest of the tab's overlay chips live in (a descendant of
Leaflet's own transformed pane cannot out-rank chrome outside it on z-index alone, whatever number
it declares).

`utils/labelPlacement.js`'s `placeWithNudges` gained a horizontal dimension (`MAP_NUDGES`/
`mapDxOffsets`) behind a new optional options parameter — the Plan tab's own callers pass none and
see byte-identical behaviour, only the map's own greedy pass opts into the wider ladder and the
left/right fallback. A new `seedObstacles` helper turns the live chrome's DOM rects — the window
control, the filters/heat-view cluster, the counts footer, either open menu, the colour-scale
notice, the LITE viewline upsell chip, the scored-locations legend, and Leaflet's own bottom-right
zoom+⌂ stack — into obstacle boxes the placer avoids, padded 5px on every side.

Reach rings — dashed circles at the same 25 mi / 50 mi tiers the Plan popup's own field map draws
— now paint on the Map tab's heat canvas too, below zoom 10.6 and only with a saved home postcode,
sharing that field map's own legibility floor and off-frame ceiling. The two tiers were extracted
from `WindowRowFieldMap.jsx` into a new `utils/reachRings.js` so neither surface can silently drift
to a different pair of circles; `WindowRowFieldMap`'s own rendering is unchanged (its full test
suite passes unedited against the extraction). Ring size is measured at HOME's own latitude
(`pxPerKmAtHome`), never the map's current viewport centre — Web Mercator's ground resolution
varies with latitude, so a ring fixed on home would otherwise visibly grow or shrink as the reader
pans north or south while home stays put. A new `rings` toggle state defaults to on; the Legend
panel switch for it is a later phase's work.

An adversarial review round found 13 confirmed issues against the first cut (obstacle coverage
missing four live chrome pieces, the ring-radius/viewport-centre bug above, the missing floor/
ceiling, tooltip edge clamping and stacking, the clustered-marker click, a filtered-pool
`reachMeasured` derivation, and two documentation citations) — all fixed here, with 107 net new
tests across the module (`labelPlacement.test.js` +11, `MapHeatLayer.test.jsx` +9, plus five new
files: `mapLabels.test.js` 39, `reachRings.test.js` 9, `MapLabels.test.jsx` 32,
`MapViewChipSelect.test.jsx` 3, `MapViewReachMeasured.test.jsx` 4).
