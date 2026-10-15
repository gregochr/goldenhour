### Added — Map tab: step back through the last few days from the window control

The Map tab's window control now reaches the stored days before the two the forecast list carries.
Pressing `‹` on the oldest window (or `←`, or the new Load row) fetches one more stored day, back to
four days ago; the pill reads `Loading Tue 13…` while the request is out, with both steppers and
the Load row disabled, and when the day lands the map moves to that day's sunset, so the next `‹`
steps to its sunrise. A day that comes back empty moves nothing and says nothing, and still counts
toward the four, so the control offers the day before it. At the oldest reachable window `‹` is
disabled. Nothing older than four days is ever requested, and the fetch stands down under an admin
rewind.

The dropdown gains an EARLIER group above the day groups: an `EARLIER` header with `rate how it
went` beside it, the Load row (`Load Tue 13`, `or press ‹ past the oldest window · fetches Tue 13`),
then a sub-header per past day with its rows, sunrise first, each showing the time and `Not rated`.
Today can appear in both groups (this morning's sunrise under EARLIER, tonight's sunset under
`FRI 16 · today`) and is not merged. The phone's Windows section gets the same header, Load row and
sub-headers, with `Past` where a verdict would be. When the menu opens it now scrolls itself just far
enough to show the window you are on, which the new group would otherwise push below the fold.

A fetched day is kept in memory only (never in the instant-paint cache) and survives a forecast
refresh, which rebuilds the list payload: the day, and its date in the Map's forecast domain, stay.
The count beside a past window (`{n} rated`) and the rating itself come in later steps.
