### Fixed — the map's counts footer states a rated figure it actually counted

The counts footer was specified as `N named · M rated of K` (README §9) and rendered
`scopedVisibleLocations.length` as **both** `N` and `M` — one expression, printed twice, so
the line could only ever claim the named and the rated totals were equal. Neither figure was
right. "Named" is how many chips `MapLabels` places, a zoom-derived budget
(`clamp(6 + (zoom − 8.6) × 11, 6, 60)`) far below that pool at county scale; "rated" was never
asked of the ratings at all.

The copy pass had already replaced the line with a single honest `N of K shown`, which removed
the false claim but lost the coverage signal with it. That signal is now restored as a real
count: `scopedRatedCount` asks `getRatingForLocation`, which already answers for all four event
types — solar through the briefing/forecast precedence, and the stored or live star counts for
astro and aurora — so the figure cannot disagree with the medallion a reader is looking at.

**It appears only when it differs from the drawn count**, which is this codebase's own rule for
a second number: `reachLens.formatLensCount` puts it as "with nothing trimmed, `138 of 138` is
a count dressed as a comparison". In the default map every drawn location is rated by
construction, because the rating filter hides the unrated — so an unconditional figure would
repeat the number beside it on almost every view. It earns its place exactly where something
drawn carries no rating: pure wildlife, which has no sky rating by design, and the unrated and
stand-down locations an admin reveals with the two debug toggles.

Pinned by a test that draws a wildlife location and asserts `5 of 5 shown · 4 rated`, and by a
second that asserts the clause stays absent when everything drawn is rated. Verified by
mutation: restoring the duplicated expression fails the first.
