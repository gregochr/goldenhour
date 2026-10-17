### Changed — Ask no longer offers a place the neighbour guard marked as an outlier

When `photocast.neighbour-guard.enabled` is on, a slot marked `neighbourOutlier` (a rating far above
every nearby place that sees the same sky) keeps its star but is no longer a pick in Ask:
`AskSnapshot.isPickEligible` refuses it, so `rank_spots`, the answer validator, the thread re-join, the
Ready freshness re-check (a stored Ready answer naming it is withheld whole), the Ready anchors and the
stub all leave it out. `list_windows` no longer reports it in a window's `bestRating` (the existing
maximum with only the outliers left out, so a verdict-ineligible region's star, a sub-3★ best and an
all-canopy window are unchanged). A follow-up whose earlier pick became an outlier is told it is "held
back: rated well above the places beside it" rather than that its rating fell, the prompt's thread rules
say never to describe such a place as rated low, and a follow-up resting only on held-back places
answers "those places rate well above their neighbours" (or "those places can no longer be picked"
when the reasons are mixed). Nothing changes while the guard is off, which is its default.
