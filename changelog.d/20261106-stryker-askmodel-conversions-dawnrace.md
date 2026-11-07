### Tests — kill Stryker survivors in askModel, conversions and dawnRace

Three new edge suites (`askModelEdges.test.js`, `conversionsEdges.test.js`, `dawnRaceEdges.test.js`)
pin answers the happy-path tests never asked for: night cards with out-of-scale stars, blank text and
a half-served window; holes in the briefing tree; suggestion and run-label lookups over malformed
lists; the exact bands and boundaries of the relative and elapsed time formatters and of the map's
auto-selection (30 minutes after the earliest sunset, to the millisecond); first-wins and sort order
in the forecast grouping; the zone-suffix pattern; the compass tables; and the dawn/dusk race's exact
hour floor, 15-minute run-on, stop-key check, tick wording and gradient. Tests only; nothing under
`src/utils` changed.

`conversionsEdges` and `dawnRaceEdges` load their module inside each test (`vi.resetModules()` plus a
dynamic import in `beforeEach`). Module-level constants are evaluated at load, and a load that happens
before any test starts is "static" to Stryker, so its mutants never ran against these files.

Mutation score (killed ÷ killed + survived + no coverage), baseline from the first full weekly run:

- `askModel.js`: 87.1% (236/271) → 97.0% (263/271)
- `conversions.js`: 86.8% (230/265) → 97.1% (268/276; 11 module-constant mutants that used to be
  ignored as static now run, and are killed)
- `dawnRace.js`: 85.5% (206/241) → 93.8% (226/241)

Every mutant left is equivalent: `[]` fallbacks whose replacement element is skipped or never returned,
optional chains on values an earlier check has proven non-null, guards that repeat a check the caller
or the formatter already made, and comparisons whose two branches return the same value. dawnRace
stays just under 95% because its 15 remaining mutants are all of that kind.
