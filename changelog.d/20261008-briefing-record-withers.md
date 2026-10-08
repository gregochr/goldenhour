### Changed — briefing record copies can no longer silently drop a component

`DailyBriefingResponse` gains `withBestBets(bestBets, bestBetsWithdrawn)` and
`withLiveOverlays(auroraTonight, auroraTomorrow, hotTopics)`, and the three places that rebuilt a
response positionally (`BriefingHonestyFilter`, `ServedBriefingAssembler.applyBestBetFallback`,
`BriefingService.getCachedBriefing`) now use withers, so none of them can forget
`renderedEvents`, `previousGeneratedAt` or `bestBetsWithdrawn` when a component is added. A new
reflection test (`RecordWitherPreservationTest`) fills every component of `DailyBriefingResponse`,
`BriefingRegion`, `BriefingEventSummary` and `BriefingWindow.Pick` with a sentinel and fails if any
`with*` method loses a component it does not name. No behaviour change: the three rebuilds carried
nothing in those components before.
