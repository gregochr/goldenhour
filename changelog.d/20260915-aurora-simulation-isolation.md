### Fixed — an admin's aurora simulation reached signed-in users as a real alert

`POST /api/aurora/admin/simulate` is documented as admin UI testing: it injects a fake Kp/storm
reading into `AuroraStateCache` so an admin can preview the banner and run the Claude scoring
pipeline against invented severe-weather data without waiting for a real geomagnetic storm. Nothing
downstream of the cache checked `isSimulated()`, so the fake reading was indistinguishable from a
real one everywhere else it was read:

- **Hot topics.** `AuroraHotTopicStrategy.detectTonight` read `getCurrentLevel()`/`getLastTriggerKp()`
  unconditionally, so a daytime STRONG simulation put "Aurora possible · Kp 7 forecast tonight" on
  every signed-in user's Plan cards, lasting until dusk.
- **The best-bet advisor's prompt.** `BriefingRollupBuilder` fed the same simulated level/Kp into the
  Claude rollup JSON, and `BriefingAuroraSummaryBuilder.buildAuroraTonight` (which
  `AuroraHotTopicStrategy` also reads, for the clear-location count and moon data) built a summary
  from it the moment the state machine read ACTIVE — which `activateSimulation` always sets.
- **Forecast-run persistence.** `POST /api/aurora/forecast/run`, while a simulation is active, makes
  a real Claude call against real weather triage and the fake Kp/storm data, and persisted the
  result identically to a real run. `AuroraForecastResultEntity` carried no simulation marker, so
  `GET /api/aurora/forecast/results` served it to every PRO/ADMIN user on the map, until the run was
  re-run or the results aged out — long after the admin cleared the simulation.

All three now check `AuroraStateCache.isSimulated()` and refuse to surface anything while it is
true. The third also gets a durable marker: `aurora_forecast_result.simulated` (V154), stamped from
`isSimulated()` at write time. `AuroraForecastResultRepository`'s read methods
(`findByForecastDateAndSimulatedFalse`, its location-fetching sibling, and
`findDistinctForecastDatesExcludingSimulated`) all exclude `simulated = true` rows — for the map
read path and for `TopicDailyLogJob`'s nightly AURORA presence log, which would otherwise have
logged a false presence for a night nothing real was ever measured on. `deleteByForecastDateIn`
stays unfiltered on purpose, so a real re-run of a night still clears out an earlier simulated test
run for that same night. The admin who ran a simulated forecast still sees exactly what Claude
scored, in the synchronous response `POST /api/aurora/forecast/run` already returns — nothing here
removes that.

All three gates are tested against a real `AuroraStateCache` driven through `activateSimulation()`,
not a mocked `isSimulated()` answer, since `activateSimulation` always sets ACTIVE alongside the
simulation flag and a mock could let the two disagree in a way production never can.
