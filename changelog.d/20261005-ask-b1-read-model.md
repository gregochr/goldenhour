### Added — Ask PhotoCast B1: the read model, the tools, the answer contract and the validator

First backend phase of Ask PhotoCast (`docs/engineering/ask-photocast-plan.md`), with no Claude call,
no endpoint and no migration. `AskSnapshotBuilder` reads the Plan tab's own assembly
(`BriefingService.getCachedBriefingForApi()`) into an `AskSnapshot`, memoised for 30 seconds. Its
window set is the solar events that carry a served `window()`, have not passed by the shared
`PlanWindowProjector.hasPassed`, and are not travel days; the served briefing does not mark a travel
day, so the builder asks `TravelDayService.isTravelDay`, the same test the best-bet advisor applies.

A slot is pick-eligible only when it has a location id, is not a wood, is rated 3★ or better and
sits in a region `BriefingRegion.verdictEligible()` would let carry a verdict — no rating exempts an
ineligible region, so a handful of hand-run 4★ ratings can never crown one. `AskTools` implements
`list_windows`, `rank_spots`, `get_hot_topics` and `get_coming_up` over it (BEST BET first among equal
ratings, the served tide state and the location's own tide preference as two separate fields, a
6,000-character cap per conversation, errors as results rather than exceptions), and
`AskAnswerValidator` holds a submitted answer to the pairs and events the tools actually returned,
joining every card fact from served data and requiring a Ready `BEST_*` answer to lead with the
forecast's BEST BET window. The window-id format `yyyy-MM-dd_sunrise|sunset` now has one codec,
`AskWindowId`, which `BriefingRollupBuilder` uses in place of its two inline copies.
