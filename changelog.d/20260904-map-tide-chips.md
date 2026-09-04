### Added — tide-alignment glyph on the Map tab (bundle rev 2)

A coastal location's label chip now carries a small wave glyph when THIS window's tide actually
lands on the light there — a glyph, not a second number, so the star rating stays the only score
on the chip. The same fact breaks the label-placement tiebreaker (among equal stars, the location
whose tide is on the light keeps its label when the budget runs out), extends the tooltip with a
third, teal-inked line ("Tide lands on the light — HW 19:52 · 36m before sunset"), and adds a
bordered tide row to the selection callout, styled on the existing `.wf-frow` tide-row look and
omitted entirely when the window's water is not on the light.

**Served, not parsed.** The design bundle's own demo code (`tideOf`/`tideFit`) derives the fact by
regexing the already-formatted tide sentence and gates it on a fixed ±45 minutes. The port instead
serves the fact structurally: `BriefingSlot.TideInfo` gains four nullable fields
(`nearestSolarOffsetMinutes`, `nearestExtremeKind`, `tideOnTheLight`, `nearestSolarOffsetPhrase`),
computed in `BriefingSlotBuilder.calculateTideData` from the nearest tide extreme of *either* kind
(high or low, type-blind — never `tideAligned`, which tests the location's configured `TideType`
preference, a different question) against `TideFactDeriver`'s own dynamic tight-alignment
half-width — the same rule the Plan tab's tide row already gates on, so the two surfaces can never
disagree about what "on the light" means. The one phrase string is built once, server-side, from
the shared `TideWording` vocabulary.

Frontend: `utils/locationSheet.buildTideAlignmentIndex` joins the fact per location per window
(mirroring `buildSlotIndex`/`buildScoreIndex`'s own shape), read through the shared
`lookupForWindow`; `utils/mapLabels.chipCandidates` sorts rating DESC → tide-on-the-light DESC →
drive ASC; `MapLabels.jsx` renders the glyph, a `data-tide` ring, and extends the chip's
aria-label (which otherwise replaces the rendered text outright) with "tide on the light";
`MapCallout.jsx` adds the `.wf-callout-tide` row.

Tests: seven new backend `BriefingSlotBuilderTest` cases (either-kind selection both ways, the sign
convention, inside/outside the dynamic half-width, the earlier-water tie-break, and both
no-extreme-found and no-stored-extremes null paths); new/extended describes in
`locationSheet.test.js`, `mapLabels.test.js`, `MapLabels.test.jsx`, and `MapCallout.test.jsx`
covering the index build, the sort tiebreaker under a real budget-of-one, the glyph/aria-label/
tooltip line, and the callout row's presence and omission rules.
