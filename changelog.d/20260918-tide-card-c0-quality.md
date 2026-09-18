### Added — a served tide alignment quality figure (Plan tab tide card, C0)

`BriefingSlot.TideInfo` and `TideDerivation` gain `Double tideAlignmentQuality`
(`@JsonInclude(NON_NULL)`, 0..1, null unless `tideAligned`): how well-centred a coastal location's
water is in its wanted state at the light, on the same time axis `tideAligned` is decided on — for
a HIGH or LOW want, distance to that extreme; for a MID want, distance to the midpoint between the
bracketing extremes (`TideService.isMidPointAligned`'s own geometry, now exposed via
`TideService.nearestMidpointOffsetMinutes`); the best of them when more than one want is aligned.
Computed in `TideFactDeriver.derive()` and relayed by `BriefingSlotBuilder` unchanged.

No visible change and no migration — this is the served figure Phase C1 of
`docs/engineering/tide-plan-card-plan.md` needs to rank a run of live tide windows by *how well*
each is matched, not just whether it is. Both `BriefingSlot.TideInfo` legacy constructors and a new
16-field legacy `TideDerivation` constructor keep every predating call site compiling. A WARN logs
the one avenue the two fields can disagree on (a MID want's second extremes fetch racing a tide
refresh) rather than letting the degraded case pass silently.
