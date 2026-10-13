### Fixed — the four-day location sheet scrolls instead of squeezing its rows

A place's window rows in the location sheet (opened from the Plan popup, Plan search, the Map
callout's "Four days here" and Ask's "Open in Plan") were shrunk to fit the sheet, so an open
row's prose was cut off at its bottom edge and closed rows lost half their second line. Rows now
keep their full height and the list scrolls. CSS only: `.wf-loc-row` gains `flex-shrink: 0`,
pinned by a stylesheet test.
