### Testing — kill Stryker survivors in the small-file tail (sweep 15)

New `*Edges.test.js` suites pin the boundaries and fallbacks of 34 small frontend utils: the exact tie and edge
cases of the spot peek's flip and arrow clamp, the run-outcome and scheduler-query address rules, the Ask
conversation's thread reset, plan view and answer normaliser, the module-level tables (label ladders, reach
rings, location types, model labels, triage wording), the cycle panel's pending phases and earliest batch, the
penny and cent boundaries of the cost formatters, the tide run's tie-break, and the preload and invite-token paths.

Survivors before and after (survived plus no coverage; baseline 147, now 59):

- to 0: windowSpotPeek 8, labelPlacement 7, reachRings 6, comingUpSparkline 4, runStatus 2, askScroll 2,
  auroraChipLevel 2, askPeek 1, modelLabels 1, inviteLinkToken 1, windowFirstAway 1
- partly: pickScope 9 to 2, askConversation 8 to 2, cacheDiagnostics 8 to 4, schedulerQuery 8 to 4, cycleView 7 to 3,
  locationTypes 14 to 5, userAdmin 6 to 3, subRegionAction 5 to 2, formatCost 3 to 1, mapPeek 3 to 2,
  runOutcome 9 to 7, windowFirstTideRun 3 to 2, askMapContext 4 to 3, pickGeography 2 to 1,
  preloadInitialTab 4 to 1, forecastDetailFacts 6 to 4
- unchanged, all equivalent: auroraRamp 5, standDown 2, startRunOptions 2, askPickChip 1, initialTab 1,
  oneTimeLinkToken 1, verdictWord 1

The survivors that remain are equivalent mutants: optional chaining on a value already proved non-null, `[]`
fallbacks whose stand-in string is skipped by the next line, guards whose removal is caught by the next check
(JSON.parse of a non-object, the scheduler date round trip), interpolation at a stop that lands on the same
colour, and `>=` on a tie that an earlier `!==` has already excluded. One module-level table in
forecastDetailFacts is pinned by a test but Stryker cannot attribute coverage to it. No source changed; this is
tests only.
