### Tests — kill Stryker survivors in skyEval, windowFirstCards and planConflicts

Batch 4 of the frontend mutation clean-up (first full run, GitHub run 42202344388). Three new behaviour suites, `skyEvalEdges.test.js`, `windowFirstCardsEdges.test.js` and `planConflictsEdges.test.js`, pin formatter thresholds, inclusive chart-window edges, sort tie rules, the rating-history geometry, the region argmax and its name tie-break, the reach counts a card carries when a tier removes places, the away-origin figures with unusable means, and the plan-conflict message's scope, nearest-location and ceiling rules. Mutation results (survivors plus no-coverage mutants before, survivors after):

- `skyEval.js`: 70 before, 11 after (killed 600, 155 ignored). Raw score 98.2%.
- `windowFirstCards.js`: 66 before, 45 after (killed 478). Raw score 91.4%.
- `planConflicts.js`: 52 before, 15 after (killed 217). Raw score 93.5%.

Every remaining survivor is an equivalent mutant: optional chaining on values the caller already filtered, `[]` fallbacks whose string stand-in has no fields, `typeof` guards implied by `Number.isFinite`, comparisons made unobservable by an earlier guard or a stable sort, and branches reachable only with a record shape `buildWindowSpots` or the tier ladder cannot produce. No source changed.
