### Changed — the Plan card's spread histogram needs a real sample before it draws a shape

A single rated 4★ location in a 31-place pool used to draw a full-height bar, reading as "this
sunrise looks good" when the truth was "this sunrise is almost entirely unlooked-at". The histogram
now draws its five bars only when the rated sample clears both a floor (`rated >= 5`) and a
coverage half (`rated >= total · 0.5`, mirroring the backend's own thin-coverage confidence rule) —
below that gate the row prints `none rated yet` or the compact `N/M rated` instead (never "N of M
rated", which measured too wide for the value column at a realistic pool size), one narrow, dated
exception to the ban on "N of M scored" copy (plan-matrix-plan.md §4 A27), because here the count
discloses that the picture is missing rather than standing in for one. An empty pool is not a gate
failure and keeps its pre-existing five-hairline picture and tooltip sentences unchanged.

Once bars are drawn, the pool's unrated remainder is now a sixth bar of its own — hatched, in the
same bone ink family the map's unscored plate already uses and at the same diagonal, never a ramp
colour — sharing the five bands' own scale and sitting to the left of 1★ with a wider gap than the
2px between bands. The tooltip and the visible row read one shared decision function
(`spreadRowState` in `utils/windowFirstSpread.js`); the tooltip deliberately says MORE than the row
below the gate (the pool size, and for a partial sample the rated count), never the row's text
verbatim. A footer clause names the hatch in words while it is on screen, at every width — it is
conditional and, in practice, close to permanent once a catalogue has any sparsely-rated windows,
not a rare or phone-only note.

A follow-up commit the same day corrected an adversarial review's findings against this one: the
hatch's angle was mirrored against the map's own canvas hatch, an empty pool had picked up new text
it should not have, the row's text could overflow its column at a realistic pool size, and the
accessible sentence had dropped the pool count and the "within reach" claim below the gate.
