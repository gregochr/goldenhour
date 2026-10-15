### Docs — "How was it?": the port plan for rating a past sunrise or sunset

`docs/engineering/outcome-rating-plan.md` plans the Map tab's outcome rating: Pro and Admin readers
record, with one tap in the location callout, how a sunrise or sunset that has passed actually
looked (1–5★ or "Didn't see it"), per user, against the last forecast run before the event; the
window control steps backwards through elapsed windows and the two served past days, then fetches
one more stored day at a time back to T-4. Six phases (a new `outcome_rating` table and its three
endpoints; a one-day `GET /api/forecast?date=` read; past windows in the window control; the rating
block, counts and pin badge; the admin list and the calibration gate reading the new ratings; a docs
sweep). The design bundle is vendored verbatim at `docs/design/outcome-rating/`. No code in this
change.
