### Fixed — a blanked region with no record no longer reads "Poor"

The honesty filter words a region with no per-location forecast by why it has none, but a region
whose record could not say why (no disposition for its slots, a tie, a renamed location, a failed
lookup, a cycle still in flight) shared the weather stand-down wording, "Too unsettled to forecast".
On the Plan tab that drew as "Poor" over an all-hatched field, which readers took for a rating, as
a T+3 sunrise did on 2026-10-19. Such a region now reads "Not scored" (awaiting), with the summary
"No per-location forecast — the record does not say why" and no claim about the weather. Only a
region that weather triage really stood down keeps "Too unsettled to forecast", and a window with
any such region still reads stand-down.
