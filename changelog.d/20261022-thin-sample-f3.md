### Added — the Plan card's verdict line counts how many places were rated (thin-sample F3)

A card whose verdict rests on a thin sample now reads `Worth it 4.0★ · 6 rated`, the same figure as
the `6/83 rated` in its Spread row (the card's reach pool). It shows only for a rated verdict
(`Worth it`, `Maybe`) when the Spread row's own minimum-sample gate fails and at least one place is
rated; never beside `Too few rated`, `Poor`, `Not scored`, an away cell or an unscored window. The
card's spoken sentence already says it ("6 of 83 rated", from the Spread row's clause). The figure is drawn only where the card
is at least 190px wide inside its padding, so the verdict row never wraps and pushes the Spread and
Best rows out of line; on narrower cards (the narrowest phones) it is not drawn, a known limit: the hatch and the Spread
row's own `N/M rated` directly below carry it.
