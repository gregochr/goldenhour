### Changed — a quiet aurora run gives nobody a night row

A stored aurora night now counts as a night worth a map row only if its best rating is at least 3 stars
(the owner's scale: the lowest star worth a trip). A quiet run, where every location is 1 or 2 stars or
only triaged, no longer adds a row for Pro, Admin or the Lite "Aurora tonight" door; `available-dates` and
`GET /api/aurora/nights` change together. A live MODERATE or STRONG alert still gives its night a row.
Admins no longer browse quiet runs on the map; the run's own response still shows them.
