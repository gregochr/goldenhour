### Changed — Plan tab: places nobody rated are hatched, and the legend names the three grounds

Second phase of the thin-sample field (`docs/engineering/thin-sample-plan.md`, F2). The Plan strip's
thumbnails and the open row's field map now hatch the ground around every place the window has not
rated, under the colour, so a window crowned on a handful of ratings reads as a small warm patch in a
large hatched area instead of looking as confident as a well-rated one. The layer is skipped on an
unscored window (its whole plate is already hatched, decided by the same `bestRating` predicate, never
an empty point set) and on an away cell; a focused region fades its hatch with its colour (and the row map's focus now also lands on a region whose places are all unrated). Nothing is hatched until the scores have loaded successfully, and a window with no rated and no stood-down place at all gets no hatch (there is no evidence which places are unrated). The legend
under the strip replaces `unshaded — not scored` with two standing swatches, `not rated yet` (hatch)
and `no places` (plain plate), and its right-hand line now reads "Colour and hatch cover every place
in the forecast — the cards allow for your drive". The old conditional note and its test ids are gone
(an intended change; the tests that pinned the old wording were updated). The aurora thumbnail and the
Map tab are untouched.
