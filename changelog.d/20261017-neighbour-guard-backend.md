### Added — the neighbour guard: a lone high sky rating is no longer named as the pick (off by default)

A sky rating far above several places that see the same sky is far more likely model noise than a
real local difference, and the harm is when the Plan tab names that one place as where to go (Caw
Gap's lone 4★ over Cawfields 1★ and Steel Rigg 2★, 2.3 km away). `NeighbourOutlierGuard` now marks
such a slot at serve time: a regioned sky slot whose `skyRating` is at least 3 and which no other
sky slot in the same window within 5 km rates within one star of, while at least two nearby places
at a similar height (or both without an elevation) sit two stars or more below it. A nearby place
that agrees clears the slot at any height, so a fell top above an inversion is not outvoted by the
valley. The mark rides the served slot as `neighbourOutlier: true`, never `false`, and is never
written to `daily_briefing_cache`; the slot keeps its star on every surface.

Its one effect in this release is `PlanWindowProjector.topSlot`: a marked slot is never the BEST
BET / ALSO GOOD destination, and after one is skipped the next slot must be rated at least 3 or the
pick names the region only. Coordinates come from `LocationPointProvider` (one projection query a
minute at most); a failed read serves the briefing unmarked. `NeighbourGuardLog` writes one INFO
line per first-flagged place and one summary per briefing. Everything is behind
`photocast.neighbour-guard.enabled`, which defaults to **false** until Ask and the Plan card follow
(documented with its bounds in `application-example.yml`); with it off nothing is marked and no
coordinates are read.
