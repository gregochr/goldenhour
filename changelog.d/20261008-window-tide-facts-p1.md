### Added — `GET /api/briefing` serves per-location tide on each window (`window.tideFacts`)

Each `BriefingWindow` gains a nullable `tideFacts` list: one `LocationTideFact` per coastal
location that has a tide state in that window, omitted when there is none. It is built by the new
`WindowTideFactProjector` from the unfiltered cached slots, before `BriefingHonestyFilter` empties a
zero-coverage region's slots, so the facts survive on every window Claude did not score (T+3 except
SETTLED, all of T+4, travel days). Nothing reads it yet: this is the additive backend half of the
fix for the Map tab showing no tide strip and no coastal locations on those windows (the 2026-10-08
Sunday-sunrise defect); the client and the removal of slot tide follow in later phases.
`PlanWindowProjector.apply` takes the facts as a new argument. Measured on a production-shaped
synthetic briefing (five days, 225/182 slots a sunrise/sunset with 59/27 coastal): about 150 KB raw
(7.8%) and 14 KB gzipped extra, and the projector takes under 1 ms. The comments claiming
`BriefingHonestyFilter`'s zero-coverage cases are rare, and that the tide gate is live, are
corrected.
