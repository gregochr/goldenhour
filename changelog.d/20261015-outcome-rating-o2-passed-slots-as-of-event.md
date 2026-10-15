### Changed — a sunrise or sunset that has already happened is served as the forecast stood at the event

`GET /api/forecast` (its two past days, and today's elapsed sunrise) and the new `?date=` read now
serve a passed slot — judged per place on its own event time plus the 30-minute afterglow — as of
that event, using the same resolution an outcome rating is recorded against, instead of its latest
row. A batch that lands after the event, or a stability skip recorded after it, can no longer repaint
a window you have already seen. For Pro and Admin accounts the star on a passed slot is the star
that was captured as served at the event; a woodland or bluebell place whose combined star cannot be
recovered reads unscored (no star, no scores) rather than showing its sky-only star, and a place with
nothing available by the event shows no row for that window. A Lite account's row is served as the
list has always served it to Lite (the row's own rating and basic scores).
Windows that are in the future, and today's event before it happens, are served exactly as before.

Because the resolution needs the scoring time recorded since V178, for the first days after this
deploys the windows that passed before it show fewer stars (a slot scored before V178 has nothing to
resolve to and reads unscored); every window scored afterwards is served in full.
