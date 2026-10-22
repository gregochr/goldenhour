### Added — heat field: a per-window state for every place, and an unknown-ground layer (no visible change yet)

First phase of the thin-sample field (`docs/engineering/thin-sample-plan.md`, F1). `buildHeatSpots` now
carries a `states` array beside each place's `scores`: `rated`, `stood` (a score row with no rating
and a triage reason, so the place was looked at and stood down), `unrated` (a sky place with neither
a rating nor a triage reason, including one with no row at all) and `na` (a wood or wildlife hide,
which is never rated by design and is never hatched). `unratedPointsFor` returns the unrated places in
`heatPointsFor`'s shape, and `forecastScoreRows` now passes a forecast row's `triageReason` through so
a past window tells a stand-down from a gap.

`heatField.js` gains the layer itself: `drawGeo` and `drawTiles` take an optional `unrated` point list
and hatch the ground around those places, above the plate and under the field, so colour wins where a
rated place is near. The mask is the spec's (a 4px grid over the field's own radius and bucket walk,
which `field()` now shares rather than copies), feathered with the field's own blur and honouring the
region focus. Nothing passes the option yet, so no host draws any differently.
