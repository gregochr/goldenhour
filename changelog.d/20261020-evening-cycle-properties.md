### Changed — the pipeline orchestrator reads a cycle's shape from the cycle type

Groundwork for an evening refresh of tomorrow's sunrise; nothing observable changes. The orchestrator's
`intraday` flag is replaced by properties of `CycleType` (`publishesStabilitySnapshot`,
`recordsReclassifyPhase`, `failureEvidence`), the wait budget is chosen per cycle type (both still the
configured safety timeout), and a candidate strategy can now say it bypasses the CACHED gate
(`bypassesCacheGate`, false for every existing strategy).
