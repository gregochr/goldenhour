### Tests — kill Stryker survivors in mapDoor.js and mapEvents.js

Added `mapDoorEdges.test.js` and `mapEventsEdges.test.js`: behaviour tests for the Plan-to-Map door's
clock, day and event wording, roster filter, best-spot and neighbour-outlier arithmetic, trigger
normalisation, and for the Map event list's past-window ordering, malformed inputs, defaults, night and
High-water rows, row shapes and clock boundaries. `mapDoor.js` went from 75.2% (361 of 480 scored mutants killed, 119 survived or uncovered) to 95.6%
(459 killed, 21 left); `mapEvents.js` from 80.2% (438 of 546, 108 left) to 92.3% (504 killed, 42 left).
Every mutant left is equivalent: array defaults and fallbacks whose replacement no input can tell apart,
optional chains on values already known non-null, and `<`/`<=` swaps on values that are never equal.
