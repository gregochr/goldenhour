### Changed — Ask no longer offers a place the neighbour guard marked as an outlier

When `photocast.neighbour-guard.enabled` is on, a slot marked `neighbourOutlier` (a rating far above
every nearby place that sees the same sky) keeps its star but is no longer a pick in Ask:
`AskSnapshot.isPickEligible` refuses it, so `rank_spots`, the answer validator, the thread re-join, the
Ready and typed-cache freshness re-check (a stored answer naming it is withheld whole), the Ready anchors
and the stub all leave it out. `list_windows` no longer reports it in a window's `bestRating` (the
existing maximum with only the outliers left out, so a verdict-ineligible region's star, a sub-3★ best
and an all-canopy window are unchanged).

`rank_spots` now adds a note, only when the guard withheld places from the windows and regions it
searched, saying how many ("1 place in these windows is rated well above the places beside it and is not
offered.") and never which, so a question about a withheld place is not answered as if it were missing
from the forecast. A follow-up whose earlier pick became an outlier is told it is "held back: rated well
above the places beside it" rather than that its rating fell; the thread rules allow the answer to say
only that such a place is not being suggested because its rating is out of line with the places beside
it, and never to call it low, poor or unreliable or quote its star. A follow-up resting only on
held-back places answers "PhotoCast doesn't have: a trustworthy rating for those places" (or "those
places as suggestions" when the reasons are mixed). Nothing changes while the guard is off, which is its
default.
