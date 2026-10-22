### Changed — a forced sample sets a verdict but can no longer take a pick

A region whose ratings cleared the sample gate only through the force-evaluation exemption
(`forcedSample`, a handful of far-out ratings in a large region) still keeps its verdict word,
stars, confidence and ordering, but it can no longer wear BEST BET or ALSO GOOD, be named in the
advisor's `bestBets` or `pipeline_run_pick`, be resurrected by the stale-pick fallback, or be offered
as an Ask pick. `BriefingRegion.pickEligible()` (a sufficient sample alone) is the new shared pick
test; `verdictEligible()` is unchanged. A window whose top region is not pick-eligible takes no pick,
so the strip's two picks are a cap rather than a quota. Ask's spread bands still read verdict
eligibility, so a forced-only region keeps counting there. No prompt change, no migration.
