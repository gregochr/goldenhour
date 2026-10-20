### Docs — plan the Plan-tab map overlay's retirement onto the Map tab (O-6)

`docs/engineering/overlay-convergence-plan.md` plans map-tab-v2's open decision O-6 as a
retirement, not a re-skin: the "Aurora tonight" / "Sunrise tomorrow" overlay — the pre-v2 map kept
behind `MapView`'s `overlayMode` gate (medallions, the Leaflet popup, its own EVENT/QUALITY/
LOGISTICS drawer and `/drive-times` fetch) — is deleted and its seven producers open the real Map
tab through the structured `source:'plan'` door, as the location sheet's *Show on map* already
does. Six phases: C1 extends the door with everything the overlay's triggers carry (a night, a
named clearable spot lens, a multi-pin fit, the coming-up filters, a breadcrumb title); C2 gives the
location sheet and callout the popup's facts the tab lacks (badges, surge and sea state, the
aurora and astro detail, score bars, the travel-day notice); C3 lets the Map tab open on an aurora
alert before any solar forecast exists; C4 moves the doors and closes the overlay-over-a-Plan-
dialog route; C5 deletes ~700 lines of `MapView.jsx`, `MapOverlay`, `utils/mapOverlay.js`,
`ForecastTypeSelector` and ~50 overlay tests; C6 sweeps the docs. §5 lists six owner decisions,
recommended but not yet taken. `overlay-convergence-prompts.md` carries one Sonnet kickoff prompt
per phase.
