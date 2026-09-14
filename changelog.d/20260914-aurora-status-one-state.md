### Fixed — one aurora status response describes one state of the alert machine

`GET /api/aurora/status` read the alert level before its live NOAA calls, and everything else the
state machine holds — `active`, the scored, dark-sky and clear counts, `detectedAt`, the trigger and
its Kp, whether it is simulated — after them. Those calls can wait on NOAA for as long as a cache
refresh takes (the solar-wind cache lasts only a minute, so a status request often makes at least
that call), and the polling job can move the state machine meanwhile, so a single response could
answer for two states at once:

- across a **CLEAR**, `MODERATE` with `active: false`, no counts and no detection time — a level the
  banner shows an alert for, beside a flag the map's aurora availability had already dropped;
- across a **NOTIFY** and the trigger the orchestrator records straight after it, `QUIET` with
  `active: true`, a detection time, a trigger and a `G1` storm scale;
- across an admin's **simulation**, the live readings marked `simulated: true`, active and carrying
  the simulation's forecast trigger, with no storm scale.

Every state-machine field is now read once, up front, before the NOAA calls. The frontend fix beside
this entry orders whole responses; this one makes each response agree with itself, which no ordering
on the client can. And the two compose: with the state read as each request arrives, a response
describes the state at that moment, so the order the client now applies answers in — the order they
were asked — is also the order of the states they describe. Before, a slow request's level was read
before a quicker later request's while its flag and counts were read after, and no order on the
client could be right for both halves.

⚠️ **Narrowed, not closed.** The fields are still separate volatiles, read one after another and
written one after another by `AuroraStateCache`, so a transition can still land between two of the
reads — a window of a few field reads rather than of NOAA round trips. Closing it needs the state
cache to publish one immutable snapshot per transition, which is a change to the state machine rather
than to this endpoint, and is not made here.

Pinned in a new `AuroraControllerStatusSnapshotTest`, a plain unit test with a real
`AuroraStateCache` whose transitions — a CLEAR, a NOTIFY with its trigger, a simulation — are made
from inside the solar-wind stub, mid-request. Against the old controller all three fail. Eleven
mutants, each moving one read back after the NOAA calls: all killed, the last only once the
simulation test was given a live Kp — without one, the storm-scale guard reading the simulated flag
late made no difference to the answer.
