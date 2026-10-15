### Added — Map tab: "How was it?" — rate a sunrise or sunset that has already happened (O4a)

Select a place on a past window and the location callout now asks how the sky actually turned out:
five stars and "Didn't see it", one tap. Pro and Admin readers save it (upsert, one record per
person, place and window); a Lite reader sees the block locked — the stars dimmed, a Pro pill, and
"Rating is part of Pro." on a tap. Once saved the block reads "You rated ★★★☆☆ 3★" (or "You marked
this not seen") with a Change button, which brings the grid back with the current choice marked; a
failed save puts the previous value back and says "Not saved. Tap a star again." until the next save
lands. Nothing says whether the forecast was right, and the forecast star stays in the callout's
verdict strip where it was.

The block shows only for a place whose OWN sunrise or sunset has passed (the window turning past on
the pill is decided on the earliest place, so along a coastline the callout can wait a few minutes
behind it), never for a wildlife hide, never for a place with no forecast row for the window, and
never while an admin rewind is in force. The callout's "Not scored yet" reads "Not scored" on a past
window. The ratings come from a new `GET /api/outcome/ratings` read over the days the map holds and are
kept in page state only — nothing is cached or stored in the browser.

Frontend only: `OutcomeRatingBlock`, `useOutcomeRatings` (optimistic write, per-slot rollback, an
overtaken response dropped whole, a re-read that cannot overwrite a save in flight), the
`outcomeRatingApi` bindings and `outcomeRatingCopy`. The callout owns the block's `changing` state and
names it in its repaint dependencies so the card re-anchors when the 44px grid becomes the 40px row.
Counts in the window menu, the pin badge and the admin's all-ratings list come in later phases.
