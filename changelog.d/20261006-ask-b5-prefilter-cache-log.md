### Added — Ask PhotoCast's free answers: the can't-answer pre-filter, the Ready match, the typed cache, the question log and the metrics

A typed question now costs nothing in four more cases, and the owner can see how the box is used. A question about
car parks, crowds, opening times, toilets, cafés, shops, pubs or restaurants is turned away free as a whole-word
phrase (so "open horizon", "national park" and "busy skies" are still answered), before the Ready match, so "is the car
park busy this weekend" is a can't-answer and never the weekend answer. A question that is exactly a Ready question
("where's good this weekend?", "sunrise or sunset tomorrow?", "any rare events coming up?") is served that Ready answer
as `kind: ready`, uncharged, only while the answer is available and fresh against live data and the question carries
nothing the answer ignores (a named place, a drive or distance, another day or time of day). An answer already paid
for is served again to the next reader who asks the same question (a Caffeine cache of 2,000 entries for 30 minutes,
keyed on scope, UK date, briefing build, normalised question, window and, for an answer that used the asker's own drive
times, the user), re-checked against the live forecast on every hit with the same all-or-nothing test a Ready answer
passes, and never stored while a simulation is active. `ask_log` (V167, user `ON DELETE SET NULL`) records one row per
answered request, keeping the normalised question only when the engine answered it, capped at 200 characters; denied
requests write no row and are counted in memory and logged once per user per hour. A nightly job (`ask_log_cleanup`,
03:55 UTC) prunes the log at `photocast.ask.log.retention-days` (90). `GET /api/admin/ask/metrics?days=` (admin) reports
the outcome counts, the cache-hit, Ready-match and can't-answer rates, the most common things readers asked for that
PhotoCast does not hold, and typed and Ready spend, and never returns a question.
