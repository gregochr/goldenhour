### Fixed — the four-day location sheet and the map legend scroll instead of squeezing their contents

A place's window rows in the location sheet (opened from the Plan popup, Plan search, the Map
callout's "Four days here" and Ask's "Open in Plan") were shrunk to fit the sheet, so an open
row's prose was cut off at its bottom edge and closed rows lost half their second line. Rows now
keep their full height and the list scrolls. The map legend had the same fault on a short map:
its colour ramp was squeezed to a 2px line; it now keeps its 9px and the legend scrolls. CSS only
(`.wf-loc-row` gains `flex-shrink: 0`, `.wf-legend-ramp` gains `flex: none`), pinned by a
stylesheet test and measured in headless Chromium.
