### Fixed — two review findings against the aurora simulation-isolation fix

Two P1 findings from an automated review, both confirmed real:

**A simulated forecast run could delete a night's real results.** `AuroraForecastResultWriter
.replaceNightResults` unconditionally called the unfiltered `deleteByForecastDateIn` before
inserting, real run or simulated. An admin simulating a night that already had stored real,
user-facing results would delete those real rows and replace them with simulated ones every
read method excludes — the night would read as never forecast at all. The writer now takes an
explicit `simulated` flag from the caller (an empty result list carries no signal of its own) and
picks the delete accordingly: a real run still clears real and simulated rows alike (an earlier
admin test run must not survive a real one), but a simulated run now calls a new,
`simulated`-scoped delete that only ever touches rows an earlier simulated run left behind.

**A lingering simulation could suppress a later real alert indefinitely.**
`AuroraStateCache.evaluate()` — the real NOAA polling path — never touched `simulated`/
`simulatedData`, only `activateSimulation()` and `reset()` did. If an admin activated a simulation
and never explicitly cleared it, a real quiet reading would CLEAR the machine back to IDLE as
normal, but leave the simulation flag standing; a later genuine alert would then reactivate the
machine while `isSimulated()` still read `true`, silently suppressed by every gate this PR just
added (hot topics, the best-bet prompt) until an admin manually reset or cleared the simulation.
The CLEAR transition now clears the simulation fields too, since `evaluate()` is exclusively the
real-data path — any real reading superseding an active state means an earlier simulation is
stale, whatever produced it.
