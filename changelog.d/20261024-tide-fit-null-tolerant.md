### Fixed — Tide fit reads a missing match as unknown, not a miss

The Map and Plan tide-fit readers now treat an absent `tideAligned` as "unknown, no fit claim"
rather than a served miss: no dimming, no "wrong water" block, no next-fit jump and no "0 of N
coastal locations on tide" line. The tide strip and the phone Tide section also draw no chart, and
use the "No per-spot tide fit for this window" wording, when the tide curve is absent. Nothing
changes for any role today, because every payload still carries the match and the curve; this is
the precondition for withholding them from LITE users later.
