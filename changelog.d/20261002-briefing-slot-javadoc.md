### Fixed — withEvaluationGate's javadoc is back on its own method

The javadoc for `BriefingSlot.withEvaluationGate` had been left stacked above `couldCarryRating()`'s
own javadoc, so the javadoc tool silently discarded it and the wither was undocumented. A fix was
written on 2026-09-17 but never left a local branch and no longer applied once the surrounding code
moved. This moves the block, text unchanged, onto the method it describes; a comment move only, no
code change.
