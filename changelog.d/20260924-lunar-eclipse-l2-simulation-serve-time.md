### Fixed — lunar eclipse simulation moved from build time to serve time (backend, L2)

Codex review of #914 (P1): the admin `LUNAR_ECLIPSE` simulation was sampled only while
`BriefingSlotBuilder` rebuilds slots, but `BriefingService.getCachedBriefing()` serves a
persisted/cached `DailyBriefingResponse` on every request and only overlays a few fields live
(aurora, hot topics) — it does not re-run the slot builder. So toggling the simulation on produced
no visible race until the next scheduled refresh, and toggling it off — or restarting the app —
left a fabricated eclipse sight visible and reloadable from `daily_briefing_cache` indefinitely.

`EclipseSightAssembler.forSlot` (the only method `BriefingSlotBuilder` calls) now attaches REAL
sights only; nothing simulated can reach the persisted cache from any path. Simulation moved to a
new `simulatedSightFor(date, eventType)`, called from `BriefingService.getCachedBriefing()`'s
live-overlay step — the same serve-time seam the aurora and hot-topic overlays already use, and
which now runs unconditionally on every call rather than only when those two happened to change.
Because a serve-time overlay has no per-location `LocationEntity` to hand (only the already-built
`BriefingSlot`s, keyed by name/id), the simulated sight's light stops are now Dunstanburgh's own
too, re-dated exactly like its moon geometry, computed once per (date, event type) and reused
across every slot on that window rather than per-location.

New withers `BriefingRegion.withSlots` and `BriefingEventSummary.withUnregioned` support the
overlay's tree rewrite, following the same "never rebuild positionally — a later field goes missing
silently" discipline this file's other withers already document. The "never on a date a real
eclipse already owns" rule moved inside `isSimulatedFor` itself (a `LunarEclipseCatalog.on(date)
.isEmpty()` check), so `simulatedSightFor` is self-contained rather than depending on its caller to
compute that separately.

Enabling the simulation now shows the race on the very next request; disabling it removes it on the
next request after that; a restart with it off serves the persisted, real-sights-only cache
untouched. Tests: `EclipseSightAssemblerTest` splits into `ForSlotNeverSimulates` (the build path
never even asks `HotTopicSimulationService` a question) and `SimulatedSightForTests` (the full
simulation-gating matrix, now against the serve-time method); a new
`BriefingServiceTest.GetCachedBriefingSimulatedEclipseTests` pins the persisted-cache round trip
(refreshing never calls `simulatedSightFor`), the on/off toggle taking effect on the very next
request each way, and the overlay running independently of the aurora/hot-topic short-circuit.
