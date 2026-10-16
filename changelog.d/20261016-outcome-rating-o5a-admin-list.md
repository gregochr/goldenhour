### Added — Map tab: an Admin's "All ratings" list under "How was it?" (O5a)

An Admin who selects a place on a past window now sees a collapsed `All ratings` box under their own
rating block. Opening it fetches that one window's ratings from every reader and lists them — the
Admin's own row first, then the rest newest first: each reader's name, their stars (or `Not seen`) and
when it was recorded (`HH:MM` for today, `Sun 19:30` for another day, on the UK clock). The header
counts every row once it has loaded (`All ratings · 3`). A footer says which forecast run the ratings
were recorded against and how long before the event it was made — `Rated against the run at Sat 17 Oct,
16:05 · 2h before sunset`, in whole hours from an hour up and minutes (`40 min`) below it — or `No
forecast run recorded before this sunset` when none was. An empty window says `No ratings yet.`, and a
list that could not be fetched says `Couldn't load ratings.` in one quiet line and can be opened again to
retry.

The list is fetched on its first open, not with the reader's own ratings; it is fetched again once the
Admin's own save lands (not for a save that finishes after they have moved to another place or window),
and it closes and forgets its rows whenever the place or the window changes. It is never drawn, and the
endpoint never asked, for a Pro or Lite reader. Frontend only: `OutcomeAdminList`, `useSlotRatings` and
`fetchAllRatingsForSlot`; `MapCallout` now holds the list's rows and loading state (so the card
re-measures when the box opens or its rows arrive). Three strings are the plan's, not the design's:
the no-run footer, `Couldn't load ratings.` and `Loading …`.
