### Changed — Plan card Spread row: names its pool and prints its counts (S2)

The Spread row on every Plan card now says which pool it counted over — a second label line, `(within
45m)` / `(within 1h30)` / `(within 2h30)` / `(any drive)`, silent when the pool holds a place with no
measured drive under a finite tier — and prints the count of each band (and of the hatched "not yet
rated" bar) under its bar, in a column sized per card (`1.2k` for a thousand). The card's accessible
sentence says the same: the tier in the lens bar's own words ("within 45 min", "any drive"), the
non-zero band counts, and "no locations within 45 min" for an empty pool beside the Best row's own
words. The hover tooltip is unchanged. Below the minimum-sample gate the row keeps its `N/M rated`
text with the label line above it.

Layout: the row is one wrapper spanning both grid columns, so the longer label cannot widen the
rating pill's column; on a card narrower than 205px (every phone and tablet width, and a docked Ask
panel) the label takes one line and the histogram its own right-aligned line below, by a container
query on the card. Cross-card alignment holds: the label and the well reserve their heights whether or
not a qualifier or bars are drawn. A zero count is quietened by weight, not ink (the muted ink is
under AA on the well). Frontend only; no backend, API or migration change.
