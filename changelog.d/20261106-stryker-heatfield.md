### Tests — kill Stryker survivors in heatField and heatSpots

Batch 1 of the frontend mutation clean-up (first full run, GitHub run 42202344388). New suites `heatFieldEdges.test.js` and `heatSpotsEdges.test.js` pin the cut-off distance, canvas call order, hatch and veil geometry, marching-squares edge ids, id-first lookup and malformed-input handling. Mutation results, survivors plus no-coverage mutants:

- `heatField.js`: 139 survived and 19 no coverage before, 58 survived and 0 no coverage after (killed 862 to 947, timeouts 15). Raw score 94.3%.
- `heatSpots.js`: 57 survived and 1 no coverage before, 18 survived after (killed 234 to 274). Raw score 93.8%.

Every remaining survivor is an equivalent mutant (unreachable bucket bounds guards, loops running one iteration past an ignored write, `typeof` guards implied by `Number.isFinite`/`Number.isInteger`, empty-array fallbacks whose string stand-in has no fields, defensive filters). No source changed.
