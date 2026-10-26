### Changed — the Lite briefing shaper narrows each slot's tide block too (a guard, not a live leak)

`BriefingRoleShaper` stripped the window tide block, the window's per-location tide facts and the "Tide aligned"
flags for a Lite reader, but each slot's own tide block passed through untouched. `BriefingSlot.forLite()` now
narrows it too: `tideAligned`, `tideAlignmentQuality`, `tideShortfall`, `tideFitPhrase`, `tideHeight`, `tideLevel`
and `nearestHighTideHeight` are null for Lite, while the state, the king and spring flags and the lunar and
solar-offset facts stay. Pro and Admin bodies, the digest and Ask are unchanged. On the live briefing the slot
tide was already removed before the shaper runs, so this closes the shaper's own gap rather than changing today's
payload.
