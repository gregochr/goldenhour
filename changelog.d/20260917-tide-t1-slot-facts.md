### Added — the map tab's per-slot tide-fit facts (T1 of the tide-window increment)

Every coastal `BriefingSlot` now carries five display facts for the map tab's tide-fit chip,
tooltip, callout and location sheet — none of it wired to the frontend yet (T3+), and none of it
browser-visible this phase. `BriefingSlot.TideInfo` gains `tideLevel` (0.0–1.0, the same
normalisation the Plan tab's window rollup uses), `tideDirection` (`RISING`/`FALLING`),
`tideHeight` (formatted metres at the light), `tideShortfall` (`HIGHER`/`LOWER`/null — null when
aligned, or when the location's wanted tide states straddle the served one and no single arrow
applies), and `tideFitPhrase` (the served block body, one wording for a match and a different one
for a miss — the miss form states the light's own clock and the day's high water, and deliberately
never repeats the nearest-extreme offset the gate sentence beside it already carries). All five ride
`daily_briefing_cache`'s JSON — no migration, and a payload written before this shipped
deserialises every one of them to null.

The one cosine interpolation the app has — previously private to `WindowTideRollupBuilder` — is
lifted into a new package-private `TideCurveCalculator`, with `WindowTideRollupBuilder` delegating
to it; its own extensive test suite passes with zero assertion changes, proving the lift changed
nothing about what the Plan tab's tide row already draws. `BriefingSlotBuilder` fetches the same
coastal location's extremes a second time, through the identical repository method and window
`TideService` already uses, to build the new per-slot facts from the same curve.

The served alignment stays the *tight* one — `TideFactDeriver`'s existing gate input — never the
widened scoring-only band, confirmed against the tree and pinned by a dedicated test.
