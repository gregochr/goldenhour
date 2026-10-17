### Fixed — a row-served slot now carries its sky rating

When a `forecast_evaluation` row won the precedence over the briefing cache,
`EvaluationViewService.toEnrichmentResult` built the enrichment result without the row's
`sky_rating` (V164), so that slot's `skyRating` was null although the column held it. The row's
`sky_rating` is now passed through. A row-served slot shows the tide-fit block's "· sky N★" clause
where it applies, exactly as a cache-served slot already did, and the briefing build persists that
`skyRating` into `daily_briefing_cache`. A rated row with no `sky_rating` (written before V164, or by
the synchronous engine) still serves null, and a triage row still carries none. No migration. This
is phase G1a of `docs/engineering/neighbour-guard-plan.md`: the guard compares sky ratings, so a
row-served slot must carry one.
