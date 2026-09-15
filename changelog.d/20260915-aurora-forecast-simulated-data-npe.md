### Fixed — a cleared aurora simulation could NPE a Kp preview or forecast run

`AuroraForecastRunService.getPreview` and `.runForecast` each read
`AuroraStateCache.isSimulated()`, then — separately — `getSimulatedData()`. Between the two reads,
an admin clearing the simulation (`POST /api/aurora/admin/simulate/clear`) or a real geomagnetic
event overriding it could null the data out from under a flag that had already read `true`, and the
second read's `.kp()` (preview) or `buildSimulatedSpaceWeather(...)` (run) threw a
`NullPointerException` on a still-true flag with no data behind it. Both call sites now read
`getSimulatedData()` exactly once and derive "simulated" from `!= null`, reusing that one reference
for everything downstream — closing the gap outright rather than narrowing it, since a single
`volatile` field read of an immutable record can never observe a half-written state.

Found while coordinating with a sibling branch fixing the same class of bug elsewhere in
`AuroraStateCache`'s own callers (`fix/aurora-simulation-lifecycle`); this project's two call sites
were pre-existing and outside that branch's scope.
