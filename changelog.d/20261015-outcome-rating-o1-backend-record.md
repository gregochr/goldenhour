### Added — the outcome rating record and its three endpoints (backend only, nothing draws it yet)

Pro and Admin readers can now record, per user and per location, how a sunrise or sunset that has
already happened actually looked: 1–5 stars, or "didn't see it". `PUT /api/outcome/ratings`
(`{locationId, eventDate, eventType, rating}`, an absent rating meaning not seen) replaces the user's
earlier answer for the slot; `GET /api/outcome/ratings?from=&to=` returns the caller's own rows
(any role — a Lite account simply has none — and at most 31 days); `GET /api/outcome/ratings/all`
returns every user's row for one window, newest first, with the caller's own marked, for Admin. A
Lite account's PUT is refused with 403, and a PUT for an event that has not yet happened (this
place's own event time plus the 30-minute afterglow, on the real clock) or is more than 24 days old
is a 400 with a plain sentence. The new `outcome_rating` table (V177) is separate from the old
`actual_outcome`, whose API is untouched.

Each rating is recorded against the forecast the reader actually saw, not the newest cycle before
the event: the new `AsOfEventResolver` takes the result the Plan tab and the map serve for the slot
if it had arrived by the event (a canopy-bearing place needs its cached entry last written by then
as well), and otherwise the latest `forecast_evaluation` row that was scored by the event and not
withdrawn by a stability skip recorded before it. The rating stores that forecast's age
(`forecast_run_at`), the cycle whose trigger instant matches it exactly, the row it was paired with
and the predicted star the reader saw — null when that star cannot be recovered, as for a
bluebell-bearing place whose combined star the cache has since replaced. Nothing here reads a
roster-wide "latest cycle", so a cycle that failed, skipped the slot or landed late is never named.
A reader who changes their mind keeps the forecast record made the first time, since the event is
fixed and a second resolution after a restart could only be poorer. The resolver also has a batch form so the forecast serve path can use the same answer for a whole
roster in a handful of queries. The upsert is a find-or-insert in its own store class with no
surrounding transaction, the one shape that survives a Postgres unique violation, and is raced by a
12-thread test (and, in CI, on Postgres).

The migrations and the Postgres race are proven by CI only; the H2 slice tests, the resolver's
provenance cases and the endpoint role matrix run locally.
