### Changed — the Plan card names whose verdict it is when nothing in reach is rated

When the reach tier gated out every rated place of the region a window's verdict belongs to, the
card read "Worth it" over a Spread of "none rated yet" and a Best of "not scored yet" — false of the
window, true only of the places you could drive to. In exactly that state the third row now reads
`Region` / `Northumberland & Tyneside · beyond 45 min` (the tier as the lens bar prints it), in
primary ink, with a tooltip saying the verdict is that region's and its rated places are all further
away, and the card's spoken sentence carries the same clause where it used to drop the Best clause.
It only ever appears with a measured drive time and an active tier: with no postcode, or under
"Any", nothing was gated, so the wording is not claimed. Frontend only (plan-card-verdict-honesty
plan, H2).
