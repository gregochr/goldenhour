### Added — Rewind: render the app as it stood before a solar event that has passed (admin)

You went out for this morning's sunrise on PhotoCast's say-so, and now you want to post the photo
beside the forecast that sent you there — but by the time you are back the Plan tab says "this
morning has gone" and the Map tab has moved on to tonight. The new **Rewind** sub-tab under
Operations lists the last three days' sunrises and sunsets, timed across the whole sky roster, and
one press rewinds the app to an hour before the roster's earliest event time: the whole app
remounts on that moment, that window is live again on the Plan matrix and the Map tab opens on it,
with its verdict, stars and best bet in place, for the screenshot. A small pill in the bottom-left
corner names the moment and is the way back to live; so is a reload or a sign-out.

A rewind turns the clock back, never the data. Every serve-time "now" on the backend already came
from one injected `Clock` bean; it is now a `RewindAwareClock`, which answers with the instant an
admin's GET carries in a new `X-Rewind-To` header (`RewindFilter`, after JWT authentication,
honoured for admins only, on GET only, never under `/api/admin/`, cleared in a `finally`). Nothing
is set on the server, so one admin's rewind can never reach another user's page. On the client,
every clock read that decides whether a window has passed, what today is, which event is next or
how old the forecast is goes through one `appNow()`, and the two stale-while-revalidate caches are
neither read nor written while rewound, so a rewound payload never paints a live page. What you see
is today's forecast cache rendered as of that moment: ratings for a passed window are not
re-scored, so it normally matches what was shown, and hot topics are recomputed from today's
readings. `GET /api/admin/rewind/events` (ADMIN) supplies the menu, including when the briefing was
last built so the view can say when that was after the moment chosen.
