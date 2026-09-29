### Fixed — the spread row's compact rating count was reaching screen readers as a fraction

The compact `N/M rated` text the Spread row shows below the minimum-sample gate (needed to fit a
realistic pool size into the value column at 320px) had leaked into the card's own accessible
sentence, so a screen reader voiced a partial-sample card's `1/4` as a fraction or a date-like
string rather than as the two counts it visually is. `spreadRowState` now returns two strings for
that state — `text` (the compact form the row shows) and `spoken` (the same fact in words, `N of M
rated`, for the accessible sentence) — equal only for "none rated yet", which carries no count to
mis-voice. The tooltip is unaffected; it already built its own fuller sentence independently.
