### Fixed — Ask answers a stay-home window, a covered empty day, favourites, a no-detail place and a named night area

Five faults found by the first keyed run of the Ask prompt regression (`docs/engineering/ask-regression-triage-2026-11-27-plan.md`,
Part B). A window is never called "marginal" (that is one place's 3★). A Ready answer names a covered day that has no place worth
going to, by its weekday, computed from the offer rather than left to the model. `favourites` is passed only for a question limited
to the reader's own places, and a companion is named with its stars. A looked-up place with no detail is still answered from its
rating and status reason, and the neighbour-outlier reason now explains the star ("Its rating is well above the places beside it, so
PhotoCast doubts that star and is not suggesting it."). `rank_night_spots` takes `areaName` (typed questions only), and a region name
that misses, in either tool, says to pass the named area as `areaName` or lists the regions that exist.
