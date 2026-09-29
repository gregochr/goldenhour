### Changed — the Plan card's spread histogram needs a real sample before it draws a shape

A single rated 4★ location in a 31-place pool used to draw a full-height bar, reading as "this
sunrise looks good" when the truth was "this sunrise is almost entirely unlooked-at". The histogram
now draws its five bars only when the rated sample clears both a floor (`rated >= 5`) and a
coverage half (`rated·2 >= total`, mirroring the backend's own thin-coverage confidence rule) —
below that gate the row prints `none rated yet` or `N of M rated` instead, one narrow, dated
exception to the ban on "N of M scored" copy (plan-matrix-plan.md §4 A27), because here the count
discloses that the picture is missing rather than standing in for one.

Once bars are drawn, the pool's unrated remainder is now a sixth bar of its own — hatched, in the
same bone ink family the map's unscored plate already uses, never a ramp colour — sharing the five
bands' own scale and sitting to the left of 1★ with a wider gap than the 2px between bands. The
tooltip, the visible row and the card's spoken sentence all read one shared decision function
(`spreadRowState` in `utils/windowFirstSpread.js`), so they cannot describe different states of the
same card, and a phone-only footer clause names the hatch in words while it is on screen.
