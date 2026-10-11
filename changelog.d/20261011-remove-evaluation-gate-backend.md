### Removed — the inert `evaluationGate` seam on the backend

`BriefingSlot.evaluationGate` and `BriefingGatingPolicy` are deleted. Both have been inert since the
tide gate lift (2026-09-18): the policy's hard-constraint set was empty, so every slot was eligible
and the field was null on every slot production built. `couldCarryRating` is now simply "has a
rating, or is not a canopy slot", the candidate collector no longer carries its dead stand-down
branch, and the region gloss runs for any region that has slots. Briefings already stored in
`daily_briefing_cache` that still carry an `evaluationGate` key deserialise as before (pinned by a
test), and `DispositionCategory.SKIPPED_HARD_CONSTRAINT` stays for historical rows. No behaviour
change.
