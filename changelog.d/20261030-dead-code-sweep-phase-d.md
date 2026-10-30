### Removed — briefing and API response fields that nothing reads (dead-code sweep phase D)

Eleven response properties that were written on every request and read by nothing, backend or
frontend, are gone from the wire: `DailyBriefingResponse.partialFailure` and `failedLocationCount`,
`BriefingRegion.lightlyEvaluated` (with the honesty filter's "lightly evaluated" tier that set it),
`BriefingSlot.standdownReason` (with `BriefingVerdictEvaluator.StanddownReason`, both
`deriveStanddownReason` methods and the Lite tide-mismatch strip), `BriefingWindow.topRarityRank`,
`TideRunDay.highWaterAnomaly`, `highWaterRank`, `alignmentPhrase` and `bestAligned`,
`AuroraStatusResponse.dataSource` and `UserSettingsResponse.postcodePromptDismissedAt` (the column and
the `postcodePromptDue` flag are unchanged).

Deploy safety: `BriefingService.loadPersistedBriefing` reads `daily_briefing_cache` with the Jackson 2
mapper, which fails on unknown properties, and the payload written by the previous release still
carries the removed keys. Every record in the briefing tree now carries
`@JsonIgnoreProperties(ignoreUnknown = true)`, pinned by `PersistedBriefingRemovedKeysTest`, so the
first start after deploy restores the Plan tab instead of leaving it empty until the next pipeline
cycle. This also makes a rollback to the previous image safe.
