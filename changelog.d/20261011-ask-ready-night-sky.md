### Added — Ask PhotoCast's Ready "Anything for the night sky tonight?" (N2 of the Ask night series)

A new Ready question, `NIGHT_SKY`, answered ahead of time like the others and free to tap. It is offered
for a scope (every region, and everywhere) only when tonight's astro night holds at least one 4-star
spot in that scope, never merely because astro rows exist, so a scope with nothing to say costs no model
call. It is astro only: a Ready answer is shared by every reader, so no aurora pick or figure is ever
stored (the aurora hot-topic card, which every role already sees on the Plan tab, may sit beside the
picks). The typed matcher learns its words conservatively: "tonight" plus the night sky, stars,
stargazing, the Milky Way, dark skies or clear skies, with nothing left over but asking words. A
question with no time, another night, a place, a drive word, "aurora" or "Bortle" still goes to the
model.

The answer rests on the night's astro run, so it carries a version (the night's date and the latest
`run_timestamp`, kept in the stored answer's JSON, no migration) and is withheld whole when the astro job
re-runs the night, when a pick's star moves or stops being eligible, or when the night has passed. The
version is served on the Ready question as `nightVersion`, the same string a typed night answer carries,
so a Ready-origin exchange resets the follow-up thread by the rule a typed one does.

The 15:30 UTC astro job now dispatches a night-only Ready precompute after a run that scored at least
one location-date, on a background executor with the precompute's own 5-minute deadline, so nothing it
does can fail the run. Without it the 14:00 answer would be withheld from 15:30 until the 18:00 cycle
rewrote it. `AskReadyPrecompute` takes an optional set of questions (every existing caller still runs
all of them) and the job passes only `NIGHT_SKY`, so no other stored answer is touched and the cost
after an astro run is at most regions + 1 Haiku conversations. A scheduled run counts against
`photocast.ask.ready.max-cycles-per-day`; an administrator's "Run now" takes the on-demand route and
does not.

Also fixed on the way: the Ready freshness check's "every window has passed" rule asked only for solar
windows, so a night window id would always have read as passed.
