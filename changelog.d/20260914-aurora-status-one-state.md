### Fixed — an aurora status response no longer straddles its own NOAA calls

`GET /api/aurora/status` read the alert level — and whether the machine was simulated, to choose its
branch — before its live NOAA calls, and `active`, the scored, dark-sky and clear counts,
`detectedAt`, the trigger and its Kp, and the simulated flag again after them. Those calls can wait
on NOAA for as long as a cache refresh takes (the solar-wind cache lasts only a minute, and
refreshing it is two HTTP requests), and the polling job can move the state machine meanwhile, so a
single response could answer for two states at once:

- across a **CLEAR**, `MODERATE` with `active: false`, no counts and no detection time — a level the
  banner shows an alert for, beside a flag the map's aurora availability had already dropped;
- across a **NOTIFY** and the trigger recorded after it, `QUIET` with `active: true`, a detection
  time, a trigger and a `G1` storm scale;
- across an admin's **simulation**, the live readings marked `simulated: true`, active and carrying
  the simulation's forecast trigger, with no storm scale.

Every state-machine field is now read once, up front, before the NOAA calls. The frontend fix beside
this entry orders whole responses; this one makes each response agree with itself, which no ordering
on the client can. The two also compose: a response's state-machine fields now describe the moment
its request arrived, so the order the client applies answers in — the order they were asked — is
also the order of the states they describe, for requests that reach the controller in the order they
were sent. Nothing guarantees that last part (the JWT filter's user lookup, the proxy hops and the
401 refresh-and-retry can each swap two requests), but a swap is normally milliseconds wide, against
the NOAA round trips the ordering exists for; and in Chromium, whose HTTP cache held a second request
to this URL back until the first was answered (measured — see the frontend entry), overlapping
requests did not reach it together at all. The live NOAA readings and `currentNightDate` are still
taken as the request finishes: the readings are whatever that request's own fetches returned, and
moving the clock-derived night date up front was weighed and left — across dawn it changes which of
two overlapping answers carries the newer night, not what a reader ends up seeing.

⚠️ **Narrowed, not closed — and one part cannot be closed here.** Two residuals, stated so that
neither is read as fixed:

- **The orchestrator writes one NOTIFY in several steps, with I/O between them.** On the forecast
  lookahead, `AuroraOrchestrator` moves the level and the flag, then fetches from NOAA, then records
  the trigger; the counts land after an Open-Meteo triage and the scores after a Claude call. (The
  real-time path records the trigger straight after the NOTIFY.) CLEAR never resets the trigger, so
  for that whole fetch the machine itself holds the new level beside the previous alert's trigger and
  storm scale — and this endpoint now serves exactly that, faithfully, from one read. Only the
  orchestrator handing over level and trigger together would close it; a snapshot inside
  `AuroraStateCache` would not.
- **The fields are separate volatiles, read one after another.** A writer part-way through its writes
  can still be caught between two of the reads: during an admin simulation, the simulated level and
  trigger beside `simulated: false`, or `simulated: true` with no data yet — a 500 the client ignores,
  unchanged from before. The window is a few adjacent field writes rather than NOAA round trips;
  closing it needs the reads to be atomic with a writer's writes — one published snapshot, a lock or
  a version check.

A trade-off, named rather than hidden: the trigger is now read up to one NOAA wait earlier, so
`GET /api/aurora/viewline/forecast` — which reads the trigger Kp again, on its own request — can
disagree with the status for that much longer. A trigger write in the gap draws the new Kp's line
under the old Kp's label, or a forecast line after a flip to a real-time trigger, until the next
status poll or focus. Cosmetic and brief; passing the Kp to that endpoint would close it, but would
change an API contract, so it is not done here.

Pinned in a new `AuroraControllerStatusSnapshotTest`, a plain unit test with a real
`AuroraStateCache` whose transitions — a CLEAR, a NOTIFY with its trigger, a simulation — are made
from inside the first NOAA stub, `fetchKp`, mid-request. Against the old controller all three fail.
Twelve mutants: eleven each moving one read back after the NOAA calls, and one reading the trigger
Kp between two of the calls — all killed. The adversarial review found that last one alive in the
first version, which moved the machine only during the last NOAA call; and one of the eleven (the
simulated flag read late by the storm-scale guard) died only once the simulation test was given a
live Kp.
