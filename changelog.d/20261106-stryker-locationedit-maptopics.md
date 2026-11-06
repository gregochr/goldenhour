### Tests — kill Stryker survivors in locationEdit and mapTopics

Batch 2 of the frontend mutation-score work (first full run 87.81%). Two new behaviour test files,
`locationEditEdges.test.js` and `mapTopicsEdges.test.js`, pin the boundaries and gaps the existing tests
left open: coordinate, height and tide-point limits; which parts of a stored floor, tide point or drive
point make it "stored"; the field-change rules; the strip's column, High-water tick and tide-at-the-light
words; and `barTarget` / `labelTarget` on grids with fewer columns than the bar. No source change.

Mutation score (killed plus timeout, of all scored mutants), measured locally with Stryker 10.1.1:

- `utils/locationEdit.js`: 80.4% (452 killed, 104 survived, 6 no coverage) to 99.3% (558 killed, 4 survived).
- `utils/mapTopics.js`: 87.0% (543 killed or timed out, 79 survived, 2 no coverage) to 95.8% (598 killed or timed out, 25 survived, 1 no coverage).

Every survivor left is an equivalent mutant (no input can tell it from the original).
