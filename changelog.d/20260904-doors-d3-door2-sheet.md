### Changed — the location sheet's `◍ Show on map →` now opens the Map tab, not the frozen overlay

Phase D3 of `docs/engineering/plan-to-map-doors-plan.md` (§6 Q3, decided yes). The four-day location
sheet's footer button — reached from search, from a spot card, or from a field chip — used to hand
its window straight to `onShowOnMap`, which routes to the pre-v2 Plan-tab overlay. It now calls the
shell's existing `openMapTab({date, targetType, locationName, region: null})` (D2's close-then-move-
and-merge wrapper), landing on the Map tab framed on that window with the location's own callout up,
the Plan's live rating and reach lens carried across, and D2's breadcrumb naming what travelled. The
popup and the sheet close first, same as before — `openMapTab` does that closing itself, so the
shell no longer hand-rolls it. Every other `onShowOnMap` producer (the popup's ranked spot cards,
`WindowPickDialog`'s region and location actions) is untouched and still opens the overlay;
converging those is map-tab-v2-plan.md's O-6, not this phase.

The button is withheld — not left wired to nothing — whenever the shell has no map door at all
(`onOpenMapTab` absent, `App`'s own "nothing to map" case): the sheet falls back to its existing
`location-sheet-nomap` sentence rather than rendering a control whose tap does nothing, the same
dead-control rule its `Plan from …` action already lives by. The button's copy, its accessible name
(one text node, only the `◍` hidden) and its styling are unchanged — only its destination and its
withholding condition moved.
