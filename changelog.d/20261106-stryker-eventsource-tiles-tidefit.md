### Tests — kill Stryker survivors in createEventSource, fourDayTiles and mapTideFit

Three new edge suites (`createEventSourceEdges.test.js`, `fourDayTilesEdges.test.js`,
`mapTideFitEdges.test.js`) pin answers the happy-path tests never asked for: the event stream's
closeOn guards, CR/LF line splitting across chunks, late responses after cleanup and the exact
listener removals; the four-day tiles' topic-to-almanac table, relative-day labels, tide spans and
earliest-start jump; and the tide strip's cache key, default arguments and earliest-fit search across
several spots. Tests only; no source changed.

Mutation score (detected ÷ detected + survived + no coverage), baseline from the first full weekly run:

- `createEventSource.js`: 80.4% (168/209) → 90.4% (189/209)
- `fourDayTiles.js`: 83.2% (198/238) → 93.7% (223/238)
- `mapTideFit.js`: 87.0% (247/284) → 93.7% (266/284)

Every mutant left is equivalent, which is why each file stays under 95%. In createEventSource most
are generation guards a stale connection can never reach, because a new connection only starts
after the previous one has failed and returned. The rest are `[]` fallbacks whose placeholder
element is skipped and never returned, optional chains on values an earlier check has proven
non-null, and sort-comparator results V8 never acts on.
