### Changed — the evening refresh also re-rates tomorrow's sunset

The 21:00 evening refresh used to re-rate tomorrow's sunrise alone. It now re-rates tomorrow's sunrise
and tomorrow's sunset, at every stability, so the evening's plan for tomorrow's sunset rests on a
forecast about 8 hours fresher than the afternoon's. The afternoon refresh leaves an already-rated
tomorrow's sunset to the evening (as it already did the sunrise) and becomes the last look at
tonight's sunset; if the evening is paused, or a place has no rating yet, the afternoon rates it as
before. The number of looks does not change, one moves.

The words follow: the evening job is now "Tomorrow, at bedtime" on the Scheduler page and "Evening
Refresh" in its stored name, the run label is "Evening refresh", the afternoon job's description no
longer says it skips settled places for tonight's sunset (it rates that at every stability), and the
Runs detail counts both deferred events under one event-neutral reason. Migration V217 rewrites the
three stored texts, each only if still unedited.
