### Changed — Mutation-gap tests for the batch task collector

Added `ForecastTaskCollectorMutationGapsTest`, which pins the decisions in `ForecastTaskCollector.collectScheduledBatches` that PIT showed unguarded: pre-fetch gates, near/far and coastal/inland bucketing, bluebell and woodland lanes, forced-candidate dispositions, the evening-deferral skip, and the summary totals. PIT on the class went from 76 of 132 mutants killed to 127. Test-only; no production code changes.
